"""Generates arcade-style bezel artworks (1920x1080) for the J2ME Player.

The game is drawn over the middle at run time, so each artwork is busy at the
sides and calm in the middle. Run: python3 make_bezels.py <outdir>
"""
import math
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
OUT = sys.argv[1]
FONT = "/usr/share/fonts/truetype/google-fonts/Poppins-Bold.ttf"


def font(size):
    return ImageFont.truetype(FONT, size)


def vgrad(top, bottom, h=H, w=W):
    t = np.linspace(0, 1, h)[:, None, None]
    a = np.array(top, float)[None, None, :]
    b = np.array(bottom, float)[None, None, :]
    return np.repeat(a + (b - a) * t, w, axis=1)


def to_img(arr):
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB")


def glow(layer, radius, strength=1.0):
    """Adds a soft glow of an RGBA layer under itself."""
    blurred = layer.filter(ImageFilter.GaussianBlur(radius))
    out = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    for _ in range(int(strength)):
        out = Image.alpha_composite(out, blurred)
    return Image.alpha_composite(out, layer)


def centre_shade(img, width=760, dark=0.75):
    """Darkens the middle so the game stands out, fading towards the sides."""
    x = np.abs(np.arange(W) - W / 2)
    m = np.clip((x - width / 2) / 260, 0, 1)
    m = (1 - dark) + dark * m
    arr = np.asarray(img.convert("RGB"), float) * m[None, :, None]
    return to_img(arr)


