#!/usr/bin/env python3
"""Vanilla flattening oracle ("原版差分", plan §4): builds a pure vanilla 1.7.10 test world and its official
1.13.2 upgrade, for `./gradlew :legacy:compareFlattening`.

    python3 oracle_world.py build     # 1.7.10 world via the plain vanilla server + RCON /setblock
    python3 oracle_world.py upgrade   # copy, `--forceUpgrade` with the 1.13.2 server, load the spawn area
    python3 oracle_world.py all

Layout (superflat `2;7,2x3,2;1;`: grass at y=3, everything is placed at y>=4, spawn at 0,4,0):
- sweep    chunks x -8..-1, z -8..-1: every vanilla id 1..175 x meta 0..15 on its own cell, enclosed by stone
- matrix   chunks x -8..-1, z  0..8 : representative blocks, each with probes on all four sides
           (A: oak fence N, glass pane S, cobblestone wall W, nether brick fence E;
            B: redstone wire N + on top, vine S, iron bars W, redstone wire E)
- scenes   chunks x  0..7,  z -8..-1: neighbour scenarios (lines, corners, doors, chests, pots, skulls ...)
- borders  chunks x  0..7,  z  0..7 : the same kind of scenarios straddling chunk borders
Apart from the deliberate border scenarios nothing is placed on chunk edges, because 1.13's upgrade
re-shapes whole chunk edges (UpgradeData sides) whenever a neighbour-dependent block sits on one.

Outputs (default `<workspace>/out/flattening-oracle`): `world-1.7.10/`, `world-1.13.2/`, `manifest.json`
(placements, failed placements, labels per position, comparison box). Before upgrading, the copy gets
`LightPopulated=1` on every chunk: 1.13.2 regenerates chunks whose light 1.7 had not populated yet.
Id:meta combinations that crash one of the servers are skipped (UNPLACEABLE).

Servers: `<workspace>/servers/vanilla-1.7.10` (25570, RCON 25580) and `<workspace>/servers/vanilla-1.13.2`
(25571, RCON 25581), both run with mise's `java@zulu-8.98.0.107`. Jars: `<workspace>/downloads/mc/`.
"""
import argparse
import gzip
import io
import json
import os
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import time
import zlib

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[3]  # the BlueMap fork checkout
WORKSPACE = pathlib.Path(os.environ["BMGTNH_WORKSPACE"]) if "BMGTNH_WORKSPACE" in os.environ else REPO.parent
ORCHESTRATOR_SRC = WORKSPACE / "orchestrator/src"  # sibling orchestrator checkout
if not (ORCHESTRATOR_SRC / "orchestrator/rcon.py").is_file():
    sys.exit(f"{ORCHESTRATOR_SRC} not found: clone the orchestrator repo next to BlueMap (or set BMGTNH_WORKSPACE)")
sys.path.insert(0, str(ORCHESTRATOR_SRC))
from orchestrator.rcon import RconClient  # noqa: E402  (stdlib-only RCON client tuned for 1.7.10)

JAVA8 = "java@zulu-8.98.0.107"
PASSWORD = "flattening-oracle"
Y = 4  # first block above the superflat grass

NAMES = {int(k): v for k, v in json.loads((HERE / "vanilla_1_7_10_blocks.json").read_text())["blocks"].items()}
ID = {v.split(":", 1)[1]: k for k, v in NAMES.items()}


class World:
    """Collects placements; positions are absolute block coordinates."""

    def __init__(self):
        self.ops = []  # (x, y, z, block id, meta, nbt or None, label)
        self.labels = {}

    def set(self, x, y, z, block, meta=0, nbt=None, label=""):
        bid = block if isinstance(block, int) else ID[block]
        self.ops.append((x, y, z, bid, meta, nbt, label))
        self.labels[f"{x},{y},{z}"] = label


# ---------------------------------------------------------------- layout helpers

def chunk_cells(cx_range, cz_range, local):
    """Yields absolute (x, z) for each chunk in the ranges and each local (lx, lz)."""
    for cz in cz_range:
        for cx in cx_range:
            for lz in local:
                for lx in local:
                    yield cx * 16 + lx, cz * 16 + lz


class Shelf:
    """Packs rectangular scenes into chunk interiors (local 1..14), never touching chunk edges."""

    def __init__(self, cx_range, cz_range):
        self.chunks = [(cx, cz) for cz in cz_range for cx in cx_range]
        self.i = 0
        self.x = 1
        self.z = 1
        self.row = 0

    def place(self, w, d):
        assert w <= 14 and d <= 14
        while True:
            cx, cz = self.chunks[self.i]
            if self.x + w > 15:
                self.x, self.z, self.row = 1, self.z + self.row + 1, 0
            if self.z + d > 15:
                self.i += 1
                self.x, self.z, self.row = 1, 1, 0
                continue
            ox, oz = cx * 16 + self.x, cz * 16 + self.z
            self.x += w + 1
            self.row = max(self.row, d)
            return ox, oz


# ---------------------------------------------------------------- regions

SWEEP_LOCAL = [1, 3, 5, 7, 9, 11, 13]

