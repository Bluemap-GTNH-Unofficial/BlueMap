"""Regenerates the :legacy test fixtures (src/test/resources/fixtures).

Usage (from the BlueMap-GTNH repo root):
    uv run --no-project --with nbtlib python legacy/src/test/python/make_fixtures.py <path/to/gtnh-world>

The world is a freshly generated GTNH 2.9.0-RC-2 world (worlds/gtnh-initial-rwg in the port workspace); it is only read.
"""
import io, os, struct, sys, zlib
import nbtlib
from nbtlib import File
from nbtlib.tag import *

W = sys.argv[1] if len(sys.argv) > 1 else "../worlds/gtnh-initial-rwg"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "resources", "fixtures")
os.makedirs(OUT, exist_ok=True)


def raw_chunk(region, cx, cz):
    data = open(region, "rb").read()
    i = (cz & 31) << 5 | (cx & 31)
    off = int.from_bytes(data[i*4:i*4+3], "big")
    start = off * 4096
    length = struct.unpack(">i", data[start:start+4])[0]
    comp = data[start+4]
    assert comp == 2
    return data[start+5:start+4+length]

def load_chunk(region, cx, cz):
    return nbtlib.File.parse(io.BytesIO(zlib.decompress(raw_chunk(region, cx, cz))))

def ub(a, i):
    return int(a[i]) & 0xFF

def nib(a, i):
    return (ub(a, i >> 1) >> ((i & 1) << 2)) & 0xF

def block_id(s, i):
    bid = ub(s["Blocks"], i)
    if "Add" in s:
        bid |= nib(s["Add"], i) << 8
        if "BlocksB2Hi" in s:
            bid |= nib(s["BlocksB2Hi"], i) << 12
            if "BlocksB3" in s:
                bid |= ub(s["BlocksB3"], i) << 16
    return bid

def block_meta(s, i):
    m = nib(s["Data"], i)
    if "Data1High" in s:
        m |= nib(s["Data1High"], i) << 4
        if "Data2" in s:
            m |= ub(s["Data2"], i) << 8
    return m


# 1. real EndlessIDs chunk (-10,-4): BlocksB2Hi + Data2 present
raw = raw_chunk(W + "/region/r.-1.-1.mca", -10, -4)
open(OUT + "/endlessids_-10_-4.zlib", "wb").write(raw)

# used block ids in that chunk
lvl = nbtlib.File.parse(io.BytesIO(zlib.decompress(raw)))["Level"]
used = set()
for s in lvl["Sections"]:
    for i in range(4096):
        used.add(block_id(s, i))

