# Conversation Scenarios

Minecraft 実機を起動しなくても、まず `examples/speech_fixtures.json` と `make agent-fixtures` で発話から Goal へのルーティングを確認する。

## Practical Voice Scenes

| Scene | Utterance | Expected Goal |
| --- | --- | --- |
| 暗所対応 | 暗くて見えないから明るくして | `place_light` |
| 中断 | 待ってストップ | `abort` |
| ドア/ゲート | ドア開けて | `context_action/open` |
| 収穫 | 成熟した作物だけ収穫して | `context_action/harvest` |
| 防御 | 敵が近いから盾構えて守って | `context_action/defend` |
| 戦闘 | ゾンビ倒して | `attack_entity/hostile` |
| 狩猟 | 牛を狩って | `attack_entity/food_animal` + `collect_drops/food_drop` |
| 方向移動 | 右に3ブロック歩いて | `move/right/3` |
| ネザー準備 | 溶岩でネザーゲート作って | `prepare_nether/lava_cast` |
| ネザー準備 | 黒曜石でネザーゲート作って | `prepare_nether/obsidian_frame` |
| 素材調達 | 砂を集めて | `collect_block/sand` |

## Trace Expectations

Planner がレシピ依存を展開するとき、同じ item に戻る循環や深さ上限超過を検知した場合は、MOD の Action plan に `planner_blocked_reason` を挿入する。

実行結果では `blocked` step として返り、`data.reason_code` には以下のどちらかが入る。

- `recipe_dependency_cycle_detected`
- `recipe_dependency_depth_exceeded`

この Action はワールドを変更しない。原因を trace / 実行結果へ残すための診断用 Action である。