# id:meta combinations that crash a server: 1.7.10 pistons with an invalid orientation (index out of bounds when
# ticked), hoppers facing up / invalid (1.13.2 keeps their block entity on air and crashes ticking it)
UNPLACEABLE = {(bid, meta) for bid in (29, 33) for meta in (6, 7, 14, 15)} | {(154, meta) for meta in (1, 6, 7, 9, 14, 15)}


def sweep(w):
    cells = list(chunk_cells(range(-8, 0), range(-8, 0), SWEEP_LOCAL))
    samples = [(bid, meta) for bid in sorted(NAMES) if bid != 0 for meta in range(16) if (bid, meta) not in UNPLACEABLE]
    assert len(samples) <= len(cells), (len(samples), len(cells))
    used = cells[: len(samples)]
    used_set = set(used)
    # stone enclosure first (placing it later would break attached samples)
    for cz in range(-8, 0):
        for cx in range(-8, 0):
            for lz in range(16):
                for lx in range(16):
                    x, z = cx * 16 + lx, cz * 16 + lz
                    if (x, z) not in used_set:
                        w.set(x, Y, z, "stone", label="sweep:stone")
    for (x, z), (bid, meta) in zip(used, samples):
        w.set(x, Y, z, bid, meta, label=f"sweep:{NAMES[bid]}:{meta}")


def matrix_subjects():
    multi = {
        **{s: range(8) for s in ("oak_stairs", "stone_stairs", "brick_stairs", "stone_brick_stairs", "nether_brick_stairs",
                                  "sandstone_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs", "quartz_stairs",
                                  "acacia_stairs", "dark_oak_stairs")},
        "stone_slab": (0, 8), "wooden_slab": (0, 8), "trapdoor": range(16), "piston": (0, 1, 2, 3, 4, 5),
        "sticky_piston": (0, 1, 2, 3, 4, 5), "fence_gate": range(8), "snow_layer": range(8), "chest": (2, 3, 4, 5),
        "trapped_chest": (2, 3, 4, 5), "hopper": (0, 2), "anvil": (0, 1), "leaves": (0, 4),
    }
    skip = {"air", "piston_head", "piston_extension"}
    subjects = []
    for bid in sorted(NAMES):
        name = NAMES[bid].split(":", 1)[1]
        if bid == 0 or name in skip:
            continue
        for meta in multi.get(name, (0,)):
            subjects.append((bid, meta))
    return subjects


def matrix(w):
    centers = list(chunk_cells(range(-8, 0), range(0, 9), [2, 6, 10]))
    subjects = matrix_subjects()
    jobs = [(s, "A") for s in subjects] + [(s, "B") for s in subjects]
    assert len(jobs) <= len(centers), (len(jobs), len(centers))
    for (x, z), ((bid, meta), kind) in zip(centers, jobs):
        label = f"matrix{kind}:{NAMES[bid]}:{meta}"
        # probes first, the subject last (placing next to it later would update / break it)
        if kind == "A":
            w.set(x, Y, z - 1, "fence", label=label + ":fence")
            w.set(x, Y, z + 1, "glass_pane", label=label + ":pane")
            w.set(x - 1, Y, z, "cobblestone_wall", label=label + ":wall")
            w.set(x + 1, Y, z, "nether_brick_fence", label=label + ":nether_fence")
        else:
            w.set(x, Y, z - 1, "redstone_wire", label=label + ":wire")
            w.set(x, Y, z + 1, "vine", 4, label=label + ":vine")  # attached to the north (the subject)
            w.set(x - 1, Y, z, "iron_bars", label=label + ":bars")
            w.set(x + 1, Y, z, "redstone_wire", label=label + ":wire")
        w.set(x, Y, z, bid, meta, label=label)
        if kind == "B":
            w.set(x, Y + 1, z, "redstone_wire", label=label + ":wire_on_top")


# ---------------------------------------------------------------- scenes (relative placements)

