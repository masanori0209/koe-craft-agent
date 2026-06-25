# macOS / Windows Setup

## 日本語

KoeCraft Agent の標準ランタイムは Fabric MOD 単体です。TypeScript Voice Agent は廃止済みです。

### 共通要件

- Java 21 以上
- 同梱の Gradle Wrapper: `mod/gradlew` / `mod/gradlew.bat`
- Minecraft Java Edition 1.21.4
- Fabric Loader 0.16.10 以上
- UTF-8 を扱えるターミナル

Node.js は vanilla term / recipe の再生成など一部メンテナンススクリプトにだけ必要です。通常のMODビルド・標準ハーネスには TypeScript は不要です。

### macOS

```bash
make agent-check
make mod-build
```

Java 21 がない場合の例:

```bash
brew install openjdk@21
```

macOS のマイク権限は launcher に依存します。Minecraft プロセスへ microphone permission を付与できる launcher で検証してください。

### Windows

PowerShell 7 / Windows Terminal を推奨します。`make` がない場合は Gradle Wrapper を直接使います。

```powershell
cd mod
.\gradlew.bat build
```

Java 21 は Eclipse Temurin などを推奨します。

```powershell
java -version
```

日本語が文字化けする場合:

```powershell
chcp 65001
```

## English

The standard KoeCraft runtime is the Fabric MOD only. The TypeScript Voice Agent has been removed.

### Common Requirements

- Java 21+
- Included Gradle Wrapper: `mod/gradlew` / `mod/gradlew.bat`
- Minecraft Java Edition 1.21.4
- Fabric Loader 0.16.10+
- UTF-8 capable terminal

Node.js is only needed for optional maintenance scripts such as vanilla term / recipe regeneration. TypeScript is not required for the standard MOD build or harness.

### macOS

```bash
make agent-check
make mod-build
```

Example Java 21 install:

```bash
brew install openjdk@21
```

### Windows

Use PowerShell 7 / Windows Terminal. If `make` is unavailable, run Gradle directly:

```powershell
cd mod
.\gradlew.bat build
```
