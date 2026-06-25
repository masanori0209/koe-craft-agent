# Demo Plan

## Main Story

Use the live MOD flow:

```text
暗いから松明置いて
```

Expected flow:

1. Press `V` to enable the MOD native microphone. Use `M` for temporary mute and `N` for full stop during troubleshooting.
2. Speak the phrase.
3. MOD records until the silence window.
4. AmiVoice returns recognized text.
5. MOD rule planner maps it to `place_light`.
6. MOD deterministic recipe resolver plans torch/material actions.
7. SurvivalActionExecutor performs legal player operations only.
8. Minecraft action bar shows current step and terminal result.

## Offline Check

Before live play:

```bash
make agent-check
make mod-build
```

These checks do not call AmiVoice or OpenAI.

## What Is Verified By Harness

- MOD compiles against Minecraft/Fabric.
- Speech fixture routing covers common conversation scenes.
- Bundled recipe catalog contains required survival basics.
- Lightweight dry-run trace generation works without TypeScript.

## What Requires Real Minecraft

- Native microphone permission.
- Live AmiVoice recognition.
- Live OpenAI fallback.
- Survival UI slot operations.
- Movement, block breaking, smelting, portal construction.