def scenes():
    """[(label, [(dx, dy, dz, block, meta, nbt)])] with dy relative to Y; footprint is computed."""
    out = []

    def scene(label, blocks):
        out.append((label, blocks))

    # lines / crosses / corners of connecting blocks
    for block in ("fence", "nether_brick_fence", "glass_pane", "iron_bars", "stained_glass_pane", "cobblestone_wall"):
        scene(f"{block}:line", [(i, 0, 0, block, 0, None) for i in range(3)])
        scene(f"{block}:cross", [(1, 0, 1, block, 0, None)] + [(1 + dx, 0, 1 + dz, block, 0, None)
                                                               for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))])
        scene(f"{block}:corner", [(0, 0, 0, block, 0, None), (1, 0, 0, block, 0, None), (0, 0, 1, block, 0, None)])
        scene(f"{block}:single", [(0, 0, 0, block, 0, None)])
    scene("fence:nether_next_to_oak", [(0, 0, 0, "fence", 0, None), (1, 0, 0, "nether_brick_fence", 0, None)])
    scene("fence:wall_next_to_fence", [(0, 0, 0, "fence", 0, None), (1, 0, 0, "cobblestone_wall", 0, None)])
    scene("pane:next_to_wall", [(0, 0, 0, "glass_pane", 0, None), (1, 0, 0, "cobblestone_wall", 0, None)])
    scene("pane:next_to_bars", [(0, 0, 0, "glass_pane", 0, None), (1, 0, 0, "iron_bars", 0, None)])
    scene("wall:block_above", [(i, 0, 0, "cobblestone_wall", 0, None) for i in range(3)] + [(1, 1, 0, "stone", 0, None)])
    scene("wall:torch_above", [(i, 0, 0, "cobblestone_wall", 0, None) for i in range(3)] + [(1, 1, 0, "torch", 5, None)])
    scene("wall:mossy_line", [(i, 0, 0, "cobblestone_wall", 1, None) for i in range(3)])
    for meta in range(8):
        # gate between walls / fences on both axes
        scene(f"gate:{meta}:walls_x", [(0, 0, 0, "cobblestone_wall", 0, None), (1, 0, 0, "fence_gate", meta, None),
                                       (2, 0, 0, "cobblestone_wall", 0, None)])
        scene(f"gate:{meta}:walls_z", [(0, 0, 0, "cobblestone_wall", 0, None), (0, 0, 1, "fence_gate", meta, None),
                                       (0, 0, 2, "cobblestone_wall", 0, None)])
        scene(f"gate:{meta}:fences_x", [(0, 0, 0, "fence", 0, None), (1, 0, 0, "fence_gate", meta, None),
                                        (2, 0, 0, "fence", 0, None), (1, 0, 1, "fence", 0, None)])

    # stairs: straight line and every inner / outer corner (1.7 meta: 0 east, 1 west, 2 south, 3 north, +4 upside down)
    facing = {0: (1, 0), 1: (-1, 0), 2: (0, 1), 3: (0, -1)}
    for half in (0, 4):
        for a in range(4):
            scene(f"stairs:{a + half}:line", [(i, 0, 0, "oak_stairs", a + half, None) for i in range(3)]
                  if a < 2 else [(0, 0, i, "oak_stairs", a + half, None) for i in range(3)])
            ax, az = facing[a]
            for b in range(4):
                if facing[b][0] * ax + facing[b][1] * az != 0:
                    continue  # same axis
                scene(f"stairs:{a + half}:front:{b + half}",
                      [(1, 0, 1, "oak_stairs", a + half, None), (1 + ax, 0, 1 + az, "stone_stairs", b + half, None)])
                scene(f"stairs:{a + half}:back:{b + half}",
                      [(1, 0, 1, "oak_stairs", a + half, None), (1 - ax, 0, 1 - az, "stone_stairs", b + half, None)])
        scene(f"stairs:mixed_half:{half}", [(1, 0, 1, "oak_stairs", 0 + half, None), (2, 0, 1, "oak_stairs", 2 + (4 - half), None)])

    # doors: lower meta = facing | open, upper meta = 8 | hinge | powered
    for door in ("wooden_door", "iron_door"):
        for f in range(4):
            for open_ in (0, 4):
                for upper in (8, 9, 10, 11):
                    scene(f"{door}:{f + open_}:{upper}", [(0, 0, 0, door, f + open_, None), (0, 1, 0, door, upper, None)])
        scene(f"{door}:stacked_lowers", [(0, 0, 0, door, 1, None), (0, 1, 0, door, 2, None), (0, 2, 0, door, 9, None)])

    # double plants
    for meta in range(6):
        scene(f"double_plant:{meta}", [(0, 0, 0, "double_plant", meta, None), (0, 1, 0, "double_plant", 8, None)])
    scene("double_plant:stacked_upper", [(0, 0, 0, "double_plant", 1, None), (0, 1, 0, "double_plant", 8, None),
                                         (0, 2, 0, "double_plant", 8, None)])

    # snowy dirt variants (they replace the grass at y=3)
    for ground, gmeta in (("grass", 0), ("dirt", 2), ("mycelium", 0), ("dirt", 0)):
        for top, tmeta in (("snow_layer", 0), ("snow_layer", 7), ("snow", 0), ("stone", 0)):
            scene(f"snowy:{ground}:{gmeta}:{top}:{tmeta}", [(0, -1, 0, ground, gmeta, None), (0, 0, 0, top, tmeta, None)])
        scene(f"snowy:{ground}:{gmeta}:none", [(0, -1, 0, ground, gmeta, None)])

    # pumpkins
    for ground, gmeta in (("grass", 0), ("dirt", 0), ("stone", 0), ("dirt", 1)):
        for meta in range(4):
            scene(f"pumpkin:{ground}:{gmeta}:{meta}", [(0, -1, 0, ground, gmeta, None), (0, 0, 0, "pumpkin", meta, None)])
        scene(f"lit_pumpkin:{ground}:{gmeta}", [(0, -1, 0, ground, gmeta, None), (0, 0, 0, "lit_pumpkin", 0, None)])

    # chests (1.7 meta: 2 north, 3 south, 4 west, 5 east)
    for chest in ("chest", "trapped_chest"):
        for meta in (2, 3, 4, 5):
            scene(f"{chest}:{meta}:pair_x", [(0, 0, 0, chest, meta, None), (1, 0, 0, chest, meta, None)])
            scene(f"{chest}:{meta}:pair_z", [(0, 0, 0, chest, meta, None), (0, 0, 1, chest, meta, None)])
            scene(f"{chest}:{meta}:row3_x", [(i, 0, 0, chest, meta, None) for i in range(3)])
            scene(f"{chest}:{meta}:row3_z", [(0, 0, i, chest, meta, None) for i in range(3)])
        scene(f"{chest}:mixed_facing", [(0, 0, 0, chest, 2, None), (1, 0, 0, chest, 3, None)])
    scene("chest:next_to_trapped", [(0, 0, 0, "chest", 2, None), (1, 0, 0, "trapped_chest", 2, None)])

    # beds (1.7 meta: 0 south, 1 west, 2 north, 3 east; +8 head, +4 occupied)
    for f in range(4):
        dx, dz = {0: (0, 1), 1: (-1, 0), 2: (0, -1), 3: (1, 0)}[f]
        for occupied in (0, 4):
            scene(f"bed:{f}:{occupied}", [(1, 0, 1, "bed", f, None), (1 + dx, 0, 1 + dz, "bed", 8 + f + occupied, None)])

    # flower pots: every vanilla content, an invalid item and empty
    contents = [(38, d) for d in range(9)] + [(37, 0)] + [(6, d) for d in range(6)] + \
               [(40, 0), (39, 0), (32, 0), (31, 2), (31, 1), (81, 0), (1, 0), (0, 0)]
    for item, data in contents:
        scene(f"flower_pot:{item}:{data}", [(0, 0, 0, "flower_pot", 0, f"{{Item:{item},Data:{data}}}")])
    scene("flower_pot:legacy_meta", [(0, 0, 0, "flower_pot", 3, None)])

    # skulls: floor (meta 1) with rotations, wall (meta 2..5)
    for t in range(6):
        for rot in (0, 3, 4, 8, 15):
            scene(f"skull:{t}:rot{rot}", [(0, 0, 0, "skull", 1, f"{{SkullType:{t}b,Rot:{rot}b}}")])
        for meta in (2, 3, 4, 5):
            scene(f"skull:{t}:wall{meta}", [(0, 0, 0, "skull", meta, f"{{SkullType:{t}b,Rot:0b}}")])

    # note blocks
    for note in (0, 5, 12, 24):
        scene(f"noteblock:{note}", [(0, 0, 0, "noteblock", 0, f"{{note:{note}b}}")])

    # signs with text
    scene("sign:standing", [(0, 0, 0, "standing_sign", 4, '{Text1:"Hello",Text2:"1.7.10",Text3:"",Text4:"sign"}')])
    scene("sign:wall", [(0, 0, 0, "stone", 0, None), (0, 0, 1, "wall_sign", 3, '{Text1:"Wall",Text2:"",Text3:"",Text4:""}')])

    # redstone wire
    wire = "redstone_wire"
    scene("wire:line", [(i, 0, 1, wire, 0, None) for i in range(3)])
    scene("wire:corner", [(0, 0, 0, wire, 0, None), (1, 0, 0, wire, 0, None), (0, 0, 1, wire, 0, None)])
    scene("wire:t", [(0, 0, 0, wire, 0, None), (1, 0, 0, wire, 0, None), (2, 0, 0, wire, 0, None), (1, 0, 1, wire, 0, None)])
    scene("wire:cross", [(1, 0, 1, wire, 0, None)] + [(1 + dx, 0, 1 + dz, wire, 0, None)
                                                      for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))])
    scene("wire:dot", [(0, 0, 0, wire, 0, None)])
    scene("wire:powered_line", [(i, 0, 0, wire, 15 - i, None) for i in range(3)])
    for meta in range(4):
        scene(f"wire:repeater:{meta}", [(1, 0, 1, "unpowered_repeater", meta, None), (0, 0, 1, wire, 0, None),
                                        (1, 0, 0, wire, 0, None)])
        scene(f"wire:comparator:{meta}", [(1, 0, 1, "unpowered_comparator", meta, None), (0, 0, 1, wire, 0, None)])
    for source, meta in (("redstone_torch", 5), ("unlit_redstone_torch", 5), ("lever", 5), ("stone_pressure_plate", 0),
                         ("wooden_pressure_plate", 0), ("light_weighted_pressure_plate", 0),
                         ("heavy_weighted_pressure_plate", 0), ("redstone_block", 0), ("trapped_chest", 2),
                         ("daylight_detector", 0), ("detector_rail", 0), ("chest", 2), ("redstone_lamp", 0),
                         ("stone", 0), ("glass", 0)):
        scene(f"wire:next_to:{source}", [(0, 0, 0, wire, 0, None), (1, 0, 0, source, meta, None)])
    for block, meta in (("stone", 0), ("glowstone", 0), ("glass", 0), ("stone_slab", 0), ("stone_slab", 8),
                        ("leaves", 4), ("double_stone_slab", 0), ("oak_stairs", 4), ("hopper", 0)):
        scene(f"wire:climb:{block}:{meta}", [(0, 0, 0, wire, 0, None), (1, 0, 0, block, meta, None), (1, 1, 0, wire, 0, None)])
    scene("wire:climb_blocked", [(0, 0, 0, wire, 0, None), (0, 1, 0, "stone", 0, None), (1, 0, 0, "stone", 0, None),
                                 (1, 1, 0, wire, 0, None)])
    scene("wire:climb_glass_cover", [(0, 0, 0, wire, 0, None), (0, 1, 0, "glass", 0, None), (1, 0, 0, "stone", 0, None),
                                     (1, 1, 0, wire, 0, None)])
    scene("wire:down_step", [(0, 1, 0, "stone", 0, None), (0, 2, 0, wire, 0, None), (1, 0, 0, wire, 0, None)])

    # repeater locking: center repeater, powered repeater / comparator at a side (every orientation)
    for center in range(4):
        for side in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for meta in range(4):
                scene(f"repeater:{center}:side{side[0]},{side[1]}:{meta}",
                      [(1, 0, 1, "unpowered_repeater", center, None),
                       (1 + side[0], 0, 1 + side[1], "powered_repeater", meta, None)])
        scene(f"repeater:{center}:comparator", [(1, 0, 1, "unpowered_repeater", center, None),
                                                (2, 0, 1, "powered_comparator", 1 + 8, None),
                                                (0, 0, 1, "powered_comparator", 3 + 8, None)])

    # tripwire with hooks (stone behind the hooks)
    for a in range(4):
        for b in range(4):
            scene(f"tripwire:x:{a}:{b}", [(0, 0, 0, "stone", 0, None), (6, 0, 0, "stone", 0, None),
                                          (1, 0, 0, "tripwire_hook", a, None), (5, 0, 0, "tripwire_hook", b, None)] +
                  [(i, 0, 0, "tripwire", 0, None) for i in (2, 3, 4)])
            scene(f"tripwire:z:{a}:{b}", [(0, 0, 0, "stone", 0, None), (0, 0, 6, "stone", 0, None),
                                          (0, 0, 1, "tripwire_hook", a, None), (0, 0, 5, "tripwire_hook", b, None)] +
                  [(0, 0, i, "tripwire", 0, None) for i in (2, 3, 4)])
    scene("tripwire:alone", [(0, 0, 0, "tripwire", 0, None)])

    # vines (1.7 meta bits: 1 south, 2 west, 4 north, 8 east; 0 = hanging from the block above)
    scene("vine:on_stone", [(0, 0, 0, "stone", 0, None), (0, 0, 1, "vine", 4, None)])
    scene("vine:under_stone", [(0, 1, 0, "stone", 0, None), (0, 0, 0, "vine", 0, None)])
    scene("vine:under_stone_and_side", [(0, 1, 1, "stone", 0, None), (0, 0, 0, "stone", 0, None), (0, 0, 1, "vine", 4, None)])
    scene("vine:hanging_from_vine", [(0, 1, 0, "stone", 0, None), (0, 1, 1, "vine", 4, None), (0, 0, 1, "vine", 4, None)])
    for block, meta in (("leaves", 0), ("glass", 0), ("log", 0), ("glowstone", 0), ("stone_slab", 0), ("ice", 0)):
        scene(f"vine:on:{block}:{meta}", [(0, 0, 0, block, meta, None), (0, 0, 1, "vine", 4, None)])
    scene("vine:unsupported", [(0, 0, 0, "vine", 1, None)])
    scene("vine:two_sides", [(0, 0, 1, "stone", 0, None), (1, 0, 0, "stone", 0, None), (0, 0, 0, "vine", 1 | 8, None)])

    # fire (doFireTick is off): on netherrack, floating next to flammable blocks, on flammable blocks
    scene("fire:netherrack", [(0, -1, 0, "netherrack", 0, None), (0, 0, 0, "fire", 0, None)])
    for block, meta in (("planks", 0), ("wool", 0), ("leaves", 0), ("log", 0), ("bookshelf", 0), ("tnt", 0),
                        ("stone", 0), ("fence", 0), ("hay_block", 0)):
        scene(f"fire:beside:{block}", [(0, 1, 0, block, meta, None), (1, 1, 0, "fire", 0, None), (1, 2, 0, block, meta, None)])
        scene(f"fire:on:{block}", [(0, 0, 0, block, meta, None), (0, 1, 0, "fire", 3, None)])

    # stems
    for stem, fruit in (("pumpkin_stem", "pumpkin"), ("melon_stem", "melon_block")):
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            scene(f"{stem}:7:{dx},{dz}", [(1, -1, 1, "farmland", 0, None), (1, 0, 1, stem, 7, None),
                                          (1 + dx, 0, 1 + dz, fruit, 0, None)])
        scene(f"{stem}:5:fruit", [(0, -1, 0, "farmland", 0, None), (0, 0, 0, stem, 5, None), (1, 0, 0, fruit, 0, None)])
        scene(f"{stem}:7:two", [(1, -1, 1, "farmland", 0, None), (1, 0, 1, stem, 7, None), (2, 0, 1, fruit, 0, None),
                                (1, 0, 0, fruit, 0, None)])
        scene(f"{stem}:7:alone", [(0, -1, 0, "farmland", 0, None), (0, 0, 0, stem, 7, None)])
    return out


