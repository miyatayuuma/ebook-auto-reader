# P2 Pixel 9での確認

対象はPixel 9／Android 17のGoogle Play版DMMブックスと日本語縦書きリフロー本文です。DMMブックスの実画面を端末で取得できるかを最初に確認します。Androidのウィンドウ単位の画面取得が保護画面を返した場合は停止します。画像と文字認識結果はセッション中のメモリに置き、合成音声はアプリの一時領域から再生後に削除します。 [Android画面取得API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))

## 1. APK導入と画面取得

管理PCでP2ブランチを取得します。以下の `RUN_ID` はP2 Draft PRに表示された **P2 Reader** の成功したActions実行の番号です。

```bash
cd ~/ebook-auto-reader
git fetch origin
git switch --track origin/p2/reader-engine
adb devices -l
bash p2/install-debug.sh --run RUN_ID --probe-only
```

複数台を接続している場合は、Pixel 9のシリアルを指定して `ANDROID_SERIAL=SERIAL bash p2/install-debug.sh --run RUN_ID --probe-only` のように実行します。すでにブランチを取得済みなら `git switch p2/reader-engine && git pull --ff-only` へ置き換えます。APKをActions画面から取得した場合は、ZIPを展開し `--apk /path/to/reader-debug.apk` を使えます。

1. 起動したEbook Auto Readerからアクセシビリティ設定を開き、Ebook Auto Readerを有効にします。
2. DMMで縦書きリフロー本文を開き、メニューを閉じて画面下の **画面取得テスト** を押します。
3. 「画面取得に成功」が表示されたときは次の手順へ進みます。制限、黒画面、取得失敗が表示されたときは、その結果を記録してください。

管理PCには `adb`、`gh`（Actionsから取得する場合）、`keytool`、`openssl` と Android SDK Build Tools 35.0.0 の `apksigner` が必要です。CI APKを管理PCで専用のデバッグ鍵に署名し直して導入します。鍵の既定の保存先は `~/.local/share/ebook-auto-reader/` です。後続ビルドも同じ鍵で更新できます。P1の `io.github.ebookautoreader.lab` とP2デバッグ版の `io.github.ebookautoreader.debug` は別アプリです。

## 2. 自作ページとDMMで再生を確認

```bash
bash p2/install-debug.sh --run RUN_ID
```

P1で用意した `p1/out/ndl-models/` を再利用します。モデルが別の場所にある場合は `P2_MODELS=/path/to/ndl-models` を指定できます。移行済みの各ファイルはサイズとSHA-256が一致すれば再転送を省略し、不一致時は転送・検証後に確定します。モデルがなければP1で構築済みのPython 3.12環境で [P1のモデル取得手順](../p1/README.md) を実行します。

1. 音声一覧から日本語オフライン音声を選び、速度・高さを変えて試聴し、保存します。アプリを開き直して設定が復元されたことを確認します。音声数と名称は端末の構成に応じます。
2. **自作ページで試す** を開き、**読み上げ開始** を押します。「夜が明けると、川岸の石は朝の光を反射し、水の流れが銀色に見えた。」が2ページにまたがる一文として読まれることを確認します。続けて章扉まで進め、画面が変わらない末尾で停止することを確認します。
3. DMMで購入済みの縦書き本文の途中を表示し、**読み上げ開始**。最初は2ページだけ、次に複数ページで試します。スワイプ方向が逆の場合は停止し、設定の「次ページは右方向にスワイプ」を切り替え、正しいページで開始してください。
4. 前のページの音声が流れている間に次ページが表示されたら **一時停止** を押します。音声が止まり、読み上げ位置のページが表示されることを確認します。**再開** では最後に確認した文頭から読みます。**終了** も表示を同期します。
5. 一時停止中に手動でページを変えてから **再開** し、移動前のセッションで自動ページ送りを続けないことを確認します。使用中に本文をタップすると手動確認のため一時停止します。

結果の共有には「画面取得」「2ページ連結」「複数ページ」「停止同期」「音声設定」の可否と、表示されたエラーを記録してください。

## 発音辞書

アプリ内部の `files/pronunciation/books/<ID>.tsv` を書籍別、`files/pronunciation/common.tsv` を共通辞書として読みます。TSVはUTF-8で、1行は「表記、読み、直前の文字列、直後の文字列」のタブ区切りです。共通辞書には少なくとも片側の適用条件を指定します。書籍別のIDは設定で選び、デバッグ版では管理PCから転送できます。

```bash
adb -s SERIAL shell -T run-as io.github.ebookautoreader.debug mkdir -p files/pronunciation/books
adb -s SERIAL shell -T run-as io.github.ebookautoreader.debug sh -c \
  "'cat > files/pronunciation/books/sample.tsv'" < /path/to/sample.tsv
```

ルビは推定した親語の範囲に付け、確信のあるものだけ使用します。書籍別辞書、条件付きの共通辞書が続き、残りはGoogle TTSの読み推定を使います。形態素解析の補正は根拠を検証した実装に差し替えられる構造です。P1の評価専用ルールは製品に含めません。

## P2の判定範囲

- 先読みは次の1ページと次の音声1件です。画像から一様な明色と判定し、検出領域もない白紙は、隣接する1枚に限って通過を試みます。図表は高確信の領域を検出した場合に短い音を鳴らし、認識できたキャプションを続けます。
- 再開位置は音声ファイルの実再生を起点にした文頭です。再生の経過時間を測り、完了はMediaPlayerの完了イベントから確定します。停止時は確認済みの画面署名と逆方向の操作を照合します。
- ルビの親語は画像上の幾何情報から保守的に推定します。P1のNDL認識器を共有し、縦書きの段・列、柱とノンブル、本文・ルビ・図版の判定を追加しました。上流CLIの一般紙面向けXML読み順、表の構造化、縦中横補正、長い行の分割認識はP3の検証対象です。100文字用モデルで終端を確認できない本文候補は停止します。
- OCRコードは国立国会図書館のNDLOCR-Lite 2.0.0の入力・出力に合わせたAndroid移植です。モデルをアプリやActions成果物に同梱せず、P1の取得手順で利用します。 [上流パッケージ](https://pypi.org/project/ndlocr-lite/2.0.0/) ／ [上流ソースとライセンス](https://github.com/ndl-lab/ndlocr-lite)
