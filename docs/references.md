# References

## Claude Code
- `CLAUDE.md` はプロジェクト共有の永続指示として使う。
- プロジェクトアーキテクチャ、ビルドコマンド、コーディング規約、共通ワークフローを置く。
- 強制したい処理は自然言語指示ではなく hooks に寄せる。

## Codex
- `AGENTS.md` は Codex が作業前に読むプロジェクト指示として使う。
- グローバル指示とプロジェクト指示を階層的に重ねる。
- 近いディレクトリの指示ほど後ろに結合され、より具体的な指示として扱われる。

## Cursor
- Project Rules / Team Rules / User Rules / `AGENTS.md` に対応する。
- `.cursor/rules/*.mdc` はスコープを絞ったルールに使う。
- ルールは短く、具体的に、ファイル種別ごとに分ける。

## Loop / Harness
- 実装、検証、失敗ログ、修正、再検証の閉ループを作る。
- Trace を残し、失敗を fixture / eval に昇格する。
- Harness には instructions, tools, validation checks, output schema, surrounding code を含める。