def place_scenes(w, shelf):
    for label, blocks in scenes():
        xs = [b[0] for b in blocks]
        zs = [b[2] for b in blocks]
        ox, oz = shelf.place(max(xs) - min(xs) + 1, max(zs) - min(zs) + 1)
        # support / structure blocks first: everything that is not listed later on top of it
        for dx, dy, dz, block, meta, nbt in blocks:
            w.set(ox + dx - min(xs), Y + dy, oz + dz - min(zs), block, meta, nbt, label=f"scene:{label}")


def borders(w):
    """Scenarios across chunk borders; each border segment holds only its scenario."""
    cases = []

    def case(label, blocks):
        cases.append((label, blocks))

    # blocks are relative to a border at dx = 0 | 1 (x = 15 | 16 of the chunk pair)
    case("fence", [(-1, 0, 0, "fence", 0), (0, 0, 0, "fence", 0), (1, 0, 0, "fence", 0), (2, 0, 0, "fence", 0)])
    case("pane", [(0, 0, 0, "glass_pane", 0), (1, 0, 0, "glass_pane", 0)])
    case("bars", [(0, 0, 0, "iron_bars", 0), (1, 0, 0, "stone", 0)])
    case("wall", [(0, 0, 0, "cobblestone_wall", 0), (1, 0, 0, "cobblestone_wall", 0), (2, 0, 0, "cobblestone_wall", 0)])
    case("wall_gate", [(0, 0, 0, "cobblestone_wall", 0), (1, 0, 0, "fence_gate", 0)])
    case("stairs_outer", [(0, 0, 0, "oak_stairs", 0), (1, 0, 0, "oak_stairs", 2)])
    case("stairs_inner", [(0, 0, 0, "oak_stairs", 1), (1, 0, 0, "oak_stairs", 2)])
    case("chest_pair", [(0, 0, 0, "chest", 2), (1, 0, 0, "chest", 2)])
    case("trapped_pair", [(0, 0, 0, "trapped_chest", 3), (1, 0, 0, "trapped_chest", 3)])
    case("wire", [(-1, 0, 0, "redstone_wire", 0), (0, 0, 0, "redstone_wire", 0), (1, 0, 0, "redstone_wire", 0)])
    case("wire_climb", [(0, 0, 0, "redstone_wire", 0), (1, 0, 0, "stone", 0), (1, 1, 0, "redstone_wire", 0)])
    case("repeater_locked", [(0, 0, 0, "unpowered_repeater", 0), (1, 0, 0, "powered_repeater", 1)])
    case("repeater_locked2", [(0, 0, 0, "unpowered_repeater", 0), (1, 0, 0, "powered_repeater", 3)])
    case("tripwire", [(-2, 0, 0, "stone", 0), (-1, 0, 0, "tripwire_hook", 3), (0, 0, 0, "tripwire", 0),
                      (1, 0, 0, "tripwire", 0), (2, 0, 0, "tripwire_hook", 1), (3, 0, 0, "stone", 0)])
    case("vine", [(0, 0, 0, "vine", 8), (1, 0, 0, "stone", 0)])
    case("fire", [(0, 1, 0, "fire", 0), (1, 1, 0, "planks", 0)])
    case("stem", [(0, -1, 0, "farmland", 0), (0, 0, 0, "pumpkin_stem", 7), (1, 0, 0, "pumpkin", 0)])
    case("bed", [(0, 0, 0, "bed", 3), (1, 0, 0, "bed", 11)])
    case("gate_fence", [(0, 0, 0, "fence_gate", 0), (1, 0, 0, "fence", 0)])
    case("pane_wall", [(0, 0, 0, "glass_pane", 0), (1, 0, 0, "cobblestone_wall", 0)])

    # x borders between chunk columns 0|1, 2|3, 4|5, 6|7 ; z rows 1, 3, 5 ... (local z = 7), one case each
    slots = [(cx * 16 + 15, cz * 16 + 7) for cz in range(0, 8) for cx in (0, 2, 4, 6)]
    for (label, blocks), (bx, bz) in zip(cases, slots):
        for dx, dy, dz, block, meta in blocks:
            w.set(bx + dx, Y + dy, bz + dz, block, meta, label=f"border-x:{label}")
    # the same along z borders (rotated: dx -> dz) in chunks x 1, 3, 5, 7 (local x = 7)
    rot = {("oak_stairs", 0): 2, ("oak_stairs", 1): 3, ("oak_stairs", 2): 0, ("chest", 2): 4, ("trapped_chest", 3): 5,
           ("unpowered_repeater", 0): 1, ("powered_repeater", 1): 2, ("powered_repeater", 3): 0,
           ("tripwire_hook", 3): 0, ("tripwire_hook", 1): 2, ("vine", 8): 1, ("bed", 3): 0, ("bed", 11): 8,
           ("fence_gate", 0): 1}
    zslots = [(cx * 16 + 7, cz * 16 + 15) for cz in (0, 2, 4, 6) for cx in (1, 3, 5, 7)]
    for (label, blocks), (bx, bz) in zip(cases, zslots):
        for dx, dy, dz, block, meta in blocks:
            w.set(bx + dz, Y + dy, bz + dx, block, rot.get((block, meta), meta), label=f"border-z:{label}")


