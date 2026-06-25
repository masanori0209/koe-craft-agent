# KoeCraft Mic Bridge.app

`KoeCraft Mic Bridge.app` は、macOS で Minecraft / Java プロセスがマイク権限を取得できない場合に使う薄い音声ブリッジである。

通常の実行フローは以下。

```text
Minecraft V key
  -> http://127.0.0.1:8790/api/mic/toggle
  -> KoeCraft Mic Bridge.app records microphone audio
  -> selected ASR provider
     - AmiVoice WebSocket streaming recognize (default)
     - OpenAI Whisper transcription
  -> http://127.0.0.1:8791/api/utterance
  -> Fabric MOD planner / executor
```

## Build

macOS:

```bash
make mic-bridge-app
```

生成先:

```text
build/KoeCraft Mic Bridge.app
~/Applications/KoeCraft Mic Bridge.app
```

Windows:

```bash
make mic-bridge-windows
```

生成先:

```text
build/windows/KoeCraft Mic Bridge/
build/KoeCraft-Mic-Bridge-Windows.zip
```

Windows では `.app` ではなく、同じ Java UI を起動する `KoeCraft Mic Bridge.bat` と `KoeCraft Mic Bridge.ps1` を同梱する。Java 21 が必要。配布先の Windows では `KoeCraft Mic Bridge.bat` をダブルクリックして起動する。

Windows のマイク許可は OS 側の `Settings > Privacy & security > Microphone` で有効にする。TTS は PowerShell 経由で `System.Speech.Synthesis.SpeechSynthesizer` を使う。

`Info.plist` に `NSMicrophoneUsageDescription` を含め、ad-hoc codesign している。初回起動時に macOS のマイク権限ダイアログが出る。

Dock に通常アプリとして残らないように `LSUIElement=true` を設定している。起動するとアプリウィンドウが開き、Bridge の状態確認、手動 ON/OFF、主要設定の編集ができる。

アプリ内 UI:

- `Status`: マイクON/OFF、ミュート、認識フェーズ、発話検知、RMS、VAD confidence、TTS状態を確認する
- `Settings`: ASR provider、AmiVoice/OpenAIキー、無音判定、録音時間、TTS、子供向けモード、MOD送信先URLを編集して保存する
- `Input device`: OSの既定マイク、またはJava Soundで見える入力デバイスから選択する
- `Mic Test`: APIを呼ばずに、2秒の環境音と3秒の発話から RMS / VAD を測り、録音再生とミニグラフでしきい値調整の目安を表示する
- `Voice presets`: Quiet Room / Minecraft BGM / Kids / Demo Fast の4種を設定UIから反映できる

設定は `~/Library/Application Support/minecraft/config/koecraft-agent.properties` に保存される。Bridge 側の録音・TTS設定は保存後の次回発話から反映される。`bridge.port` の変更は Bridge app の再起動が必要。

ブラウザでも同じ状態確認と手動 ON/MUTE/OFF を行える。

```text
http://127.0.0.1:8790/
```

## Local Config

設定ファイルは Minecraft と同じローカル設定を読む。

```text
~/Library/Application Support/minecraft/config/koecraft-agent.properties
```

Bridge 利用時の代表設定:

```properties
voice.nativeMicEnabled=false
speech.provider=amivoice
amivoice.transport=websocket
amivoice.websocketEndpoint=
amivoice.resultUpdatedIntervalMillis=500
voice.keepListeningOnError=true
voice.adaptiveNoise.enabled=true
voice.adaptiveNoise.warmupMillis=450
voice.adaptiveNoise.multiplier=3.0
voice.adaptiveNoise.minThreshold=0.0035
voice.vad.enabled=true
voice.vad.provider=silero_onnx
voice.vad.modelPath=
voice.vad.confidenceThreshold=0.50
voice.vad.minSpeechFrames=2
voice.vad.hangoverFrames=6
voice.pcmNormalize.enabled=true
voice.pcmNormalize.targetRms=0.035
voice.pcmNormalize.maxGain=3.0
voice.pcmSoftClip.enabled=true
voice.silenceMillis=800
voice.maxRecordingMillis=9000
bridge.port=8790
bridge.modUtteranceUrl=http://127.0.0.1:8791/api/utterance
agent.utteranceUrl=
tts.enabled=true
tts.url=http://127.0.0.1:8790/api/speak
tts.voice=Kyoko
tts.rate=180
tts.micSuppressMillis=1200
tts.interruptOnSpeech=true
tts.interruptRmsThreshold=0.018
```

