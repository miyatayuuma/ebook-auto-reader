"""Run the official PC OCR and score it alongside Android result files."""
import argparse
import json
import platform
import re
import shutil
import statistics
import subprocess
import tempfile
import unicodedata
from collections import Counter, defaultdict
from importlib.metadata import version
from pathlib import Path

from fixtures import OUT

FIXTURES = OUT / "fixtures"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run_ndl(fixtures, destination):
    command = shutil.which("ndlocr-lite")
    if not command:
        raise SystemExit("Install ndlocr-lite==2.0.0 into the active Python environment")
    if version("ndlocr-lite") != "2.0.0":
        raise SystemExit("Use the pinned NDLOCR-Lite 2.0.0 for comparison")
    pages = {}
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        # Warm the downloaded model cache before measuring individual cold processes.
        images = sorted((fixtures / "pages").glob("*.png"))
        if not images:
            raise SystemExit("Generate fixtures before running OCR")
        args = [command, "--output", str(directory), "--device", "cpu", "--json-only", "--sourceimg"]
        subprocess.run([*args, str(images[0])], check=True)
        for image in images:
            metrics = directory / "time"
            subprocess.run(
                ["/usr/bin/time", "-o", str(metrics), "-f", "%e %M", *args, str(image)],
                check=True,
            )
            seconds, rss = metrics.read_text().split()
            lines = []
            for line in read_json(directory / f"{image.stem}.json")["contents"][0]:
                x, y = zip(*line["boundingBox"])
                lines.append(dict(text=line["text"], box=[min(x), min(y), max(x), max(y)],
                                  layoutClass=line["class_index"]))
            pages[image.stem] = dict(lines=lines, elapsedMs=float(seconds) * 1000,
                                     peakRssKiB=int(rss))
            print(f"{image.stem}: {len(lines)} lines, {seconds}s, {rss} KiB", flush=True)
    save_json(destination, dict(engine="NDLOCR-Lite 2.0.0 PC CLI", system=platform.machine(),
                                timing="single-page cold process after download", pages=pages))


def normalize(text):
    # Preserve compatibility variants, kana, punctuation and symbols. Ignore layout spaces.
    text = unicodedata.normalize("NFC", text)
    return re.sub(r"\s+", "", text)


def edits(reference, actual):
    """Minimum character edits, preserving counts for omissions and insertions."""
    reference, actual = normalize(reference), normalize(actual)
    previous = [(j, 0, 0, j) for j in range(len(actual) + 1)]
    for i, left in enumerate(reference, 1):
        current = [(i, 0, i, 0)]
        for j, right in enumerate(actual, 1):
            cost, substitutions, deletions, insertions = previous[j - 1]
            change = int(left != right)
            diagonal = (cost + change, substitutions + change, deletions, insertions)
            cost, substitutions, deletions, insertions = previous[j]
            delete = (cost + 1, substitutions, deletions + 1, insertions)
            cost, substitutions, deletions, insertions = current[j - 1]
            insert = (cost + 1, substitutions, deletions, insertions + 1)
            current.append(min((diagonal, delete, insert), key=lambda entry: entry[0]))
        previous = current
    distance, substitutions, deletions, insertions = previous[-1]
    return dict(chars=len(reference), errors=distance, substitutions=substitutions,
                deletions=deletions, insertions=insertions)


def match_region(box, regions):
    left, top, right, bottom = box
    area = max(1, (right - left) * (bottom - top))

    def intersection(region):
        l, t, r, b = region["box"]
        return max(0, min(right, r) - max(left, l)) * max(0, min(bottom, b) - max(top, t))

    if not regions:
        return None
    best = max(range(len(regions)), key=lambda index: intersection(regions[index]))
    return best if intersection(regions[best]) / area >= 0.30 else None


