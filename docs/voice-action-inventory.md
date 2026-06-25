# Voice Action Inventory

KoeCraft は「サバイバル合法の完全自律代行」から、「音声で気持ちよく Minecraft を操作する」方向へ寄せている。ただし、何でも即生成すると遊びが薄くなるため、補助は trace に `world_assist: true` / `voice_assist: true` / 互換用 `programmatic_assist: true` が残る bounded な便利機能として扱う。デフォルトは `koecraft.executor.assistMode=world_assist`。

## 指示できる主な操作

| 発話例 | Goal | 主な Action |
| --- | --- | --- |
| 歩いて / 走って / 100歩あるいて / 泳いで | `move` | `use_boat_if_water` optional, `move` with scan-aware adaptive distance |
| 橋かけて | `build_bridge` | `grant_item` optional, `build_bridge` |
| 村を探して / 構造物を探して | `search_structure` | `grant_item` optional, `scan_explore_area`, `explore` |
| 土を掘って / 砂を集めて / 木を切って | `collect_block` | `collect_block`, `collect_drops` |
| 落ちてるアイテム拾って | `pickup_items` | `collect_drops` with `magnet_fallback`, `magnet_only` |
| 作業台/棒/石のツルハシ/かまど/チェスト/盾など作って | `craft_item` | `take_from_container`, `collect_block`, `collect_drops`, `craft`, `open_workstation` |
| 作業台を近くに置いて | `place_workstation` | `grant_item` optional, `ensure_hotbar`, `open_workstation` |
| 松明置いて / 明るくして | `place_light` | `ensure_hotbar`, `place_block` |
| おうち作って / 小さい家作って | `build_structure` | `ambient_chat`, `scan_build_area`, `grant_item` optional, animated `build_blueprint` |
| ドア開けて | `context_action/open` | `open_passage` |
| 成熟した作物だけ収穫して | `context_action/harvest` | `collect_block`, `collect_drops`, `replant_crop` |
| 牛を狩って / 鶏を狩って | `attack_entity/food_animal` | `equip_gear`, `attack_entity`, `collect_drops`, `smelt_food` optional |
| ゾンビ倒して / 敵を倒して | `attack_entity/hostile` | `equip_gear`, `attack_entity` |
| 逃げて / 守って | `retreat` / `defend` | `defensive_move`, `equip_gear` |
| ネザーゲート作って | `prepare_nether` | `collect_fluid`, `build_nether_portal`, `ignite_nether_portal` |
| エンドラ倒したい | autonomous goal mode roadmap | まずネザー準備、ブレイズ、エンダーパール、要塞探索、エンド突入、戦闘へ段階化 |
| やったー / すごい | `celebrate` | `celebrate` |
| わからない / 雑談 | `ambient_chat` | `ambient_chat` |

## 体験が悪くなりやすい箇所

| 箇所 | 悪く見える理由 | 方針 |
| --- | --- | --- |
| 拾い物 | 1個だけ拾って終わる、段差や水際で届かない、拾うためにうろうろする | `collect_drops` は近くの複数 drop を対象にし、`magnet_only` で歩き回らず吸い寄せる |
| 橋・探索 | ブロックがなくて止まるとテンポが悪い | 土だけを bounded に optional `grant_item` する |
| 小建築 | 「家を作る」と言ったのに素材集めで長く止まる | 5x5 の地形候補を選び、素材不足でも common block の world-assist animated `build_blueprint` で家を建てる |
| 狩猟 | 動物を倒して終わると肉が拾えず成果が見えにくい | 食料動物だけ肉 drop 回収を追加し、焼ける場合だけ optional で焼く |
| クラフト | UI スロット操作、作業台設置、素材 pickup が詰まりやすい | レシピは deterministic resolver、拾い漏れは item-specific pickup + magnet、非レア素材は optional top-up、作業台は direct craft fallback を使う |
| 長距離探索 | 崖や水で遅い、ぐるぐる見える | 8チャンク scan summary で読み込み済みヒントを探し、なければ既訪問方向を避けて 300 ブロック探索する |
| 水上移動 | 泳ぐと遅く、川や海でテンポが落ちる | 近くが水場なら optional `use_boat_if_water` でボートに乗ってから移動する |
| 方向移動 | 左・右・後ろが横歩き/後退でぎこちない、段差で止まる | 視点を進行方向へ向けてから forward 移動し、local lane scan でジャンプ・短縮・延長を判断する |
| blocked後の復帰 | 止まった理由は分かっても、次に何を言えばいいか分からない | 三人称リアクション後に3択吹き出しを出し、読み上げ後にポップ演出で消す。選択肢は通常の音声ルーターへ戻す |

