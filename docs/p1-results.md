# P1 技術選定

## P2に残す比較候補

| 対象 | 第一候補 | 比較・判断 |
| --- | --- | --- |
| OCR | NDLOCR-Lite 2.0.0（CPU） | ML Kit日本語16.0.1と同じ画像・同じ実機で比較。重大な本文誤り、本文順、ルビ、負荷の順で選ぶ |
| 読み | 本文と分離したルビ・限定辞書 | Sudachiの読みを候補として比較する。形態素解析の一律置換は避け、正解が確認できる場合を優先する |
| TTS | Google TTSの日本語オフライン音声 | 原文と補正入力の同一音声比較を優先。Open JTalkはPCの読み・音声の独立した対照。Googleの精度・自然さが不足する場合はVOICEVOX COREの公式arm64 Androidビルドを比較対象とする |

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

PCとPixelの実行記録は [比較ラボ](../p1/README.md) の手順で取得する。端末側の画面取得可否、Googleの実音声、TalkBackとの誤読差、ページ境界の知覚差、メモリと電池の長時間測定は実機または試聴で判定する。
