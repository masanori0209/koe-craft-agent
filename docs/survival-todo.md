# Survival TODO

## P0: Bounded Safety

- [x] Stop on user interrupt, lava/fire, fall risk, low health.
- [x] Re-check immediate danger during long-running actions.
- [x] Bound replanning to 5 attempts.
- [x] Add active bounded shelter creation for nearby hostile pressure or fall-risk recovery.
- [x] Add lava/water/fire escape patterns beyond stop-and-replan.
- [ ] Tune shelter and fluid escape against real terrain.

## P1: Survival Continuity

- [x] Food recovery from inventory.
- [x] Mature crop harvest and replant.
- [x] Hunting, meat drop pickup, and cooking.
- [x] Short-range path-aware movement.
- [x] Tool suitability before mining.
- [x] Inventory-full recovery with low-value item dropping.
- [x] Shield/weapon/armor equip action.
- [x] Shield and basic weapon/tool craft routes.
- [x] Armor craft routes for leather, iron, golden, and diamond armor.
- [x] Generic wood-family craft route selection.
- [x] Generic color/material/stone-family craft route selection.
- [x] Distance-bounded movement with local assist for sprint-jump, support placement, and tool-aware digging.
- [x] Tool durability checks before selected harder collection batches.
- [ ] Extend durability policy to combat and all long collection batches.

## P2: Navigation

- [x] Path-aware approach for drops, blocks, workstations, torches, and retreat.
- [x] Bounded village search exploration with random unvisited cardinal sectors.
- [x] Door/fence-gate interaction for local movement assist.
- [ ] Long-range exploration route.
- [ ] Village/cave navigation.

## P2: Nether Preparation

- [x] Detect `ネザーに行きたい` / nether portal preparation intent.
- [x] Plan bucket-portal preparation kit: bucket, flint and steel, iron acquisition, flint acquisition.
- [x] Smelt raw iron into iron ingots through furnace UI.
- [x] Bucket liquid interaction: collect water/lava source blocks.
- [x] Direct obsidian frame construction from 14 obsidian.
- [x] Experimental lava-cast portal frame construction with water/lava placement.
- [x] Flint and steel portal ignition.
- [ ] Tune lava-cast placement against real terrain and lava pools.
- [ ] Enter portal and stop after dimension transition scan.
- [ ] Nether arrival safety scan for lava, fire, fall risk, and hostile mobs.

## P3: Ownership and Server Etiquette

- [x] Village farm harvest permission flag.
- [ ] Distinguish village farms from player-built farms when server context exposes ownership.
- [ ] Configurable allow/deny policy for hunting, harvesting, and dropping items.

## Current Focus

Implemented expanded deterministic crafting routes without Minecraft commands:

1. Added recipe metadata for survival basics: tools, armor, shield, chest, ladder, bowl, bow, arrows, fishing rod, bucket, shears, flint and steel, bed, and wood-family blocks/items.
2. Added generic craft parsing so phrases like `ボート作って`, `剣作って`, `ベッド作って`, and `石の階段作って` resolve to concrete variants from inventory or nearby materials.
3. Extended the Fabric executor craft path for shaped tool, armor, wood-family, and practical early-survival recipes.
4. Kept all craft execution deterministic through known recipe layouts instead of LLM-only recipe solving.

Next focus:

1. Close the remaining executor gaps listed in [Crafting Coverage TODO](crafting-coverage-todo.md).
2. Tune lava-cast portal construction in real Minecraft terrain and add explicit enter-portal confirmation.
3. Tune lava-cast, shelter, and fluid escape from real Minecraft traces.
4. Add acquisition routes for missing high-risk materials such as string, dyes, glass, terracotta, and diamond.

See also [Practical Readiness TODO](practical-readiness-todo.md) for the cross-cutting voice runtime, LLM fallback, nether, material route, navigation, and survival safety checklist.
