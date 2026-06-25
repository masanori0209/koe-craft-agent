# Pathfinding Goals

KoeCraft の移動・採掘・探索は、Baritone のような「移動プリミティブ + コスト評価」と、mineflayer-pathfinder のような「Goal 抽象」を参考にする。ただし Baritone をそのまま組み込むのではなく、KoeCraft の Action DSL と Safety Filter に合わせた bounded local pathing として実装する。

## 方針

- LLM は経路や座標列を生成しない。
- Planner は `move`, `collect_block`, `explore` などの Action DSL と Goal 種別を作る。
- Executor は周囲の短距離 voxel 状態から、Goal に近づく候補をコスト付きで選ぶ。
- 失敗した対象は短時間 blacklist し、同じ石・同じ探索ヒントへ粘着しない。
- 掘削・足場設置・ジャンプ・段差下降は bounded primitive として扱い、無制限トンネル掘りや長期自律探索へ広げない。

## Goal 種別

最小実装では `KoeCraftLocalGoalPathfinder.Goal.near(target, range)` を使う。

今後増やす候補:

- `GoalNearBlock`: 特定ブロックの周囲まで移動する。
- `GoalNearEntity`: ドロップやmobへ近づく。
- `GoalExploreDirection`: 既訪問方向を避けて範囲端まで進む。
- `GoalWorkbenchReach`: 作業台やかまどを開ける距離へ移動する。
- `GoalEscapeHazard`: 水・溶岩・敵対mobから離れる。

## コスト

局所pathは単純最短ではなく、以下のコストを加味する。

- 斜め移動
- ジャンプ/段差上り
- 1ブロック段差下降
- 危険ブロック近接
- Goal から遠ざかる候補
- `open_passage` cost
- `place_support` cost
- `dig` cost

これにより、目先の最短経路で崖際や障害物へ突っ込むより、少し迂回しても安定する経路を選びやすくする。

## 現在の実装範囲

- `KoeCraftLocalGoalPathfinder`: Minecraft 起動なしで検証できる純粋な局所 Goal planner。
- `SurvivalActionExecutor#planLocalPath`: 既存 BFS をコスト付き planner に差し替え。
- `moveWithinReachOfBlock`: 採掘・作業台・コンテナ接近で、単に近い点ではなく、Goal planner で到達できる接近候補を選ぶ。
- `navigateNearExplorationTarget`: 村/構造物ヒントへ向かう時に、直線 steering ではなく Goal planner の waypoint へ進む。
- `tryMovementAssist`: 詰まり recovery で `walk` / `open_passage` / `place_support` / `dig` を固定順ではなく Goal planner の first assist primitive から選ぶ。
- `unreachableBlockTargets`: 採掘・探索で届かなかった対象を短時間避ける。
- `localGoalPathfinderSmoke`: 仮想地形で detour / hazard avoidance / open / place / dig / unreachable reason を検証する。

## 非目標

- Baritone 互換コマンドを実装しない。
- Minecraft slash command で移動・採掘・設置を解決しない。
- LLM に path や Action DSL を直接出させない。
- 長距離完全自律botにはしない。
