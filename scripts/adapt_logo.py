"""Genera TODOS los assets de marca a partir de la fuente maestra oficial.

Fuente: assets_branding/logo_RBS_master.png (logo_RBS-1.2.png).

Se conservan los nombres de archivo históricos para no tocar codigo Kotlin,
el AndroidManifest ni el HTML de la landing.
"""

from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
MASTER = ROOT / "assets_branding" / "logo_RBS_master.png"

# Recorte content-aware verificado: el logo ocupa x=66..786, y=27..936.
CROP = (40, 0, 810, 960)

DARK_SLATE = (15, 23, 42, 255)   # 0xFF0F172A
HERITAGE_BRASS = (212, 175, 55, 255)  # 0xFFD4AF37

DRAWABLE_DIR = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"
RES_DIR = ROOT / "android" / "app" / "src" / "main" / "res"
WEB_DIR = ROOT / "backend" / "public" / "images"

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def load_logo() -> Image.Image:
    if not MASTER.is_file():
        raise SystemExit(f"No se encontro la fuente maestra: {MASTER}")
    with Image.open(MASTER) as raw:
        return raw.convert("RGBA").crop(CROP)


def _blurred_background(logo: Image.Image, width: int, height: int) -> Image.Image:
    """Fondo derivado del propio logo: evita costuras con su fondo oscuro opaco."""
    bg = logo.resize((width, height), Image.Resampling.LANCZOS)
    radius = max(2, min(width, height) // 6)
    return bg.filter(ImageFilter.GaussianBlur(radius))


def _fitted(logo: Image.Image, max_dim: int) -> Image.Image:
    w, h = logo.size
    if w >= h:
        new_w = max_dim
        new_h = max(1, int(h * max_dim / w))
    else:
        new_h = max_dim
        new_w = max(1, int(w * max_dim / h))
    return logo.resize((new_w, new_h), Image.Resampling.LANCZOS)


def square(logo: Image.Image, size: int, ratio: float = 0.92) -> Image.Image:
    canvas = _blurred_background(logo, size, size)
    art = _fitted(logo, int(size * ratio))
    canvas.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return canvas


def wide(logo: Image.Image, width: int, height: int, ratio: float = 0.96) -> Image.Image:
    canvas = _blurred_background(logo, width, height)
    art = _fitted(logo, int(height * ratio))
    canvas.alpha_composite(art, ((width - art.width) // 2, (height - art.height) // 2))
    return canvas


def transparent_hero(logo: Image.Image, size: int = 512) -> Image.Image:
    """Logo recortado sobre transparente para el hero de Soporte.

    La maestra es opaca y su fondo es marrón/oliva; GrabCut separa el emblema
    sin depender de un color fijo. Se recorta al bounding box resultante y se
    centra en un canvas cuadrado para que `ContentScale.Fit` no descentre.
    """
    rgb = np.asarray(logo.convert("RGB"))
    h, w = rgb.shape[:2]
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    mask = np.zeros((h, w), np.uint8)
    bg_model = np.zeros((1, 65), np.float64)
    fg_model = np.zeros((1, 65), np.float64)
    cv2.grabCut(
        bgr, mask, (2, 2, w - 4, h - 4), bg_model, fg_model, 5, cv2.GC_INIT_WITH_RECT
    )
    opaque = np.isin(mask, (cv2.GC_FGD, cv2.GC_PR_FGD)).astype(np.uint8) * 255
    ys, xs = np.nonzero(opaque)
    if xs.size == 0:
        raise SystemExit("GrabCut no detectó el logo en la maestra")

    x0, x1 = int(xs.min()), int(xs.max()) + 1
    y0, y1 = int(ys.min()), int(ys.max()) + 1
    cut = logo.crop((x0, y0, x1, y1)).convert("RGBA")
    alpha = Image.fromarray(opaque[y0:y1, x0:x1]).filter(ImageFilter.GaussianBlur(0.8))
    cut.putalpha(alpha)

    side = max(cut.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.alpha_composite(cut, ((side - cut.width) // 2, (side - cut.height) // 2))
    return canvas.resize((size, size), Image.Resampling.LANCZOS)


def launcher_round(logo: Image.Image, size: int) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    stroke = max(1, size // 48)
    ImageDraw.Draw(canvas).ellipse(
        (1, 1, size - 2, size - 2), fill=DARK_SLATE, outline=HERITAGE_BRASS, width=stroke
    )
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((2, 2, size - 2, size - 2), fill=255)
    canvas.paste(square(logo, size, ratio=0.78), (0, 0), mask)
    return canvas


def save(img: Image.Image, path: Path, fmt: str | None = None, **kwargs) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format=fmt, **kwargs)
    print(f"Generado: {path.relative_to(ROOT)}")


def main() -> None:
    logo = load_logo()
    print(f"Fuente: {MASTER.relative_to(ROOT)} -> recorte {logo.size}")

    # 1. Drawables de pantalla (mismos nombres: 0 cambios en Kotlin).
    save(square(logo, 512, ratio=0.96), DRAWABLE_DIR / "ic_logo_ruta_biker_sync.webp", "WEBP", quality=94)
    save(transparent_hero(logo, 512), DRAWABLE_DIR / "ic_logo_rbs_transparent.png", "PNG")
    save(wide(logo, 330, 200), DRAWABLE_DIR / "ic_gold_winged_crest.png", "PNG")
    save(wide(logo, 640, 360), DRAWABLE_DIR / "ic_biker_crest_gold.png", "PNG")

    # 2. Launcher (10 PNGs legacy; sin adaptive icon en este proyecto).
    for folder, px in DENSITIES.items():
        target = RES_DIR / folder
        save(square(logo, px, ratio=0.90), target / "ic_launcher.png", "PNG")
        save(launcher_round(logo, px), target / "ic_launcher_round.png", "PNG")

    # 3. Branding web.
    save(square(logo, 256, ratio=0.94), WEB_DIR / "logo-patch.webp", "WEBP", quality=94)
    save(square(logo, 256, ratio=0.94), WEB_DIR / "logo-patch.png", "PNG")
    save(square(logo, 32, ratio=0.96), ROOT / "backend" / "public" / "favicon.png", "PNG")
    save(square(logo, 64, ratio=0.96), WEB_DIR / "favicon-64.png", "PNG")
    save(square(logo, 192, ratio=0.90), WEB_DIR / "icon-192.png", "PNG")

    print("--- TODOS LOS ASSETS HAN SIDO GENERADOS CON EL LOGO NUEVO ---")


if __name__ == "__main__":
    main()
