#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
package=io.github.ebookautoreader.lab
apk=android/app/build/outputs/apk/debug/app-debug.apk
models=p1/out/ndl-models

[[ -f "$apk" ]] || { echo 'Build app-debug.apk first' >&2; exit 1; }
if [[ "${1:-}" != --without-ndl ]]; then
  for file in deim.onnx parseq.onnx charset.json; do
    [[ -s "$models/$file" ]] || { echo "Missing $models/$file. Run p1/prepare_models.py." >&2; exit 1; }
  done
fi
adb get-state >/dev/null
adb install -r "$apk"
adb shell am force-stop "$package"
adb shell run-as "$package" rm -f files/results/status.json
if [[ "${1:-}" != --without-ndl ]]; then
  adb shell run-as "$package" mkdir -p files/ndl
  for file in deim.onnx parseq.onnx charset.json; do
    adb exec-in run-as "$package" sh -c "'cat > files/ndl/$file.tmp && mv files/ndl/$file.tmp files/ndl/$file'" < "$models/$file"
  done
fi
adb shell am start -n "$package/.LabActivity" --ez autorun true
destination=p1/out/pixel-$(date +%Y%m%d-%H%M%S)
for (( attempt=0; attempt<180; attempt++ )); do
  if adb shell run-as "$package" test -s files/results/status.json; then
    mkdir -p "$destination"
    adb exec-out run-as "$package" tar -cf - -C files/results . | tar -xf - -C "$destination"
    cat "$destination/status.json"
    printf '\nResults: %s\n' "$destination"
    exit 0
  fi
  sleep 2
done
echo 'Result timeout. Inspect: adb logcat -d -s EbookReaderLab' >&2
exit 1
