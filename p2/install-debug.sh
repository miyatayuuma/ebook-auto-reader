#!/usr/bin/env bash
set -euo pipefail

repo=miyatayuuma/ebook-auto-reader
package=io.github.ebookautoreader.debug
root=$(cd "$(dirname "$0")/.." && pwd)
models=${P2_MODELS:-"$root/p1/out/ndl-models"}
keydir=${P2_SIGNING_DIR:-"${XDG_DATA_HOME:-$HOME/.local/share}/ebook-auto-reader"}
mode=${1:-}
input=${2:-}
if [[ "$mode" != --run && "$mode" != --apk ]] || [[ -z "$input" ]]; then
  printf 'Usage: %s --run ACTIONS_RUN_ID | --apk PATH [--probe-only]\n' "$0" >&2
  exit 2
fi
probe=false
if [[ "${3:-}" == --probe-only ]]; then probe=true; fi
if [[ $# -gt 3 || ( $# -eq 3 && "$probe" == false ) ]]; then exit 2; fi

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  inventory=$(adb devices)
  mapfile -t devices < <(awk 'NR > 1 && NF >= 2 { print $1 }' <<< "$inventory")
  if [[ "${#devices[@]}" != 1 ]]; then
    printf '%s\nSet ANDROID_SERIAL to the Pixel 9 shown by adb devices.\n' "$inventory" >&2
    exit 1
  fi
  export ANDROID_SERIAL=${devices[0]}
fi
phone=(adb -s "$ANDROID_SERIAL")
if [[ $("${phone[@]}" get-state 2>/dev/null) != device ]]; then
  printf 'Target device is unavailable or USB debugging has not been authorized.\n' >&2
  exit 1
fi

if [[ "$probe" == false ]]; then
  for file in deim.onnx parseq.onnx charset.json; do
    [[ -s "$models/$file" ]] || { printf 'Missing model: %s\n' "$models/$file" >&2; exit 1; }
  done
fi

if command -v apksigner >/dev/null; then
  signer=$(command -v apksigner)
else
  sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Android/Sdk"}}
  signer="$sdk/build-tools/35.0.0/apksigner"
fi
[[ -x "$signer" ]] || { printf 'Install Android SDK build-tools;35.0.0 for apksigner.\n' >&2; exit 1; }

working=$(mktemp -d)
trap 'rm -rf -- "$working"' EXIT
if [[ "$mode" == --run ]]; then
  [[ "$input" =~ ^[0-9]+$ ]] || exit 2
  gh run download "$input" --repo "$repo" --name p2-debug --dir "$working/download"
  apk="$working/download/reader-debug.apk"
else
  apk=$input
fi
[[ -f "$apk" ]] || { printf 'APK not found: %s\n' "$apk" >&2; exit 1; }

(
  umask 077
  mkdir -p -- "$keydir"
  exec 9>"$keydir/p2-debug.lock"
  flock -x 9
  key="$keydir/p2-debug.p12"
  password="$keydir/p2-debug.password"
  if [[ ! -f "$key" ]]; then
    if [[ ! -f "$password" ]]; then openssl rand -hex 24 > "$password"; fi
    keytool -genkeypair -keystore "$key" -storetype PKCS12 -storepass:file "$password" \
      -alias p2-debug -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Ebook Auto Reader Local Debug"
  fi
  "$signer" sign --ks "$key" --ks-key-alias p2-debug --ks-pass "file:$password" \
    --out "$working/p2-debug.apk" "$apk"
  "$signer" verify --print-certs "$working/p2-debug.apk"
)

if ! "${phone[@]}" install -r "$working/p2-debug.apk"; then
  printf 'APK installation failed. If Android reported a signature mismatch, use the original P2_SIGNING_DIR to preserve app data.\n' >&2
  exit 1
fi
"${phone[@]}" shell am force-stop "$package"
if [[ "$probe" == false ]]; then
  run=("${phone[@]}" shell -T run-as "$package")
  "${run[@]}" mkdir -p files/ndl
  "${run[@]}" rm -f files/ndl/.p2-verified
  for file in deim.onnx parseq.onnx charset.json; do
    local_path="$models/$file"
    dest="files/ndl/$file"
    expected_size=$(stat -c %s -- "$local_path")
    expected_hash=$(sha256sum < "$local_path")
    expected_hash=${expected_hash%% *}
    matches() {
      local path=$1 actual_size actual_hash
      "${run[@]}" test -f "$path" &&
        actual_size=$("${run[@]}" stat -c %s "$path") &&
        actual_hash=$("${run[@]}" sha256sum "$path") &&
        [[ "$actual_size" == "$expected_size" && "${actual_hash%% *}" == "$expected_hash" ]]
    }
    if ! matches "$dest"; then
      if ! "${run[@]}" sh -c "'cat > $dest.incoming'" < "$local_path" ||
         ! matches "$dest.incoming" ||
         ! "${run[@]}" mv -f "$dest.incoming" "$dest" || ! matches "$dest"; then
        "${run[@]}" rm -f "$dest.incoming" || true
        printf 'Model transfer or verification failed: %s\n' "$file" >&2
        exit 1
      fi
    fi
    printf 'Verified %s (%s bytes)\n' "$file" "$expected_size"
  done
  "${run[@]}" touch files/ndl/.p2-verified
fi
"${phone[@]}" shell am start -n "$package/io.github.ebookautoreader.reader.ReaderActivity"
