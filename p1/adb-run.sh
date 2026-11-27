#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
package=io.github.ebookautoreader.lab
apk=${P1_APK:-android/app/build/outputs/apk/debug/app-debug.apk}
models=p1/out/ndl-models
run_ndl=true
if [[ "${1:-}" == --without-ndl ]]; then run_ndl=false; fi

[[ -f "$apk" ]] || { echo 'Build app-debug.apk first' >&2; exit 1; }
if [[ "$run_ndl" == true ]]; then
  for file in deim.onnx parseq.onnx charset.json; do
    [[ -s "$models/$file" ]] || { echo "Missing $models/$file. Run p1/prepare_models.py." >&2; exit 1; }
  done
fi
adb get-state >/dev/null
adb install -r "$apk"
adb shell am force-stop "$package"
adb shell run-as "$package" rm -f files/results/status.json
if [[ "$run_ndl" == true ]]; then
  adb shell run-as "$package" mkdir -p files/ndl
  for file in deim.onnx parseq.onnx charset.json; do
    local_path="$models/$file"
    remote_path="files/ndl/$file"
    expected_size=$(stat -c %s -- "$local_path")
    expected_hash=$(sha256sum < "$local_path")
    expected_hash=${expected_hash%% *}
    if ! adb shell -T run-as "$package" sh -c "'cat > $remote_path.tmp'" < "$local_path" ||
       ! adb shell -T run-as "$package" test -f "$remote_path.tmp" ||
       ! actual_size=$(adb shell -T run-as "$package" stat -c %s "$remote_path.tmp") ||
       ! actual_hash=$(adb shell -T run-as "$package" sha256sum "$remote_path.tmp") ||
       [[ "$actual_size" != "$expected_size" || "${actual_hash%% *}" != "$expected_hash" ]] ||
       ! adb shell -T run-as "$package" mv -f "$remote_path.tmp" "$remote_path"; then
      adb shell -T run-as "$package" rm -f "$remote_path.tmp" || true
      printf 'NDL model transfer or verification failed: %s\n' "$file" >&2
      exit 1
    fi
    printf 'Verified %s (%s bytes, SHA-256 %s)\n' "$file" "$expected_size" "$expected_hash"
  done
fi
adb shell am start -n "$package/.LabActivity" --ez autorun true --ez run_ndl "$run_ndl"
destination=p1/out/pixel-$(date +%Y%m%d-%H%M%S)
for (( attempt=0; attempt<180; attempt++ )); do
  if adb shell run-as "$package" test -s files/results/status.json; then
    mkdir -p "$destination"
    adb exec-out run-as "$package" tar -cf - -C files/results . | tar -xf - -C "$destination"
    cat "$destination/status.json"
    printf '\nResults: %s\n' "$destination"
    python3 - "$destination/status.json" "$run_ndl" <<'PYTHON'
import json
import sys
from pathlib import Path
status = json.loads(Path(sys.argv[1]).read_text())
required = ["mlkit", "google"]
if sys.argv[2] == "true":
    required.append("ndl")
sys.exit(any(status.get(part) != "ok" for part in required))
PYTHON
    exit 0
  fi
  sleep 2
done
echo 'Result timeout. Inspect: adb logcat -d -s EbookReaderLab' >&2
exit 1
