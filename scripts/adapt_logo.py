import cv2
import numpy as np
from PIL import Image, ImageFilter, ImageDraw
import os

print("--- INICIANDO ADAPTACIÓN DEL LOGO OFICIAL RUTA BIKER SYNC ---")

src_path = "ruta_biker_raw_logo.png"
src = cv2.imread(src_path, cv2.IMREAD_UNCHANGED)
h, w = src.shape[:2]

# 1. SEGMENTACIÓN DE ALTA PRECISIÓN CON GRABCUT
rect = (320, 180, 1410, 1700)
mask = np.zeros((h, w), np.uint8)
bgdModel = np.zeros((1, 65), np.float64)
fgdModel = np.zeros((1, 65), np.float64)

# Asegurar 3 canales BGR para grabCut
src_bgr = src[:, :, :3].copy()

print("Ejecutando GrabCut refinado...")
cv2.grabCut(src_bgr, mask, rect, bgdModel, fgdModel, 4, cv2.GC_INIT_WITH_RECT)
binary_mask = np.where((mask == 2) | (mask == 0), 0, 255).astype('uint8')

# Suavizado de bordes y antialiasing
kernel_smooth = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
binary_mask = cv2.morphologyEx(binary_mask, cv2.MORPH_CLOSE, kernel_smooth)

# Crear máscara antialias con desenfoque gaussiano suave (1.5 px)
blurred_mask = cv2.GaussianBlur(binary_mask, (5, 5), 1.5)

