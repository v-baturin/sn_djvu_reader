"""Build Android launcher icons from the generated monochrome source image."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets" / "icon-source.png"
RES = ROOT / "android" / "app" / "src" / "main" / "res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main() -> None:
    source = Image.open(SOURCE).convert("L").point(lambda value: 255 if value >= 192 else 0)
    for density, size in SIZES.items():
        inner = size * 82 // 100
        artwork = source.resize((inner, inner), Image.Resampling.LANCZOS)
        icon = Image.new("RGB", (size, size), "white")
        icon.paste(artwork, ((size - inner) // 2, (size - inner) // 2))

        destination = RES / f"mipmap-{density}"
        icon.save(destination / "ic_launcher.png")

        round_icon = icon.convert("RGBA")
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        round_icon.putalpha(mask)
        round_icon.save(destination / "ic_launcher_round.png")

        if density == "mdpi":
            icon.save(ROOT / "assets" / "icon.png")


if __name__ == "__main__":
    main()
