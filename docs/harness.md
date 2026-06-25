# Agent Harness

## 目的

KoeCraft Agent の標準ハーネスは TypeScript に依存しない。MOD 側をビルドし、音声fixtureとレシピカタログの最低限の整合性をローカルで検証する。

## 標準コマンド

```bash
make agent-check
make agent-fixtures
make agent-dry-run
make asr-comparison-report
make live-asr-comparison-report
make rerender-live-asr-comparison-report
make recipe-dependency-audit
make mod-build
```

## 実行内容

`make agent-check` は以下を行う。

1. `mod/gradlew build`
2. `examples/speech_fixtures.json` の会話シナリオ検証
   - Python lightweight checker
   - MOD内 `KoeCraftUtteranceGoalRouter` の Java fixture runner
3. bundled vanilla recipe catalog の必須出力確認
4. 全 vanilla recipe の依存計画監査
   - `logs/reports/recipe-dependency-audit.md`
   - `logs/reports/recipe-dependency-audit.json`
5. `place_torch_missing` の軽量 dry-run trace 生成

通常ハーネスは AmiVoice / OpenAI を呼ばない。

`make asr-comparison-report` は `examples/asr_comparison_scenarios.json` の fixture ASR テキストを Java ルーター/Planner に通し、以下を生成する。

```text
logs/reports/asr-comparison-report.html
logs/reports/asr-comparison-report.json
```

このレポートは、KoeCraft の既存 `speech_fixtures` に紐づく20シナリオを使い、AmiVoice の profileWords / WebSocket partial / Minecraft文脈post-normalizer / rule fast path の効きどころを見える化する運用レポートである。通常はライブ ASR API ベンチではない。AmiVoice / Whisper の実認識結果を比較する場合は、同 JSON の `asr.amivoice` / `asr.whisper` を実測文字列へ差し替えて再生成する。

`expected_behavior=ignore` のシナリオは、フィラーやため息を誤爆させず無視できた場合を成功として扱う。

`make live-asr-comparison-report` は macOS の `say` / `afconvert` で20シナリオの日本語TTS音声を生成し、AmiVoice と OpenAI `whisper-1` に実際に送信する。通常ハーネスと異なり、AmiVoice の認識時間と OpenAI API 料金を少量消費する。出力は以下に残る。

```text
logs/asr-live-audio/*.wav
logs/reports/asr-live-recognitions.json
logs/reports/asr-comparison-report.html
logs/reports/asr-comparison-report.json
```

`make rerender-live-asr-comparison-report` は `logs/reports/asr-live-recognitions.json` に保存済みの実認識テキストを再評価し、HTML/JSONだけを再生成する。ASR API は呼ばない。post-normalizer や Planner 修正後の再評価に使う。

## Fixture

会話シナリオは以下に置く。

```text
examples/speech_fixtures.json
```

人間が読むための一覧は以下に置く。

```text
docs/conversation-scenarios.md
```

## TypeScript 廃止後の注意

旧 TypeScript Voice Agent の広い planner/simulation harness は標準経路から外した。今後の実用回帰は MOD 側 planner / executor へ寄せる。

新しい Goal / Action / Executor behavior を追加した場合は、以下のどちらかを追加する。

- MOD 側のJavaテストまたはデバッグ検証入口
- `scripts/python/` の軽量fixture検証

発話ルーティングだけを確認する場合は以下でよい。

```bash
cd mod
./gradlew plannerFixtures
```

## Done Definition

- 実装済み
- `make agent-check` 通過
- MOD変更時は `make mod-build` 通過
- 必要に応じて speech fixture / docs 更新
- API key や secret を repository に含めない
