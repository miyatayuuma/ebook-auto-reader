# P1 技術選定

## P2の採用判断

| 対象 | 第一候補 | 比較・判断 |
| --- | --- | --- |
| OCR | NDLOCR-Lite 2.0.0（CPU） | P2の標準実装候補。ML Kitとの実機での優劣判定は詳細値の入手まで保留 |
| 読み | 本文と分離したルビ・限定辞書 | Sudachiの読みを候補として比較する。形態素解析の一律置換は避け、正解が確認できる場合を優先する |
| TTS | Google TTSの日本語オフライン音声 | 正式採用。端末で利用可能な日本語オフライン音声を選べるようにする |

- NDLOCR-Lite 2.0.0は2026年11月24日公開。PC CLIを固定した。Android試作はONNXの検出、認識、簡易読順、ルビ候補の親行対応までを試す。上流の完全なレイアウトと読順を移植していない。上流のコードはCC BY 4.0、レイアウトモデルはDINOv3条件も確認する。GoogleのML Kitは日本語モデルをアプリへ同梱でき、比較試作では公式の依存関係だけを指定する。 [NDLラボ](https://lab.ndl.go.jp/news/2026/2026-11-24/) ／ [NDLOCR-Lite 2.0.0](https://pypi.org/project/ndlocr-lite/2.0.0/) ／ [Google ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- Nanamiは公式クラウド候補。AzureのニューラルTTS無料枠は月50万文字。仮に300字/分なら月約28時間に相当し、利用量と無料枠の確認が必要になる。端末単体・書籍本文を外部へ自動送信しない方針から、P2の読み上げ候補はオフラインを優先する。 [Microsoftの対応音声](https://learn.microsoft.com/azure/ai-services/speech-service/language-support?tabs=tts) ／ [Azure Speech料金](https://azure.microsoft.com/ja-jp/pricing/details/speech/)
- VOICEVOX COREは公式のAndroid arm64バイナリがある。ローカル候補の次点として残す。モデル、音声ライブラリ及び話者の利用条件を個別に確認し、実機で自然さと読みを比べる。AivisSpeech、Style-Bert-VITS2、sherpa-onnx系に候補を広げる判断は、この比較の結果から行う。 [VOICEVOX CORE releases](https://github.com/VOICEVOX/voicevox_core/releases) ／ [VOICEVOX利用規約](https://voicevox.hiroshiba.jp/term/)
- Sudachiは辞書読みと未知語・位置を取得する比較対象。Open JTalkは自作コーパスの音声を作る独立対照。評価の小さな文脈ルールと辞書は、確かな上書きを渡す場合の改善幅を見る。評価語に合わせた既知のルールなので汎化精度は別のコーパスで測る。 [SudachiPy](https://github.com/WorksApplications/sudachi.rs/tree/develop/python) ／ [pyopenjtalk-plus](https://pypi.org/project/pyopenjtalk-plus/0.4.1.post7/)

## 読みと同期で確定した設計

- 原文に付帯したルビ、本人の書籍別辞書、条件を特定できる共通辞書、文脈・形態素解析、TTS既定の順で採用する。原文と位置は不変とし、TTSへの投入時だけ仮名を反映する。曖昧な候補、該当範囲がずれた補正を拒む。
- TTSの `speak` における `onRangeStart` は、エンジンが範囲のタイミング情報を提供する場合に使える。 `synthesizeToFile` の `onDone` は合成完了を示す。 P2では音声の実再生フレームを原文範囲に対応付け、文中の対応が取れない時は最後の確定済み文頭で再開する。先読み済みと読み上げ済みのページを分け、合成済みは表示復元の根拠に使わない。 [Android TTS](https://developer.android.com/reference/android/speech/tts/TextToSpeech) ／ [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener) ／ [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)
- 確認済み図表は本文の文連結から分離し、通知音・図注順を保持する。先読みの表示移動は1ページから始める。停止時は直近の確認済み移動を最大回数として逆向きに照合する。手動操作・同一ページ・認識失敗で位置が曖昧になった場合は推測で送り続けない。
- P2開始時に、公式経路でDMM画面を取得できること、1回の操作で表示遷移を確認できること、PixelのOCRと試聴の比較条件を確認する。P2は前後2ページでの本文取得、文の連結、先読み合成、実再生、停止時の同期を対象にする。製品の120分・100ページや図表、ポケット操作は後続の受入試験で確認する。

## 測定記録

PCとPixelの実行記録は [比較ラボ](../p1/README.md) の手順で取得する。端末側の画面取得可否、Google音声の読みの評価、TalkBackとの誤読差、ページ境界の知覚差、メモリと電池の長時間測定は実機または試聴で判定する。

### 2026-11-27 PC測定

GitHub ActionsのLinux x86_64で、自作の1080×2424画像8枚と日本語20題を実行した。OCRは明朝22／34／48 px、図表混在、章扉、図専用、白紙、文がページをまたぐ2枚を含む。

| 試験 | 結果 |
| --- | --- |
| NDLOCR-Lite本文 | 611字中1字置換（中サイズで「頁」を「貞」と認識）。脱落・挿入・本文行順逆転0 |
| 図題・注釈、章扉、白紙 | 図題と注釈48字、章扉8字に誤り0。白紙は文字出力0 |
| ルビ、ページ番号 | ルビ3件とも本文との対応が得られず、ルビ文字も出力されなかった。ページ番号は期待8字すべて未出力 |
| PC OCR時間・メモリ | モデル取得後の1ページ1プロセスで中央値7.085秒、最大7.58秒、最大RSS約704 MiB。Pixelのウォーム推論・追加PSSとは測定条件が異なる |
| Android試作のNDLモデル | 検出40,280,965バイト＋認識42,588,187バイト。実行時メモリは実機のPSSで確認する |

| 読み推定 | 正解 | 誤り | 採点対象外 |
| --- | ---: | ---: | ---: |
| Sudachi標準 | 10 | 7 | 3 |
| Sudachi＋読み注釈 | 15 | 2 | 3 |
| Open JTalk標準 | 10 | 7 | 3 |
| Open JTalk＋読み注釈 | 16 | 1 | 3 |

Open JTalk標準は「一行だけ」「市場で仕入れ」「舞台の上手」などで期待読みと不一致。付与したルビ・規則で改善した一方、補正対象に含めていない「川の上手」は「じょうず」が残った。対象語と内部のトークン範囲を対応付けられなかった例は採点対象外とした。数字・単位を含む音声は試聴で判定する。代表12題の原文入力・補正入力から24 WAVを生成し、全ファイルに有効なPCMを確認した。PCの合成時間中央値は約122 ms。ここでの正解数は読み推定の一致数で、発音・アクセント・自然さは別に試聴する。

通常のPC CLI出力に現れなかったルビを、DEIMの検出結果から直接切り出して確認した。同じ「空音／あおね」を小・中・大で描いた3枚では、DEIMが全件で `block_rubi` を検出し、PARSeqが3件とも「あおね」と読んだ。正解座標と検出候補の対応付けを採点時に行い、認識の入力には検出座標を使った。この試験は1種類のルビを対象にしており、親語への自動対応と別の書体・ルビは未検証。P2では本文行とルビ領域を分離した経路を優先し、Pixelで親語対応を確認する。

最新のPC試験で本文・図注・ルビの成績を再現した。AndroidのデバッグAPKビルドと、ページまたぎ文の連結・再生位置の単体テストも成功した。Pixelでの実行時の挙動は端末試験で判定する。

[NDL OCRの記録](../p1/results/ocr-pc-2026-11-27.json) ／ [読み推定の記録](../p1/results/reading-pc-2026-11-27.json) ／ [初回PC試験](https://github.com/miyatayuuma/ebook-auto-reader/actions/runs/45975416092) ／ [ルビ補助試験の記録](../p1/results/ruby-probe-pc-2026-11-27.json) ／ [再現試験・APK・WAV](https://github.com/miyatayuuma/ebook-auto-reader/actions/runs/45976227007)

### Pixel 9での初回試験（2026-11-27、本人確認）

- APKのインストール、ML Kitの実行、Google TTSの実行、管理PCでのNDLOCR-Liteモデル取得に成功。
- 初回のADB転送後は端末のモデルディレクトリが空だった。本人が `adb shell -T run-as` へ切り替えて転送に成功。正式スクリプトでは端末側のファイルの存在、サイズ、SHA-256を確認してから試験を開始する。
- 転送修正後、NDLは形状 `[-1, 3, 800, 800]` でテンソル生成に失敗した。検出の画像・対象サイズ入力と認識の画像入力でバッチ軸だけを1に具体化し、チャンネル・空間軸などが未確定なら明示的に失敗するよう修正した。

### 修正版APKの再試験（2026-11-27、本人確認）

Pixel 9／Android 17／API 37 で ML Kit、NDLOCR-Lite、Google TTS の3項目が成功。NDLは修正版で推論が完走し、Google TTSは本人の試聴で良好だった。声を選択できる構成をP2に採用する。

実機OCRの詳細採点、推論時間、メモリの値は未共有。PCの記録を実機値として扱わず、OCR間の優劣判定は保留する。DMM実画面の取得・遷移はP2で実機確認する。
