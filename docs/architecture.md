# アーキテクチャ

## 全体像

KoeCraft Agent の標準ランタイムは Fabric Client MOD 単体である。

```text
Minecraft Java Edition
  + Fabric Client MOD
      + native mic recorder
      + AmiVoice adapter
      + OpenAI ASR text normalizer
      + OpenAI JSON-goal fallback
      + rule-based goal planner
      + deterministic recipe resolver
      + survival Action executor
```

TypeScript Voice Agent は標準ランタイムと標準ハーネスから廃止した。通常プレイでは別サーバーやブラウザUIを起動しない。

## MOD 側責務

- Minecraft キーバインドから native mic を ON/OFF する
- Java Sound API で音声を録音し、AmiVoice WebSocket へ 16kHz/16bit/mono PCM chunk を送信する
- `amivoice.transport=http` の場合は従来の短発話 WAV + HTTP multipart へ戻せる
- ルールで解けない認識文だけ OpenAI ASR normalizer に渡し、Minecraft 文脈の誤認識を補正する
- ルールベースで解けない発話だけ OpenAI JSON-goal fallback へ渡す
- LLM fallback へ渡す場合は、半径 8 程度の軽量 world context を添付する
- LLM 出力を Goal JSON として正規化する
- レシピは bundled vanilla catalog / deterministic resolver で解決する
- Action DSL は allowlist されたものだけ生成する
- Action DSL をサバイバル操作として実行する
- 実行中ステータス、blocked reason、中断を Minecraft 画面上へ表示する

## 設定

MOD 側のローカル設定ファイル:

```text
.minecraft/config/koecraft-agent.properties
```

初回起動時に空テンプレートを生成する。API key は repository に含めず、ログにも出さない。環境変数 `AMIVOICE_API_KEY` / `OPENAI_API_KEY` も fallback として読める。

Executor の便利補助はデフォルトで `koecraft.executor.assistMode=world_assist`。これは Minecraft slash command を生成・実行するモードではなく、Action DSL と Safety Filter を通した MOD/server 補助である。既存の `programmatic` 設定値は後方互換で `world_assist` として扱う。`balanced` / `survival` / `off` に戻せる。

`world_assist` は、他の server-side helper MOD と同様に、設定・allowlist・trace 付きの便利補助として扱う。デフォルトでは Rare/progression item 以外を ON にする。

代表設定:

- `koecraft.worldAssist.enabled=true`
- `koecraft.worldAssist.consumeItemsWhenPossible=true`
- `koecraft.worldAssist.allowCommonMaterialTopUp=true`
- `koecraft.worldAssist.allowRareItems=false`
- `koecraft.worldAssist.allowWorkstationPlacement=true`
- `koecraft.worldAssist.allowMagnetPickup=true`
- `koecraft.worldAssist.allowDirectCraft=true`
- `koecraft.worldAssist.allowSmallBuilds=true`
- `koecraft.executor.programmaticExploreDistanceBlocks=300`
- `koecraft.executor.programmaticBoatTravelDistanceBlocks=180`

## 音声フロー

```text
V key toggle
  -> native mic waits for speech
  -> speech pre-roll + PCM chunk streaming
  -> AmiVoice WebSocket recognize
  -> rule-based planner
  -> optional OpenAI ASR normalizer
  -> rule-based planner retry
  -> optional OpenAI JSON-goal fallback
  -> deterministic Action plan
  -> SurvivalActionExecutor
```

`amivoice.transport=websocket` がデフォルトである。WebSocket では AmiVoice 側の逐次結果と発話終端検出を使えるため、短い命令の先頭欠けや発話後待ち時間を HTTP multipart より抑えやすい。障害切り分けや互換性確認では `amivoice.transport=http` に戻せる。

マイク入力は `voice.adaptiveNoise.enabled=true` の場合、発話前の短い環境音からノイズ床を見積もり、固定しきい値を下回らない範囲で発話検知しきい値を自動調整する。これはノイズサプレッションではなく、AmiVoiceへ送る前の誤発話開始を減らす軽量ゲートである。

Minecraft の BGM や環境音を誤って拾う場合は、RMS だけでなく `voice.vad.*` による発話区間の信頼度ゲートを使う。標準方針は Silero VAD の ONNX model を Java 側で固定バージョン・固定ハッシュとして扱い、`voice.vad.provider=silero_onnx` で RMS / adaptive noise と AND 条件にする。RNNoise WASM / AudioWorklet は、Bridge を Web Audio 経路に寄せる場合の optional noise suppression として扱い、標準 MOD 単体には直接入れない。詳細は `docs/audio-vad-noise-suppression.md` に残す。

WebSocket の逐次結果はUIへ表示し、`歩いて`, `止まって`, `拾って`, `掘って`, `走って`, `泳いで`, `橋かけて` のような安全な短命令だけ、確定結果を待ちすぎず録音を早めに閉じる。クラフト、探索、戦闘、ネザー準備などの長い計画は final result を待つ。

