# KoeCraft Agent 仕様書

## 概要
KoeCraft Agent は、Minecraft Java Edition を日本語音声で自然に動かすための音声操作エージェントである。
ユーザーが自然な日本語で目的を話すと、AmiVoice で音声認識し、LLM で目的を解釈し、MOD Executor が実行できるタスク計画へ変換する。

例: 「暗いから松明置いて」

この依頼に対して、エージェントは以下を行う。
1. 松明を持っているか確認する
2. 松明がなければ、棒・石炭・木炭の有無を確認する
3. 素材がなければ、周囲の木・石・石炭鉱石を探索する
4. 石炭ルートまたは木炭ルートを選択する
5. 原木・丸石など必要素材を集める
6. かまど・木炭・松明を作る
7. 暗い場所に松明を設置する

本プロジェクトは、Minecraft を完全攻略する AI ではない。目的は、AmiVoice で認識した日本語音声を、ユーザーが気持ちよく遊べる短〜中期の行動計画へ変換することである。

## 設計思想

### 主役は Minecraft MOD ではなく音声エージェント設計
Minecraft は題材であり、本質は以下である。

```text
日本語音声 -> 音声認識 -> 目的解釈 -> 不足素材・レシピ解決 -> 音声操作向け行動計画 -> 実行・監視・中断
```

### LLM に全部任せない
LLM の役割は、自然発話から目的を抽出することに限定する。

LLM に任せること:
- 発話から目的を抽出する
- 言い直し・曖昧表現を文脈で解釈する
- ユーザー向け説明文を生成する

ただし、まずはルールベース + 辞書で処理する。Minecraft のバニラ用語、AmiVoice 用の登録語、行動語（置く、作る、掘る、集める、探す）を先に照合し、対応済み Goal に落とせるものは LLM を呼ばない。

例:
- `あれあのあかりつけるやつ置いて` -> `place_light` / `minecraft:torch`
- `土を掘りたい` -> `collect_block` / `dirt`
- `石炭鉱石を掘って` -> `collect_block` / `coal_ore`
- `掘って` -> `context_action` / `mine` として、視線先ブロックを安全確認つきで掘る
- `取って` -> `context_action` / `take` として、近くのドロップを優先し、なければ視線先ブロックを取る
- `倒して` -> `context_action` / `attack` として、近くの敵対 mob を優先し、いなければ近くの食料動物を対象にする
- `ドア開けて` -> `context_action` / `open` として、近くのドアまたはフェンスゲートを開ける
- `収穫して` -> `context_action` / `harvest` として、成熟作物だけを収穫し、可能なら植え直す
- `逃げて` / `離れて` -> `context_action` / `retreat` として、防御移動で距離を取る
- `防御して` -> `context_action` / `defend` として、装備準備後に盾/後退判断を行う
- `やったー` / `いえーい` / `おおーすごい` -> `celebrate` として、第三者視点で短いポーズ/ダンスを行い、一人称視点へ戻す
- `村を探したい` / `構造物を探して` -> `search_structure` として、8チャンクの summary scan で読み込み済みヒントを探し、なければ既訪問方向を避けて 300 ブロック程度の bounded exploration へ進む

LLM に任せないこと:
- Minecraft レシピの正確な解決
- インベントリ改ざん
- Minecraft コマンド生成
- 危険判定の最終判断
- 実行可能な Action DSL への最終検証
- 長期自律探索

### Minecraft コマンドではなく Action DSL
`/give`, `/fill`, `/setblock`, `/tp` のような Minecraft コマンドで世界を改変しない。
エージェントは Action DSL を生成し、MOD Executor がサバイバルで可能なプレイヤー操作として実行する。

```json
[
  { "type": "look_at", "target": { "block": "minecraft:oak_log", "pos": [124, 65, -30] } },
  { "type": "move_to", "target_pos": [124, 65, -30], "max_distance": 12, "avoid_hazards": true },
  { "type": "break_block_sequence", "target_block": "minecraft:oak_log", "count": 2 },
  { "type": "craft", "recipe": "minecraft:stick", "count": 4 },
  { "type": "place_block", "item": "minecraft:torch", "target": { "kind": "nearby_dark_wall" } }
]
```

## 差別化ポイント

