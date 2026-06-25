# Article Assets

## Core Fixture

- `examples/cases/place_torch_missing.json`
- Speech: `暗いから松明置いて`
- Expected route: `charcoal_route`

## CLI Screenshot Targets

Run:

```bash
make agent-dry-run
```

Capture the JSON fields:

- `recognized_text`
- `goal.type`
- `selected_route`
- `tasks`
- `safety.valid`
- `trace_path`
- `explanation`

## Trace Screenshot Targets

Open the latest `logs/traces/*.json` and capture:

- `speech_features`
- `recipe_dependencies`
- `task_plan`
- `action_plan`
- `execute_request`
- `safety_result`

## Practical Route Comparison Fixtures

Use `examples/cases/`:

- `place_torch_direct.json`: torch already exists, so place directly.
- `place_torch_coal.json`: coal exists, so craft sticks and torches.
- `place_torch_missing.json`: no torch and no coal, so craft wooden pickaxe, furnace, charcoal, torch, then place.
- `self_correction_pickaxe.json`: self-correction switches active goal.
- `interrupt_abort.json`: speech interruption becomes abort.
- `danger_abort.json`: dangerous world state becomes abort.

## AmiVoice Dictionary Comparison Fixtures

Use `examples/speech_fixtures.json`:

- `amivoice_dictionary_without_custom_term`
- `amivoice_dictionary_with_custom_term`

These are fixtures for article explanation, not proof of live AmiVoice API behavior.

## Speech Interaction Fixtures

Self-correction:

```text
松明置いて、いや、先に石のピッケル作って
```

Interruption:

```text
待って、やっぱやめて
```