ASR 後処理は LLM の前に軽量辞書補正を行う。`ハッシュ` を橋文脈で `橋` に寄せる、`きのう + ツルハシ` を作成文脈で `木のツルハシ` に寄せる、といった Minecraft 文脈に限定した補正だけを行う。安全な短命令は plan cache に載せるが、クラフト計画はインベントリ状態で変わるためキャッシュしない。

小さい声には `voice.pcmNormalize.enabled=true` で AmiVoice へ送るPCMだけを軽く正規化する。発話検知は生PCMのRMSで行い、正規化はASR送信直前だけに限定する。

## LLM 境界

LLM は Goal JSON だけを返す。LLM から Minecraft command や Action DSL を直接実行する経路は作らない。

ASR normalizer は Goal JSON も Action DSL も返さない。AmiVoice の認識文を、Minecraft 文脈で自然な日本語へ補正するだけに限定する。

```json
{ "normalized_text": "前に橋を架けて", "intent_hint": "build_bridge", "confidence": 0.86 }
```

許可する代表例:

```json
{ "type": "craft_item", "target_item": "minecraft:shield" }
```

会話リアクション用の例:

```json
{ "type": "ambient_chat", "message": "近くが暗いから足元に気をつけよう。", "style": "nod" }
```

`ambient_chat` は LLM が Action を決めるのではなく、MOD 側 Planner が allowlist 済みの吹き出し表示と短いリアクションへ変換する。

建築用の例:

```json
{ "type": "build_structure", "style": "cute_house", "size": "tiny", "palette": "available" }
```

`build_structure` も LLM がブロック座標や Minecraft command を直接出さない。LLM は style / size / palette の希望だけを返し、MOD 側 Planner が 8チャンクの要約スキャン、検証済み `build_blueprint` Action へ変換する。デフォルトの world assist では、Executor が近くの地形に応じた 5x5 候補地を選び、common block を短い tick 間隔で順番に配置して「建てている」見え方にする。巨大建築や任意座標の blueprint は拒否または小さな家への確認に縮退する。

禁止:

```json
{ "type": "execute_actions", "actions": [{ "type": "minecraft_command", "command": "/give ..." }] }
```

## Executor

Executor は Minecraft コマンドを使わず、以下のようなサバイバル操作だけを行う。

- 周囲 scan
- 移動 / 逃走 / 探索
- ブロック採取 / ドロップ回収
- 作業台 / かまど / 石切台 / 鍛冶台を開く
- recipebook 未解放でも bundled recipe catalog からスロット配置でクラフトする
- 食料確保、戦闘、防御、緊急シェルター、液体脱出
- ネザーポータル準備と着火

Executor は各 Action の前後に軽量スナップショットを取り、座標・インベントリ・画面状態・足元ブロックの変化を `watchdog` 診断として実行結果へ付与する。移動系 Action が成功扱いでも実際に進捗していない場合は `blocked` に変換し、採掘中に射程端でブロック状態が変わらない場合も早めに停止する。

`world_assist` による素材補充、direct craft fallback、作業台配置などが発生した場合は、Minecraft 画面上の HUD に短時間の `World Assist` カードを出す。これは補助内容を透明化しつつ、プレイ中に「何を手伝ったか」が楽しく分かるようにするための表示であり、trace にも `world_assist: true` を残す。

最終結果が `blocked` になった場合は、停止したことがプレイ画面で分かるように、Executor が三人称表示へ切り替えて短いスニーク/左右ゆれリアクションを再生する。同時に `すみませんが、できないです。` の吹き出しを表示し、TTS が有効なら同じ文を読み上げる。さらに、声で選べる3択の介入吹き出しを出し、`右にずれて` / `手前を掘って` / `一回やめて` や、クラフト詰まり時の `作業台を近くに置いて` のような次の音声指示へ誘導する。危険な足場では左右移動せず、しゃがみと視点ゆれだけに縮退する。

`watchdog.no_progress=true` や採掘の no-progress を検知した場合、Executor は同じ Action をただ繰り返さず、1回だけ bounded recovery を試す。Recovery は周囲の短距離 scan を使って、安全な隣接レーンへ避ける、対象へ近づき直す、手前の遮蔽ブロックを露出させる、足場を置く、邪魔なブロックを掘る、といったサバイバル操作プリミティブから選ぶ。本格的な長距離経路探索は行わず、短距離の局所探索に限定する。

移動・採掘・探索の一般化は、周囲を 3D voxel grid として扱い、`walk`, `jump`, `sprint_jump`, `dig`, `place_support`, `open_passage` にコストを付けた bounded local goal pathing として進める。現在の方針と実装範囲は `docs/pathfinding-goals.md` にまとめる。

Planner がレシピ依存の循環や深さ超過を検知した場合は、`planner_blocked_reason` Action を生成し、Executor は `blocked` step として理由を返す。