# ---------------------------------------------------------------- servers

def write_properties(server_dir, port, rcon_port, level):
    (server_dir / "server.properties").write_text("\n".join([
        f"server-port={port}", "server-ip=127.0.0.1", "enable-rcon=true", f"rcon.port={rcon_port}",
        f"rcon.password={PASSWORD}", f"level-name={level}", "level-type=FLAT", "generator-settings=2;7,2x3,2;1;",
        "level-seed=1710", "generate-structures=false", "spawn-animals=false", "spawn-monsters=false",
        "spawn-npcs=false", "online-mode=false", "spawn-protection=0", "difficulty=0", "max-tick-time=-1",
        "view-distance=4", "allow-nether=false", "",
    ]))
    (server_dir / "eula.txt").write_text("eula=true\n")


def java(server_dir, jar, args, log_name, ready=r"RCON running on"):
    log = open(server_dir / log_name, "w")
    cmd = ["mise", "exec", JAVA8, "--", "java", "-Xmx3G", "-jar", jar] + args + ["nogui"]
    proc = subprocess.Popen(cmd, cwd=server_dir, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + 900
    while time.time() < deadline:
        if proc.poll() is not None:
            raise SystemExit(f"server exited early, see {server_dir / log_name}")
        if re.search(ready, (server_dir / log_name).read_text(errors="replace")):
            return proc
        time.sleep(0.5)
    proc.kill()
    raise SystemExit("server did not become ready")


def stop(proc, rcon):
    rcon.command("stop")
    rcon.close()
    proc.wait(timeout=300)


def build(out):
    server = WORKSPACE / "servers/vanilla-1.7.10"
    server.mkdir(parents=True, exist_ok=True)
    shutil.copy(WORKSPACE / "downloads/mc/minecraft_server.1.7.10.jar", server)
    shutil.rmtree(server / "world", ignore_errors=True)
    write_properties(server, 25570, 25580, "world")

    w = World()
    sweep(w)
    matrix(w)
    place_scenes(w, Shelf(range(0, 8), range(-8, 0)))
    borders(w)
    print(f"{len(w.ops)} placements")

    # first boot creates the world; move the spawn to the origin and restart so the spawn chunks are around it
    proc = java(server, "minecraft_server.1.7.10.jar", [], "oracle-build.log")
    rcon = RconClient("127.0.0.1", 25580, PASSWORD, timeout=120)
    rcon.connect()
    for rule in ("doDaylightCycle", "doMobSpawning", "doFireTick", "mobGriefing", "doTileDrops"):
        rcon.command(f"gamerule {rule} false")
    rcon.command("setworldspawn 0 4 0")
    stop(proc, rcon)
    proc = java(server, "minecraft_server.1.7.10.jar", [], "oracle-build.log")
    rcon = RconClient("127.0.0.1", 25580, PASSWORD, timeout=120)
    rcon.connect()

    failed = []
    for x, y, z, bid, meta, nbt, label in w.ops:
        cmd = f"setblock {x} {y} {z} {NAMES[bid]} {meta} replace" + (f" {nbt}" if nbt else "")
        reply = rcon.command(cmd)
        if not reply.startswith("Block placed"):
            failed.append({"pos": [x, y, z], "block": f"{NAMES[bid]}:{meta}", "label": label, "reply": reply.strip()})
    time.sleep(5)  # let scheduled ticks run (fluids, falling blocks) before the snapshot
    # verify every last placement per position (later placements replace earlier ones)
    final = {}
    for x, y, z, bid, meta, nbt, label in w.ops:
        final[(x, y, z)] = (bid, meta, label)
    changed = []
    for (x, y, z), (bid, meta, label) in final.items():
        reply = rcon.command(f"testforblock {x} {y} {z} {NAMES[bid]} {meta}")
        if "Successfully" not in reply:
            changed.append({"pos": [x, y, z], "block": f"{NAMES[bid]}:{meta}", "label": label, "reply": reply.strip()})
    rcon.command("save-all")
    time.sleep(3)
    stop(proc, rcon)

    shutil.rmtree(out / "world-1.7.10", ignore_errors=True)
    shutil.copytree(server / "world", out / "world-1.7.10")
    manifest = {
        "placements": len(w.ops),
        "positions": len(final),
        "failed": failed,
        "changed_after_placement": changed,
        "labels": w.labels,
        "box": [-128, 0, -128, 127, 12, 143],
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1))
    print(f"placed {len(w.ops) - len(failed)}/{len(w.ops)}, positions {len(final)}, changed afterwards {len(changed)}")


