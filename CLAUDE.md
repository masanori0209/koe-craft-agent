# CLAUDE.md

## Project
KoeCraft Agent is an unofficial Japanese voice-first survival agent for Minecraft Java Edition.

The goal is not to build a fully autonomous Minecraft AI. The goal is to convert Japanese voice requests recognized by AmiVoice into survival-legal task plans and Action DSL executed by a Fabric client mod.

## Read First
Before changing code, read:
1. `docs/spec.md`
2. `docs/architecture.md`
3. `docs/harness.md`
4. `docs/loop-engineering.md`
5. Relevant fixtures under `examples/`

## Non-negotiable Rules
- Do not generate or execute Minecraft commands such as `/give`, `/fill`, `/setblock`, `/tp`, `/summon`, `/kill`.
- Do not implement creative-mode behavior.
- Do not let the LLM directly decide final Minecraft actions without schema validation.
- Do not let the LLM solve Minecraft recipes from memory. Use Recipe Resolver data.
- Do not bypass Safety Filter.
- Do not commit API keys, tokens, `.env`, local paths, or personal data.
- Do not claim the project is affiliated with Minecraft, Mojang, Microsoft, AmiVoice, or Advanced Media.

## Architecture Rules
Keep responsibilities separated:
- AmiVoice Adapter: speech-to-text and dictionary handling.
- Speech Feature Extractor: filler, correction, interrupt, confidence features.
- Goal Parser: LLM-based natural-language-to-goal conversion.
- Recipe Resolver: deterministic recipe graph.
- Route Selector: route choice from inventory/world state.
- Task Planner: goal-to-task conversion.
- Action Planner: task-to-Action-DSL conversion.
- Safety Filter: final validation before execution.
- Fabric MOD Executor: survival-legal player actions only.

## Coding Loop
For every task:
1. Inspect related docs and fixtures.
2. Make a small plan.
3. Implement the smallest useful change.
4. Add or update fixtures/tests.
5. Run the relevant harness command.
6. If it fails, use the failure log for one focused repair.
7. Re-run the harness.
8. Report changed files, commands run, results, and remaining risks.

## Required Commands
Prefer these commands when available:
```bash
make agent-check
make agent-fixtures
make agent-dry-run
make mod-build
```

If a command is missing, create or update the harness instead of skipping validation.

## Safety Rule
If an action can move the player, consume resources, approach hazards, enter combat, or continue autonomously, it must have an abort path.

Voice interrupts such as `待って`, `止まって`, `やっぱやめて`, `危ない` must take priority over the current plan.
