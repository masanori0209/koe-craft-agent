# Survival Gaps

## Added Now

The executor now exposes survival danger signals from `scan_state`:

- low health
- low hunger
- hostile mob nearby
- lava/fire nearby
- fall risk below player
- inventory full

The Agent Safety Filter treats these as interruption conditions before executing non-read-only action batches.

Food recovery has also started:

- `お腹すいた`, `食料ほしい`, `食べ物ほしい` -> `get_food`
- if food is in inventory, run `eat_food`
- if no food is available, prefer nearby non-combat food sources such as berries or apple-bearing leaves
- harvest only mature crops
- after crop harvest, replant reachable empty farmland when seeds are available
- skip village-like farms unless `allow_village_farm_harvest` is true
- if no non-combat source is available, hunt nearby food animals with bounded melee attacks
- after hunting, collect nearby raw meat/fish drops with `collect_drops`
- if furnace access and fuel are visible, cook raw meat/fish with `smelt_food`
- if wheat is already available, craft `minecraft:bread` before eating
- `hunger_below_6` is no longer a hard abort by itself; it should trigger food recovery instead

Combat support is now available for explicit combat utterances:

- `敵を倒して`, `戦って` -> `attack_entity` with `entity_group: "hostile"`
- hostile combat is bounded by entity group, count, radius, reach, and timeout
- if sword/axe/shield/armor are already available, run `equip_gear` before hostile combat
- if a hostile mob is very close or the player is weak, run `defensive_move` before attacking
- if hunger is low and food is available, eat before attacking
- command-based combat is still forbidden

Path-aware movement is now shared by several executor actions:

- `collect_drops`: short waypoint path to nearby item entities
- `collect_block`: approach matching blocks before breaking when they are outside reach
- `defensive_move`: choose a safe retreat waypoint away from a hostile mob
- `open_workstation`: approach existing crafting tables or furnaces before interacting
- `place_block` for torches: approach nearby dark floor placement before placing

Long-running actions now re-check immediate danger:

- block breaking
- path-aware block approach
- drop pickup movement
- furnace output waiting
- eating

If low health, nearby lava/fire, or fall risk appears, the action stops with `danger_stop: true` and returns to the bounded replanning loop.

Tool suitability is enforced before block breaking:

- stone, deepslate, ores, cobblestone, and similar blocks require an available pickaxe
- ore tiers are bounded: iron/copper need stone+, gold/redstone/lapis/diamond/emerald need iron+, obsidian needs diamond+
- logs/stems prefer axes, dirt/sand/gravel/clay prefer shovels when available

Deterministic crafting coverage now includes practical early-survival recipes:

- wooden, stone, iron, golden, and diamond tools for pickaxe, axe, shovel, sword, and hoe
- leather, iron, golden, and diamond armor
- shield, chest, ladder, bowl, bow, arrows, fishing rod, bucket, shears, flint and steel, and white bed
- wood-family recipes for planks, buttons, pressure plates, slabs, stairs, doors, trapdoors, fences, fence gates, signs, hanging signs, boats, and chest boats
- bamboo raft/chest raft, color-family, tool-material, armor-material, and stone-family targets are resolved by the Agent recipe resolver
- generic wood target resolution, for example `ボート作って` resolves to the available wood family before execution
- existing basics such as planks, sticks, crafting table, furnace, torch, charcoal, stone pickaxe, and bread
- executor-side slot layouts and material checks are hardcoded; LLM output still cannot invent executable recipes

## Next Practical Survival Elements

These are the next useful layers to add, in this order:

1. Emergency retreat
   - add active shelter creation when night or multiple hostile mobs are detected
   - connect shield craft route to repeated hostile danger planning
   - add water/lava escape patterns beyond stopping/replanning

2. Inventory handling
   - detect nearly full inventory before collection
   - prefer dropping low-value blocks only with explicit allowlist
   - never drop tools, food, ore, crafted workstations, or torches

3. Better food procurement
   - optionally craft or place a furnace when cooking is useful but no furnace is visible
   - distinguish naturally generated village farms from player-built farms when the server context can provide ownership

4. Shelter and sleep
   - detect night/danger
   - locate or craft bed only if wool/wood route is safe
   - avoid long-range wandering

5. Tool suitability
   - expand required-tool/tier table for more vanilla blocks
   - account for tool durability before starting long collection batches
   - choose Silk Touch/Fortune preference when enchantment data is exposed

6. Crafting coverage
   - add acquisition routes for missing iron, string, feather, flint, wool, leather, and diamond when the immediate craft materials are absent
   - add recipe resolver data export for broader vanilla coverage instead of manually maintaining every shaped layout
   - keep executable recipes limited to deterministic resolver data and Safety Filter validation

## Loop Rule

Survival handling should remain bounded:

```text
scan -> detect need -> plan finite route -> execute -> rescan -> at most 5 replans
```

No survival behavior should recursively call itself without:

- max replan count
- repeated blocked-signature stop
- danger stop
- user interrupt stop
