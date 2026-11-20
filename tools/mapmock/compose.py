#!/usr/bin/env python3
"""휴대 지도 예시 이미지 만들기 (docs/design/handheld-map.md).

python3 compose.py --world <월드>/dimensions/minecraft/overworld/region --colors mapcolors.json --jar <마인크래프트 클라이언트 jar> --out out/

- 지도 색표(mapcolors.json)는 MapColors.java 로 실제 게임 코드에서 뽑는다(아래).
- 지도 배경·아이콘·HUD 텍스처는 실행하는 사람의 마인크래프트 jar 에서 읽는다. 결과물에 Mojang 텍스처가 들어가므로 저장소에는 넣지 않는다.

MapColors.java: javac -cp "<server.jar>:<libraries/*.jar>" MapColors.java && java -cp "...:." MapColors  → mapcolors.json
"""
import argparse, io, os, zipfile
import sys, math, numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anvil import World, palette_rgb, nearest_index

ap = argparse.ArgumentParser()
ap.add_argument('--world', required=True); ap.add_argument('--colors', required=True)
ap.add_argument('--jar', required=True); ap.add_argument('--out', default='out')
ap.add_argument('--center', default='-316,-262')
args = ap.parse_args()
os.makedirs(args.out, exist_ok=True)
_jar = zipfile.ZipFile(args.jar)
_TEX = {
    'map_background': 'assets/minecraft/textures/map/map_background.png',
    'hotbar': 'assets/minecraft/textures/gui/sprites/hud/hotbar.png',
    'hotbar_selection': 'assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png',
    'hotbar_offhand_left': 'assets/minecraft/textures/gui/sprites/hud/hotbar_offhand_left.png',
    'full': 'assets/minecraft/textures/gui/sprites/hud/heart/full.png',
    'food_full': 'assets/minecraft/textures/gui/sprites/hud/food_full.png',
}
def tex(name):
    path = _TEX.get(name, f'assets/minecraft/textures/map/decorations/{name}.png')
    return Image.open(io.BytesIO(_jar.read(path)))

w = World(args.world, args.colors)
pal = palette_rgb(w.col)
CX, CZ = (int(v) for v in args.center.split(','))
X0, Z0 = CX - 64, CZ - 64
base = w.render(X0, Z0, 128, 128)

FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
def font(sz): return ImageFont.truetype(FONT, sz, index=1)  # KR

BLUE = nearest_index(pal, (0x33, 0x55, 0xBB))
RED = nearest_index(pal, (0xFF, 0x00, 0x45))
LV = {2: nearest_index(pal, (0x55, 0xBB, 0x44)), 3: nearest_index(pal, (0x66, 0x00, 0x99)), 4: nearest_index(pal, (0x66, 0x00, 0x99))}

own = set((cx, cz) for cx in range(-24, -21) for cz in range(-21, -13)) | {(-21, -17), (-21, -16), (-21, -15)}
enemy = set((cx, cz) for cx in range(-18, -16) for cz in range(-19, -12))

def borders(img, cells, color, dotted=False):
    for py in range(128):
        for px in range(128):
            bx, bz = X0 + px, Z0 + py
            c = (bx >> 4, bz >> 4)
            if c not in cells: continue
            lx, lz = bx & 15, bz & 15
            edge = (lx == 0 and (c[0] - 1, c[1]) not in cells) or (lx == 15 and (c[0] + 1, c[1]) not in cells) or \
                   (lz == 0 and (c[0], c[1] - 1) not in cells) or (lz == 15 and (c[0], c[1] + 1) not in cells)
            if edge and (not dotted or (px + py) % 3 != 0):
                img[py, px] = color

def infection(img):
    for py in range(128):
        for px in range(128):
            bx, bz = X0 + px, Z0 + py
            c = (bx >> 4, bz >> 4)
            lv = 2 if c in own else (4 if c in enemy else 3)
            if (px + 2 * py) % 5 == 0 and img[py, px] // 4 != 0:
                img[py, px] = LV[lv]

