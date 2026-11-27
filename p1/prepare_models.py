"""Stage the two NDL models and character map needed by the Android prototype."""
from importlib.util import find_spec
from pathlib import Path
from shutil import copyfile

import yaml
from model_download import MODELS, get_model_path

from fixtures import OUT
from ocr import save_json


if __name__ == "__main__":
    source = Path(find_spec("ocr").origin).parent
    if not (source / "config" / "NDLmoji.yaml").is_file():
        from importlib.metadata import distribution
        source = Path(distribution("ndlocr-lite").locate_file("config")).parent
    output = OUT / "ndl-models"
    output.mkdir(parents=True, exist_ok=True)
    for key, name in [("det_weights", "deim.onnx"), ("rec_weights", "parseq.onnx")]:
        filename, fingerprint = MODELS[key]
        cached = get_model_path(filename, fingerprint, source / "model")
        copyfile(cached, output / name)
        print(f"{name}: {cached.stat().st_size} bytes")
    config = yaml.safe_load((source / "config" / "NDLmoji.yaml").read_text(encoding="utf-8"))
    save_json(output / "charset.json", list(config["model"]["charset_train"]))
