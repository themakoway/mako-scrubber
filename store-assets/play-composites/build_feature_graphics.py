"""Build the Play Store feature graphics (1024x500) for every language.

Takes feature/feature-blank.jpg (hand + phone with a flat dark screen, cat background, no text) and
  - places the language's main-screen screenshot on the phone screen (clipped to the screen shape),
  - writes the right-aligned title "MAKO SCRUBBER" and a translated subtitle, white with a 2 px black outline.
Usage:  python -X utf8 build_feature_graphics.py [locale ...] [--out DIR]
Needs Pillow + numpy. Fonts and the language table are shared with build_composites.py (same folder).
"""
import os, sys
from collections import deque
import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")
BLANK = os.path.join(HERE, "feature", "feature-blank.jpg")
SRC = os.path.normpath(os.path.join(HERE, "..", "..", "screenshots"))
OUT = os.path.join(HERE, "output-feature")

W, H = 1024, 500
SCREEN_SEED = (260, 250)              # a point inside the phone's flat dark screen
SHOT_POS, SHOT_SIZE = (140, 40), (240, 480)   # x, scale measured from the pt-BR example (0.4435); y nudged down so the app footer row is cropped off
TITLE = dict(text="MAKO SCRUBBER", right=952, baseline=71, size=57, track=-0.22)
SUB_RIGHT, SUB_BASE, SUB_LINE, SUB_MAXW = 955, 103, 31, 480
STROKE = 2

# locale: (screenshot folder, output folder, file prefix, script, subtitle lines)
LOCALES = {
    "zh":    ("chinese",            "chinese",   "zh",   "cjk",   ["保护隐私的照片和视频 EXIF 清理工具"]),
    "de":    ("Deutsch",            "deutsch",   "de",   "latin", ["PRIVATER EXIF-BEREINIGER FÜR FOTOS UND VIDEOS"]),
    "es":    ("Español",            "espanol",   "es",   "latin", ["LIMPIADOR DE EXIF PRIVADO PARA FOTOS Y VÍDEOS"]),
    "fr":    ("Français",           "francais",  "fr",   "latin", ["NETTOYEUR EXIF POUR PHOTOS ET VIDÉOS,", "EN TOUTE CONFIDENTIALITÉ"]),
    "it":    ("Italiano",           "italiano",  "it",   "latin", ["STRUMENTO PER PULIRE I DATI EXIF DI FOTO", "E VIDEO, IN TUTTA PRIVACY"]),
    "ja":    ("japanese",           "japanese",  "ja",   "cjk",   ["写真・動画のためのプライバシー保護", "EXIFクリーナー"]),
    "nl":    ("Nederlands",         "nederland", "nl",   "latin", ["PRIVACYVRIENDELIJKE EXIF-OPSCHONER VOOR", "FOTO'S EN VIDEO'S"]),
    "pt":    ("Português",          "portugal",  "pt",   "latin", ["LIMPADOR DE EXIF PRIVADO PARA FOTOS E VÍDEOS"]),
    "pt-br": ("Português (Brasil)", "brasil",    "ptbr", "latin", ["LIMPADOR DE EXIF PRIVADO PARA FOTOS E VÍDEOS"]),
    "uk":    ("Українська",         "ukrainian", "uk",   "cyr",   ["ПРИВАТНИЙ ОЧИЩУВАЧ EXIF ДЛЯ ФОТО Й ВІДЕО"]),
}

def font_for(script, size):
    if script == "latin":
        return ImageFont.truetype(os.path.join(FONTS, "Economica-Regular.ttf"), size)
    if script == "cjk":
        return ImageFont.truetype(os.path.join(FONTS, "SourceHanSansHC-Normal.otf"), size)
    f = ImageFont.truetype(os.path.join(FONTS, "RobotoCondensed-VF.ttf"), size)
    f.set_variation_by_axes([300])
    return f

def width_of(text, font, track):
    return sum(font.getlength(c) for c in text) + track * (len(text) - 1)

def draw_right(img, text, font, right, baseline, track=0.0):
    """Right-aligned text: black 2px outline pass first, then white fill (so tight letters don't overlap)."""
    d = ImageDraw.Draw(img)
    x0 = right - width_of(text, font, track)
    for fill, stroke in (((0, 0, 0), STROKE), ((255, 255, 255), 0)):
        x = x0
        for c in text:
            d.text((x, baseline), c, font=font, fill=fill, anchor="ls",
                   stroke_width=stroke, stroke_fill=(0, 0, 0))
            x += font.getlength(c) + track

def screen_mask(blank):
    a = np.asarray(blank).astype(int)
    flat = np.abs(a - a[SCREEN_SEED[1], SCREEN_SEED[0]]).max(axis=2) <= 3
    seen = np.zeros_like(flat); q = deque([(SCREEN_SEED[1], SCREEN_SEED[0])]); seen[SCREEN_SEED[1], SCREEN_SEED[0]] = True
    while q:
        y, x = q.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < H and 0 <= nx < W and flat[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True; q.append((ny, nx))
    return Image.fromarray((seen * 255).astype("uint8")).filter(ImageFilter.GaussianBlur(0.7))

def main():
    global OUT
    args = sys.argv[1:]
    if "--out" in args:
        i = args.index("--out"); OUT = args[i + 1]; del args[i:i + 2]
    only = set(args)
    blank = Image.open(BLANK).convert("RGB"); assert blank.size == (W, H)
    mask = screen_mask(blank)
    region = _region_mask()
    tfont = font_for("latin", TITLE["size"])
    for loc, (sf, of, pre, script, lines) in LOCALES.items():
        if only and loc not in only:
            continue
        img = blank.copy()
        shot = Image.open(os.path.join(SRC, sf, "2_main_dark.png")).convert("RGB").resize(SHOT_SIZE, Image.LANCZOS)
        layer = Image.new("RGB", (W, H)); layer.paste(shot, SHOT_POS)
        alpha = ImageChops.multiply(mask, region)   # visible only inside the phone screen AND the screenshot rectangle
        img = Image.composite(layer, img, alpha)
        draw_right(img, TITLE["text"], tfont, TITLE["right"], TITLE["baseline"], TITLE["track"])
        base_size = {"latin": 28, "cjk": 27, "cyr": 29}[script]
        size = base_size
        while True:   # shrink until every line fits
            sfont = font_for(script, size)
            if max(width_of(t, sfont, 0) for t in lines) <= SUB_MAXW or size <= 14:
                break
            size -= 1
        for k, t in enumerate(lines):
            draw_right(img, t, sfont, SUB_RIGHT, SUB_BASE + k * SUB_LINE)
        od = os.path.join(OUT, of); os.makedirs(od, exist_ok=True)
        img.save(os.path.join(od, f"{pre}-feature.png"))
        print(loc, "subtitle size", size, "lines", len(lines))

_REG = None
def _region_mask():
    """Where the screenshot may show: the shot rectangle only (the phone-screen mask is applied on top)."""
    global _REG
    if _REG is None:
        m = Image.new("L", (W, H), 0)
        ImageDraw.Draw(m).rectangle([SHOT_POS[0], SHOT_POS[1], SHOT_POS[0] + SHOT_SIZE[0], SHOT_POS[1] + SHOT_SIZE[1]], fill=255)
        _REG = m
    return _REG

main()
