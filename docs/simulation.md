# Simulation Harness

The previous TypeScript simulation harness has been removed with the TypeScript Agent.

The scenario fixtures under `examples/sim*` and generated reports under `logs/sim` are kept as historical data and as design references for a future Java/MOD-side simulator.

Current standard validation:

```bash
make agent-check
make mod-build
```

Future simulation work should be implemented without reintroducing the TypeScript Agent. Preferred directions:

- Java-side pure policy tests for movement / fluid escape / emergency shelter.
- MOD debug actions that replay reduced trace fixtures.
- Python-only lightweight validators if a full Minecraft runtime is unnecessary.
