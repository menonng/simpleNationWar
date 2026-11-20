"""작은 Anvil(.mca) + NBT 리더와 바닐라 지도 색 렌더러 (예시 이미지용)."""
import json, math, os, struct, zlib
import numpy as np

class R:
    def __init__(self, b): self.b, self.i = b, 0
    def take(self, n):
        v = self.b[self.i:self.i+n]; self.i += n; return v
    def u8(self): return self.take(1)[0]
    def i16(self): return struct.unpack('>h', self.take(2))[0]
    def u16(self): return struct.unpack('>H', self.take(2))[0]
    def i32(self): return struct.unpack('>i', self.take(4))[0]
    def i64(self): return struct.unpack('>q', self.take(8))[0]

def payload(r, t):
    if t == 1: return struct.unpack('>b', r.take(1))[0]
    if t == 2: return r.i16()
    if t == 3: return r.i32()
    if t == 4: return r.i64()
    if t == 5: return struct.unpack('>f', r.take(4))[0]
    if t == 6: return struct.unpack('>d', r.take(8))[0]
    if t == 7: n = r.i32(); return r.take(n)
    if t == 8: n = r.u16(); return r.take(n).decode('utf-8', 'replace')
    if t == 9:
        et = r.u8(); n = r.i32(); return [payload(r, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = r.u8()
            if tt == 0: return d
            name = r.take(r.u16()).decode('utf-8', 'replace')
            d[name] = payload(r, tt)
    if t == 11: n = r.i32(); return np.frombuffer(r.take(4*n), dtype='>i4')
    if t == 12: n = r.i32(); return np.frombuffer(r.take(8*n), dtype='>i8')
    raise ValueError(t)

def nbt(b):
    r = R(b); t = r.u8(); r.take(r.u16()); return payload(r, t)

def pname(p):
    if isinstance(p, str): return p
    if 'Name' in p: return p['Name']
    if '' in p: return p['']
    return 'minecraft:air'

class World:
    def __init__(self, region_dir, colors_json):
        self.dir = region_dir
        d = json.load(open(colors_json))
        self.block_color = d['blocks']; self.col = d['colors']
        self.cache = {}; self.regions = {}

    def _region(self, rx, rz):
        k = (rx, rz)
        if k not in self.regions:
            p = os.path.join(self.dir, f'r.{rx}.{rz}.mca')
            self.regions[k] = open(p, 'rb').read() if os.path.exists(p) else None
        return self.regions[k]

    def chunk(self, cx, cz):
        k = (cx, cz)
        if k in self.cache: return self.cache[k]
        data = self._region(cx >> 5, cz >> 5); res = None
        if data:
            idx = 4 * ((cx & 31) + (cz & 31) * 32)
            off = int.from_bytes(data[idx:idx+3], 'big'); cnt = data[idx+3]
            if off and cnt:
                o = off * 4096; ln = struct.unpack('>i', data[o:o+4])[0]; comp = data[o+4]
                raw = data[o+5:o+4+ln]
                if comp == 2:
                    root = nbt(zlib.decompress(raw))
                    res = self._columns(root)
        self.cache[k] = res
        return res

    def _columns(self, root):
        secs = root.get('sections') or []
        status = root.get('Status', '')
        if 'full' not in str(status): return None
        # 각 열에서 위에서부터 지도 색이 있는 첫 블록과 물 깊이
        top_y = np.full((16, 16), -9999, dtype=np.int32)
        cid = np.zeros((16, 16), dtype=np.int32)
        depth = np.zeros((16, 16), dtype=np.int32)
        done = np.zeros((16, 16), dtype=bool)
        in_water = np.zeros((16, 16), dtype=bool)
        for s in sorted(secs, key=lambda s: -s['Y']):
            bs = s.get('block_states')
            if not bs: continue
            pal = bs['palette']
            pcol = np.array([self.block_color.get(pname(p), 0) for p in pal], dtype=np.int32)
            if 'data' in bs:
                bits = max(4, math.ceil(math.log2(len(pal))))
                per = 64 // bits
                longs = bs['data'].astype(np.uint64)
                idx = np.zeros(4096, dtype=np.int64)
                mask = np.uint64((1 << bits) - 1)
                for j in range(per):
                    vals = (longs >> np.uint64(j * bits)) & mask
                    pos = np.arange(len(longs)) * per + j
                    ok = pos < 4096
                    idx[pos[ok]] = vals[ok].astype(np.int64)
                blk = pcol[idx].reshape(16, 16, 16)  # [y][z][x]
            else:
                blk = np.full((16, 16, 16), pcol[0], dtype=np.int32)
            base = s['Y'] * 16
            for ly in range(15, -1, -1):
                layer = blk[ly]
                y = base + ly
                water = (layer == 12)
                # 물 위에서 처음 만남
                start_w = (~done) & (~in_water) & water
                top_y[start_w] = y; cid[start_w] = 12; in_water[start_w] = True
                depth[in_water & water & ~done] += 1
                # 물 밖 단단한 블록
                land = (~done) & (layer != 0) & (~water)
                hit_land = land & ~in_water
                top_y[hit_land] = y; cid[hit_land] = layer[hit_land]; done[hit_land] = True
                end_w = in_water & ~water & ~done
                done[end_w] = True
            if done.all(): break
        return top_y, cid, depth

    def render(self, x0, z0, w, h):
        H = np.full((h + 1, w), -9999, dtype=np.int32); C = np.zeros((h + 1, w), dtype=np.int32); D = np.zeros((h + 1, w), dtype=np.int32)
        for zz in range(h + 1):
            z = z0 - 1 + zz
            for xx in range(w):
                pass
        # 청크 단위로 채우기
        for cz in range((z0 - 1) >> 4, ((z0 + h) >> 4) + 1):
            for cx in range(x0 >> 4, ((x0 + w - 1) >> 4) + 1):
                c = self.chunk(cx, cz)
                if c is None: continue
                ty, ci, de = c
                for lz in range(16):
                    z = cz * 16 + lz; row = z - (z0 - 1)
                    if row < 0 or row > h: continue
                    for lx in range(16):
                        x = cx * 16 + lx; col = x - x0
                        if col < 0 or col >= w: continue
                        H[row, col] = ty[lz, lx]; C[row, col] = ci[lz, lx]; D[row, col] = de[lz, lx]
        img = np.zeros((h, w), dtype=np.int32)  # 팔레트 인덱스 (색 id * 4 + 밝기)
        for r in range(1, h + 1):
            for c in range(w):
                cidv = C[r, c]
                if cidv == 0 or H[r, c] == -9999:
                    img[r - 1, c] = 0; continue
                if cidv == 12:
                    f = D[r, c] * 0.1 + ((c + r) & 1) * 0.2
                    br = 2 if f < 0.5 else (0 if f > 0.9 else 1)
                else:
                    prev = H[r - 1, c] if H[r - 1, c] != -9999 else H[r, c]
                    f = (H[r, c] - prev) * 4.0 / 4 + (((c + r) & 1) - 0.5) * 0.4
                    br = 2 if f > 0.6 else (0 if f < -0.6 else 1)
                img[r - 1, c] = cidv * 4 + br
        return img

MULT = [180, 220, 255, 135]

def palette_rgb(col):
    pal = np.zeros((256, 3), dtype=np.uint8)
    for i in range(64):
        c = col[i]; r, g, b = (c >> 16) & 255, (c >> 8) & 255, c & 255
        for k in range(4):
            m = MULT[k]
            pal[i * 4 + k] = (r * m // 255, g * m // 255, b * m // 255)
    return pal

def nearest_index(pal, rgb, allow=None):
    best, bi = 1e18, 0
    for i in range(4, 256):
        if allow is not None and (i // 4) not in allow: continue
        if i // 4 == 0: continue
        d = sum((int(pal[i][k]) - rgb[k]) ** 2 for k in range(3))
        if d < best: best, bi = d, i
    return bi
