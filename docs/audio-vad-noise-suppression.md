# Audio VAD / Noise Suppression Plan

## Goal

Minecraft の BGM、環境音、TTS、スピーカー音をマイクが拾って、短命令や Ambient Chat が誤発火する問題を減らす。

KoeCraft の標準ランタイムは Fabric MOD 内の Java Sound 録音であるため、ブラウザの `AudioWorklet` / WASM をそのまま差し込む構成ではない。まずは MOD / Java 録音経路に発話区間の信頼度ゲートを追加し、それでも厳しい場合だけ Bridge 側の Web Audio / WASM 経路を検討する。

## Current State

- MOD native mic と Mic Bridge はどちらも 16kHz / 16bit / mono PCM を Java Sound API で取得している。
- 発話開始は `voice.speechRmsThreshold` と `voice.adaptiveNoise.*` による RMS ゲートで判定している。
- `voice.pcmNormalize.*` と `voice.pcmSoftClip.enabled` は AmiVoice / Whisper へ送る音声を整えるだけで、発話判定そのものは生 PCM の RMS ベースである。
- RMS ゲートは「音量が大きいか」を見るだけなので、Minecraft BGM や環境音が人声並みに大きいと誤発話として拾う。

## Recommended Order

1. Silero VAD confidence gate を追加する。
2. 既存 RMS / adaptive noise gate と Silero の speech probability を AND 条件にする。
3. UI / trace に `vad_confidence` と `vad_provider` を出す。
4. それでもスピーカー由来の音を拾う場合だけ、Bridge の Web Audio 経路で echo cancellation / noise suppression / RNNoise を検討する。

## Silero VAD

Silero VAD は 8kHz / 16kHz に対応し、ONNX Runtime で実行できる。KoeCraft の PCM は既に 16kHz mono なので、音声フォーマットは合わせやすい。

導入方針:

- `voice.vad.enabled=true`
- `voice.vad.provider=silero_onnx`
- `voice.vad.modelPath=` はローカル固定パスを優先し、未設定時だけ bundled resource を使う
- `voice.vad.confidenceThreshold=0.50`
- `voice.vad.minSpeechFrames=2`
- `voice.vad.hangoverFrames=6`
- モデルは実行時ダウンロードしない
- モデルと依存 jar はバージョンと SHA-256 を固定する
- 読み込み失敗時は警告を出して `rms` provider に戻す

現在の固定値:

- Silero VAD upstream commit: `dbacf536adadf42210f37ae50fbaf75f6235b3cf`
- Bundled model: `mod/src/main/resources/assets/koecraft-agent/models/silero_vad_op18_ifless.onnx`
- Bundled model SHA-256: `7671cd04b004e9076da0d4a7b1a5aec36adf161c39230c1cb94a4fd5db6bbd28`
- ONNX Runtime Java: `com.microsoft.onnxruntime:onnxruntime:1.26.0`
- ONNX Runtime jar SHA-256 for Mic Bridge builds: `cf5a48c6f5d07b15f10634b80433ddce8f5892662b1a122bbbc0907f4f442c60`

安全面:

- Silero VAD は MIT license。
- モデルファイルはコード実行ではなく ONNX model data だが、実行時に ONNX Runtime が解釈するため、配布物は pinned version / checksum で扱う。
- CDN から毎回取得しない。
- ONNX Runtime Java は OS / CPU architecture ごとの native library を含むため、macOS x64 / macOS ARM64 / Windows x64 で `make mod-build` と実機起動確認を分けて行う。

実装メモ:

- 512 samples / 32ms window を基本単位にする。
- Silero ONNX は前回 context と LSTM state を持つため、発話単位の開始/終了で reset する。
- RMS threshold を超えた chunk だけでなく、直前 pre-roll にも VAD を通して先頭欠けを避ける。
- 短命令のテンポを落としすぎないように、`minSpeechFrames` は 2 から始める。

## RNNoise WASM / AudioWorklet

