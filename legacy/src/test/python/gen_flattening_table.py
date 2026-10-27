#!/usr/bin/env python3
"""Generates legacy/src/main/resources/de/bluecolored/bluemap/gtnh/legacy/flattening_1_7_10.tsv.

One line per vanilla 1.7.10 block id (1..175) and meta (0..15):
    <id> TAB <1.7.10 registry name> TAB <meta> TAB <complete 1.13.2 block state> TAB <registered 1|0>

Pipeline:
1. PrismarineJS minecraft-data `data/pc/common/legacy.json` (MIT, pinned) gives `id:meta -> 1.13 state`.
   Metas it does not list fall back to the lowest listed meta of the block (what 1.7 renders for an unused meta).
   `registered` = 0 marks those fallbacks (plus UNREGISTERED): metas outside Mojang's flattening table, which a
   real 1.13.2 upgrade turns into air (observed in the oracle world); the comparator reports them separately.
2. CORRECTIONS / correct(): entries where legacy.json disagrees with what Mojang's 1.13 upgrade actually writes
   (found by cross-checking ViaVersion `mapping-1.12.json` and Mojang's table as transcribed by PaperMC DataConverter,
   confirmed by the 1.13.2 oracle world, see `oracle_world.py`). Mostly invalid / rarely used metas.
3. Every state is completed with the defaults of the 1.13.2 block-state report, which is what a 1.13.2 server writes
   when it re-saves a converted chunk (and what resource-pack variant matching needs, e.g. `oak_wood[axis=y]`).
   Report: `java -cp minecraft_server.1.13.2.jar net.minecraft.data.Main --reports` -> generated/reports/blocks.json.
4. Cross-check (informational, nothing is copied): differences to ViaVersion's mapping are printed.

1.7.10 registry names: `vanilla_1_7_10_blocks.json` next to this script (FML.ItemData `minecraft:*` blocks of a
Forge 1.7.10 level.dat).

Usage: python3 gen_flattening_table.py --blocks-report generated/reports/blocks.json
"""
import argparse
import json
import pathlib
import sys
import urllib.request

LEGACY_JSON = ("https://raw.githubusercontent.com/PrismarineJS/minecraft-data/"
               "4235c91366fe094e0c14abc8e3cfe841b8c0c0ff/data/pc/common/legacy.json")
VIA_JSON = ("https://raw.githubusercontent.com/ViaVersion/Mappings/"
            "f686dfcbb1c33264191cd432aa734bb6bc777869/mappings/mapping-1.12.json")
MAX_ID = 175

HERE = pathlib.Path(__file__).resolve().parent
LEGACY_ROOT = HERE.parents[2]
OUT = LEGACY_ROOT / "src/main/resources/de/bluecolored/bluemap/gtnh/legacy/flattening_1_7_10.tsv"

# Listed by legacy.json, but not by Mojang's flattening table (a 1.13.2 upgrade turns them into air).
UNREGISTERED = {"26:4", "26:5", "26:6", "26:7", "155:6", "155:10", "175:12", "175:13"}

# Irregular metas where Mojang's flattening differs from legacy.json.
CORRECTIONS = {
    # upper door halves with the unused bit 4
    "64:12": "minecraft:oak_door[facing=east,half=upper,hinge=left,open=true,powered=false]",
    "64:13": "minecraft:oak_door[facing=south,half=upper,hinge=left,open=true,powered=false]",
    "64:14": "minecraft:oak_door[facing=west,half=upper,hinge=left,open=true,powered=false]",
    "64:15": "minecraft:oak_door[facing=north,half=upper,hinge=left,open=true,powered=false]",
    "71:12": "minecraft:iron_door[facing=east,half=upper,hinge=left,open=true,powered=false]",
    "71:13": "minecraft:iron_door[facing=south,half=upper,hinge=left,open=true,powered=false]",
    "71:14": "minecraft:iron_door[facing=west,half=upper,hinge=left,open=true,powered=false]",
    "71:15": "minecraft:iron_door[facing=north,half=upper,hinge=left,open=true,powered=false]",
    # lever floor/ceiling orientation
    "69:0": "minecraft:lever[face=ceiling,facing=west,powered=false]",
    "69:5": "minecraft:lever[face=floor,facing=north,powered=false]",
    "69:6": "minecraft:lever[face=floor,facing=west,powered=false]",
    "69:7": "minecraft:lever[face=ceiling,facing=north,powered=false]",
    "69:8": "minecraft:lever[face=ceiling,facing=west,powered=true]",
    "69:13": "minecraft:lever[face=floor,facing=north,powered=true]",
    "69:14": "minecraft:lever[face=floor,facing=west,powered=true]",
    "69:15": "minecraft:lever[face=ceiling,facing=north,powered=true]",
    # buttons: floor/ceiling buttons face north
    "77:0": "minecraft:stone_button[face=ceiling,facing=north,powered=false]",
    "77:5": "minecraft:stone_button[face=floor,facing=north,powered=false]",
    "77:8": "minecraft:stone_button[face=ceiling,facing=north,powered=true]",
    "77:13": "minecraft:stone_button[face=floor,facing=north,powered=true]",
    "143:0": "minecraft:oak_button[face=ceiling,facing=north,powered=false]",
    "143:5": "minecraft:oak_button[face=floor,facing=north,powered=false]",
    "143:8": "minecraft:oak_button[face=ceiling,facing=north,powered=true]",
    "143:13": "minecraft:oak_button[face=floor,facing=north,powered=true]",
    # flower pot contents come from the block entity only (no block entity: empty pot)
    "140:1": "minecraft:flower_pot", "140:2": "minecraft:flower_pot", "140:3": "minecraft:flower_pot",
    "140:4": "minecraft:flower_pot", "140:5": "minecraft:flower_pot", "140:6": "minecraft:flower_pot",
    "140:7": "minecraft:flower_pot", "140:8": "minecraft:flower_pot", "140:9": "minecraft:flower_pot",
    "140:10": "minecraft:flower_pot", "140:11": "minecraft:flower_pot", "140:12": "minecraft:flower_pot",
    "140:13": "minecraft:flower_pot", "140:14": "minecraft:flower_pot", "140:15": "minecraft:flower_pot",
    # tripwire metas with the 1.7 "suspended" bit 2
    "132:3": "minecraft:tripwire[attached=false,disarmed=false,powered=true]",
    "132:6": "minecraft:tripwire[attached=true,disarmed=false,powered=false]",
    "132:7": "minecraft:tripwire[attached=true,disarmed=false,powered=true]",
    "132:10": "minecraft:tripwire[attached=false,disarmed=true,powered=false]",
    "132:11": "minecraft:tripwire[attached=false,disarmed=true,powered=true]",
    "132:14": "minecraft:tripwire[attached=true,disarmed=true,powered=false]",
    # upper double-plant halves: the type comes from the lower half (neighbour fix), alone they are peony
    "175:8": "minecraft:peony[half=upper]",
    "175:9": "minecraft:peony[half=upper]",
    "175:10": "minecraft:peony[half=upper]",
    "175:11": "minecraft:peony[half=upper]",
}


