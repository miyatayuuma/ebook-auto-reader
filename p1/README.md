# P1 比較ラボ

Linux管理PCとUSBデバッグを有効にしたPixel 9を使用します。OCRも音声も自作素材で測定します。試作アプリは画面取得、他アプリの操作、本文・読書履歴へのアクセスを持ちません。

## 1. PCで素材と試聴用音声を作る

Python 3.12、JDK 17、Gradle 8.11.1、Android SDK command-line toolsとplatform-toolsを用意します。Ubuntu系では `fonts-noto-cjk` と GNU `time` を導入します。Android SDKに `ANDROID_HOME` を設定し、`sdkmanager --licenses` を済ませてください。

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r p1/requirements.txt
python p1/fixtures.py
python p1/reading.py prepare
python p1/reading.py pc
python p1/ocr.py ndl
python p1/prepare_models.py
python p1/ruby_probe.py
```

NDLOCR-Lite初回は公式配布元からONNXを取得し、上流のチェックサム検証を利用します。モデル利用時には上流のCC BY 4.0とDINOv3条件を確認してください。[NDLOCR-Lite](https://github.com/ndl-lab/ndlocr-lite) ／ [DINOv3ライセンス](https://github.com/facebookresearch/dinov3/blob/main/LICENSE.md)

明朝セットを標準とします。ゴシック比較が必要になった場合は `python p1/fixtures.py --gothic` を実行し、明朝とは分けて測ります。フォントのサイズは画像上のピクセル値です。

## 2. Pixelで一括測定する

AndroidのTTS設定でGoogleの日本語オフライン音声をダウンロードしておきます。日本語音声が端末に複数ある場合は、現在のオフライン音声を優先します。USB経由で対象のPixelを1台つなぎ、画面を点灯・解錠してください。

```bash
sdkmanager "platform-tools" "platforms;android-35"
gradle -p android :app:assembleDebug :app:testDebugUnitTest
bash p1/adb-run.sh
```

CI成果物の `p1-debug` からデバッグAPKを取得した場合は、SDKのビルド手順の代わりに `P1_APK=/path/to/app-debug.apk bash p1/adb-run.sh` を使えます。

出力先は `p1/out/pixel-YYYYMMDD-HHMMSS/` です。`status.json` の3項目で実行結果を確認します。Android側のOCRはML Kit日本語16.0.1と、NDL v2のDEIM・PARSeq（100文字モデル）をCPUで動かします。後者は比較用の簡易後処理で、上流PC実装の読み順・カスケード・表解析の完全な移植はP2の判断事項です。ADBスクリプトは試作用アプリのデータだけを更新します。

## 3. 結果を見る

```bash
pixel_dir="$(find p1/out -maxdepth 1 -type d -name 'pixel-*' | sort | tail -n 1)"
python p1/ocr.py score p1/out/ndl-pc.json "$pixel_dir/ndl-android.json" "$pixel_dir/mlkit.json"
python p1/reading.py listen p1/out/openjtalk "$pixel_dir/google"
xdg-open p1/out/listen/index.html
```

- OCRは `ocr-report.json` に、本文・ルビ・図注・見出し・ページ番号の文字誤りを分けて記録します。文字単位で置換・脱落・挿入を計数し、本文は正解領域に対応した行の認識とエンジンの出力順を記録します。生の出力も保存するので重複やページ番号の割り込みを確認できます。欠けた本文領域、本文の順序逆転、ルビ文字列と親行への対応は別項目です。図表分類やセル順序の精度は、このセットだけでは確定しません。
- PC OCRはモデル取得後に1枚ずつ別プロセスで起動します。時間は起動・読込込みの壁時計時間、メモリは最大RSSです。Androidは同一プロセスで初期化と推論を分け、前後のPSS（KiB）を記録します。端末の負荷、音声設定、発熱の有無を別途記録します。この二つのメモリ数値は直接比較できません。Pixelの厳密なピークPSSと電池は後続の長時間試験で測ります。
- 試聴はまずGoogleの同じ声で `original` と `corrected` を比べます。対象語の誤読、文全体の誤読件数、自然さ1〜5を `p1/out/listen/ratings.tsv` に入力し、`python p1/reading.py summarize p1/out/listen/ratings.tsv` で集計します。Open JTalkのフロントエンド一致率は音声判定とは別です。
- TalkBack比較は同じ日本語音声、速度で行い、まず自作テキストを表示するアプリで代表語を確認します。書籍の実利用結果は別の本人用記録に留めます。比較対象語の読み、聞こえた読み、飛ばし・重複の件数を記録します。アプリの合成完了コールバックはWAVの生成完了に用います。
- 1回目は `r01/r02`（一行）、`r06`（上手）、`r10`（地名）、`r12`（ルビ）、`r15`（単位）、`r18`（長文）を優先します。音声ファイルがそろえば残りも同じ画面で比較できます。

ルビの補助試験は、通常のエンジン出力に含まれた領域と正解座標から切り出したルビの認識を分け、選定上のボトルネックを確認します。正解座標による切り出しはOCRの採点に含めません。

PC試聴セットの全ファイルはGitHub Actionsの `p1-pc` アーティファクトを使って開くこともできます。PCとPixelを実行した結果や試聴記録は `p1/out/` に置きます。

NDLの転送後は、端末側の存在・サイズ・SHA-256が一致してから試験を起動します。修正版APKで再試験する場合、既存のPython環境と `p1/out/ndl-models/` の3ファイルを再利用できます。