def nibbles(values):
    out = bytearray(len(values) // 2)
    for i, v in enumerate(values):
        out[i >> 1] |= (v & 0xF) << ((i & 1) << 2)
    return out

def idx(x, y, z):
    return (y & 15) << 8 | z << 4 | x

def barr(b):
    return ByteArray([v - 256 if v > 127 else v for v in b])

# 2. synthetic NotEnoughIDs chunk
b16 = [0] * 4096; d16 = [0] * 4096
b16[idx(0, 0, 0)] = 11302; d16[idx(0, 0, 0)] = 0
b16[idx(1, 2, 3)] = 3034;  d16[idx(1, 2, 3)] = 8877
b16[idx(4, 5, 6)] = 20000; d16[idx(4, 5, 6)] = 3      # unknown id
b16[idx(15, 15, 15)] = 1;  d16[idx(15, 15, 15)] = 0
sky = [0] * 4096; blk = [0] * 4096
sky[idx(1, 2, 3)] = 9; blk[idx(1, 2, 3)] = 4
sky[idx(2, 2, 3)] = 15; blk[idx(2, 2, 3)] = 14
sec0 = Compound({
    "Y": Byte(0),
    "Blocks16": barr(b"".join(struct.pack(">H", v) for v in b16)),
    "Data16": barr(b"".join(struct.pack(">H", v) for v in d16)),
    # conflicting vanilla copy (PostNeidWorldsSupport): must be ignored
    "Blocks": barr(bytes([1] * 4096)),
    "Data": barr(bytes(2048)),
    "BlockLight": barr(nibbles(blk)),
    "SkyLight": barr(nibbles(sky)),
})
# vanilla / EndlessIDs layout without Blocks16: id 3005 (0xBBD), meta 0x27 via Data1High (no Data2)
blocks = [0] * 4096; add = [0] * 4096; data = [0] * 4096; d1h = [0] * 4096
p = idx(2, 19, 4)
blocks[p] = 0xBD; add[p] = 0xB; data[p] = 7; d1h[p] = 2
p2 = idx(3, 19, 4)
blocks[p2] = 2  # grass, id 2 meta 0
sec1 = Compound({
    "Y": Byte(1),
    "Blocks": barr(bytes(blocks)),
    "Add": barr(nibbles(add)),
    "Data": barr(nibbles(data)),
    "Data1High": barr(nibbles(d1h)),
    "BlockLight": barr(bytes(2048)),
    "SkyLight": barr(bytes([0xFF] * 2048)),
})
biomes16 = bytearray(512); biomes16[3 * 16 + 5] = 4; biomes16[256 + 3 * 16 + 5] = 1  # high byte ignored by EndlessIDs
hm = [0] * 256; hm[3 * 16 + 5] = 20
neid = File({"Level": Compound({
    "V": Byte(1), "xPos": Int(3), "zPos": Int(-2), "LastUpdate": Long(5),
    "TerrainPopulated": Byte(1), "LightPopulated": Byte(1), "InhabitedTime": Long(42),
    "HeightMap": IntArray(hm),
    "Biomes16": barr(biomes16),
    "Biomes": barr(bytes([1] * 256)),
    "Sections": List[Compound]([sec0, sec1]),
    "Entities": List[Compound]([]),
    "TileEntities": List[Compound]([Compound({"id": String("Chest"), "x": Int(3 * 16 + 1), "y": Int(2), "z": Int(-2 * 16 + 3), "Items": List[Compound]([])})]),
    "POSTEA": Long(123),
    "SomeMod": Compound({"foo": Int(1)}),
})})
buf = io.BytesIO(); neid.write(buf)
open(OUT + "/neid_synthetic.zlib", "wb").write(zlib.compress(buf.getvalue()))

# 3. synthetic vanilla chunk: vanilla Biomes byte array (unsigned), not populated, no light populated
vb = bytearray(256); vb[0] = 0x81; vb[1] = 0xFF; vb[2] = 3
vanilla = File({"Level": Compound({
    "V": Byte(1), "xPos": Int(0), "zPos": Int(0),
    "TerrainPopulated": Byte(0), "LightPopulated": Byte(0), "InhabitedTime": Long(0),
    "HeightMap": IntArray([0] * 256),
    "Biomes": barr(vb),
    "Sections": List[Compound]([Compound({
        "Y": Byte(4),
        "Blocks": barr(bytes([1] * 4096)),
        "Data": barr(bytes(2048)),
        "BlockLight": barr(bytes(2048)),
        "SkyLight": barr(bytes(2048)),
    })]),
    "TileEntities": List[Compound]([]),
})})
buf = io.BytesIO(); vanilla.write(buf)
open(OUT + "/vanilla_synthetic.zlib", "wb").write(zlib.compress(buf.getvalue()))

# 4. trimmed level.dat: real FML.ItemData entries for used blocks + a few extras, plus a synthetic alias
lvl_dat = nbtlib.load(W + "/level.dat")
fml = lvl_dat["FML"]
want_blocks = used | {3005, 3053, 2}
items = []
for e in fml["ItemData"]:
    k = str(e["K"]); v = int(e["V"])
    if (k[0] == "\u0001" and v in want_blocks) or (k[0] == "\u0002" and v in (1, 3005, 32000)):
        items.append(Compound({"K": String(k), "V": Int(v)}))
items.append(Compound({"K": String("\u0001oldmod:old_block"), "V": Int(4000)}))
items.append(Compound({"K": String("\u0002fixture:item_only"), "V": Int(20000)}))
trimmed = File({
    "Data": Compound({"LevelName": String("fixture")}),
    "FML": Compound({
        "ItemData": List[Compound](items),
        "BlockAliases": List[Compound]([Compound({"K": String("oldmod:old_block"), "V": String("newmod:new_block")})]),
        "BlockSubstitutions": List[Compound]([]),
        "ItemAliases": List[Compound]([]),
    }),
}, gzipped=True)
trimmed.save(OUT + "/level.dat")

reg = {int(e["V"]): str(e["K"])[1:] for e in items if str(e["K"])[0] == "\u0001"}
print("block entries", len(reg), sorted(reg.items()))
print("items", [(str(e["K"]), int(e["V"])) for e in items if str(e["K"])[0] == "\u0002"])