# ---------------------------------------------------------------- 1.13.2 upgrade

def read_nbt(data):
    """Minimal NBT reader (returns python values; compounds as dicts)."""
    buf = io.BytesIO(data)

    def rd(fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(">" + fmt, buf.read(size))[0]

    def string():
        return buf.read(rd("H")).decode("utf-8", "replace")

    def payload(tag):
        if tag == 1: return rd("b")
        if tag == 2: return rd("h")
        if tag == 3: return rd("i")
        if tag == 4: return rd("q")
        if tag == 5: return rd("f")
        if tag == 6: return rd("d")
        if tag == 7: return buf.read(rd("i"))
        if tag == 8: return string()
        if tag == 9:
            t, n = rd("b"), rd("i")
            return [payload(t) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                t = rd("b")
                if t == 0:
                    return out
                name = string()
                out[name] = payload(t)
        if tag == 11: return [rd("i") for _ in range(rd("i"))]
        if tag == 12: return [rd("q") for _ in range(rd("i"))]
        raise ValueError(tag)

    tag = rd("b")
    string()
    return payload(tag)


def pending_upgrades(world):
    """Chunks that still carry non-empty UpgradeData (the 1.13 shape updates were not applied)."""
    pending = []
    for region in sorted((world / "region").glob("r.*.mca")):
        data = region.read_bytes()
        for i in range(1024):
            loc = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
            if loc == 0:
                continue
            off = (loc >> 8) * 4096
            length = struct.unpack(">I", data[off:off + 4])[0]
            chunk = read_nbt(zlib.decompress(data[off + 5:off + 4 + length]))
            level = chunk.get("Level", {})
            upgrade = level.get("UpgradeData")
            if upgrade and (upgrade.get("Sides") or any(upgrade.get("Indices", {}).values())):
                pending.append((level.get("xPos"), level.get("zPos")))
    return pending


def mark_light_populated(world):
    """Sets `LightPopulated` on every chunk of the copy: 1.13.2 regenerates (wipes) chunks whose light was not
    populated in 1.7, which says nothing about block conversion. Rewrites each region file."""
    flag = b"\x01\x00\x0eLightPopulated"
    for region in sorted((world / "region").glob("r.*.mca")):
        data = region.read_bytes()
        chunks = []
        for i in range(1024):
            loc = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
            if loc == 0:
                continue
            off = (loc >> 8) * 4096
            length = struct.unpack(">I", data[off:off + 4])[0]
            nbt = bytearray(zlib.decompress(data[off + 5:off + 4 + length]))
            at = nbt.find(flag)
            if at >= 0:
                nbt[at + len(flag)] = 1
            stamp = data[4096 + i * 4:4096 + i * 4 + 4]
            chunks.append((i, stamp, zlib.compress(bytes(nbt))))
        header = bytearray(8192)
        body = bytearray()
        for i, stamp, payload in chunks:
            sector = 2 + len(body) // 4096
            record = struct.pack(">IB", len(payload) + 1, 2) + payload
            record += b"\x00" * (-len(record) % 4096)
            header[i * 4:i * 4 + 4] = struct.pack(">I", (sector << 8) | (len(record) // 4096))
            header[4096 + i * 4:4096 + i * 4 + 4] = stamp
            body += record
        region.write_bytes(bytes(header + body))


def upgrade(out):
    server = WORKSPACE / "servers/vanilla-1.13.2"
    server.mkdir(parents=True, exist_ok=True)
    shutil.copy(WORKSPACE / "downloads/mc/minecraft_server.1.13.2.jar", server)
    shutil.rmtree(server / "world", ignore_errors=True)
    shutil.copytree(out / "world-1.7.10", server / "world")
    mark_light_populated(server / "world")
    write_properties(server, 25571, 25581, "world")
    proc = java(server, "minecraft_server.1.13.2.jar", ["--forceUpgrade"], "oracle-upgrade.log")
    rcon = RconClient("127.0.0.1", 25581, PASSWORD, timeout=120)
    rcon.connect()
    rcon.command("gamerule randomTickSpeed 0")
    time.sleep(5)  # a few ticks with the spawn chunks loaded: UpgradeData is applied when chunks become ticking
    rcon.command("save-all flush")
    time.sleep(2)
    stop(proc, rcon)
    shutil.rmtree(out / "world-1.13.2", ignore_errors=True)
    shutil.copytree(server / "world", out / "world-1.13.2")
    pending = pending_upgrades(out / "world-1.13.2")
    print(f"chunks with pending UpgradeData: {len(pending)} {pending[:20]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("step", choices=["build", "upgrade", "all"])
    parser.add_argument("--out", default=str(WORKSPACE / "out/flattening-oracle"))
    args = parser.parse_args()
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    if args.step in ("build", "all"):
        build(out)
    if args.step in ("upgrade", "all"):
        upgrade(out)


if __name__ == "__main__":
    main()
