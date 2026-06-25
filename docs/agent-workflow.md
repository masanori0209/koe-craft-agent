# AI Agent Workflow

## 対応ツール
- Claude Code: `CLAUDE.md`
- Codex: `AGENTS.md`
- Cursor: `.cursor/rules/*.mdc` + `AGENTS.md`

## 共通ルール
すべての AI エージェントは以下を守る。
- 仕様は `docs/spec.md` を最優先する
- 実装前に関連 docs と fixture を読む
- LLM に Minecraft コマンドを直接生成させない
- Action DSL を Schema 検証する
- Safety Filter を必ず通す
- 変更後は関連 Harness を実行する
- 失敗ログを読んでから修正する
- API キーや個人情報をコミットしない

## タスク種別ごとの入口
### 音声認識・AmiVoice
読むもの:
- `docs/spec.md`
- `docs/harness.md`
- `examples/speech_fixtures.json`

確認すること:
- Minecraft 用語辞書
- フィラー保持
- 言い直し検出
- 割り込み検出
- ルールベース + 辞書で解決できる発話は LLM fallback に送らない
- 未対応 intent は理由付き `unknown` にし、勝手に Action DSL を作らない
- 雑談や外部世界の依頼を表現する場合は `ambient_chat` Goal に限定し、world context 以上の事実を断定しない

### Planner
読むもの:
- `docs/spec.md`
- `examples/state_fixtures.json`
- `examples/planner_fixtures.json`

確認すること:
- LLM 丸投げしていないか
- Recipe Resolver を通しているか
- Safety Filter を通しているか

### MOD Executor
読むもの:
- `docs/architecture.md`
- `docs/spec.md`
- `docs/harness.md`

確認すること:
- Minecraft コマンドを使っていないか
- サバイバル操作として実行しているか
- 中断時にキー入力を解除しているか

## 変更報告テンプレート
```md
## Summary
## Files Changed
## Commands Run
## Results
## Risks
## Next Step
```
