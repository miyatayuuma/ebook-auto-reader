"""Measure whether missing ruby stems from detection or from recognizing the tiny crop."""
import time
from importlib.metadata import distribution

import numpy as np
from PIL import Image
import yaml
from deim import DEIM
from parseq import PARSEQ

from fixtures import OUT
from ocr import match_region, normalize, read_json, save_json


if __name__ == "__main__":
    models = OUT / "ndl-models"
    fixtures = OUT / "fixtures"
    official = distribution("ndlocr-lite")
    class_map = official.locate_file("config/ndl.yaml")
    charset = read_json(models / "charset.json")
    config = yaml.safe_load(class_map.read_text(encoding="utf-8"))
    detector = DEIM(str(models / "deim.onnx"), str(class_map), conf_threshold=0.25)
    recognizer = PARSEQ(str(models / "parseq.onnx"), charset)
    for name, model in (("DEIM", detector), ("PARSeq", recognizer)):
        for direction, tensors in (("input", model.session.get_inputs()), ("output", model.session.get_outputs())):
            print(f"{name} {direction}: {[(tensor.name, tensor.type, tensor.shape) for tensor in tensors]}")
    rows = []
    for page, reference in read_json(fixtures / "reference.json").items():
        ruby_regions = [region for region in reference["regions"] if region["role"] == "ruby"]
        if not ruby_regions:
            continue
        with Image.open(fixtures / "pages" / f"{page}.png") as image:
            rgb = np.asarray(image.convert("RGB"))
        started = time.perf_counter()
        regions = detector.detect(rgb)
        detection_ms = (time.perf_counter() - started) * 1000
        for ruby_index, target in enumerate(ruby_regions):
            candidates = [region for region in regions
                          if match_region(region["box"], ruby_regions) == ruby_index]
            for candidate in candidates:
                left, top, right, bottom = candidate["box"]
                candidate["cropText"] = recognizer.read(rgb[top:bottom, left:right])
            left, top, right, bottom = target["box"]
            known_crop = recognizer.read(rgb[top:bottom, left:right])
            rows.append(dict(
                page=page, expected=target["text"], oracleCrop=known_crop,
                oracleCorrect=normalize(known_crop) == normalize(target["text"]),
                candidates=[dict(kind=config["names"][c["class_index"]],
                                 reading=c["cropText"], confidence=float(c["confidence"]))
                            for c in candidates],
                detectorMs=round(detection_ms, 1),
            ))
    save_json(OUT / "ruby-probe.json", rows)
    print(rows)
