"""Compare readings and synthesize a listening set from the same original prompts."""
import argparse
import csv
import time
import unicodedata
import wave
from importlib.metadata import version
from pathlib import Path

from fixtures import HERE, OUT
from ocr import read_json, save_json

PLACE_NAMES = {"御器所": "ごきそ", "百舌鳥": "もず"}
CONTEXT = [
    ("一行", "船に乗", "いっこう"),
    ("一行", "一行だけ", "いちぎょう"),
    ("市場", "市場で仕入", "いちば"),
    ("市場", "市場に出回", "しじょう"),
    ("上手", "舞台の上手", "かみて"),
    ("生物", "常温に置", "なまもの"),
]
SMOKE_TAKES = ("original", "corrected")


def kana(text):
    return "".join(chr(ord(ch) - 96) if "ァ" <= ch <= "ヶ" else ch
                   for ch in unicodedata.normalize("NFC", text))


def correction(case):
    surface = case["focus"]
    if case.get("ruby"):
        return case["ruby"], "ruby"
    if surface in PLACE_NAMES:
        return PLACE_NAMES[surface], "dictionary"
    for word, cue, reading in CONTEXT:
        if surface == word and cue in case["text"]:
            return reading, "context-rule"
    return None, None


def item(case):
    original = case["text"]
    target = case["focus"]
    if original.count(target) != 1:
        raise ValueError(f"{case['id']} needs one unambiguous reference span")
    start = original.index(target)
    end = start + len(target)
    reading, source = correction(case)
    changes = [] if reading is None else [dict(start=start, end=end, reading=reading, source=source)]
    corrected = original if not changes else original[:start] + reading + original[end:]
    return dict(case, original=original, corrected=corrected, start=start, end=end, changes=changes)


def sudachi_span(case, tokenizer):
    from sudachipy import SplitMode

    selected = [part for part in tokenizer.tokenize(case["original"], SplitMode.C)
                if part.begin() < case["end"] and part.end() > case["start"]]
    if (not selected or selected[0].begin() != case["start"] or selected[-1].end() != case["end"]
            or any(part.is_oov() or not part.reading_form() or part.reading_form() == "*"
                   for part in selected)):
        return None
    return kana("".join(part.reading_form() for part in selected))


def jtalk_span(text, start, end, frontend):
    # Open JTalk may rewrite numbers or punctuation while tokenizing. Do not guess
    # an alignment in those cases. The WAV remains available for a listening verdict.
    normalized = unicodedata.normalize("NFKC", text)
    left = len(unicodedata.normalize("NFKC", text[:start]))
    right = len(unicodedata.normalize("NFKC", text[:end]))
    position = 0
    readings = []
    for node in frontend(text):
        surface = unicodedata.normalize("NFKC", node["string"])
        while position < len(normalized) and normalized[position].isspace():
            position += 1
        if not normalized.startswith(surface, position):
            return None
        next_position = position + len(surface)
        if position < right and next_position > left:
            if position < left or next_position > right or node["read"] == "*":
                return None
            readings.append(node["read"])
        if next_position == right and readings:
            return kana("".join(readings))
        position = next_position
    return None


def verdict(actual, accepted):
    return "unscored" if actual is None else "correct" if actual in accepted else "wrong"


def prepare(fixtures):
    cases = [item(case) for case in read_json(HERE / "corpus.json")]
    save_json(fixtures / "speech.json", cases)


