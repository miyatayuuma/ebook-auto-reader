# P2.3 Pixel 9での確認

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

APKを取得済みの場合は、最後の行を `bash p2/install-debug.sh --apk ~/Downloads/EbookAutoReader-P2.3.apk` に置き換えます。既存のインストールと同じ管理PC・署名鍵で実行します。前回 `P2_SIGNING_DIR` を指定した場合は同じ値を使います。既定の鍵は `~/.local/share/ebook-auto-reader/` にあります。複数台接続時は、環境変数 `ANDROID_SERIAL` でPixel 9を選びます。

インストーラーは `adb install -r` で更新し、サイズとSHA-256が一致する導入済みモデルを再利用します。画面と操作の確認から始める場合は `--probe-only` を付けられます。初めてモデルを導入する場合は [P1のモデル取得手順](../p1/README.md) を使います。

## 1. DMMのページ往復を確認

1. Ebook Auto Readerのアクセシビリティを有効にします。DMMで前後にページがある縦書き本文を表示し、今の位置を覚えてメニューを閉じます。画面端の **▶** をタップし **画面取得テスト** を押します。
2. **ページ送り・復元テスト** を押します。既定の方式は **左右端タップ** です。画面が変わったら、意図した次の1ページを目視で確認し **次へ1ページ進んだ・復元** を押します。方向・ページ数を確認できない場合は **終了** を押し、表示を手動で確認します。往路を目視で確認し、復元後の画像署名が元のページと一致した設定を読み上げで使えます。
3. 逆向きならトップ画面の **次へ進む操作は右** を切り替えます。タップが無反応なら、**パネルを外してから0.55秒待つ** 条件、**アクセシビリティのページ操作**、**横スワイプ** を個別に試します。公開ノード方式は、明示的な横方向のページ操作かスクロール操作を持つ単一の本文サイズのノードを探します。曖昧な遷移では停止します。
4. パネルの影響を比較する場合は、DMMの本文をメニューなしで表示した状態からトップ画面へ戻り **パネルなしでDMMのページ送りを試す** を押します。DMMが前面になって3秒後、パネルを一度も付けずに往路を開始します。目視確認と復元にはパネルを使います。通常の読み上げ用設定を確定するには手順2で往復を確認します。

対象ウィンドウの左右約9.26%／90.74%、中央の高さに単発タップします。ウィンドウが1080×2424の全画面なら事前のADB試験と同じ (100, 1212)／(980, 1212) です。通常はパネルとタッチガードを外して2フレーム待ち、画面の安定確認後に再表示します。音量キーはADBでの比較結果として扱います。[AndroidのジェスチャーAPI](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,android.accessibilityservice.AccessibilityService.GestureResultCallback,android.os.Handler))

## 2. 自作ページと実書籍での受入

1. **自作ページで試す** を開いて往復を確認します。自作ページでは右方向が次ページです。端末画面のページ番号とログの `fixture display` を見比べます。最初の「夜が明けると、川岸の石は朝の光を反射し、水の流れが銀色に見えた。」がページをまたいでつながること、短い3ページから小さい文字・広い余白、標準、高密度の順に再生されることを確認します。
2. **画面下端の自作ページで試す** では「空音は実をそっと地面へ戻した。」まで聞こえ、ノンブル27が入らないことを確認します。中央タップでメニューを出した状態の取得は停止します。**音声冒頭を検証（自作）** ではスピーカーと普段のBluetooth出力で「はじめの三文字を確認します。」を冒頭から聞きます。
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
| `page direction source=fixture`／`source=user` | 自作ページの移動量／DMMの目視確認 |
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

DMMのページ変化の自動判定は画面署名に基づきます。方向・移動量は診断で目視確認します。位置はセッション内の相対位置です。音声内の文字単位の同期、書籍内の恒久位置、組版変更後の位置復元は今後の対象です。

## 発音辞書

アプリ内部の `files/pronunciation/books/<ID>.tsv` を書籍別、`files/pronunciation/common.tsv` を共通辞書として読みます。TSVはUTF-8で、1行は「表記、読み、直前の文字列、直後の文字列」のタブ区切りです。共通辞書には少なくとも片側の条件を指定します。書籍別のIDは設定で選びます。

```bash
adb shell -T run-as io.github.ebookautoreader.debug mkdir -p files/pronunciation/books
adb shell -T run-as io.github.ebookautoreader.debug sh -c \
  "'cat > files/pronunciation/books/sample.tsv'" < /path/to/sample.tsv
```

ルビは推定した親語に付け、確信のあるものだけ使用します。書籍別辞書、条件付きの共通辞書が続き、残りはGoogle TTSの読み推定を使います。OCRは[国立国会図書館のNDLOCR-Lite 2.0.0](https://pypi.org/project/ndlocr-lite/2.0.0/)のAndroid移植です。
