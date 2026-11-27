"""Render original 1080×2424 pages and keep reference annotations off the device."""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, features

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"
SERIF = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc"
SANS = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
BODY = [
    "水面には、夜明けの雲が薄く映っていた。",
    "空音は橋を渡り、記録帳をそっと開いた。",
    "古い温度計の針は、まだ動いていない。",
    "「待とう」と言って、風の向きを記した。",
    "頁の端に、小さな印をひとつだけ付けた。",
    "やがて石英のかけらが淡い光を返した。",
    "細い道をたどると、図書館の扉が見えた。",
    "光の強さと影の長さを一行ずつ比べた。",
    "文字を急いで写すと、読点を落としやすい。",
    "夕方の鐘が鳴る前に、静かな通りへ出た。",
]


def render(root, font_path, font_index):
    if not features.check_feature("raqm"):
        raise RuntimeError("Pillow with libraqm support is required for vertical glyphs")
    image_dir = root / "pages"
    image_dir.mkdir(parents=True, exist_ok=True)
    references = {}

    def page(key, kind, size, columns=(), graph=False, folio=None, ruby=False):
        image = Image.new("RGB", (1080, 2424), "#fffdf8")
        draw = ImageDraw.Draw(image)
        regions = []

        def label(x, y, value, px, role, vertical, **metadata):
            font = ImageFont.truetype(font_path, px, index=font_index)
            options = {"font": font, "direction": "ttb" if vertical else "ltr", "language": "ja"}
            bounds = draw.textbbox((x, y), value, **options)
            draw.text((x, y), value, fill="#201b19", **options)
            regions.append(dict(role=role, text=value, box=[int(v) for v in bounds], **metadata))

        step = int(size * 1.9)
        for index, column in enumerate(columns):
            x = 950 - index * step
            role = "heading" if kind == "chapter" else "body"
            label(x, 200, column, size, role, True)
            if ruby and index == 1:
                label(x + int(size * 1.08), 200, "あおね", max(11, size // 2), "ruby", True,
                      parentRegion=len(regions) - 1, surface="空音")
        if graph:
            left, top, right, bottom = 150, 1750, 855, 2020
            draw.rectangle((left, top, right, bottom), outline="#453b2f", width=3)
            draw.line([(left+25, bottom-24), (left+200, bottom-85), (left+450, top+70),
                       (right-25, top+34)], fill="#465f83", width=6)
            label(left, top - 55, "図　光の強さ", 27, "caption", False)
            label(left, bottom + 22, "注　縦軸は相対値", 23, "caption", False)
        if folio is not None:
            label(520, 2295, folio, 24, "folio", False)
        image.save(image_dir / f"{key}.png")
        references[key] = {"kind": kind, "fontPixels": size, "regions": regions}

    for size, suffix in [(22, "small"), (34, "medium"), (48, "large")]:
        page(f"body-{suffix}", "mixed", size, BODY, graph=True, folio="12", ruby=True)
    page("chapter", "chapter", 66, ["第二章　橋の向こう"], folio="13")
    page("figure", "figure", 34, graph=True)
    page("blank", "blank", 34)
    page("join-a", "body", 34, ["夜が明けると、川岸の石は", "朝の光を反射し、"])
    page("join-b", "body", 34, ["水の流れが銀色に見えた。", "しばらく橋の上で立ち止まった。"])
    (root / "reference.json").write_text(json.dumps(references, ensure_ascii=False, indent=2) + "\n")
    (root / "corpus.json").write_text((HERE / "corpus.json").read_text())
    print(f"Rendered {len(references)} pages to {root}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=OUT / "fixtures")
    parser.add_argument("--gothic", action="store_true")
    parser.add_argument("--font")
    parser.add_argument("--font-index", type=int, default=0)
    args = parser.parse_args()
    render(args.out, args.font or (SANS if args.gothic else SERIF), args.font_index)
