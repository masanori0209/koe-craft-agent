# AmiVoice Dictionary / AmiVoice辞書

## 日本語

`data/amivoice/dict.txt` は KoeCraft Agent 用の Minecraft 語彙リストです。

形式:

```text
表記<TAB>読み<TAB>カテゴリ
```

例:

```text
松明	たいまつ	item
石のピッケル	いしのぴっける	item
クリーパー	くりーぱー	mob
```

検証:

```bash
npm run amivoice:dict-check
```

### バニラ網羅候補

Minecraft 1.21.4 の公式 manifest / asset index とローカルの Minecraft jar から、バニラの block / item / entity 名を生成する。

```bash
npm run minecraft:generate-vanilla-terms
```

出力:

- `data/minecraft/vanilla_terms.json`
- `data/amivoice/vanilla_dict.tsv`

`vanilla_terms.json` は `block.minecraft.*`, `item.minecraft.*`, `entity.minecraft.*` の翻訳キーから生成した網羅リストである。

`vanilla_dict.tsv` は AmiVoice 登録候補だが、読みは自動候補であり、人間レビュー前提。とくに漢字・記号・長い複合語・英数字を含む語は、登録前に読みを整える。

### 料金と運用方針

AmiVoice公式情報上、料金は主に音声認識の利用時間に基づく従量課金として案内されています。ユーザー辞書は認識率調整機能として説明されていますが、料金や上限は契約・エンジン・登録方法で変わる可能性があります。登録 API を大量に叩く前に、まず `dict.txt` をレビューし、必要な語だけを登録してください。

公式ブログでは、登録語数が多くなると似た読みとの誤認識リスクが高まるため、必要最小限かつ長くユニークな単語を登録することが推奨されています。

### 重要

AmiVoice辞書は「松明」「石ピッケル」などの既知語を認識しやすくするためのものです。

以下のような意味解釈は Agent 側で扱います。

```text
あれあのあかりつけるやつ置いて
  -> light_source + place
  -> place_light / minecraft:torch
```

## English

`data/amivoice/dict.txt` is the Minecraft vocabulary list for KoeCraft Agent.

Format:

```text
surface<TAB>reading<TAB>category
```

Validation:

```bash
npm run amivoice:dict-check
```

### Cost and Operation Policy

Official AmiVoice pricing primarily describes usage-based billing by recognized audio duration. User dictionaries are described as a recognition-tuning feature, but exact limits and billing behavior can depend on contract, engine, and registration method. Before making bulk registration API calls, review `dict.txt` and register only the terms that are useful.

The official AmiVoice blog notes that registering too many words can increase false recognition risk, especially for short or similar readings. Prefer necessary, long, and distinctive terms.

### Important

The AmiVoice dictionary helps recognition for known words such as `松明` or `石ピッケル`.

Semantic interpretation still belongs in KoeCraft Agent:

```text
あれあのあかりつけるやつ置いて
  -> light_source + place
  -> place_light / minecraft:torch
```

### Vanilla Coverage Candidates

Generate vanilla block / item / entity term candidates from the official Minecraft 1.21.4 manifest / asset index and the local Minecraft jar:

```bash
npm run minecraft:generate-vanilla-terms
```

Outputs:

- `data/minecraft/vanilla_terms.json`
- `data/amivoice/vanilla_dict.tsv`

`vanilla_terms.json` is the coverage source generated from `block.minecraft.*`, `item.minecraft.*`, and `entity.minecraft.*` translation keys.

`vanilla_dict.tsv` is an AmiVoice registration candidate file, not a blind bulk-upload file. Readings are conservative generated candidates and should be reviewed before registration.