PIXEL = {  # 5x7 letters for arcade text
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "C": ["01111", "10000", "10000", "10000", "10000", "10000", "01111"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "G": ["01111", "10000", "10000", "10111", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00110", "01000", "10000", "11111"],
    "0": ["01110", "10011", "10101", "10101", "11001", "10001", "01110"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    " ": ["00000"] * 7,
}


def pixel_text(draw, text, x, y, size, colour):
    for ch in text:
        rows = PIXEL.get(ch, PIXEL[" "])
        for r, row in enumerate(rows):
            for c, bit in enumerate(row):
                if bit == "1":
                    draw.rectangle([x + c * size, y + r * size, x + (c + 1) * size - 1, y + (r + 1) * size - 1], fill=colour)
        x += 6 * size
    return x


def pixel_width(text, size):
    return len(text) * 6 * size - size


# ---------------------------------------------------------------- 1 synthwave
def synthwave():
    horizon = 640
    sky = vgrad((10, 2, 34), (70, 10, 90), horizon)
    sky2 = vgrad((70, 10, 90), (255, 70, 140), 80)
    sky[horizon - 80:] = sky2 * 0.6 + sky[horizon - 80:] * 0.4
    ground = vgrad((25, 5, 45), (8, 2, 18), H - horizon)
    arr = np.concatenate([sky, ground], axis=0)
    img = to_img(arr).convert("RGBA")
    rnd = random.Random(4)
    d = ImageDraw.Draw(img)
    for _ in range(420):  # stars
        x, y = rnd.randrange(W), rnd.randrange(horizon - 120)
        b = rnd.randrange(90, 255)
        s = rnd.choice([1, 1, 1, 2])
        d.ellipse([x, y, x + s, y + s], fill=(b, b, 255, 255))
    # suns, one per side, striped
    for cx in (300, W - 300):
        sun = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        sd = ImageDraw.Draw(sun)
        r = 210
        for yy in range(horizon - 2 * r, horizon):
            t = (yy - (horizon - 2 * r)) / (2 * r)
            col = (255, int(220 - 160 * t), int(80 + 60 * t), 255)
            band = int((yy - (horizon - r)) / 14) if yy > horizon - r else -1
            if band >= 0 and (yy % 28) < 6 + band * 2:
                continue
            dy = yy - (horizon - r)
            if abs(dy) <= r:
                dx = int(math.sqrt(r * r - dy * dy))
                sd.line([cx - dx, yy, cx + dx, yy], fill=col)
        img = Image.alpha_composite(img, glow(sun, 30, 2))
    # mountains
    mnt = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    md = ImageDraw.Draw(mnt)
    for side in (0, 1):
        pts = [(0 if side == 0 else W, horizon)]
        x = 0 if side == 0 else W
        step = 60 if side == 0 else -60
        rnd2 = random.Random(side + 9)
        for i in range(11):
            x += step
            pts.append((x, horizon - rnd2.randrange(40, 190)))
        pts.append((x, horizon))
        md.polygon(pts, fill=(30, 6, 50, 255), outline=(255, 60, 200, 255))
    img = Image.alpha_composite(img, glow(mnt, 6))
    # perspective grid
    grid = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    vx = W / 2
    for i in range(-30, 31):
        gd.line([(vx + i * 22, horizon), (vx + i * 260, H)], fill=(255, 40, 200, 255), width=3)
    y = horizon
    k = 6
    while y < H:
        gd.line([(0, y), (W, y)], fill=(255, 40, 200, 255), width=3)
        k *= 1.28
        y += k
    img = Image.alpha_composite(img, glow(grid, 8, 2))
    return centre_shade(img, 700, 0.6)


# ---------------------------------------------------------------- 2 arcade cabinet
def arcade():
    img = Image.new("RGBA", (W, H), (8, 8, 14, 255))
    d = ImageDraw.Draw(img)
    colours = [(255, 196, 0), (255, 120, 0), (235, 40, 60), (200, 30, 140), (40, 190, 220)]
    for side in (0, 1):
        for i, c in enumerate(colours):
            off = i * 70
            if side == 0:
                poly = [(-200 + off, H), (-60 + off, H), (560 + off - 400, -60), (420 + off - 400, -60)]
            else:
                poly = [(W + 200 - off, H), (W + 60 - off, H), (W - 560 - off + 400, -60), (W - 420 - off + 400, -60)]
            d.polygon(poly, fill=c + (255,))
    # halftone dots over the stripes
    dots = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dd = ImageDraw.Draw(dots)
    for y in range(0, H, 18):
        for x in range(0, W, 18):
            r = 2 + 4 * (y / H)
            dd.ellipse([x - r, y - r, x + r, y + r], fill=(0, 0, 0, 90))
    img = Image.alpha_composite(img, dots)
    # chrome trim lines
    d = ImageDraw.Draw(img)
    for x in (560, W - 560):
        for k, a in ((0, 255), (3, 120)):
            d.line([(x + k, 0), (x + k, H)], fill=(200, 205, 220, a), width=2)
    # labels, on dark plates so they read over the stripes
    for x0, x1 in ((50, 50 + pixel_width("HI-SCORE", 7) + 40), (W - 90 - pixel_width("HI-SCORE", 7), W - 50)):
        d.rounded_rectangle([x0, 40, x1, 180], radius=18, fill=(8, 8, 14, 235), outline=(200, 205, 220, 160), width=3)
    for x0, txt, col in ((70 + (pixel_width("HI-SCORE", 7) - pixel_width("1UP", 7)) // 2, "1UP", (255, 60, 60)), (W - 70 - pixel_width("HI-SCORE", 7), "HI-SCORE", (255, 196, 0))):
        pixel_text(d, txt, x0, 60, 7, col + (255,))
    pixel_text(d, "000000", 70 + (pixel_width("HI-SCORE", 7) - pixel_width("000000", 5)) // 2, 130, 5, (255, 255, 255, 255))
    pixel_text(d, "020000", W - 70 - pixel_width("HI-SCORE", 7) + (pixel_width("HI-SCORE", 7) - pixel_width("020000", 5)) // 2, 130, 5, (255, 255, 255, 255))
    # insert coin, glowing
    txt = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    td = ImageDraw.Draw(txt)
    for cx in (280, W - 280):
        s = 6
        pixel_text(td, "INSERT", cx - pixel_width("INSERT", s) // 2, H - 250, s, (120, 255, 255, 255))
        pixel_text(td, "COIN", cx - pixel_width("COIN", s) // 2, H - 190, s, (120, 255, 255, 255))
    img = Image.alpha_composite(img, glow(txt, 10, 2))
    # control panel: joystick and buttons
    d = ImageDraw.Draw(img)
    jx, jy = 230, 560
    d.ellipse([jx - 70, jy + 40, jx + 70, jy + 80], fill=(20, 20, 26, 255))
    d.rectangle([jx - 8, jy - 60, jx + 8, jy + 60], fill=(180, 185, 200, 255))
    d.ellipse([jx - 46, jy - 120, jx + 46, jy - 28], fill=(230, 30, 50, 255))
    d.ellipse([jx - 28, jy - 110, jx - 2, jy - 86], fill=(255, 140, 150, 255))
    for i, c in enumerate([(255, 196, 0), (40, 190, 220), (235, 40, 60), (120, 220, 80)]):
        bx, by = W - 400 + (i % 2) * 120, 470 + (i // 2) * 120
        d.ellipse([bx - 44, by - 40, bx + 44, by + 48], fill=(20, 20, 26, 255))
        d.ellipse([bx - 40, by - 44, bx + 40, by + 36], fill=c + (255,))
        d.ellipse([bx - 24, by - 36, bx + 4, by - 16], fill=(255, 255, 255, 110))
    return centre_shade(img, 760, 0.9)


# ---------------------------------------------------------------- 3 carbon racing
def carbon():
    tile = np.zeros((16, 16, 3))
    for y in range(16):
        for x in range(16):
            a = ((x // 8) + (y // 8)) % 2
            t = (y % 8) / 7 if a else (x % 8) / 7
            v = 22 + 30 * math.sin(t * math.pi)
            tile[y, x] = (v, v, v + 4)
    arr = np.tile(tile, (H // 16 + 1, W // 16 + 1, 1))[:H, :W]
    sheen = np.linspace(1.25, 0.7, H)[:, None, None]
    img = to_img(arr * sheen).convert("RGBA")
    d = ImageDraw.Draw(img)
    # racing stripes
    for side in (0, 1):
        for i, c in enumerate(((235, 30, 40), (255, 255, 255), (235, 30, 40))):
            x = 360 + i * 46 if side == 0 else W - 360 - i * 46 - 34
            d.rectangle([x, 0, x + 34, H], fill=c + (255,))
    # chequered strip at the bottom
    s = 30
    for y in range(H - 120, H - 60, s):
        for x in range(0, W, s):
            if ((x // s) + (y // s)) % 2:
                d.rectangle([x, y, x + s - 1, y + s - 1], fill=(240, 240, 240, 255))
            else:
                d.rectangle([x, y, x + s - 1, y + s - 1], fill=(15, 15, 18, 255))
    # bolts
    for (bx, by) in ((70, 70), (W - 70, 70), (70, H - 200), (W - 70, H - 200)):
        d.ellipse([bx - 22, by - 22, bx + 22, by + 22], fill=(150, 155, 165, 255), outline=(230, 232, 238, 255), width=3)
        d.line([bx - 12, by, bx + 12, by], fill=(60, 62, 70, 255), width=5)
    # speed text
    f = font(120)
    for x, txt in ((80, "TURBO"), (W - 80, "NITRO")):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        tw = ld.textlength(txt, font=f)
        tx = x if x < W / 2 else x - tw
        ld.text((tx, 380), txt, font=f, fill=(255, 255, 255, 40))
        img = Image.alpha_composite(img, layer.rotate(0))
    return centre_shade(img, 760, 0.7)


# ---------------------------------------------------------------- 4 pixel landscape
def pixel_land():
    w, h = W // 8, H // 8
    arr = np.zeros((h, w, 3))
    bands = [(40, 60, 140), (56, 88, 170), (80, 120, 200), (120, 160, 220), (170, 200, 235)]
    for y in range(h):
        arr[y, :] = bands[min(len(bands) - 1, y * len(bands) // (h * 2 // 3))]
    img = to_img(arr).convert("RGBA")
    d = ImageDraw.Draw(img)
    rnd = random.Random(7)
    for _ in range(9):  # clouds
        cx, cy = rnd.randrange(w), rnd.randrange(10, 50)
        for k in range(5):
            d.ellipse([cx + k * 6 - 8, cy - (k % 2) * 4 - 5, cx + k * 6 + 8, cy + 5], fill=(245, 248, 255, 255))
    def ridge(base, amp, col, seed, step):
        r = random.Random(seed)
        pts = [(0, h)]
        y = base
        for x in range(0, w + step, step):
            y = max(base - amp, min(base + amp, y + r.randrange(-6, 7)))
            pts.append((x, y))
        pts.append((w, h))
        d.polygon(pts, fill=col)
    ridge(80, 18, (90, 100, 150), 1, 6)
    ridge(95, 12, (60, 120, 90), 2, 4)
    ridge(112, 6, (50, 150, 70), 3, 3)
    for _ in range(26):  # trees
        x = rnd.randrange(w)
        y = rnd.randrange(112, h - 4)
        d.polygon([(x, y - 9), (x - 5, y + 2), (x + 5, y + 2)], fill=(30, 100, 50))
        d.rectangle([x - 1, y + 2, x + 1, y + 4], fill=(90, 60, 40))
    img = img.resize((W, H), Image.NEAREST)
    return centre_shade(img, 760, 0.7)


# ---------------------------------------------------------------- 5 deep space
def space():
    rnd = np.random.default_rng(3)
    base = vgrad((4, 4, 14), (10, 6, 24))
    neb = np.zeros((H // 8, W // 8, 3))
    for c, (cx, cy, r) in (((140, 60, 220), (0.12, 0.35, 0.35)), ((30, 180, 200), (0.85, 0.65, 0.32)), ((220, 60, 120), (0.9, 0.15, 0.22))):
        yy, xx = np.mgrid[0:H // 8, 0:W // 8]
        dist = np.sqrt(((xx / (W / 8)) - cx) ** 2 + ((yy / (H / 8)) - cy) ** 2 * 1.8)
        noise = rnd.random((H // 8, W // 8))
        k = np.exp(-(dist / r) ** 2) * (0.55 + 0.45 * noise)
        neb += k[:, :, None] * np.array(c)[None, None, :]
    nebi = to_img(neb).resize((W, H), Image.BICUBIC).filter(ImageFilter.GaussianBlur(18))
    arr = base + np.asarray(nebi, float) * 0.9
    img = to_img(arr).convert("RGBA")
    d = ImageDraw.Draw(img)
    r2 = random.Random(5)
    for _ in range(900):
        x, y = r2.randrange(W), r2.randrange(H)
        b = r2.randrange(80, 255)
        s = r2.choice([1, 1, 1, 1, 2, 2, 3])
        d.ellipse([x, y, x + s, y + s], fill=(b, b, min(255, b + 30), 255))
    # planet with ring on the left, moon on the right
    pl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    pd = ImageDraw.Draw(pl)
    cx, cy, r = 250, 760, 170
    for i in range(r, 0, -2):
        t = i / r
        pd.ellipse([cx - i, cy - i, cx + i, cy + i], fill=(int(60 + 140 * (1 - t)), int(40 + 90 * (1 - t)), int(120 + 100 * (1 - t)), 255))
    pd.ellipse([cx - 330, cy - 50, cx + 330, cy + 50], outline=(220, 200, 255, 200), width=8)
    pd.ellipse([cx - 300, cy - 40, cx + 300, cy + 40], outline=(160, 140, 220, 150), width=4)
    mx, my, mr = W - 280, 260, 80
    for i in range(mr, 0, -2):
        t = i / mr
        pd.ellipse([mx - i, my - i, mx + i, my + i], fill=(int(120 + 120 * (1 - t)), int(130 + 110 * (1 - t)), int(150 + 90 * (1 - t)), 255))
    img = Image.alpha_composite(img, glow(pl, 20))
    return centre_shade(img, 760, 0.7)


for n, (name, fn) in enumerate((("synthwave", synthwave), ("arcade", arcade), ("carbon", carbon), ("pixel", pixel_land), ("space", space)), 1):
    fn().convert("RGB").save(f"{OUT}/bezel-{n}-{name}.jpg", quality=90)
    print("made", name)
