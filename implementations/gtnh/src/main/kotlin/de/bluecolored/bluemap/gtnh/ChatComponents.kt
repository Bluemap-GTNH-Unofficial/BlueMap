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
package de.bluecolored.bluemap.gtnh

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.minecraft.event.ClickEvent as McClickEvent
import net.minecraft.event.HoverEvent as McHoverEvent
import net.minecraft.util.ChatComponentText
import net.minecraft.util.ChatStyle
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.IChatComponent

/**
 * Converts adventure components to 1.7.10 chat components: RGB colours become the nearest of the 16 legacy colours,
 * click/hover events keep the actions 1.7.10 knows. Not via JSON: 1.7.10 rejects the modern hover-event format.
 */
object ChatComponents {
    fun toMinecraft(component: Component): IChatComponent {
        val text =
            when (component) {
                is TextComponent -> component.content()
                is TranslatableComponent -> component.fallback() ?: component.key()
                else -> ""
            }
        val result = ChatComponentText(text)
        result.setChatStyle(toMinecraft(component.style()))
        for (child in component.children()) {
            result.appendSibling(toMinecraft(child))
        }
        return result
    }

    private fun toMinecraft(style: Style): ChatStyle {
        val result = ChatStyle()
        style.color()?.let { color -> result.setColor(EnumChatFormatting.valueOf(NamedTextColor.NAMES.keyOrThrow(NamedTextColor.nearestTo(color)).uppercase())) }
        decoration(style, TextDecoration.BOLD)?.let { result.setBold(it) }
        decoration(style, TextDecoration.ITALIC)?.let { result.setItalic(it) }
        decoration(style, TextDecoration.UNDERLINED)?.let { result.setUnderlined(it) }
        decoration(style, TextDecoration.STRIKETHROUGH)?.let { result.setStrikethrough(it) }
        decoration(style, TextDecoration.OBFUSCATED)?.let { result.setObfuscated(it) }
        style.clickEvent()?.let { result.setChatClickEvent(toMinecraft(it)) }
        style.hoverEvent()?.let { result.setChatHoverEvent(toMinecraft(it)) }
        return result
    }

    private fun decoration(
        style: Style,
        decoration: TextDecoration,
    ): Boolean? =
        when (style.decoration(decoration)) {
            TextDecoration.State.TRUE -> true
            TextDecoration.State.FALSE -> false
            else -> null
        }

    private fun toMinecraft(event: ClickEvent): McClickEvent? {
        val action =
            when (event.action()) {
                ClickEvent.Action.OPEN_URL -> McClickEvent.Action.OPEN_URL
                ClickEvent.Action.RUN_COMMAND -> McClickEvent.Action.RUN_COMMAND
                ClickEvent.Action.SUGGEST_COMMAND, ClickEvent.Action.COPY_TO_CLIPBOARD -> McClickEvent.Action.SUGGEST_COMMAND
                else -> return null
            }
        val payload = event.payload() as? ClickEvent.Payload.Text ?: return null
        return McClickEvent(action, payload.value())
    }

    private fun toMinecraft(event: HoverEvent<*>): McHoverEvent? {
        val value = event.value() as? Component ?: return null
        return McHoverEvent(McHoverEvent.Action.SHOW_TEXT, toMinecraft(value))
    }
}
