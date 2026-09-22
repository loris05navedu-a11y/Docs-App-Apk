"""Dessine l'icône de l'application : web/logo.png et icon.ico.

Chaque taille est tracée à sa résolution propre plutôt que réduite depuis la
plus grande — à 16 pixels, des traits fins réduits deviendraient illisibles.
"""

from PIL import Image, ImageDraw

GREEN = (0x0B, 0x6E, 0x4F, 255)
WHITE = (255, 255, 255)
AMBER = (0xF5, 0xB3, 0x01, 255)
SUPERSAMPLE = 8

def render(size):
    n = size * SUPERSAMPLE
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    k = n / 64.0

    def box(x, y, w, h, radius, fill):
        draw.rounded_rectangle([x * k, y * k, (x + w) * k, (y + h) * k],
                               radius=radius * k, fill=fill)

    box(0, 0, 64, 64, 15, GREEN)
    box(13, 16, 38, 8, 2, WHITE + (235,))          # bandeau d'en-tête
    for x in (13, 26.5, 40):
        box(x, 27, 11, 8, 1.6, WHITE + (140,))
    for x in (13, 26.5):
        box(x, 38, 11, 8, 1.6, WHITE + (140,))
    box(40, 38, 11, 8, 1.6, AMBER)                 # la cellule active
    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = {s: render(s) for s in sizes}
    images[256].save("web/logo.png")
    images[256].save("icon.ico", format="ICO",
                     sizes=[(s, s) for s in sizes],
                     append_images=[images[s] for s in sizes if s != 256])
    print("web/logo.png et icon.ico regénérés")
