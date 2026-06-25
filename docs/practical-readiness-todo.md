# Practical Readiness TODO

This tracks the remaining work needed to move KoeCraft from local prototype quality to practical voice-controlled Minecraft play.

## P0: Voice Runtime

- [x] Split debug browser UI from daemon API.
- [x] Add daemon native mic bridge toggle path.
- [x] Show a clear error when native mic starts before the Minecraft executor is reachable.
- [x] Show mic ON/OFF in the Minecraft action bar from `/api/mic/toggle`.
- [x] Add native 2-second silence detection so OFF is not required for normal utterances.
- [ ] Tune native mic thresholds from real Minecraft fullscreen play.
- [ ] Add Windows native mic defaults and documented device discovery.
- [ ] Package daemon as a background app or tray app.

## P0: LLM Fallback

- [x] Add OpenAI fallback only for rule-parser `unknown`.
- [x] Restrict LLM output to Goal JSON.
- [x] Validate LLM Goal JSON before planning.
- [x] Keep recipes, Action DSL, and safety decisions deterministic.
- [x] Add live OpenAI smoke test behind an explicit paid-test flag.
- [x] Add UI/trace cost reporting from OpenAI usage.
- [ ] Refresh pricing snapshot before a public playtest rehearsal.

## P1: Nether

- [x] Prepare bucket-portal kit: bucket, flint and steel, raw iron, smelting.
- [x] Collect water source with bucket.
- [x] Collect lava source with bucket.
- [x] Build direct obsidian portal frame with bounded placement.
- [x] Add experimental lava-bucket cast portal frame action.
- [x] Ignite portal with flint and steel.
- [ ] Tune lava-cast placement against real Minecraft terrain and lava pools.
- [ ] Enter portal only after user confirmation or explicit intent.
- [ ] Stop after dimension transition and run arrival safety scan.

## P1: Material Routes

- [x] Add deterministic acquisition tasks for sand, gravel, clay, sugar cane, flint, leather, feather, and wool.
- [ ] Add bounded string route with spider/cobweb risk handling.
- [ ] Add glass route through sand smelting.
- [ ] Add terracotta route through clay smelting or biome search.
- [ ] Add safe ore routes for gold, redstone, lapis, diamond, emerald, and quartz.
- [ ] Explain missing material reason to the user when route cannot proceed.

## P2: Navigation

- [x] Short-range path-aware movement for blocks, drops, workstations, torches, retreat, and village-hint exploration.
- [x] Open nearby doors and fence gates when they block a local path.
- [ ] Cave navigation with light, retreat, and fall-risk checks.
- [ ] Village internal navigation between hint blocks.
- [ ] Long-range exploration with breadcrumb memory and return safety.

## P2: Survival Safety

- [x] Stop/replan on lava, fire, fall risk, low health, and close hostile mobs.
- [x] Bound replans to 5.
- [x] Build a bounded emergency solid-block shelter under hostile pressure or fall-risk recovery.
- [x] Add bounded water/lava/fire escape action beyond stop-and-replan.
- [x] Check tool durability before selected harder mining batches.
- [ ] Add configurable policy for hunting, harvesting, dropping items, and village farms.
- [ ] Tune shelter shape, fluid escape steering, and door/gate interaction from real-world play traces.
