# Vanilla Coverage

## 現在の整理

「バニラのマイクラのものを全て対応」は、KoeCraft では層を分けて扱う。

1. 名前認識
2. 目的解釈
3. 行動計画
4. Executor 実行

名前認識は広く対応する。`npm run minecraft:generate-vanilla-terms` で Minecraft 1.21.4 の block / item / entity 名を `data/minecraft/vanilla_terms.json` と `data/amivoice/vanilla_dict.tsv` に生成している。

レシピ認識は `npm run minecraft:generate-vanilla-recipes` で Minecraft 1.21.4 の jar から `data/minecraft/vanilla_recipes.json` を生成する。同時に MOD 同梱用の `mod/src/main/resources/koecraft/vanilla_crafting_recipes.json`, `mod/src/main/resources/koecraft/vanilla_stonecutting_recipes.json`, `mod/src/main/resources/koecraft/vanilla_smithing_recipes.json`, `mod/src/main/resources/koecraft/vanilla_smithing_trim_recipes.json` も生成する。`npm run minecraft:validate-recipe-catalog` で deterministic resolver の output/count と generic mapping が vanilla catalog に存在すること、MOD 同梱レシピが生成されていることを検証する。

Executor 実行は、サバイバル操作として検証できたものから広げる。全レシピを直接実行許可するのではなく、Minecraft の RecipeBook / ScreenHandler / vanilla recipe catalog に照合しながら段階的に広げる。

## 対応済み

- vanilla block id / block group を対象にした `scan_state`
- vanilla block id / block group を対象にした `collect_block`
- `break_block` の完了待ち
- `minecraft:torch` の暗所設置
- 近くの作業台/かまど/石切台/鍛冶台を探して開く
- 他プレイヤーが近くにいる作業台/かまど/石切台/鍛冶台を利用中候補として避ける
- 作業台/かまど/石切台/鍛冶台がなければホットバーから設置して開く
- 必要アイテムがホットバーになければ inventory から移す
- RecipeBook quick craft、同梱 shaped/shapeless recipe、または direct slot layout によるクラフト
- stonecutter slot 操作による stonecutting
- smithing table slot 操作による netherite upgrade
- smithing table slot 操作による armor trim と `minecraft:trim` component 差分での成功判定
- inventory/crafting table slot 操作による selected special crafting と component 差分での成功判定
- furnace slot 操作による `minecraft:charcoal` smelt
- 音声の汎用木材指定を、所持/周辺木材から具体レシピへ解決
  - 例: `ボート作って` + `minecraft:spruce_planks` 所持 -> `minecraft:spruce_boat`
  - 例: `ボート作って` + `minecraft:bamboo_planks` 所持 -> `minecraft:bamboo_raft`
- 音声の汎用素材/色/石材指定を、所持素材から具体レシピへ解決
  - 例: `剣作って` + `minecraft:iron_ingot` 所持 -> `minecraft:iron_sword`
  - 例: `ベッド作って` + `minecraft:blue_wool` 所持 -> `minecraft:blue_bed`
  - 例: `石の階段作って` + `minecraft:granite` 所持 -> `minecraft:granite_stairs`

## Craft 対応済みレシピ