def score_page(page, result):
    regions = page["regions"]
    lines = result["lines"]
    assignments = [match_region(line["box"], regions) for line in lines]
    grouped = defaultdict(list)
    for index, line in zip(assignments, lines):
        if index is not None:
            grouped[index].append(line)
    chars_by_role = defaultdict(Counter)
    recovered = [
        "".join(line["text"] for line in grouped[index])
        for index in range(len(regions))
    ]
    for region, text in zip(regions, recovered):
        chars_by_role[region["role"]].update(edits(region["text"], text))
    main_roles = {"body", "heading"}
    main_order = list(dict.fromkeys(
        index for index in assignments if index is not None and regions[index]["role"] in main_roles
    ))
    inversions = sum(left > right for i, left in enumerate(main_order) for right in main_order[i+1:])
    ruby_exact = []
    for index, region in enumerate(regions):
        if region["role"] == "ruby":
            expected_parents = {line_index for line_index, region_index in enumerate(assignments)
                                if region_index == region["parentRegion"]}
            linked = any(assignments[j] == index and line.get("rubyParentLine") in expected_parents
                         for j, line in enumerate(lines))
            ruby_exact.append(dict(
                surface=region["surface"],
                readingCorrect=normalize(recovered[index]) == normalize(region["text"]),
                linkCorrect=linked,
            ))
    expected_body = "".join(region["text"] for region in regions if region["role"] in main_roles)
    associated_body = "".join(line["text"] for index, line in zip(assignments, lines)
                              if index is not None and regions[index]["role"] in main_roles)
    raw_text = "".join(line["text"] for line in lines)
    timing = {key: value for key, value in result.items() if key != "lines"}
    return dict(kind=page["kind"], fontPixels=page["fontPixels"], **timing,
                byRole=chars_by_role, expectedBody=expected_body, associatedBody=associated_body,
                rawText=raw_text, regionMatches=sum(i is not None for i in assignments),
                unmatchedLines=sum(i is None for i in assignments),
                missingBodyRegions=sum(i not in main_order for i, region in enumerate(regions)
                                       if region["role"] in main_roles),
                orderInversions=inversions, ruby=ruby_exact,
                bodyInEngineOrder=edits(expected_body, associated_body),
                rawReadout=edits(expected_body, raw_text))


def score(fixtures, result_paths, target):
    references = read_json(fixtures / "reference.json")
    comparison = []
    for path in result_paths:
        result = read_json(path)
        available = result["pages"].keys()
        if set(references) != set(available):
            raise SystemExit(f"{path}: expected all fixtures, got {sorted(available)}")
        pages = {key: score_page(reference, result["pages"][key]) for key, reference in references.items()}
        roles = defaultdict(Counter)
        for page in pages.values():
            for role, counts in page["byRole"].items():
                roles[role].update(counts)
        elapsed = [page["elapsedMs"] for page in pages.values()]
        summary = {role: dict(counts, cer=round(counts["errors"] / counts["chars"], 5))
                   for role, counts in roles.items()}
        metadata = {key: value for key, value in result.items() if key != "pages"}
        totals = dict(medianMs=statistics.median(elapsed), maxMs=max(elapsed),
                      bodyOrderInversions=sum(page["orderInversions"] for page in pages.values()),
                      rubyReadingCorrect=sum(r["readingCorrect"] for p in pages.values() for r in p["ruby"]),
                      rubyLinksCorrect=sum(r["linkCorrect"] for p in pages.values() for r in p["ruby"]),
                      rubyTotal=sum(len(p["ruby"]) for p in pages.values()))
        comparison.append(dict(metadata=metadata, byRole=summary, totals=totals, pages=pages))
        print(result["engine"], json.dumps(dict(byRole=summary, **totals), ensure_ascii=False))
    save_json(target, comparison)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=FIXTURES)
    commands = parser.add_subparsers(dest="command", required=True)
    ndl = commands.add_parser("ndl")
    ndl.add_argument("--out", type=Path, default=OUT / "ndl-pc.json")
    report = commands.add_parser("score")
    report.add_argument("results", nargs="+", type=Path)
    report.add_argument("--out", type=Path, default=OUT / "ocr-report.json")
    args = parser.parse_args()
    if args.command == "ndl":
        run_ndl(args.fixtures, args.out)
    else:
        score(args.fixtures, args.results, args.out)
