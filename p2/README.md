# P2.4 Pixel 9での確認

対象はPixel 9／Android 17のGoogle Play版DMMブックスと日本語縦書きリフロー本文です。画像と文字認識結果はセッション中のメモリに置き、合成音声はアプリの一時領域から再生後や停止時に削除します。Androidの画面取得が保護画面または黒画面を返すと停止します。[Androidの画面取得API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))

## APK更新

管理PCの既存チェックアウトを更新し、P2 Readerの成功したActions実行番号を指定します。

```bash
cd ~/ebook-auto-reader
git switch p2/reader-engine
git pull --ff-only
adb devices -l
bash p2/install-debug.sh --run RUN_ID
```

APKを取得済みの場合は、最後の行を `bash p2/install-debug.sh --apk ~/Downloads/EbookAutoReader-P2.4.apk` に置き換えます。既存のインストールと同じ管理PC・署名鍵で実行します。前回 `P2_SIGNING_DIR` を指定した場合は同じ値を使います。既定の鍵は `~/.local/share/ebook-auto-reader/` にあります。複数台接続時は、環境変数 `ANDROID_SERIAL` でPixel 9を選びます。

インストーラーは `adb install -r` で更新し、サイズとSHA-256が一致する導入済みモデルを再利用します。画面と操作の確認から始める場合は `--probe-only` を付けられます。初めてモデルを導入する場合は [P1のモデル取得手順](../p1/README.md) を使います。

## 1. DMMでの通常操作

アクセシビリティを有効にし、DMMの縦書き本文でメニューを閉じ、画面端の **▶** から **読み上げ開始** を押します。診断を事前に実行する必要はありません。次ページ方向はトップ画面の設定で切り替えます。

通常のページ送りは左右端タップ固定です。対象ウィンドウの左から約9.26%／90.74%、上から95%を70msタップします。1080×2424の全画面ならおよそ (100, 2303)／(980, 2303) です。パネルとタッチガードを外して2フレーム待ち、ページの安定確認後に復元します。下端にシステムUIやDMMの操作領域がある場合もあるため実機で確認してください。

## Lubuntuからの診断（デバッグAPKのみ）

DMM本文または自作ページを前面に置き、読み上げを終了した状態で実行します。ADB接続は既存の認証済み端末のみ使用します。

```bash
# 画面取得
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command capture
# 通常と同じパネル取り外しで往復
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command turn
# パネルなしとの比較
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command turn-headless
# 代替方式の診断（通常設定は変更しない）
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command turn --es method SWIPE
# method NODE、--ez delayed true も指定可能
```

往復は画面署名の変化と復元を検査します。DMMでの実際の移動方向・ページ数は自動保証されないため、目視で確認してください。エラー時は画面を確認してから再試行します。診断用受信口は DUMP 権限を要求し、リリースAPKでは登録しません。

自作ページ・音声冒頭検証はADBで起動します。

```bash
adb shell am start -n io.github.ebookautoreader.debug/io.github.ebookautoreader.reader.DiagnosticActivity --es test fixture --es page join-a
# page body-dense / body-bottom も指定可能
adb shell am start -n io.github.ebookautoreader.debug/io.github.ebookautoreader.reader.DiagnosticActivity --es test voice
```

## 2. 自作ページと実書籍での受入

1. 上記ADBコマンドで自作ページを起動します。自作ページでは右方向が次ページです。端末画面のページ番号とログの `fixture display` を見比べます。最初の「夜が明けると、川岸の石は朝の光を反射し、水の流れが銀色に見えた。」がページをまたいでつながることを確認します。
2. `body-bottom` では「空音は実をそっと地面へ戻した。」まで聞こえ、ノンブル27が入らないことを確認します。音声冒頭検証はADBの `test voice` で行います。
3. DMMで5〜10ページ聴き、飛ばし・重複、ページまたぎ、初回準備後の待ち時間を確認します。画面の **読み上げ中** と **表示** は開始位置からの相対ページ番号です。表示が先行した時点で **一時停止** と **終了** を試し、再生中の文のページか最後に完了した文末のページへ戻ることを確認します。**再開** は中断した文の文頭からです。
4. 一時停止中に手動でページを変える試験と、文字サイズ・余白を変える試験を行います。古い先読みを読み始めず安全に停止することを確認します。標準条件と文字が小さく余白が異なる条件で、新規に3ページずつ読み直します。
5. トップ画面で速度と高さを変更し、読み上げ中にもパネルの **−**／**＋** で速度を変えます。今の文、再開後、再起動後、トップ画面で照合します。速度は0.65〜1.65倍で自動保存します。トップ画面の **設定を保存** は音声の選択と辞書IDに使います。

## 3. 診断ログ

```bash
adb logcat -v threadtime -s EbookReader:I
```

| 記録 | 観測対象 |
| --- | --- |
| `input … accepted=` | Androidの入力受付。ノード操作は `callback=none` |
| `gesture android_result=` | ジェスチャー完了または中断 |
| `page sample` | 画面変化、本文領域の平均輝度差と変化セル数 |
| `page settle`／`stable_page_changed` | 安定した別表示を確認するまでの時間 |
| `page direction source=fixture`／`source=dmm` | 自作ページの移動量／DMMは移動量未検証 |
| `page turn test result=round_trip_ok` | 復元画像の署名が元と一致 |
| `fixture touch` | 自作画面のMotionEvent、座標、指の数、押下時間 |
| `ocr stage=MODEL/DETECT/RECOGNIZE/LAYOUT` | 初期化、領域検出、文字認識、組版解析 |
| `tts synthesize`／`audio prepare`／`audio completed` | 合成、事前の再生準備、再生ヘッド確認から完了まで |
| `buffer startup`／`buffer start`／`buffer wait`／`buffer stop` | 初回の蓄積、速度反映後の残量、枯渇回数と待ち時間、ページ境界の間隔 |
| `restore synchronized=` | 読み上げ位置への同期結果 |