RNNoise は noise suppression であり、VAD ではない。BGMや環境音を軽くする効果は期待できるが、スピーカーから出た Minecraft 音を完全に消す echo cancellation ではない。

導入方針:

- 標準の Fabric MOD 単体には入れない。
- Bridge をブラウザ / WebView / Electron / Tauri のような Web Audio 経路に寄せる場合の optional provider とする。
- `getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }})` をまず試す。
- 足りない場合だけ RNNoise WASM を `AudioWorklet` で pre-ASR に入れる。

安全面:

- Xiph の RNNoise 本体は BSD-3-Clause。
- Jitsi の `rnnoise-wasm` は Apache-2.0 で、AudioWorklet 向け sync build も想定されている。
- ただし Jitsi repo は GitHub releases / packages がないため、npm や CDN の未固定バイナリをそのまま使わない。
- 導入する場合は固定 commit から CI で自前ビルドし、生成された `rnnoise.wasm` / glue JS の SHA-256 を記録する。
- AGPL の PoC や保守状態が不明な random npm package は使わない。

## What WASM Can And Cannot Do

WASM で RNNoise や Silero browser wrapper を動かすことはできる。ただし AudioWorklet は Web Audio API の機能なので、現在の Java Sound 録音経路には直接入らない。

MOD単体で完結したい場合:

- Silero ONNX Runtime Java が第一候補。
- RNNoise は Java native binding か ONNX/Java相当が必要で、WASM AudioWorklet よりパッケージングが重い。

Bridge/Web経路を許容する場合:

- Browser VAD / ONNX Runtime Web / RNNoise WASM / AudioWorklet を組み合わせられる。
- WebRTC の echo cancellation と noise suppression を利用できる。
- ただし macOS app / Windows app として配るなら、WebView の署名、権限、WASM asset 固定が追加で必要になる。

## Practical Defaults

まずは以下を推奨する。

```properties
voice.adaptiveNoise.enabled=true
voice.pcmNormalize.enabled=true
voice.pcmSoftClip.enabled=true
voice.vad.enabled=true
voice.vad.provider=silero_onnx
voice.vad.confidenceThreshold=0.50
voice.vad.minSpeechFrames=2
voice.vad.hangoverFrames=6
```

BGM混入が強い環境では、モデル導入後でも以下を推奨する。

- Minecraft の BGM / 環境音量を下げる
- 可能ならヘッドホンを使う
- macOS / Windows 側でノイズ抑制付きマイクデバイスを選ぶ
- TTS は `tts.micSuppressMillis` と `tts.interruptOnSpeech` を併用する

## Decision

現在の標準構成では、RNNoise WASM を先に入れるより Silero ONNX の confidence gate を先に入れる。RNNoise WASM は安全に導入可能だが、標準ランタイムではなく Bridge の Web Audio 化とセットで扱う。

Implemented:

- MOD native mic は Silero ONNX gate を `KoeCraftVoiceActivityGate` として使い、失敗時は `rms` provider に戻す。
- Mic Bridge は同じ Silero ONNX model をアプリ jar に同梱し、ONNX Runtime jar を fixed version / SHA-256 check でアプリ配布物へ入れる。
- Bridge Settings から `voice.vad.*` を変更できる。
- Bridge Status の `Mic Check` で API を呼ばずに quiet/speech の RMS と VAD confidence を測り、Minecraft BGMや環境音に対するしきい値調整の目安を出せる。
- health JSON / HUD に `vad_confidence` を出す。

実装/運用 acceptance:

- Silero ONNX model の取得元 commit と SHA-256 を記録する。
- ONNX Runtime の Maven version と license を記録する。
- `voice.vad.provider=silero_onnx` が失敗した場合、マイク自体を落とさず `rms` gate へ fallback する。
- UI / trace に `vad_provider`, `vad_confidence`, `vad_fallback_reason` を出す。
- RNNoise WASM は fixed commit / self-build / SHA-256 なしでは採用しない。
