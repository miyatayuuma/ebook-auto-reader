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


DENSE = [
    "水路の見回りを頼まれた朝、門番は地図を一枚くれた。紙は何度も折られて、角だけが柔らかくなっていた。",
    "空音は地図を広げ、橋のたもとに描かれた小さな丸を指で押さえた。そこには名前も目印も書かれていなかった。",
    "川沿いをしばらく歩くと、石垣の下から水の音がした。岸辺から見ると、小さな溝が堤の向こうへ続いている。",
    "先週の雨で落ち葉が積もり、溝の入口をふさいでいた。枝を差し込んで少し持ち上げると、水が足元を抜けた。",
    "空の色を映した流れが、砂の粒を運んでいく。空音はその様子を眺めながら、手帳に時刻と水の深さを書いた。",
    "向こうから長靴をはいた人が歩いてきた。肩にかけた袋から、赤と白に塗った細い棒の先がのぞいていた。",
    "その人は袋を降ろし、古い石段のいちばん下に棒を立てた。水が増えたときに、どこまで届くか調べるという。",
    "二人で岸の高さを見比べると、同じように見えた石段にも差があった。左の端だけ石が少し沈んでいる。",
    "石の間には草の根が伸びていた。むやみに引き抜けば石もゆるみそうなので、場所を記録して先へ進んだ。",
    "昼前になると、水面の反射が強くなった。橋の影へ入ると、流れの底に木の実がいくつも転がっている。",
    "岸に上がった二人は、並んでおにぎりを食べた。手帳を広げていると、風がページを一枚ずつめくった。",
    "空音は古い記録を見返した。水が少ない日も、石段の端にある細い筋だけは、いつも濡れていたらしい。",
    "石段の下へ戻ると、棒の目盛りが朝より二つ下がっていた。溝につまった葉を除いた先でも水が流れていた。",
    "地図の丸に着いてようやく、そこが二つの水路が合わさる場所だとわかった。浅いほうには小魚が集まっていた。",
    "もう片方の水路はまっすぐで、岸に背の高い草が茂っている。流れを横切る風が吹くたび、穂が一斉になびいた。",
    "手帳に二本の線と丸を描き足し、水路の入口を丸で囲んだ。次に見回る人が迷わないよう、石段の数も書き添えた。",
    "夕方、門番は地図を受け取って窓際で広げた。朝には何もなかった丸の横に、今日の日付と水の深さが並んだ。",
    "帰り道、橋の上で空音は足を止めた。朝と同じ川を見ているのに、ここから先の流れまで想像できる気がした。",
]


def render(root, font_path, font_index, dense=False):
    if not features.check_feature("raqm"):
        raise RuntimeError("Pillow with libraqm support is required for vertical glyphs")
    image_dir = root / "pages"
    image_dir.mkdir(parents=True, exist_ok=True)
    references = {}

    def page(key, kind, size, columns=(), graph=False, folio=None, ruby=False, column_pitch=None):
        image = Image.new("RGB", (1080, 2424), "#fffdf8")
        draw = ImageDraw.Draw(image)
        regions = []

        def label(x, y, value, px, role, vertical, **metadata):
            font = ImageFont.truetype(font_path, px, index=font_index)
            options = {"font": font, "direction": "ttb" if vertical else "ltr", "language": "ja"}
            bounds = draw.textbbox((x, y), value, **options)
            draw.text((x, y), value, fill="#201b19", **options)
            regions.append(dict(role=role, text=value, box=[int(v) for v in bounds], **metadata))

        step = column_pitch or int(size * 1.9)
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
    if dense:
        page("body-dense", "body", 32, DENSE, folio="14", ruby=True, column_pitch=49)
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
    parser.add_argument("--dense", action="store_true", help="Include the P2 dense vertical page")
    parser.add_argument("--font")
    parser.add_argument("--font-index", type=int, default=0)
    args = parser.parse_args()
    render(args.out, args.font or (SANS if args.gothic else SERIF), args.font_index, dense=args.dense)