- 各種木材の `*_planks`
- `minecraft:stick`
- `minecraft:crafting_table`
- `minecraft:torch`
- `minecraft:furnace`
- 木/石/鉄/金/ダイヤの `*_pickaxe`, `*_axe`, `*_shovel`, `*_sword`, `*_hoe`
- 革/鉄/金/ダイヤの `*_helmet`, `*_chestplate`, `*_leggings`, `*_boots`
- `minecraft:shield`
- `minecraft:chest`
- `minecraft:ladder`
- `minecraft:bowl`
- `minecraft:bow`
- `minecraft:arrow`
- `minecraft:fishing_rod`
- `minecraft:bucket`
- `minecraft:shears`
- `minecraft:flint_and_steel`
- `minecraft:white_bed`
- 各種木材の `*_button`, `*_pressure_plate`, `*_slab`, `*_stairs`, `*_door`, `*_trapdoor`, `*_fence`, `*_fence_gate`, `*_sign`, `*_hanging_sign`
- 各種通常木材の `*_boat`, `*_chest_boat`
- bamboo raft/chest raft、色付きベッド/カーペット/旗、石材 slab/stairs/wall は Agent resolver と Executor direct slot layout の両方で対応済み。
- vanilla shaped/shapeless recipe は RecipeBook 未解放でも MOD 同梱レシピから実行可能。
- Planner は MOD 同梱の vanilla crafting catalog から対象レシピの ingredient を逆算し、中間クラフトを再帰展開する。再帰は深さ 32 と seen set で安全弁をかけ、未登録の素材調達ルートに当たった場合は `recipe_material_route_missing` の `planner_blocked_reason` を trace に残して停止する。
- クラフト材料の採掘後 pickup は、可能な限り `any_drop` ではなく期待ドロップに絞る。例: `stone`/`cobblestone` 系は `minecraft:cobblestone`、generic log 採取は `log` group、exact 木材指定は `minecraft:oak_log` など。
- vanilla stonecutting recipe は石切台画面で MOD 同梱レシピから実行可能。
- vanilla smithing transform recipe は鍛冶台画面で MOD 同梱レシピから実行可能。
- vanilla smithing trim recipe は鍛冶台画面で MOD 同梱レシピから実行可能。結果 item id ではなく `minecraft:trim` component 差分と item+component 等価スタックの回収確認で成功判定する。
- `component_craft` は dyed leather armor, banner duplicate, map cloning/extending, firework rocket/star/fade を component 差分と item+component 等価スタックの回収確認で成功判定する。
- 残る大きな TODO は written book cloning / repair item / shield decoration / tipped arrows などの special crafting component outputs と素材調達ルート。詳細は [Crafting Coverage TODO](crafting-coverage-todo.md) を参照。
- vanilla recipe catalog snapshot は生成済みで、`make agent-check` 内で resolver/catalog 整合性を検証する。

## Smelt 対応済みレシピ

- `minecraft:oak_log` + fuel -> `minecraft:charcoal`
- `minecraft:raw_iron` + fuel -> `minecraft:iron_ingot`

## Nether Preparation 対応状況

- `ネザーに行きたい` は nether preparation intent として検出する。
- 既定は bucket-portal preparation route として、`minecraft:bucket` と `minecraft:flint_and_steel` を揃える計画を生成する。
- `黒曜石でポータル作って` などは direct obsidian frame route として、14個の `minecraft:obsidian` をホットバーに移し、4x5フルフレームを設置してから着火する。
- `溶岩バケツでポータル作って` などは lava-cast route として、水バケツ/溶岩バケツを揃え、溶岩設置 + 水設置 + 黒曜石化確認を繰り返す。
- 足りない鉄は `iron_ore` 採掘、`raw_iron` 回収、かまど製錬へ落とす。
- 足りない火打石は `gravel` 採掘、`flint` 回収へ落とす。
- `collect_fluid` で水/溶岩 source block のバケツ回収を行う。
- `build_nether_portal` は `obsidian_frame` と experimental `lava_cast` の2パターンを持つ。
- `ignite_nether_portal` は `minecraft:flint_and_steel` を使い、`minecraft:nether_portal` block の出現で成功判定する。
- ディメンション遷移はゲーム体験を削りすぎないよう自動化せず、明示的な「入って」系の意図を別途必要とする。遷移後の安全確認は未実装。

## まだ全バニラ実行と言わない理由

Minecraft 1.21.4 では、クライアント側に安定した全レシピ ID がそのまま来るわけではなく、RecipeBook では runtime の `NetworkRecipeId` が使われる。したがって「全レシピを安全に実行」は、単に `minecraft:item_id` を知っているだけでは足りない。

安全に広げる順序:

1. survival-critical recipe allowlist
2. vanilla recipe catalog を生成して deterministic resolver を検証
3. RecipeBook / ScreenHandler で実行可能 recipe を照合
4. UI が開いていて材料がある時だけ実行
5. 実行後 inventory delta を確認

この順序なら、LLM が勝手にレシピを作ったり、Executor が存在しないクラフトを成功扱いしたりしない。
