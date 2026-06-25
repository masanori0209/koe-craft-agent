# Loop Engineering

## 目的
Loop Engineering は、AI コーディングエージェントに一発で完璧な実装を期待するのではなく、実装 → 検証 → 失敗ログ → 修正 → 再検証 のループを設計する開発手法である。

KoeCraft Agent では、音声認識・LLM・Planner・Minecraft MOD が絡むため、失敗を前提にループを設計する。

## 基本ループ
```text
Plan -> Implement -> Run Harness -> Collect Failures -> Repair -> Re-run Harness -> Summarize
```

## エージェントに守らせるループ
1. 変更対象を読む
2. 変更計画を短く書く
3. 小さく実装する
4. 関連テスト・fixture を追加する
5. Harness を実行する
6. 失敗ログを読む
7. 最小修正する
8. 再度 Harness を実行する
9. 変更内容・未解決リスクをまとめる

## Repair Loop
### 入力
```json
{
  "task": "place_torch goal should choose charcoal_route when torch and coal are missing",
  "failure_log": "...",
  "changed_files": [
    "mod/src/main/java/dev/koecraft/agentmod/KoeCraftNativeGoalPlanner.java",
    "examples/state_fixtures.json"
  ]
}
```

### 修正ルール
- 失敗している最小単位だけ直す
- 関係ないリファクタを混ぜない
- テスト期待値を安易に変更しない
- 仕様が曖昧なら `docs/spec.md` を確認する
- 仕様変更が必要なら `docs/spec.md` と fixture を同時に更新する

## Trace Loop
実行ログは保存する。

```text
logs/traces/2026-06-15-place-torch.jsonl
```

Trace に含めるもの:
- recognized_text
- speech_features
- goal
- state_snapshot
- selected_route
- task_plan
- action_plan
- safety_result
- execution_result
- failure_reason

## Eval Loop
失敗を fixture に昇格する。

例:
1. 「松明置いて」が `craft_torch` ではなく `place_block` と誤解された
2. trace を保存する
3. `examples/speech_fixtures.json` にケース追加
4. Goal Parser を修正する
5. Harness で回帰テストする

## 作業単位
悪い依頼:
```text
KoeCraft Agentを全部作って
```

良い依頼:
```text
Speech Feature Extractorに「いや」「やっぱり」を使った self_correction 検出を追加し、
examples/speech_fixtures.json の self_correction_pickaxe が通るようにして。
変更後は make agent-fixtures を実行して。
```

## Done Definition
- 実装済み
- テストまたは fixture 追加済み
- Harness 通過
- 失敗時はログを残した
- docs に影響する場合は更新済み
- 次にやるべきことが書かれている
