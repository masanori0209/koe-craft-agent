# Cost and Live Test Policy

## 基本方針

KoeCraft Agent の通常ハーネスは無料で回せるようにする。

無料で回るもの:
- `npm run agent-check`
- `npm run agent-fixtures`
- `npm run agent-dry-run`
- `npm run agent-scan`
- `npm run amivoice:dict-check`
- `npm run minecraft:generate-vanilla-terms`
- `make mod-build`

これらは fixture、dry-run、型チェック、辞書検証、MOD ビルドだけを行い、OpenAI API と AmiVoice API を呼ばない。

## AmiVoice

AmiVoice は音声認識の実時間または認識対象時間に対して課金される。手元では 10 時間無料クーポンを前提にしてよいが、通常ハーネスには AmiVoice 呼び出しを混ぜない。

実音声認識テストは、将来的に `--live-amivoice` のような明示オプションだけで有効化する。

## OpenAI

OpenAI の課金対象は、主に自然発話を Goal に変換する LLM Goal Parser の live 実行である。

KoeCraft ではまずルールベース + 辞書で処理し、未知語・曖昧語・未実装 intent だけを LLM fallback に送る。fallback の第一候補は低コストな `gpt-4o-mini` とし、必要な場合だけ上位モデルへ切り替える。

LLM に任せるのは以下に限定する:
- 雑な日本語発話から目的を抽出する
- 言い直し、フィラー、曖昧表現を Goal 候補へ寄せる
- ユーザー向けの短い説明文を作る

LLM に任せない:
- Minecraft レシピ解決
- Action DSL の最終決定
- Safety Filter
- Minecraft コマンド生成

## 見積もりコマンド

OpenAI API を呼ばずに費用だけを見積もる。

```bash
npm run openai:cost -- --scenario goal-parser-smoke
```

100 発話のデモ練習を見積もる例:

```bash
npm run openai:cost -- --scenario demo-session --usd-jpy 160
```

任意の前提で見積もる例:

```bash
npm run openai:cost -- --model gpt-5.4-mini --requests 100 --input-tokens 1200 --output-tokens 250 --usd-jpy 160
```

`gpt-4o-mini` を明示する例:

```bash
npm run openai:cost -- --model gpt-4o-mini --scenario demo-session --usd-jpy 160
```

価格スナップショットは `data/pricing/openai-pricing.json` に置く。価格は変わる可能性があるため、live 有料テスト前に OpenAI 公式 pricing を確認する。

## 現実的な運用

開発中:
- 通常は fixture / dry-run だけで回す
- 曖昧表現の改善はまず fixture に追加する
- live OpenAI は小さい smoke だけにする

デモ前:
- 10 発話程度の live Goal Parser smoke
- 代表発話 50 発話程度の regression
- Minecraft 実機は API なしでも Executor smoke 可能な範囲を先に確認する

本番寄り:
- OpenAI の Project budget / monthly budget を設定する
- live LLM テストは CI のデフォルトに入れない
- API 使用量は dashboard で確認する