## 補助の境界

許可する補助:

- 足場・橋・小建築用の `minecraft:dirt` / `minecraft:oak_planks` など、低価値で体験テンポを上げる支援ブロック。
- 水場での `use_boat_if_water`。統合サーバーのAPIで oak boat を用意・騎乗し、Minecraft slash command は使わない。
- クラフト依存で詰まりやすい非レア素材の `common_resource_top_up`。例: 原木、丸石、砂、砂利、羊毛、革、羽、食料、鉄など。
- 近くに落ちている item entity を引き寄せる `magnet_fallback` / `magnet_only`。
- 近くのチェストから必要素材を直接移す integrated-server inventory transfer。
- recipebook 未解放でも deterministic vanilla recipe data に基づく direct craft fallback。
- クラフト UI が詰まった場合に、同じ deterministic recipe data から `world_assist_direct_recipe` として不足 common 素材を即時消費扱いで補い、成果物を inventory に入れる fallback。
- 補助が発生したことを Minecraft HUD の `World Assist` カードで見せる表示。
- blocked 時に提示する3択の介入吹き出し。選択肢は `place_workstation` / `move` / `dig_pattern` / `abort` などの既存 Goal に再ルーティングする。

避ける補助:

- ダイヤ、エメラルド、ネザライト、黒曜石、ブレイズロッド、エンダーパール、シュルカー、ウィザー/ドラゴン系、ネザースター、mob head、smithing template、レコード系、creative-only/特殊ブロックなど、探索・戦闘・進行の達成感を大きく壊すアイテムの自動付与。
- LLM からの直接 Action DSL や Minecraft command 生成。
- `/give`, `/fill`, `/setblock`, `/tp`, `/summon`, `/kill`。

## 今回の調整

- `拾って` 系は `any_drop` を 8 個まで対象にし、マグネット fallback と magnet-only pickup を明示的に有効化した。
- `橋かけて` は bridge 実行前に `minecraft:dirt` の optional voice assist を入れるようにした。
- 小さな家は、8チャンクの建築候補 scan 後、world assist 既定で近くの5x5候補地に oak planks / cobblestone / glass などを1ブロックずつ置いて建てる。
- 食料動物の狩猟後に `food_drop` 回収を追加し、焼ける場合だけ optional `smelt_food` へ進む。
- optional `grant_item` と optional `smelt_food` は、補助できない場合でも本来の計画を止めないようにした。
- 資源不足で partial/block になりやすいクラフト依存には、非レア素材だけ `common_resource_top_up` を追加した。
- rare/progression 系の `grant_item` はデフォルト拒否する。bedrock / command block / structure block などの admin/creative-only item は常時拒否する。
- `search_structure` は `village_hint` と `structure_hint` を扱い、読み込み済み範囲にヒントがなければ world assist 既定で 300 ブロック先まで進む。
- 作業台を置いた直後にクラフト UI が安定するまで少し待ち、画面/handler の実状態を trace に残す。
- 採掘でクロスヘア合わせだけが原因で詰まった場合、土・砂・石・原木などの通常ブロックに限り `world_assist_direct_break` で bounded に補助する。
- blocked 時の3択吹き出しは、クラフト・採掘・移動/探索・拾得で文言を出し分ける。