### 既存 Minecraft LLM エージェントとの差分
Steve / Voyager 系の Minecraft AI は、自律的な作業代行や探索が主目的である。KoeCraft Agent は以下を重視する。
- 日本語音声で雑に頼める
- 「いや」「やっぱり」「待って」などの言い直し・割り込みを扱う
- 足りない素材を説明しながら解決する
- サバイバルで可能な操作だけを実行する
- 実行中でもユーザーが音声で止められる

### 既存音声コマンド MOD との差分
固定コマンドではなく「目的」を受け取る。

```text
暗いから松明置いて
松明ないなら作って
右の洞窟……いや左を見て
待って、やっぱやめて
木がないなら取ってきて
```

## 基本ユースケース

### 松明を置く
発話: `暗いから松明置いて`

期待動作:
1. `place_light` 目的として解釈する
2. インベントリから `minecraft:torch` を探す
3. 松明がない場合、`torch` のレシピを解決する
4. `stick` と `coal_or_charcoal` の有無を確認する
5. 石炭がなければ木炭ルートを検討する
6. 原木・丸石・燃料・かまどの有無を確認する
7. 必要素材を集める
8. 木炭を焼く
9. 松明をクラフトする
10. 近くの暗い壁または床に設置する

### 石のピッケルを作る
発話: `石のピッケル作れる？`

期待動作:
1. `craft_stone_pickaxe` と解釈する
2. 棒・丸石・作業台の有無を確認する
3. 足りなければ木を切る
4. 板材・棒・作業台を作る
5. 丸石を採掘する
6. 石のピッケルをクラフトする

### 音声中断
発話: `待って、やっぱやめて`

期待動作:
1. 実行中 Action を即停止する
2. キー入力を解除する
3. 現在地と状態を再スキャンする
4. 中断理由を UI / Minecraft チャットに表示する

## システム構成
```text
User Voice
  ↓
AmiVoice Adapter
  ↓
Speech Feature Extractor
  ↓
Goal Parser / LLM
  ↓
Inventory & World Scanner
  ↓
Recipe Resolver
  ↓
Route Selector
  ↓
Survival Task Planner
  ↓
Action Planner
  ↓
Fabric Client MOD Executor
  ↓
Execution Monitor
```

## Safety Constraints
禁止:
- `/give`
- `/fill`
- `/setblock`
- `/tp`
- `/summon`
- `/kill`
- クリエイティブモード前提の操作
- 所持していないアイテムの使用
- 届かないブロックの破壊
- 見えていない対象への攻撃
- 溶岩・高所への無確認接近
- 無制限探索
- ユーザー中断後の継続実行

音声操作向け補助:
- `world_assist` は明示 Action として、統合サーバー側の MOD API で限定的な補助を行う。Minecraft slash command は生成・実行せず、trace には `world_assist: true` を残す。
- `grant_item` は `world_assist` の一部として、統合サーバーのプレイヤーインベントリに限定数のアイテムを補充できる。Rare/progression item はデフォルト拒否し、bedrock / command block などの admin/creative-only item は常時拒否する。
- LLM が直接 Minecraft command を生成する経路は持たない。補助も Action DSL と Safety Filter を通す。

### 子供向けモード
子供向けモードは、自由な発話を受けても楽しく分かりやすく進めるための補助モードである。
必要に応じて小さな作業へ縮退したり、音声操作向け補助 Action を使って素材不足による詰まりを減らす。

設定:
- `childMode.enabled`: 子供向けの補助計画を有効化する
- `childMode.materialAssist`: 家づくりなどで不足素材の任意調達を先に試す
- `childMode.shelterMaterialTarget`: 小さな家・安全地帯で事前に確保したい目安素材数

中断条件:
```json
[
  "health_below_8",
  "hunger_below_6",
  "hostile_mob_within_5_blocks",
  "lava_within_2_blocks",
  "fall_risk_detected",
  "target_not_found",
  "inventory_full",
  "user_says_stop",
  "execution_timeout"
]
```

## 初期対応範囲
### Phase 1
- 音声認識
- 目的解釈
- Minecraft 用語辞書
- インベントリ取得
- 周囲ブロック取得
- 松明を置く
- 石のピッケルを作る
- 音声中断

### Phase 2
- 木炭ルート
- かまど作成
- 橋をかける
- 洞窟を安全に進む
- 危険 mob 検知による中断

### Phase 3
- 食料確保
- 簡易拠点準備
- 鉄ピッケル作成
- ベッド作成
- 拠点へ戻る