def to_rgb(idx):
    rgba = np.zeros(idx.shape + (4,), dtype=np.uint8)
    rgba[..., :3] = pal[idx]; rgba[..., 3] = np.where(idx // 4 == 0, 0, 255)
    return Image.fromarray(rgba, 'RGBA')

def deco(canvas, name, mx, my, rot16=0, scale=4, label=None):
    """mx,my: 지도 좌표(0~128). 8×8 아이콘을 8 지도 칸 크기로."""
    t = tex(name).convert('RGBA').resize((8 * scale, 8 * scale), Image.NEAREST)
    if rot16: t = t.rotate(-rot16 * 22.5, resample=Image.NEAREST, expand=False)
    canvas.alpha_composite(t, (int(mx * scale - 4 * scale), int(my * scale - 4 * scale)))
    if label:
        d = ImageDraw.Draw(canvas); f = font(5 * scale)
        tw = d.textlength(label, font=f)
        x = mx * scale - tw / 2; y = my * scale + 4 * scale + 2
        d.rectangle([x - 4, y - 1, x + tw + 4, y + 6 * scale + 2], fill=(0, 0, 0, 150))
        d.text((x, y - scale), label, font=f, fill=(255, 255, 255, 255))

def map_item(idx, scale=4, decos=True):
    m = to_rgb(idx).resize((128 * scale, 128 * scale), Image.NEAREST).convert('RGBA')
    if decos:
        deco(m, 'blue_banner', (-372 - X0), (-300 - Z0), label='수도')
        deco(m, 'blue_marker', 52, 70, rot16=3)
        deco(m, 'blue_marker', 47, 77, rot16=14)
        deco(m, 'red_x', 92, 34, label='사망 지점')
        deco(m, 'player', 64, 64, rot16=1)
    bg = tex('map_background').convert('RGBA').resize((142 * scale, 142 * scale), Image.NEAREST)
    bg.alpha_composite(m, (7 * scale, 7 * scale))
    return bg

a = base.copy(); borders(a, own, BLUE); borders(a, enemy, RED, dotted=True)
b = a.copy(); infection(b); borders(b, own, BLUE); borders(b, enemy, RED, dotted=True)
A = map_item(a); B = map_item(b)
A.save(os.path.join(args.out, 'handheld-map-a.png')); B.save(os.path.join(args.out, 'handheld-map-b.png'))

# 검토용 한 장: A, B 나란히 + 화면 배치 개략도
W = 568 * 2 + 60
sheet = Image.new('RGBA', (W, 568 + 90 + 720 // 2 * 0 + 60 + 400), (24, 24, 28, 255))
d = ImageDraw.Draw(sheet)
d.text((20, 14), 'A. 기본: 지형 + 국가 경계 + 나·자국민·사망 지점', font=font(22), fill=(235, 235, 235))
d.text((568 + 40, 14), 'B. A + 감염 레벨 무늬 (초록 Lv1~2, 보라 Lv3~4)', font=font(22), fill=(235, 235, 235))
sheet.alpha_composite(A, (20, 50)); sheet.alpha_composite(B, (568 + 40, 50))

# 화면 배치 개략도 (게임 화면 아님)
scr = Image.new('RGBA', (1280, 720), (0, 0, 0, 255))
sd = ImageDraw.Draw(scr)
for y in range(720):
    t = y / 720
    col = (int(150 + 60 * t), int(190 + 40 * t), int(240 - 10 * t)) if y < 380 else (int(96 - 20 * (t - .53)), int(130 - 30 * (t - .53)), int(60 - 10 * (t - .53)))
    sd.line([(0, y), (1280, y)], fill=col + (255,))
gs = 2
hb = tex('hotbar').convert('RGBA'); hb = hb.resize((hb.width * gs, hb.height * gs), Image.NEAREST)
hx = 640 - hb.width // 2; hy = 720 - hb.height
scr.alpha_composite(hb, (hx, hy))
sel = tex('hotbar_selection').convert('RGBA'); sel = sel.resize((sel.width * gs, sel.height * gs), Image.NEAREST)
scr.alpha_composite(sel, (hx - gs, hy - gs))
off = tex('hotbar_offhand_left').convert('RGBA'); off = off.resize((off.width * gs, off.height * gs), Image.NEAREST)
scr.alpha_composite(off, (hx - off.width - 2 * gs, 720 - off.height))
heart = tex('full').convert('RGBA').resize((18, 18), Image.NEAREST)
food = tex('food_full').convert('RGBA').resize((18, 18), Image.NEAREST)
for i in range(10):
    scr.alpha_composite(heart, (hx + i * 16, hy - 40))
    scr.alpha_composite(food, (hx + hb.width - 18 - i * 16, hy - 40))
# 보조손 지도: 왼쪽 아래, 약간 기울임
mini = map_item(a, scale=2).resize((300, 300), Image.NEAREST).rotate(6, resample=Image.BICUBIC, expand=True)
scr.alpha_composite(mini, (70, 720 - mini.height - 40))
cross = ImageDraw.Draw(scr); cross.line([(632, 360), (648, 360)], fill=(255, 255, 255, 220), width=2); cross.line([(640, 352), (640, 368)], fill=(255, 255, 255, 220), width=2)
scr = scr.resize((W - 40, int(720 * (W - 40) / 1280)), Image.LANCZOS)
y0 = 50 + 568 + 30
d.text((20, y0), 'C. 배치 개략도: 보조손에 들었을 때 (실제 게임 화면이 아니라 크기·위치 참고용)', font=font(22), fill=(235, 235, 235))
sheet = sheet.crop((0, 0, W, y0 + 40 + scr.height + 20)); d = ImageDraw.Draw(sheet)
sheet.alpha_composite(scr, (20, y0 + 40))
sheet.convert('RGB').save(os.path.join(args.out, 'handheld-map-review.png'))
print('ok', sheet.size)
