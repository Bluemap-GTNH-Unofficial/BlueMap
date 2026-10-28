/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.gtnh.render.mods.addons

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.render.mods.FaceTexture
import de.bluecolored.bluemap.gtnh.render.mods.Faces
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshBuilder
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos

/**
 * The elements of a raw JSON block model (Blockbench / 1.8+ format) as a block-space mesh: `from`/`to` boxes in
 * pixels, per-face `uv` (absent: the face's automatic uv), `rotation` in 90° steps and `#name` texture references,
 * and the element rotation about `origin` (right-handed like `glRotatef`, optionally `rescale`d by 1 / cos on the
 * two other axes). Lighting hints (`shade`, `light_emission`), `tintindex` and `display` are not used.
 */
object JsonModelMesh {
    /** Raw jar texture of a model texture name: `<domain>:<path>` -> `<domain>:textures/<path>.png`. */
    fun rawTexture(name: String): String {
        val colon = name.indexOf(':')
        val (domain, path) = if (colon < 0) "minecraft" to name else name.substring(0, colon) to name.substring(colon + 1)
        return "$domain:textures/$path.png"
    }

    /** The model's texture variable -> texture name, `#` chains resolved (unresolvable ones dropped). */
    fun textures(model: JsonObject): Map<String, String> {
        val raw = model.getAsJsonObject("textures")?.entrySet()?.associate { (k, v) -> k to v.asString } ?: emptyMap()
        val resolved = HashMap<String, String>()
        for (key in raw.keys) {
            var value = raw[key] ?: continue
            val seen = HashSet<String>()
            while (value.startsWith("#")) {
                val ref = value.substring(1)
                if (!seen.add(ref)) break
                value = raw[ref] ?: break
            }
            if (!value.startsWith("#")) resolved[key] = value
        }
        return resolved
    }

    /**
     * The model's faces in block space (0..1), each with the texture [texture] returns for its resolved texture
     * name. Null when a face's texture is missing from the pack.
     */
    fun mesh(model: JsonObject, texture: (String) -> Key?): Mesh? {
        val textures = textures(model)
        val quads = ArrayList<MeshQuad>()
        for (e in model.getAsJsonArray("elements") ?: return Mesh.EMPTY) {
            val element = e.asJsonObject
            val from = floats(element.getAsJsonArray("from"))
            val to = floats(element.getAsJsonArray("to"))
            val transform = element.getAsJsonObject("rotation")?.let(::rotation)
            val fromBlock = FloatArray(3) { from[it] / 16f }
            val toBlock = FloatArray(3) { to[it] / 16f }
            for ((name, f) in element.getAsJsonObject("faces")?.entrySet() ?: emptySet()) {
                val dir = direction(name) ?: continue
                val face = f.asJsonObject
                val ref = face.get("texture")?.asString ?: continue
                val textureName = if (ref.startsWith("#")) textures[ref.substring(1)] ?: continue else ref
                val key = texture(textureName) ?: return null
                val uv = face.getAsJsonArray("uv")?.let(::floats)
                val rotation = Math.floorMod((face.get("rotation")?.asInt ?: 0) / 90, 4)
                val faceTexture = if (uv != null) {
                    FaceTexture(key, uv[0] / 16f, uv[1] / 16f, uv[2] / 16f, uv[3] / 16f, rotation)
                } else {
                    MeshBuilder.autoUv(dir, from[0], from[1], from[2], to[0], to[1], to[2], key).copy(rotation = rotation)
                }
                val quad = Faces.face(dir, fromBlock, toBlock, faceTexture)
                quads.add(if (transform != null) quad.transformed(transform) else quad)
            }
        }
        return Mesh(quads)
    }

    /** An element rotation: about `origin` (pixels) on `axis` by `angle` degrees, `rescale`d if asked. */
    fun rotation(rotation: JsonObject): Transform? {
        val angle = rotation.get("angle")?.asFloat ?: return null
        if (angle == 0f) return null
        val origin = rotation.getAsJsonArray("origin")?.let(::floats) ?: floatArrayOf(8f, 8f, 8f)
        val o = Transform.translate(origin[0] / 16f, origin[1] / 16f, origin[2] / 16f)
        val back = Transform.translate(-origin[0] / 16f, -origin[1] / 16f, -origin[2] / 16f)
        val axis = rotation.get("axis")?.asString ?: return null
        val s = if (rotation.get("rescale")?.asBoolean == true) 1f / cos(Math.toRadians(angle.toDouble())).toFloat() else 1f
        val (turn, scale) = when (axis) {
            "x" -> Transform.rotate(angle, 1f, 0f, 0f) to Transform.scale(1f, s, s)
            "y" -> Transform.rotate(angle, 0f, 1f, 0f) to Transform.scale(s, 1f, s)
            "z" -> Transform.rotate(angle, 0f, 0f, 1f) to Transform.scale(s, s, 1f)
            else -> return null
        }
        // scaling the two other axes evenly commutes with the rotation about this one
        return Transform.gl(o, turn, scale, back)
    }

    private fun direction(name: String): Direction? = when (name) {
        "down" -> Direction.DOWN
        "up" -> Direction.UP
        "north" -> Direction.NORTH
        "south" -> Direction.SOUTH
        "west" -> Direction.WEST
        "east" -> Direction.EAST
        else -> null
    }

    private fun floats(array: JsonArray): FloatArray = FloatArray(array.size()) { array[it].asFloat }
}

/** Placed JSON model meshes, per model instance (identity: a reload brings new instances) and placement. */
internal object JsonMeshes {
    private class CacheKey(val model: JsonObject, val params: Any) {
        override fun equals(other: Any?): Boolean = other is CacheKey && other.model === model && other.params == params

        override fun hashCode(): Int = System.identityHashCode(model) * 31 + params.hashCode()
    }

    private val cache = ConcurrentHashMap<CacheKey, Mesh>()

    /** The cached mesh, or [build]'s (not cached when null: assets missing). */
    fun get(model: JsonObject, params: Any, build: () -> Mesh?): Mesh? {
        val key = CacheKey(model, params)
        cache[key]?.let { return it }
        return build()?.also { cache[key] = it }
    }
}