# Bounding box del parche recortado para centrado
contours, _ = cv2.findContours(binary_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
largest = max(contours, key=cv2.contourArea)
bx, by, bw, bh = cv2.boundingRect(largest)
print(f"Parche detectado: x={bx}, y={by}, w={bw}, h={bh}")

# Aplicar canal alfa
b, g, r = cv2.split(src[:h, :w, :3])
rgba = cv2.merge([b, g, r, blurred_mask])
rgba_pil = Image.fromarray(cv2.cvtColor(rgba, cv2.COLOR_BGRA2RGBA))

# Recortar al bounding box con un padding elegante de 40 px
pad = 40
crop_x1 = max(0, bx - pad)
crop_y1 = max(0, by - pad)
crop_x2 = min(w, bx + bw + pad)
crop_y2 = min(h, by + bh + pad)

cropped_shield = rgba_pil.crop((crop_x1, crop_y1, crop_x2, crop_y2))

# Guardar parche transparente maestro alta resolución (2048px equivalente)
os.makedirs("assets_branding", exist_ok=True)
cropped_shield.save("assets_branding/logo_patch_transparent_full.png", format="PNG")
print("Guardado: assets_branding/logo_patch_transparent_full.png")

# 2. VERSIÓN LIMPIA (RETIRO DEL TEXTO 'Sync' DUPLICADO ARRIBA)
# El 'Sync' superior bordado en cursiva compite con 'SYNC' inferior en metal
# Vamos a crear una versión limpia clonando suavemente la textura del cuero negro
clean_cv = src[:, :, :3].copy()
mask_inpaint = np.zeros((h, w), dtype=np.uint8)
# Coordenadas exactas del texto 'Sync' superior en el centro del escudo
cv2.ellipse(mask_inpaint, (1024, 385), (145, 65), 0, 0, 360, 255, -1)
# Rellenar con textura de cuero mediante inpaint
clean_bgr = cv2.inpaint(clean_cv, mask_inpaint, inpaintRadius=9, flags=cv2.INPAINT_TELEA)

# Crear versión transparente de la versión limpia
b, g, r = cv2.split(clean_bgr)
rgba_clean = cv2.merge([b, g, r, blurred_mask])
rgba_clean_pil = Image.fromarray(cv2.cvtColor(rgba_clean, cv2.COLOR_BGRA2RGBA))
cropped_shield_clean = rgba_clean_pil.crop((crop_x1, crop_y1, crop_x2, crop_y2))
cropped_shield_clean.save("assets_branding/logo_patch_clean_hq.png", format="PNG")
print("Guardado: assets_branding/logo_patch_clean_hq.png (Versión limpia sin 'Sync' redundante)")

# 3. GENERAR ASSETS PARA ANDROID DRAWABLE
# ic_logo_ruta_biker_sync.webp (512x512 y escalado para UI)
android_drawable_dir = "android/app/src/main/res/drawable"
os.makedirs(android_drawable_dir, exist_ok=True)

# Crear un lienzo cuadrado con el escudo centrado para la app
def make_square_centered(img_pil, size=512, padding_ratio=0.88):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # Redimensionar manteniendo aspecto
    orig_w, orig_h = img_pil.size
    target_dim = int(size * padding_ratio)
    if orig_w > orig_h:
        new_w = target_dim
        new_h = int(orig_h * (target_dim / orig_w))
    else:
        new_h = target_dim
        new_w = int(orig_w * (target_dim / orig_h))
    
    resized = img_pil.resize((new_w, new_h), Image.Resampling.LANCZOS)
    pos_x = (size - new_w) // 2
    pos_y = (size - new_h) // 2
    canvas.paste(resized, (pos_x, pos_y), resized)
    return canvas

# Guardar en drawable Android
logo_drawable_512 = make_square_centered(cropped_shield_clean, size=512, padding_ratio=0.92)
logo_drawable_512.save(os.path.join(android_drawable_dir, "ic_logo_ruta_biker_sync.webp"), format="WEBP", quality=95)
print("Actualizado: android/app/src/main/res/drawable/ic_logo_ruta_biker_sync.webp")

# 4. GENERAR LAUNCHER ICONS (MIPMAPS) PARA ANDROID
# Densidades estándar de Android
densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

# Icono con fondo oscuro para escritorio (Launchers redondos y cuadrados)
# Fondo de cuero oscuro carbón (#0B0E14)
for folder, px in densities.items():
    target_dir = os.path.join("android/app/src/main/res", folder)
    os.makedirs(target_dir, exist_ok=True)
    
    # 1. ic_launcher normal (parche centrado con fondo transparente / Dark Elite)
    icon_square = make_square_centered(cropped_shield_clean, size=px, padding_ratio=0.88)
    icon_square.save(os.path.join(target_dir, "ic_launcher.png"), format="PNG")
    
    # 2. ic_launcher_round (con fondo circular Slate-950 / carbón y borde biselado)
    round_canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    draw = ImageDraw.Draw(round_canvas)
    draw.ellipse((1, 1, px - 2, px - 2), fill=(11, 14, 20, 255), outline=(200, 109, 59, 255), width=max(1, px // 48))
    
    badge_in_round = make_square_centered(cropped_shield_clean, size=px, padding_ratio=0.74)
    round_canvas.paste(badge_in_round, (0, 0), badge_in_round)
    round_canvas.save(os.path.join(target_dir, "ic_launcher_round.png"), format="PNG")
    
    print(f"Generado Launcher {folder}: {px}x{px} px")

# 5. GENERAR ASSETS PARA LA LANDING PAGE Y WEB
web_img_dir = "backend/public/images"
os.makedirs(web_img_dir, exist_ok=True)

# Logo navbar (128x128 y 256x256)
logo_web_256 = make_square_centered(cropped_shield_clean, size=256, padding_ratio=0.94)
logo_web_256.save(os.path.join(web_img_dir, "logo-patch.webp"), format="WEBP", quality=95)
logo_web_256.save(os.path.join(web_img_dir, "logo-patch.png"), format="PNG")

# Favicons
fav_32 = make_square_centered(cropped_shield_clean, size=32, padding_ratio=0.96)
fav_32.save("backend/public/favicon.png", format="PNG")
fav_64 = make_square_centered(cropped_shield_clean, size=64, padding_ratio=0.96)
fav_64.save(os.path.join(web_img_dir, "favicon-64.png"), format="PNG")
fav_192 = make_square_centered(cropped_shield_clean, size=192, padding_ratio=0.90)
fav_192.save(os.path.join(web_img_dir, "icon-192.png"), format="PNG")

print("--- TODOS LOS ASSETS HAN SIDO GENERADOS CON ÉXITO ---")