API key は repository に含めない。`amivoice.apiKey` は設定ファイルか `AMIVOICE_API_KEY` 環境変数から読む。
アプリ内 Settings で保存した API key も同じローカル設定ファイルにだけ書き込む。

`speech.provider=amivoice` がデフォルトである。Bridge app の Settings では `Speech provider` から `AmiVoice` / `Whisper` を切り替えられる。

`amivoice.transport=websocket` がデフォルト。AmiVoice WebSocket は `LSB16K` として 16kHz/16bit/mono PCM を逐次送信する。切り分け時だけ `amivoice.transport=http` にすると、従来の WAV + HTTP multipart 認識へ戻せる。

`speech.provider=whisper` の場合、Bridge はローカルの無音検知で1発話ぶんのWAVを切り出し、OpenAI `openai.transcription.model` へ送信する。デフォルトは `whisper-1`。Whisper は AmiVoice WebSocket の partial 即実行を使わないため、短命令の体感速度はAmiVoiceより遅くなりやすいが、比較検証や切り分けに使える。

ノイズが多い環境では `voice.adaptiveNoise.enabled=true` を使う。Bridge app の `Settings > Voice` から ON/OFF、warmup、multiplier、min threshold を変更できる。発話前の短い環境音を測り、`max(voice.speechRmsThreshold, voice.adaptiveNoise.minThreshold, noiseFloor * voice.adaptiveNoise.multiplier)` を実際の発話しきい値として使う。

Minecraft の BGM や環境音を拾う場合は、RMS threshold だけでは足りない。標準では Silero VAD confidence gate を有効にし、`voice.vad.enabled=true` / `voice.vad.provider=silero_onnx` で発話確率がしきい値を超えた区間だけ ASR に送る。Bridge app の Settings から VAD ON/OFF、provider、model path、confidence threshold、min speech frames、hangover frames を変更できる。RNNoise WASM / AudioWorklet は Bridge を Web Audio 経路に寄せる場合の optional noise suppression として扱う。npm/CDN の未固定 wasm をそのまま読む構成にはしない。

小さい声や子供の声は `voice.pcmNormalize.enabled=true` で AmiVoice へ送るPCMだけ軽く正規化できる。音が割れそうな場合は `voice.pcmSoftClip.enabled=true` が効く。上げすぎると環境音も増えるため、`voice.pcmNormalize.maxGain` は 2.0 から 3.0 程度を推奨する。

`Mic Test` は、マイクOFF時だけ実行できるローカル診断である。最初の2秒は黙ってMinecraftのBGM/環境音だけを流し、次の3秒で普段の声量で話す。結果には quiet/speech の RMS と VAD confidence、推奨 `voice.speechRmsThreshold`、RMS/VADミニグラフ、ローカル再生ボタンが出る。AmiVoice / Whisper / OpenAI へ音声は送らないため、API料金やAmiVoice無料枠は消費しない。

## TTS

`ambient_chat` や聞き返しの吹き出しは、MOD から Bridge の `/api/speak` に送られ、Bridge が OS 標準TTSで読み上げる。

macOS では `say` を使う。Windows では PowerShell の `System.Speech.Synthesis.SpeechSynthesizer` を使う。

読み上げが邪魔な場合は `tts.enabled=false` にするか、Bridge ウィンドウの `TTS OFF` を押す。

TTS の読み上げ音を自分のマイクで拾って無限ループしないように、Bridge は読み上げ中と読み上げ直後 `tts.micSuppressMillis` ミリ秒のマイク入力を捨てる。

`tts.interruptOnSpeech=true` の場合、読み上げ中にしきい値以上の入力を検知するとTTSプロセスを止める。デモで説明が長くなりすぎる時や、ユーザーが途中で話し始める時のテンポを優先する設定である。

## Continuous Listening

Bridge はマイクON中、1発話ごとに AmiVoice 認識と MOD 送信を行ったあと、自動で `LISTENING` に戻る。

Minecraft 側のデフォルトキーは `V` が ON/OFF、`M` が MUTE/UNMUTE、`N` が完全停止である。MUTE は「待受モードは維持したまま録音だけ止める」ため、TTSや環境音が落ち着くまで一時的に閉じたい時に使う。

テンポ重視では `voice.silenceMillis=800` を推奨する。これは「話し終わった」と判定する無音時間で、短いほど早く動き始める。発話が途中で切れる場合は `1000` から `1200` へ戻す。

`voice.keepListeningOnError=true` の場合、AmiVoice の空返答、HTTP一時失敗、MOD側の一時不通ではマイクをOFFにしない。マイクデバイス権限や入力デバイス自体の失敗だけOFFへ落とす。
