# Version Matrix / バージョン互換表

| Component | Current Pin | Notes |
|---|---:|---|
| Minecraft Java Edition | 1.21.4 | Pinned in `mod/gradle.properties` |
| Java | 21+ | Required for Minecraft 1.21.x / Fabric development |
| Fabric Loader | 0.16.10 | Pinned in `mod/gradle.properties` |
| Fabric API | 0.114.2+1.21.4 | Fabric API version for Minecraft 1.21.4 |
| Yarn mappings | 1.21.4+build.8 | Mapping version for Minecraft 1.21.4 |
| Gradle Wrapper | 8.12 | Pinned in `mod/gradle/wrapper/gradle-wrapper.properties` |
| WebSocket port | 8787 | Compatibility executor port |
| Vanilla terms | Minecraft 1.21.4 | Generated from official assets with maintenance scripts |

TypeScript is no longer part of the standard runtime or harness.

## Change Rules

- If `minecraft_version` changes, review `yarn_mappings`, `fabric_version`, `loader_version`, and Java version together.
- If moving back to Minecraft 1.20.x, Java 17 may be required.
- Verify MOD execution per OS. On Windows, check firewall behavior. On macOS, check Java runtime and microphone permission behavior.
