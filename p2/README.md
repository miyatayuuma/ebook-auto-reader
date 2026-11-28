# P2.5 統合版：Pixel 9実機受入

P2.6第1陣の統合候補では、[追加受入手順](../docs/p26-integration.md) を優先してください。以下の固定APKはP2.5の履歴です。

対象はPixel 9／Android 17のGoogle Play版DMMブックスと日本語縦書きリフロー本文です。画像は処理後に解放し、合成音声は一時領域から再生後や停止時に削除します。開発用デバッグ版では認識済みページ（原文・ルビ・位置・分類等）を端末のバックアップ対象外の私有領域に保持し、同一画像・モデル・認識条件の再読時に再利用します。保存件数・容量の表示と全削除を設定画面から実行できます。自動期限・恒久保存方針は未決定で、書籍別ページ番号索引も未実装です。Androidの画面取得が保護画面または黒画面を返すと停止します。[Androidの画面取得API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))

## APK更新

管理PCの既存チェックアウトを更新し、受入対象のソースSHAと成功済みP2 ReaderのActions実行番号を固定します。受入対象は `5e826ac49db05ad9cc911f37ece221dc325646c2`、[P2 Reader CI](https://github.com/miyatayuuma/ebook-auto-reader/actions/runs/46124278499)、[APK artifact](https://github.com/miyatayuuma/ebook-auto-reader/actions/runs/46124278499/artifacts/15255001924) です。P1 Labも成功済みです。今回、APKの内部versionNameは旧来の `p2.5c2-crop2` のままなので、SHAとActions実行番号で識別してください。

```bash
cd ~/ebook-auto-reader
git fetch origin p2/reader-engine
git switch --detach 5e826ac49db05ad9cc911f37ece221dc325646c2
adb devices -l
bash p2/install-debug.sh --run 46124278499
```

APKを取得済みの場合は、最後の行を `bash p2/install-debug.sh --apk ~/Downloads/reader-debug.apk` に置き換えます。既存のインストールと同じ管理PC・署名鍵で実行します。前回 `P2_SIGNING_DIR` を指定した場合は同じ値を使います。既定の鍵は `~/.local/share/ebook-auto-reader/` にあります。複数台接続時は、環境変数 `ANDROID_SERIAL` でPixel 9を選びます。

インストーラーは `adb install -r` で更新し、サイズとSHA-256が一致する導入済みモデルを再利用します。画面と操作の確認から始める場合は `--probe-only` を付けられます。初めてモデルを導入する場合は [P1のモデル取得手順](../p1/README.md) を使います。

## 1. DMMでの通常操作

アクセシビリティと通知を許可し、クイック設定を開いて編集から **Ebook Auto Reader** を追加します。DMMの縦書き本文でメニューを閉じ、タイルの **読み上げ開始** を押します。再生中はタイルで一時停止、一時停止中は再開します。通知欄では状態と相対位置を確認でき、一時停止・再開・終了を操作できます。次ページ方向はトップ画面の設定で切り替えます。

通常のページ送りは左右端タップ固定です。対象ウィンドウの左から約9.26%／90.74%、上から95%を70msタップします。1080×2424の全画面ならおよそ (100, 2303)／(980, 2303) です。パネルや透明タッチガードは使用しません。通知欄の表示中はページ操作を保留します。下端にシステムUIやDMMの操作領域がある場合もあるため実機で確認してください。

## Lubuntuからの診断（デバッグAPKのみ）

DMM本文または自作ページを前面に置き、読み上げを終了した状態で実行します。ADB接続は既存の認証済み端末のみ使用します。

```bash
# 画面取得
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command capture
# 通常動作と同じ方法で往復
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command turn
# 3秒後に往復（パネルなし）
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

## 2. 統合版の優先受入

最初は既定の組版・ページ番号表示ありで実書籍を使い、下記を順番に確認します。異常時はそこで止め、ログと目視結果を残してください。APKの再導入は不要です。

1. **ページ送り往復**：読み上げ停止中に `turn` 診断を実行し、隣接ページへの移動・元ページへの復元を目視。番号違い・入力再送・不明位置への復元がないこと。移動時間を記録。
2. **耳で聞く**：既定速度で本文を開いて準備を待ち、5〜10ページ連続再生。初回の開始時間、ヘッダー除外、章扉・図版の読み上げ、音切れ、通知操作、停止時の復元を確認。
3. **目で追う**：設定で選び、新しいセッションを開始。画像を取得後にOCR完了を待たず元ページへ戻ること、音声開始時の表示一致を確認。ページまたぎの文は連結を維持し、文境界で同期する（文字単位の厳密同期は未実装）。
4. **事前準備の失効**：本文画面に入って準備完了後、手動で別ページに進んで開始。旧ページの音声が出ないこと。準備前・準備済みの開始時間を比較。事前OCRはウィンドウ進入につき1回で、再生前にページ操作はしない。
5. **キャッシュ**：同一組版・同一表示ページを再読して `ocr cache hit` または事前準備再利用を確認。設定画面の件数・容量・削除を試す。削除は読書終了後に行う。
6. **速度**：100%、200%、250%を試し、連続再生・保存・復元・音質を確認。高速時の先読み枯渇は別途記録。

## 3. 補足受入：自作ページ・組版・操作中断

1. 上記ADBコマンドで自作ページを起動します。自作ページでは右方向が次ページです。端末画面のページ番号とログの `fixture display` を見比べます。最初の「夜が明けると、川岸の石は朝の光を反射し、水の流れが銀色に見えた。」がページをまたいでつながることを確認します。
2. `body-bottom` では「空音は実をそっと地面へ戻した。」まで聞こえ、ノンブル27が入らないことを確認します。音声冒頭検証はADBの `test voice` で行います。
3. DMMで5〜10ページ聴き、飛ばし・重複、ページまたぎ、初回準備後の待ち時間を確認します。画面の **読み上げ中** と **表示** は開始位置からの相対ページ番号です。表示が先行した時点で **一時停止** と **終了** を試し、再生中の文のページか最後に完了した文末のページへ戻ることを確認します。**再開** は中断した文の文頭からです。
4. 一時停止中に手動でページを変える試験と、文字サイズ・余白を変える試験を行います。古い先読みを読み始めず安全に停止することを確認します。標準条件と文字が小さく余白が異なる条件で、新規に3ページずつ読み直します。
5. トップ画面で速度と高さを変更し、速度を変更して再開後、再起動後、トップ画面で照合します。速度は100〜250%（5%刻み）で自動保存します。トップ画面の **設定を保存** は音声の選択と辞書IDに使います。

6. 通知欄・クイック設定を開いたままページ送りが発生しないこと、閉じた後に操作が再開することを確認します。タイルと通知から開始・一時停止・再開・終了をそれぞれ試します。
7. 読み上げ中と一時停止中に手動でページを変え、画面署名の不一致が検出された後に停止し、未確認位置に復元しないことを確認します。即時検知は保証しません。
8. 画面ロック、別アプリへの移動、通知を閉じず別アプリへ移動する場合を試し、位置が不確定なら停止することを確認します。通知欄の表示状態を判別できない場合も安全側に停止します。

## 4. 診断ログ

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
| `startup capture/ocr/synthesis/playback` | 開始命令から各段階までの累積時間（ms） |
| `ocr classification`／`prefetch stopped` | ページ分類と先読み停止理由 |
| `buffer start`／`buffer wait`／`buffer stop` | 速度反映後の残量、枯渇回数と待ち時間、ページ境界の間隔 |
| `restore synchronized=` | 読み上げ位置への同期結果 |
| `prewarm page ready=` / `startup prepared result=` | 事前準備と再利用の成否 |
| `ocr cache hit` / `ocr cache miss` / `ocr cache store` | OCRキャッシュの成否 |
| `eye prefetch start` / `eye prefetch restored` | 目で追うモードの先読みと復元 |
| `image buffer captured` | 保持画像数（上限3） |
| `page_number navigation` / `page turn result=` | ページ番号認識と遷移判定 |

ログは本文・画像・音声を含みません。音声冒頭の自作WAVは次のコマンドで取得できます。

```bash
adb shell -T run-as io.github.ebookautoreader.debug cat cache/diagnostics/sample-raw.wav > sample-raw.wav
adb shell -T run-as io.github.ebookautoreader.debug cat cache/diagnostics/sample-played.wav > sample-played.wav
```

P2.2のPixel実測はOCR初期化約7.4秒、ウォーム時の領域検出・文字認識約5.4秒、TTS合成1文約0.1〜0.35秒でした。P2.3ではページ取得・OCR、合成・プレーヤー準備、再生を並行します。初回は最初の音声クリップの準備が整い次第再生します。再生中は20秒分を目標に、最大4ページ先、準備済み音声8件（WAV各4 MiB）まで保持します。読み上げ可能時間よりもページ安定確認とOCRの合計が長い短文ページが続くと、バッファが減ります。

DMMのページ変化の自動判定は日本語ML Kitによるページ番号と本文・ヘッダー署名に基づきます。番号が取得できない場合や重複する場合は、中断のない条件で安定した署名差分を用います。番号と署名の矛盾、未知の位置、保護画面では安全停止します。方向・移動量・復元の確度は実機で目視確認します。位置はセッション内の相対位置です。音声内の文字単位の同期、書籍内の恒久位置、組版変更後の位置復元は今後の対象です。

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

デバッグ版でアクセシビリティサービスを有効にし、DMMのページ番号が表示された本文を開いて、アイドル中に次を実行する。診断コマンド自体はページ移動や本文OCR・TTSを行わない。本文画面への進入時の事前準備が別途実行される場合がある。

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
