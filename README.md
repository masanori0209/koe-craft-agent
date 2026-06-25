# KoeCraft Agent

KoeCraft Agent is an unofficial Japanese voice-first Minecraft control MOD for Minecraft Java Edition.

It converts casual Japanese or English voice requests into deterministic actions inside the Fabric client MOD so Minecraft can be controlled naturally by voice. It is not an official Minecraft, Mojang, Microsoft, or AmiVoice project.

## Current Runtime

The standard runtime is MOD-only.

```text
Minecraft Java Edition
  + Fabric Client MOD
      + native mic recorder
      + AmiVoice HTTP adapter
      + OpenAI JSON-goal fallback
      + deterministic planner / recipe resolver
      + voice Action executor
```

The previous TypeScript Voice Agent has been removed from the standard runtime and harness. Do not start a separate `voice:daemon` or browser voice UI for normal play.

## Setup

Build the MOD:

```bash
make mod-build
```

Copy the built jar to your Minecraft mods folder:

```bash
cp mod/build/libs/koecraft-agent-mod-0.1.0.jar "$HOME/Library/Application Support/minecraft/mods/koecraft-agent-mod-0.1.0.jar"
```

The MOD targets Minecraft Java Edition 1.21.4 and Java 21.

## Voice Configuration

The MOD reads local settings from:

```text
.minecraft/config/koecraft-agent.properties
```

The file is created on first launch with blank placeholders. API keys can also be supplied through environment variables:

```text
AMIVOICE_API_KEY
OPENAI_API_KEY
```

Keys must never be committed to the repository.

When `voice.nativeMicEnabled=true`, press the Minecraft keybinding `V` to toggle the MOD native microphone. The mic stays ON and sends each utterance to AmiVoice after the configured silence window. `M` mutes/unmutes continuous listening without leaving voice mode, and `N` stops the mic completely.

## Validation

The default harness no longer requires TypeScript.

```bash
make agent-check
make agent-fixtures
make agent-dry-run
make mod-build
```

These commands run the Java/Fabric build and lightweight Python fixture checks. They do not call AmiVoice or OpenAI.

## Data Generation

Some repository maintenance scripts are still plain JavaScript for Minecraft data generation and local cost estimates:

```bash
npm run minecraft:generate-vanilla-terms
npm run minecraft:generate-vanilla-recipes
npm run minecraft:validate-recipe-catalog
npm run openai:cost -- --scenario goal-parser-smoke
```

These are not part of the TypeScript voice runtime.

## Safety

KoeCraft must not generate or execute Minecraft world-editing commands such as:

```text
/give
/fill
/setblock
/tp
/summon
/kill
```

Recipes are resolved through deterministic catalog data, not LLM-only guessing. LLM fallback may return only a normalized Goal JSON candidate; raw Action DSL and Minecraft commands from LLM output are not executed.

Voice assist uses explicit Action DSL such as `grant_item` for bounded item top-ups when natural voice play would otherwise get stuck. It does not generate slash commands, and traces include `voice_assist: true`.
