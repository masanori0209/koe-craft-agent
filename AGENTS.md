# AGENTS.md

## Repository Mission
KoeCraft Agent is an unofficial Japanese voice-first Minecraft control agent for Minecraft Java Edition, powered by AmiVoice and LLMs.

It converts Japanese voice requests into deterministic task plans and Action DSL so Minecraft can be controlled naturally by voice. It should avoid raw Minecraft slash commands from LLM output; convenience assists must be represented as explicit, bounded Action DSL.

## Essential Context
Read these before implementing:
- `docs/spec.md`
- `docs/architecture.md`
- `docs/harness.md`
- `docs/loop-engineering.md`
- `docs/agent-workflow.md`

## Build and Validation
Use the harness.
```bash
make agent-check
```

When touching planner logic:
```bash
make agent-fixtures
make agent-dry-run
```

When touching the Fabric mod:
```bash
make mod-build
```

If commands are missing, add scripts or Make targets rather than silently skipping validation.

## Hard Constraints
- No raw `/give`, `/fill`, `/setblock`, `/tp`, `/summon`, `/kill` emitted from LLM output.
- Inventory/world convenience behavior must go through explicit Action DSL such as `grant_item`, not free-form command strings.
- No direct Minecraft command generation from LLM output.
- No API keys or secrets in the repository.
- No claims of official Minecraft/Mojang/Microsoft/AmiVoice affiliation.
- No LLM-only recipe solving. Recipes must go through deterministic resolver data.
- Every executable action must pass Safety Filter.

## Required Development Loop
1. Plan the smallest useful change.
2. Implement.
3. Add or update tests/fixtures.
4. Run the relevant harness.
5. Repair from failure logs.
6. Re-run harness.
7. Summarize results.

## Output Format for Agent Responses
```md
## Summary
## Files Changed
## Commands Run
## Results
## Risks
## Next Step
```