ログは本文・画像・音声を含みません。音声冒頭の自作WAVは次のコマンドで取得できます。

```bash
adb shell -T run-as io.github.ebookautoreader.debug cat cache/diagnostics/sample-raw.wav > sample-raw.wav
adb shell -T run-as io.github.ebookautoreader.debug cat cache/diagnostics/sample-played.wav > sample-played.wav
```

P2.2のPixel実測はOCR初期化約7.4秒、ウォーム時の領域検出・文字認識約5.4秒、TTS合成1文約0.1〜0.35秒でした。P2.3ではページ取得・OCR、合成・プレーヤー準備、再生を並行します。初回の目標は8秒分、最初の音声準備後の追加待機は最大9秒です。再生中は20秒分を目標に、最大4ページ先、準備済み音声8件（WAV各4 MiB）まで保持します。読み上げ可能時間よりもページ安定確認とOCRの合計が長い短文ページが続くと、バッファが減ります。

DMMのページ変化の自動判定は画面署名に基づきます。DMMの方向・移動量は実機で目視確認します。位置はセッション内の相対位置です。音声内の文字単位の同期、書籍内の恒久位置、組版変更後の位置復元は今後の対象です。

## Workでの自作OCR測定

公式NDLOCR-LiteモデルをLinux x86_64でロードし、製品のKotlin認識・組版処理を実行しました。Androidの画像処理部分はAWTで代替しています。単発測定でモデル初期化は4.00秒でした。

| 入力 | 検出 | 文字認識 | 結果 |
| --- | ---: | ---: | --- |
| 6文字の短文3枚 | 0.96〜1.19秒 | 0.10〜0.13秒 | 3枚とも正解と一致 |
| 小さい文字・広い余白 | 1.32秒 | 0.76秒 | 7行を取得。131字中1字の欠落 |
| 高密度887字 | 1.11秒 | 2.30秒 | 18行・887字。18行中15行が完全一致 |
| 下端153字 | 1.08秒 | 0.37秒 | 3行・153字。27の混入なし。3行中2行が完全一致 |

ページまたぎ2枚の4行は正解と一致しました。認識した各行に矩形があり、文の範囲へ引き継ぐことを回帰試験で確認しました。Pixel上の実音、連続ページの待ち時間、DMMが公式API経由のタップを受けるかは実機で判定します。

## 発音辞書

アプリ内部の `files/pronunciation/books/<ID>.tsv` を書籍別、`files/pronunciation/common.tsv` を共通辞書として読みます。TSVはUTF-8で、1行は「表記、読み、直前の文字列、直後の文字列」のタブ区切りです。共通辞書には少なくとも片側の条件を指定します。書籍別のIDは設定で選びます。

```bash
adb shell -T run-as io.github.ebookautoreader.debug mkdir -p files/pronunciation/books
adb shell -T run-as io.github.ebookautoreader.debug sh -c \
  "'cat > files/pronunciation/books/sample.tsv'" < /path/to/sample.tsv
```

ルビは推定した親語に付け、確信のあるものだけ使用します。書籍別辞書、条件付きの共通辞書が続き、残りはGoogle TTSの読み推定を使います。OCRは[国立国会図書館のNDLOCR-Lite 2.0.0](https://pypi.org/project/ndlocr-lite/2.0.0/)のAndroid移植です。

### ページ番号 OCR 診断（P2.5A-1）

デバッグ版でアクセシビリティサービスを有効にし、DMMのページ番号が表示された本文を開いて、アイドル中に次を実行する。ページ移動や本文OCR・TTSは行わない。

```bash
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER \
  -p io.github.ebookautoreader.debug --es command page-number
adb logcat -d -s EbookReader:I | grep 'page_number result='
```

`number` は認識番号、`captureMs` は画像取得、`ocrMs` は数字OCR、`totalMs` は全体のミリ秒。`result=unrecognized` は番号を特定できなかった場合、`result=error` は取得・処理失敗を表す。`-1` は当該処理を開始していないことを表す。本文、書名、画像はログに含めない。Pixel 9 の複数ページで番号と時間を目視照合する。初回は認識器の初期化を含むため、初回と2回目以降を分けて確認する。

### 日本語・ラテン文字モデルの比較（P2.5A-1.1）

`--es model japanese`、`latin`、`both` のいずれかを指定する。省略時は従来どおり日本語。`both` は **同じスクリーンショット**を日本語、ラテン文字の順に認識し、モデル別に `page_number` ログを1行ずつ出す。

```bash
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER \
  -p io.github.ebookautoreader.debug --es command page-number --es model both
adb logcat -d -s EbookReader:I | grep 'page_number result=' | tail -n 2
```

`phase=first` はアクセシビリティサービス起動後、そのモデルを初めて呼び出した試行で、`phase=subsequent` は2回目以降。`attempt` はモデルごとの試行回数。初回が失敗した場合、2回目が十分ウォーム状態とは限らない。認識器はサービス存続中はモデルごとに再利用する。`captureMs` は共通、`ocrMs` は各モデル単独の処理時間、`totalMs` は診断開始から各モデルの完了までの累積時間なので、両者の速度比較には `ocrMs` を使う。処理順序の影響もあり得るため、単独実行も比較する。複数ページで表示番号と照合し、精度と初回・2回目以降の処理時間を記録する。画像、書籍名、本文は保存・出力しない。