def run(destination, samples):
    import numpy as np
    import pyopenjtalk
    from sudachipy import Dictionary

    tokenizer = Dictionary().create()
    rows = []
    for case in map(item, read_json(HERE / "corpus.json")):
        source = case["changes"][0]["source"] if case["changes"] else None
        end_corrected = case["end"] + len(case["corrected"]) - len(case["original"])
        guesses = dict(sudachi=sudachi_span(case, tokenizer),
                       openJtalkOriginal=jtalk_span(case["original"], case["start"], case["end"],
                                                     pyopenjtalk.run_frontend),
                       openJtalkCorrected=jtalk_span(case["corrected"], case["start"], end_corrected,
                                                      pyopenjtalk.run_frontend))
        guesses["sudachiWithOverrides"] = (case["changes"][0]["reading"] if source else guesses["sudachi"])
        row = dict(id=case["id"], category=case["category"], source=source, expected=case["readings"],
                   readings=guesses, verdicts={key: verdict(value, case["readings"])
                                              for key, value in guesses.items()})
        if case.get("smoke"):
            for take in SMOKE_TAKES:
                started = time.perf_counter()
                signal, rate = pyopenjtalk.tts(case[take])
                elapsed = time.perf_counter() - started
                pcm = np.clip(signal, -32768, 32767).astype("<i2")
                target = samples / f'{case["id"]}-{take}.wav'
                target.parent.mkdir(parents=True, exist_ok=True)
                with wave.open(str(target), "wb") as audio:
                    audio.setparams((1, 2, rate, 0, "NONE", "not compressed"))
                    audio.writeframes(pcm.tobytes())
                row[take] = dict(ms=round(elapsed * 1000, 2), audioSeconds=len(pcm) / rate,
                                 peak=int(np.abs(pcm.astype("int32")).max()))
        rows.append(row)
    methods = rows[0]["verdicts"]
    totals = {method: {status: sum(row["verdicts"][method] == status for row in rows)
                       for status in ["correct", "wrong", "unscored"]} for method in methods}
    save_json(destination, dict(versions={name: version(name) for name in
                                          ["pyopenjtalk-plus", "SudachiPy", "SudachiDict-core"]},
                                totals=totals, rows=rows))
    print(totals)


def listening(root, folders):
    from html import escape
    from os.path import relpath

    root.mkdir(parents=True, exist_ok=True)
    cases = read_json(HERE / "corpus.json")
    html = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>P1 試聴</title>",
            "<h1>同じ原文の読み比べ</h1>",
            "<p>音声を聴き、対象語の読みと自然さを ratings.tsv に記録してください。</p>"]
    with (root / "ratings.tsv").open("w", encoding="utf-8") as file:
        writer = csv.writer(file, delimiter="\t")
        writer.writerow(["id", "engine", "take", "focus_ok_0_or_1", "whole_text_errors",
                         "naturalness_1_to_5", "heard", "notes"])
        for case in cases:
            html.append(f"<h2>{case['id']} {escape(case['category'])}</h2>")
            html.append(f"<p>{escape(case['text'])}<br>対象 {escape(case['focus'])} ／ "
                        f"{escape('・'.join(case['readings']))}</p>")
            for folder in folders:
                for take in ("original", "corrected", "talkback"):
                    sound = folder / f"{case['id']}-{take}.wav"
                    if sound.is_file():
                        engine = folder.name
                        html.append(f"<p>{escape(engine)} / {take}<br>"
                                    f"<audio controls preload='none' src='{escape(relpath(sound, root))}'>"
                                    "</audio></p>")
                        writer.writerow([case["id"], engine, take, "", "", "", "", ""])
    (root / "index.html").write_text("\n".join(html), encoding="utf-8")


def summarize(path):
    from collections import Counter, defaultdict
    data = defaultdict(Counter)
    seen = set()
    with path.open(encoding="utf-8") as file:
        for row in csv.DictReader(file, delimiter="\t"):
            key = tuple(row[field] for field in ("id", "engine", "take"))
            if key in seen:
                raise ValueError(f"Duplicate rating for {key}")
            seen.add(key)
            counters = data["/".join(key[1:])]
            if not row["focus_ok_0_or_1"]:
                counters["notRated"] += 1
                continue
            correct, errors, natural = (int(row[field]) for field in
                ("focus_ok_0_or_1", "whole_text_errors", "naturalness_1_to_5"))
            if correct not in (0, 1) or errors < 0 or not 1 <= natural <= 5:
                raise ValueError(f"Out-of-range rating for {key}")
            counters["rated"] += 1
            counters["correct"] += correct
            counters["errors"] += errors
            counters["naturalnessSum"] += natural
    for stats in data.values():
        if stats["rated"]:
            stats["meanNaturalness"] = stats["naturalnessSum"] / stats["rated"]
    print(dict(data))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="command", required=True)
    prep = modes.add_parser("prepare")
    prep.add_argument("--fixtures", type=Path, default=OUT / "fixtures")
    modes.add_parser("pc")
    listen = modes.add_parser("listen")
    listen.add_argument("folders", nargs="+", type=Path)
    rated = modes.add_parser("summarize")
    rated.add_argument("tsv", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.fixtures)
    elif args.command == "pc":
        run(OUT / "reading-pc.json", OUT / "openjtalk")
    elif args.command == "listen":
        listening(OUT / "listen", args.folders)
    else:
        summarize(args.tsv)