def correct(name, block, meta, state):
    """Regular corrections (whole blocks)."""
    base, props = parse(state)
    if base.endswith("_stairs"):
        props["shape"] = "straight"  # recomputed from the neighbours anyway
    elif base == "minecraft:vine":
        props["up"] = "true"  # recomputed from the block above anyway
    elif name in ("minecraft:unpowered_comparator", "minecraft:powered_comparator"):
        props["powered"] = str(meta & 8 != 0).lower()  # Mojang reads the meta bit, not the block id
    elif name == "minecraft:trapdoor":
        props["powered"] = "false"
    return format_state(base, props)


def fetch_json(url):
    with urllib.request.urlopen(url) as r:
        return json.load(r)


def parse(state):
    name, _, props = state.partition("[")
    if ":" not in name:
        name = "minecraft:" + name
    return name, dict(kv.split("=", 1) for kv in props.rstrip("]").split(",") if kv)


def format_state(name, props):
    return name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")


def with_fallback(table):
    out = {}
    for block in range(MAX_ID + 1):
        present = sorted(m for m in range(16) if (block << 4 | m) in table)
        for meta in range(16):
            if present:
                out[block << 4 | meta] = table.get(block << 4 | meta, table[block << 4 | present[0]])
    return out


def complete(state, defaults):
    name, props = parse(state)
    if name not in defaults:
        raise SystemExit(f"unknown 1.13.2 block {name}")
    full = dict(defaults[name])
    for k, v in props.items():
        if k not in full:
            raise SystemExit(f"unknown property {k} of {name}")
        full[k] = v
    return format_state(name, full)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--blocks-report", required=True)
    parser.add_argument("--ids", default=str(HERE / "vanilla_1_7_10_blocks.json"))
    args = parser.parse_args()

    names = {int(k): v for k, v in json.loads(pathlib.Path(args.ids).read_text())["blocks"].items()}
    defaults = {}
    for block, data in json.loads(pathlib.Path(args.blocks_report).read_text()).items():
        default = next(s for s in data["states"] if s.get("default"))
        defaults[block] = default.get("properties", {})

    legacy = {}
    for key, state in fetch_json(LEGACY_JSON)["blocks"].items():
        block, meta = map(int, key.split(":"))
        legacy[block << 4 | meta] = state
    listed = set(legacy)
    legacy = with_fallback(legacy)
    via = with_fallback({int(k): v for k, v in fetch_json(VIA_JSON)["blocks"].items()})

    lines = []
    differences = 0
    for block in sorted(names):
        if block == 0 or block > MAX_ID:
            continue
        for meta in range(16):
            packed = block << 4 | meta
            key = f"{block}:{meta}"
            state = CORRECTIONS.get(key) or correct(names[block], block, meta, legacy[packed])
            state = complete(state, defaults)
            registered = (packed in listed or key in CORRECTIONS) and key not in UNREGISTERED
            lines.append(f"{block}\t{names[block]}\t{meta}\t{state}\t{int(registered)}")
            other = complete(via[packed], defaults) if packed in via else None
            if other != state and not state.startswith("minecraft:skeleton_skull"):
                differences += 1
                print(f"viaversion differs: {block}:{meta} table={state} via={other}", file=sys.stderr)
    OUT.write_text("\n".join(lines) + "\n")
    print(f"wrote {len(lines)} entries to {OUT}; differences to ViaVersion: {differences}")


if __name__ == "__main__":
    main()
