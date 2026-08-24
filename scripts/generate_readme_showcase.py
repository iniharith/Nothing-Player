from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "asset"
SCREENSHOT_DIR = ASSET_DIR / "screenshot"
FONT_DIR = ROOT / "composeApp" / "src" / "commonMain" / "composeResources" / "font"
OUTPUT = ASSET_DIR / "nothing-player-showcase.png"

WIDTH, HEIGHT = 1600, 900
BLACK = "#070707"
WHITE = "#F4F4F0"
MUTED = "#A7A7A0"
RED = "#E3262E"
GRID = "#1B1B1B"


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / name), size)


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def phone_panel(
    source_name: str,
    crop: tuple[int, int, int, int],
    size: tuple[int, int],
    label: str,
    replace_brand: bool = False,
) -> Image.Image:
    source = Image.open(SCREENSHOT_DIR / source_name).convert("RGB").crop(crop)
    if replace_brand:
        draw = ImageDraw.Draw(source)
        draw.rounded_rectangle((26, 48, 185, 93), 8, fill="#090909")
        draw.text((37, 59), "Nothing Player", font=font("poppins_medium.ttf", 15), fill=WHITE)

    frame = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(frame)
    draw.rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), 43, fill="#0C0C0C", outline="#3A3A38", width=2)

    inner_size = (size[0] - 24, size[1] - 52)
    fitted = source.resize(inner_size, Image.Resampling.LANCZOS)
    frame.paste(fitted, (12, 40), rounded_mask(inner_size, 31))

    draw.rounded_rectangle((size[0] // 2 - 29, 12, size[0] // 2 + 29, 20), 4, fill="#30302E")
    label_width = draw.textbbox((0, 0), label, font=font("poppins_bold.ttf", 14))[2]
    draw.rounded_rectangle((18, size[1] - 48, 40 + label_width, size[1] - 16), 16, fill=RED)
    draw.text((29, size[1] - 43), label, font=font("poppins_bold.ttf", 14), fill=WHITE)
    return frame


def paste_with_shadow(
    canvas: Image.Image,
    panel: Image.Image,
    position: tuple[int, int],
    angle: float,
) -> None:
    rotated = panel.rotate(angle, resample=Image.Resampling.BICUBIC, expand=True)
    alpha = rotated.getchannel("A")
    shadow = Image.new("RGBA", rotated.size, (0, 0, 0, 0))
    shadow.putalpha(alpha.filter(ImageFilter.GaussianBlur(22)).point(lambda value: value * 145 // 255))
    canvas.alpha_composite(shadow, (position[0] + 14, position[1] + 22))
    canvas.alpha_composite(rotated, position)


def draw_pill(draw: ImageDraw.ImageDraw, x: int, y: int, text: str, accent: bool = False) -> int:
    pill_font = font("poppins_medium.ttf", 15)
    width = draw.textbbox((0, 0), text, font=pill_font)[2] + 34
    fill = RED if accent else "#111111"
    outline = RED if accent else "#494946"
    draw.rounded_rectangle((x, y, x + width, y + 38), 19, fill=fill, outline=outline, width=1)
    draw.text((x + 17, y + 8), text, font=pill_font, fill=WHITE)
    return width


def main() -> None:
    canvas = Image.new("RGBA", (WIDTH, HEIGHT), BLACK)
    draw = ImageDraw.Draw(canvas)

    for x in range(0, WIDTH, 80):
        draw.line((x, 0, x, HEIGHT), fill=GRID, width=1)
    for y in range(0, HEIGHT, 80):
        draw.line((0, y, WIDTH, y), fill=GRID, width=1)

    glow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((1080, 90, 1740, 780), fill=(227, 38, 46, 80))
    canvas = Image.alpha_composite(canvas, glow.filter(ImageFilter.GaussianBlur(135)))
    draw = ImageDraw.Draw(canvas)

    draw.rectangle((0, 0, 18, HEIGHT), fill=RED)
    draw.rectangle((84, 76, 96, 193), fill=RED)
    draw.text((125, 70), "NOTHING", font=font("poppins_bold.ttf", 69), fill=WHITE)
    draw.text((78, 150), "PLAYER", font=font("poppins_bold.ttf", 112), fill=WHITE)
    draw.text((84, 293), "MUSIC WITHOUT THE NOISE.", font=font("poppins_medium.ttf", 23), fill=RED)
    draw.multiline_text(
        (84, 346),
        "A fast, open-source YouTube Music client\nfor Android and desktop.",
        font=font("poppins_regular.ttf", 21),
        fill=MUTED,
        spacing=9,
    )

    x = 84
    x += draw_pill(draw, x, 448, "LIQUID GLASS", True) + 12
    draw_pill(draw, x, 448, "SYNCED LYRICS")
    x = 84
    x += draw_pill(draw, x, 500, "OFFLINE PLAYBACK") + 12
    draw_pill(draw, x, 500, "ANDROID AUTO")

    draw.line((84, 593, 602, 593), fill="#444440", width=1)
    draw.text((84, 622), "2.0", font=font("poppins_bold.ttf", 42), fill=WHITE)
    draw.text((225, 635), "RELEASE", font=font("poppins_medium.ttf", 17), fill=MUTED)
    draw.text((84, 700), "OPEN SOURCE", font=font("poppins_bold.ttf", 17), fill=WHITE)
    draw.text((84, 735), "ANDROID  /  WINDOWS  /  MACOS  /  LINUX", font=font("poppins_regular.ttf", 15), fill=MUTED)

    for row in range(4):
        for column in range(10):
            color = RED if (row + column) % 7 == 0 else "#575752"
            draw.ellipse((86 + column * 20, 815 + row * 20, 93 + column * 20, 822 + row * 20), fill=color)

    search = phone_panel("03.png", (92, 305, 516, 1080), (315, 680), "SEARCH")
    library = phone_panel("04.png", (112, 305, 505, 1080), (350, 760), "LIBRARY")
    home = phone_panel("05.png", (112, 305, 505, 1080), (315, 680), "HOME", replace_brand=True)

    paste_with_shadow(canvas, search, (665, 133), -7.5)
    paste_with_shadow(canvas, home, (1231, 130), 7.5)
    paste_with_shadow(canvas, library, (925, 67), 0)

    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((1435, 62, 1534, 103), 20, fill=WHITE)
    draw.text((1452, 70), "v2.0", font=font("poppins_bold.ttf", 18), fill=BLACK)

    canvas.convert("RGB").save(OUTPUT, quality=95, optimize=True)
    print(f"Generated {OUTPUT.relative_to(ROOT)} ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()
