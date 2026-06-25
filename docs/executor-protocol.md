# Executor Protocol

## Agent -> MOD

The Agent sends one WebSocket JSON message:

```json
{
  "type": "execute_actions",
  "request_id": "req_...",
  "goal": "place_torch",
  "actions": [
    {
      "type": "scan_state",
      "radius": 12,
      "include_blocks": true,
      "include_entities": true
    },
    {
      "type": "place_block",
      "item": "minecraft:torch",
      "target": {
        "kind": "nearby_dark_spot"
      }
    }
  ]
}
```

The Agent must run the Safety Filter before sending this request.

## MOD -> Agent

The MOD responds:

```json
{
  "type": "execute_result",
  "request_id": "req_...",
  "ok": true,
  "steps": [
    {
      "type": "scan_state",
      "status": "accepted",
      "message": "Scanned nearby player state.",
      "data": {
        "player": {
          "health": 20.0,
          "hunger": 20,
          "dimension": "minecraft:overworld",
          "position": [0.0, 64.0, 0.0]
        },
        "radius": 12,
        "light_level": 10,
        "nearby_blocks": [],
        "nearby_entities": []
      }
    },
    {
      "type": "place_block",
      "status": "accepted",
      "message": "Placed minecraft:torch using the current crosshair block target."
    }
  ]
}
```

## Safety Rules

The MOD scaffold also rejects request text containing:

- `/give`
- `/fill`
- `/setblock`
- `/tp`
- `/summon`
- `/kill`

On abort, disconnect, client stopping, or websocket error, the executor releases movement/use keys.

## Current Implementation Status

Implemented:

- WebSocket listener scaffold.
- JSON request parsing with request id and action body extraction.
- Banned command text rejection.
- Abort/key release hook.
- Step result response.
- `scan_state` for bounded nearby block/entity/player scanning.
- `look_at` for coordinate aiming.
- `break_block` with survival block breaking and completion monitoring.
- `collect_block` with reachable target selection, repeated block breaking up to `count`, and partial result reporting.
- `ensure_hotbar` for moving required items from inventory to the hotbar before placement or workstation use.
- `take_from_container` for optionally checking nearby chests/barrels before mining or crafting missing materials.
- `open_workstation` for nearby or placeable crafting tables, furnaces, stonecutters, and smithing tables.
- `close_screen` for leaving handled screens before movement or another UI action.
- `place_block` for `minecraft:torch` when the torch is in the hotbar. `crosshair_block` uses the current block face, and `nearby_dark_spot` searches for a reachable dark floor placement.
- `craft` for the currently planned survival recipes using inventory/crafting-table slot operations.
- `smithing_trim` for armor trim smithing-table slot operations, confirmed by item component delta rather than output item id delta.
- `component_craft` for allowlisted special crafting recipes whose result is defined by item components.
- `smelt` for charcoal using furnace slot operations.
- `collect_fluid` for collecting nearby water/lava source blocks with an empty bucket.
- `build_nether_portal` for direct obsidian frame placement and experimental lava-cast frame construction.
- `ignite_nether_portal` for lighting a nearby completed obsidian frame with flint and steel.

Not implemented:

- Fully generic execution for arbitrary vanilla recipes is guarded by the planner safety allowlist, but the MOD now scans runtime RecipeBook entries and prefers RecipeBook quick craft for supported `craft` actions before falling back to bundled shaped/shapeless, stonecutting, smithing-transform, or component-verified armor-trim layouts.
- Full world-scale pathfinding. Current movement is local, bounded, and safety-checked.

## `scan_state`

The old TypeScript CLI for sending `scan_state` has been removed. Use this JSON shape when testing through a future MOD-side debug entrypoint or a direct WebSocket client.

Limits:

- `radius`: 1..32
- block samples: max 512
- entity samples: max 64
- vertical block scan: at most +/- 8 blocks

The scan is a read-only survival-safe operation. It does not use Minecraft commands.

`scan_state` includes lightweight diagnostics for tuning voice latency:

- `scan_elapsed_ms`: time spent building the scan payload inside the executor.
- `scan_json_bytes_estimate`: UTF-8 byte estimate of the returned scan payload.

Every executor step also includes timing diagnostics in its `data` object when available:

- `action_elapsed_ms`: time spent executing the action body.
- `step_total_elapsed_ms`: total bounded step time including snapshots, watchdog checks, and recovery.
- `recovery_elapsed_ms` / `retry_action_elapsed_ms`: present when a recovery path ran.

`scan_state` also returns survival danger signals:

```json
{
  "dangerous_conditions": [
    "hunger_below_6",
    "hostile_mob_within_5_blocks",
    "lava_within_2_blocks",
    "in_lava",
    "in_water",
    "fire_within_2_blocks",
    "fall_risk_detected",
    "inventory_full"
  ]
}
```

Current checks:

- `health_below_8`: player health is below 8.
- `hunger_below_6`: hunger is below 6.
- `hostile_mob_within_5_blocks`: common hostile mob ids are within 5 blocks.
- `lava_within_2_blocks`: lava, fire, or soul fire is within 2 blocks.
- `in_lava`: the player's feet or head block is lava.
- `in_water`: the player's feet or head block is water.
- `fire_within_2_blocks`: the player's current block is fire or soul fire.
- `fall_risk_detected`: several air blocks are directly below the player.
- `inventory_full`: the main inventory/hotbar has no empty slot.

These conditions are fed back into the Agent safety loop. Most non-read-only actions are interrupted when dangerous conditions are present. `hostile_mob_within_5_blocks` can also be handled by an explicit bounded `attack_entity` action with `entity_group: "hostile"`.

For crops, `nearby_blocks` includes maturity metadata when available:

```json
{
  "block": "minecraft:wheat",
  "crop": true,
  "crop_age": 7,
  "crop_max_age": 7,
  "crop_mature": true
}
```

`collect_block` with `block_group: "crop"` only targets mature crops or harvestable food blocks. This prevents the food route from destroying immature crops.

`scan_state` also marks nearby entities with food/combat metadata:

```json
{
  "type": "minecraft:cow",
  "distance": 4.0,
  "food_animal": true,
  "hostile": false
}
```

## `look_at` and `break_block`

`look_at` turns the player view toward a target coordinate.

```json
{
  "type": "look_at",
  "target": { "x": 10, "y": 64, "z": 10 },
  "max_degrees_per_tick": 180
}
```

`break_block` starts breaking either the current crosshair block or an explicit coordinate target.

```json
{
  "type": "break_block",
  "target": { "kind": "crosshair_block" },
  "max_distance": 5,
  "timeout_ticks": 80
}
```

The old TypeScript action CLI has been removed. Keep examples in this document as Action JSON contracts rather than runnable CLI commands.

Current implementation note: `break_block` starts block breaking using survival interaction and keeps updating block breaking progress until the block becomes air, the timeout is reached, or the request is aborted.

## `collect_block`

`collect_block` finds the nearest matching block by exact block id or block group, uses the bounded local waypoint planner to move within survival reach when needed, then breaks it with survival interaction.

When the target block is below the player and a flat approach cannot reach it, block approach can dig a bounded downward stair step toward the target. The assist still uses normal survival breaking, tool selection, fluid checks, headroom checks, and progress bounds; traces expose this as `strategy: "downward_stair_dig"`.

```json
{
  "type": "collect_block",
  "block_group": "dirt",
  "count": 1,
  "search_radius": 12
}
```

The old TypeScript action CLI has been removed. Use the JSON example above as the protocol contract.

Current block groups include:

```text
dirt, log, leaves, stone, cobblestone, sand, gravel, clay,
coal_ore, iron_ore, copper_ore, gold_ore, redstone_ore,
lapis_ore, diamond_ore, emerald_ore, ore, light_source,
village_hint, crop
```

Current implementation note: `collect_block` repeats path-aware approach plus block breaking until `count` is reached or no matching block can be reached. Approach may use short bridge/step assists, blocking-step digging, or downward stair digging when needed. It reports `accepted`, `partial`, `blocked`, or `aborted` with `approached_count`, `broken_count`, and `broken_blocks`.

## `dig_pattern`

`dig_pattern` executes a user-requested digging style directly, without requiring a target block name. It is intended for voice commands such as `階段掘りして`, `横穴掘って`, and `安全に縦掘りして`.

Supported patterns:

- `stair_down`: dig one step forward and one block downward, then move onto the new landing.
- `tunnel_forward`: clear a two-block-high forward passage, then move forward.
- `shaft_down_safe`: safe downward digging implemented as bounded stepwise descent, not direct digging under the player.

```json
{
  "type": "dig_pattern",
  "pattern": "stair_down",
  "steps": 4,
  "direction": "forward",
  "timeout_ticks": 560
}
```

## `celebrate`

`celebrate` plays a short non-world-modifying reaction for positive voice
utterances such as `やったー`, `いえーい`, and `おおーすごい`. The action uses
third-person presentation, bounded jump/sneak/side-step/key-release sequences,
and returns to first-person through the executor's existing camera restore path.

```json
{
  "type": "celebrate",
  "style": "youtuber_pose",
  "duration_ticks": 70,
  "third_person": true
}
```

Safety limits:
- `style` is one of `youtuber_pose`, `dance`, or `cheer`.
- `duration_ticks` is clamped by the Safety Filter to 20..120.
- The executor does not place blocks, attack entities, or use Minecraft commands.

The executor selects required tools through normal survival logic, refuses unsafe fluid/headroom/support situations, stops on immediate survival danger, and reports per-step results.

## Blocked Reaction

When a step ends with `blocked`, the executor makes the stop visible before
returning the terminal result. It switches to third-person presentation, plays a
short sneak-based sad shuffle, shows the bubble text
`すみませんが、できないです。`, and lets the existing TTS bridge read the same
message when TTS is enabled. It also publishes a voice-intervention choice
bubble with three short utterances. The user can say one of those choices as the
next command, for example `右にずれて`, `手前を掘って`, or `一回やめて`; crafting
and workstation failures prefer `作業台を近くに置いて` as the first choice.
The choice HUD pops after the estimated TTS readout finishes. The reaction is
presentation-only: it does not
place blocks, grant items, attack entities, or use Minecraft commands. If the
local left/right step check says the footing is unsafe, the reaction keeps the
player crouched in place and only moves the view.

The step result includes:

```json
{
  "blocked_reaction": {
    "style": "sad_sneak_shuffle",
    "message": "すみませんが、できないです。",
    "played": true,
    "side_step_ticks": 24
  },
  "intervention_choices": [
    { "utterance": "右にずれて", "hint": "move_right" },
    { "utterance": "手前を掘って", "hint": "dig_front" },
    { "utterance": "一回やめて", "hint": "abort" }
  ]
}
```

## `collect_fluid`

`collect_fluid` finds a nearby source block for water or lava, moves within survival reach, selects an empty bucket, interacts with the source, and verifies that the corresponding filled bucket count increased.

```json
{
  "type": "collect_fluid",
  "fluid": "minecraft:lava",
  "bucket_item": "minecraft:bucket",
  "search_radius": 12
}
```

Safety limits:

- `fluid` must be `minecraft:water` or `minecraft:lava`.
- `bucket_item` must be `minecraft:bucket`.
- `search_radius` is clamped by the Safety Filter to 1..16.
- Lava collection is still proximity-limited; wider lava-pool navigation should be handled by repeated scan/replan rather than an unbounded chase.

## `build_nether_portal` and `ignite_nether_portal`

`build_nether_portal` supports two construction methods:

- `obsidian_frame`: places a stable full 4x5 frame from 14 `minecraft:obsidian` items.
- `lava_cast`: experimental route that repeatedly collects lava, places lava at frame positions, places water above it, waits for obsidian conversion, and collects the water back.

```json
{
  "type": "build_nether_portal",
  "method": "obsidian_frame",
  "timeout_ticks": 400
}
```

```json
{
  "type": "build_nether_portal",
  "method": "lava_cast",
  "timeout_ticks": 400
}
```

`obsidian_frame` intentionally requires 14 obsidian instead of the speedrun-style 10-block frame. The extra corner blocks let the executor use a bounded, deterministic placement order without relying on temporary scaffolding.

`lava_cast` requires a water bucket and reachable lava sources. It is marked experimental because real terrain around lava pools varies a lot; the executor verifies every lava placement, water placement, and obsidian conversion and returns `blocked` instead of pretending success when the cast fails.

After a frame exists, `ignite_nether_portal` selects flint and steel, finds a nearby air block inside an obsidian frame, interacts, and verifies that a `minecraft:nether_portal` block appears.

```json
{
  "type": "ignite_nether_portal",
  "item": "minecraft:flint_and_steel",
  "search_radius": 8
}
```

Current policy: entering the portal is not automatic. The planner stops after ignition unless the user explicitly asks to enter, so `ネザーに行きたい` can still preserve the player's game experience instead of taking over the whole milestone.

## `place_block` Current Limits

The first executable operation is intentionally narrow:

- `item` must be `minecraft:torch`.
- `target.kind` must be `nearby_dark_spot` or `crosshair_block`.
- A torch must already be in the hotbar.
- `crosshair_block` requires the crosshair target to be within survival reach.
- `nearby_dark_spot` scans nearby air blocks with light level 7 or below and places on a reachable solid floor support.

## `scan_build_area` and `build_blueprint`

`scan_build_area` is a read-only build-site summary scan. It samples up to 8 chunks around the player and returns chunk-level safe/flat/fluid sample counts instead of sending every block to the LLM.

```json
{
  "type": "scan_build_area",
  "radius_chunks": 8,
  "samples_per_chunk": 4
}
```

`build_blueprint` constructs a bounded small house from a deterministic blueprint. The planner may derive style/size/palette from LLM Goal JSON, but the executor owns the concrete block positions and rejects unsupported styles.

In default `assistMode=world_assist`, house building is terrain-aware and animated: the executor chooses a nearby mostly-flat 5x5 site, avoids immediate hazards and occupied spaces, then places common building blocks one-by-one with a short tick delay. This uses the MOD/server API, not Minecraft slash commands. The legacy `programmatic` setting value is still accepted as an alias. When world assist is disabled, the action falls back to the older survival-inventory placement path.

```json
{
  "type": "build_blueprint",
  "style": "small_house",
  "size": "tiny",
  "palette": "available",
  "max_blocks": 96,
  "terrain_aware": true,
  "programmatic_build": true,
  "animated": true,
  "animation_delay_ticks": 2
}
```

Current limits:

- Styles are `small_house`, `cute_house`, and `hideout`.
- The blueprint is intentionally bounded; large houses, towns, and castles must be decomposed into future smaller steps.
- Programmatic house blocks are limited to common low-value building blocks such as oak planks, cobblestone, glass, dirt, and sandstone.
- Missing materials no longer block the default house experience; the trace records `world_assist` plus legacy `programmatic_build` / `programmatic_assist` style metadata.

## `craft` and `smelt`

## `take_from_container`

When a plan is missing materials, it can first check nearby survival containers before falling back to mining, harvesting, or crafting.

```json
{
  "type": "take_from_container",
  "item": "minecraft:cobblestone",
  "count": 3,
  "search_radius": 8,
  "optional": true
}
```

## `grant_item`

`grant_item` is an explicit world-assist action for "voice-controlled Minecraft" play. It does not send Minecraft slash commands; the MOD inserts a bounded item stack into the integrated-server player inventory and records `world_assist: true` / `voice_assist: true` in the step trace. The feature can be disabled with `koecraft.worldAssist.allowCommonMaterialTopUp=false` or legacy `koecraft.executor.voiceAssistEnabled=false`.

```json
{
  "type": "grant_item",
  "item": "minecraft:dirt",
  "count": 32,
  "min_count": 32,
  "optional": true
}
```

Current policy:

- The action accepts exact `minecraft:*` item ids only.
- The grant count is bounded by the action count and executor config.
- Optional grants are best-effort. If the integrated server is unavailable, inventory is full, or the feature is disabled, the step is recorded and the original plan continues.
- Current planner usage is intentionally bounded: utility support blocks for bridges, village exploration, small shelters, tiny house blueprints, and non-rare recipe resources that would otherwise cause a partial stop.
- Rare/progression items are not granted by default. This includes diamond/netherite/debris/obsidian, blaze/ender/shulker/wither/dragon related items, mob heads/skulls, smithing templates, music discs, keys, nether stars, heavy cores, and enchanted golden apples.
- Admin/creative-only blocks and items such as bedrock, barrier, command blocks, structure blocks, jigsaw, debug stick, knowledge book, spawner, and reinforced deepslate are always rejected even if rare-item assist is manually enabled.
- The LLM must not output slash commands. Any assist must be represented as Action DSL and still pass the Executor safety checks.

Before placing or using an item, the Agent can ensure it is available in the hotbar:

```json
{
  "type": "ensure_hotbar",
  "item": "minecraft:torch",
  "count": 1
}
```

Executor behavior:

- If the item is already in the hotbar, it selects that slot.
- If the item exists elsewhere in inventory, it swaps it into an empty hotbar slot.
- If the hotbar is full, it swaps into the currently selected hotbar slot and reports that a stack was displaced.
- If the item does not exist, it returns `blocked`; it does not create items or call commands.

Before `craft` or `smelt`, the Agent can ask the MOD to open a workstation:

```json
{
  "type": "open_workstation",
  "station": "minecraft:furnace",
  "search_radius": 12,
  "allow_place": true,
  "avoid_occupied": true
}
```

Executor behavior:

- If the correct screen is already open, the action succeeds.
- It searches for a reachable nearby `minecraft:crafting_table`, `minecraft:furnace`, `minecraft:stonecutter`, or `minecraft:smithing_table`.
- If `avoid_occupied` is true, it skips stations with another player within roughly 2 blocks. This is a multiplayer-friendly heuristic because the client cannot reliably know another player's open screen.
- If no available station is found and `allow_place` is true with `assistMode=world_assist`, it first chooses a safe nearby floor spot biased toward the player's current facing direction, places the supported workstation through the bounded MOD/server helper, consumes the matching inventory item when configured, then faces and opens it. This avoids depending on fragile right-click placement camera geometry.
- If world-assist placement is unavailable, it falls back to selecting the station in the hotbar, synchronizing the selected slot to the server, placing it via normal item use, and opening it.
- After placement, it waits briefly for the target block and the expected screen handler to settle instead of immediately starting the next craft action. The trace records `block_settle_ticks` and `open_wait_ticks`.
- The step trace records `world_assist_workstation_placement` for the primary world-assist path, and may still record the legacy `programmatic_workstation_fallback` field when the normal item-use fallback needs a final rescue. This does not use Minecraft slash commands.
- If the item is not in the hotbar or the screen does not open, it returns `blocked`.

The Agent uses `close_screen` before movement/block breaking and before switching from crafting table to furnace. `close_screen` waits until the handled screen actually closes and records `close_wait_ticks`.

`craft` performs survival UI slot operations for supported deterministic recipe targets:

- RecipeBook quick craft when a matching unlocked runtime entry is available.
- Bundled vanilla shaped/shapeless recipe layouts when inventory or crafting-table slots are open.
- Bundled vanilla stonecutting layouts when a stonecutter screen is open.
- Bundled vanilla smithing transform layouts when a smithing table screen is open.
- Allowlisted legacy slot layouts for core survival recipes as a fallback.
- With `assistMode=world_assist` and `koecraft.worldAssist.allowDirectCraft=true`, deterministic vanilla crafting may bypass fragile UI slot interactions through the integrated-server inventory API. If a normal UI craft stops because the screen, cursor stack, output slot, or ingredients are not usable, the executor retries once as `world_assist_direct_recipe`.
- In that fallback, missing non-rare common ingredients can be generated and immediately consumed as recipe inputs when `koecraft.worldAssist.allowCommonMaterialTopUp=true`. The output is inserted into inventory, `generated_ingredients` is recorded, and no Minecraft slash command is used.
- The Minecraft HUD shows a short `World Assist` card for material top-up and direct craft fallback so the player can see when helper behavior was used.

Example Action JSON:

```json
{ "type": "craft", "recipe": "minecraft:stone_pickaxe", "count": 1 }
```

Execution constraints:

- Inventory, crafting table, stonecutter, or smithing table should already be open. If a crafting-table recipe starts immediately after `open_workstation`, `craft` waits a few ticks for the crafting screen handler before deciding it is blocked.
- Stonecutting and smithing plans set `expected_station` on the `craft` action. The executor waits for that station screen before treating the recipe as blocked.
- Result-slot reads use bounded output waits instead of fixed sleeps. Output pickup is confirmed by inventory count or equivalent component-stack count where applicable.

## Timing-Sensitive Interaction Guardrails

The following interactions must not assume the next tick is already stable:

- Workstation placement/opening: wait for block id and expected screen handler.
- Container supply: wait for `GenericContainerScreenHandler` after chest/barrel interaction.
- Furnace smelting: wait for `AbstractFurnaceScreenHandler` before slot insertion and confirm output by inventory delta.
- Stonecutter/smithing/component crafting: wait for output slot and confirm output pickup.
- Fluid collection and block placement: wait for the filled bucket count or target block id.
- Screen close: wait until the handled screen is gone before movement or block interaction.
- Crafting input slots must be empty.
- Cursor stack must be empty.
- Required materials must be in inventory.
- The executor refuses to overwrite occupied crafting slots.

`component_craft` handles allowlisted special crafting recipes that cannot be confirmed by output item id alone. The action explicitly supplies the recipe kind, output item, and deterministic ingredient item ids. The executor places the ingredients into inventory/crafting-table input slots, snapshots the relevant component, checks the output slot, quick-moves the output, and confirms the matching item+component stack count increased.

For `map_extending`, pass ingredients as `filled_map` followed by eight `paper` entries; the executor places the map in the 3x3 center slot and papers around it.

Supported `recipe_kind` values:

- `armor_dye` -> `minecraft:dyed_color`
- `banner_duplicate` -> `minecraft:banner_patterns`
- `map_cloning` -> `minecraft:map_id`
- `map_extending` -> `minecraft:map_post_processing`
- `firework_rocket` -> `minecraft:fireworks`
- `firework_star` -> `minecraft:firework_explosion`
- `firework_star_fade` -> `minecraft:firework_explosion`

Example Action JSON:

```json
{
  "type": "component_craft",
  "recipe_kind": "armor_dye",
  "output": "minecraft:leather_chestplate",
  "ingredients": ["minecraft:leather_chestplate", "minecraft:red_dye"],
  "source_item": "minecraft:leather_chestplate"
}
```

`smithing_trim` is separated from `craft` because armor trim keeps the same output item id as the base armor. The executor requires an open smithing table, places template/base/addition into slots 0/1/2, snapshots the base armor component state, then checks slot 3 for:

- the same item id as `base`
- changed component signature
- changed non-empty `minecraft:trim` component
- a matching item+component stack in inventory after quick-moving the output

Example Action JSON:

```json
{
  "type": "smithing_trim",
  "template": "minecraft:sentry_armor_trim_smithing_template",
  "base": "minecraft:iron_chestplate",
  "addition": "minecraft:amethyst_shard"
}
```

Special crafting policy:

- normal recipes that create more output items are confirmed with inventory item-count delta
- recipes whose output identity depends on components are confirmed with output-slot component snapshots and `ItemStack.areItemsAndComponentsEqual`
- armor trim uses the `minecraft:trim` component as the first implemented component-delta path
- dyed armor, banner duplicate, map cloning/extending, and firework special recipes use `component_craft`
- future special recipes such as written books, repair item, shield decoration, tipped arrows, and decorated-pot style outputs should use the same component-delta result contract instead of only checking item id

`smelt` currently supports the planned charcoal route:

```json
{ "type": "smelt", "input": "minecraft:oak_log", "fuel": "minecraft:oak_planks", "output": "minecraft:charcoal" }
```

Execution constraints:

- A furnace screen must already be open.
- Furnace input and fuel slots must be empty.
- Input and fuel must be in inventory.
- The executor waits for the output slot and quick-moves the output into inventory.

## `eat_food`

`eat_food` chooses a supported food item from inventory, moves it to the hotbar, uses it, and waits until hunger reaches the requested threshold or the timeout is reached.

```json
{
  "type": "eat_food",
  "min_hunger": 14,
  "timeout_ticks": 160
}
```

Example Action JSON:

```json
{ "type": "eat_food", "item": "minecraft:bread", "min_hunger": 14, "timeout_ticks": 160 }
```

Current food priority includes bread, cooked meats/fish, baked potatoes, apples, carrots, berries, beetroots, potatoes, and raw meats/fish as a fallback.

Food procurement priority is:

1. Eat food already in inventory.
2. Craft bread from available wheat.
3. Collect nearby non-combat food sources such as sweet berries, glow berries, or apple-bearing leaves.
4. Harvest mature crops only, then run `replant_crop` when supported seeds are available.
5. Hunt nearby food animals with bounded melee attacks.
6. Collect nearby food drops after hunting.
7. Cook raw meat/fish when furnace access and fuel are available.

Village-like farms are permission-gated in the Agent state. If `village_hint` blocks such as composters or dirt paths are nearby, crop harvesting is skipped unless `allow_village_farm_harvest` is true.

## `replant_crop`

`replant_crop` finds reachable empty farmland, moves an available seed item to the hotbar, and plants it with a survival right-click interaction.

```json
{
  "type": "replant_crop",
  "seed_group": "crop_seed",
  "count": 3,
  "search_radius": 8
}
```

Supported seed items include wheat seeds, carrots, potatoes, beetroot seeds, pumpkin seeds, and melon seeds.

## `attack_entity`

`attack_entity` attacks only a bounded entity group:

```json
{
  "type": "attack_entity",
  "entity_group": "food_animal",
  "count": 1,
  "search_radius": 12,
  "max_distance": 4.5,
  "timeout_ticks": 160
}
```

Supported groups:

- `food_animal`: cows, mooshrooms, pigs, chickens, sheep, rabbits, cod, salmon.
- `hostile`: common hostile mobs detected by `scan_state`.

It does not use commands, does not spawn entities, and does not chase beyond the bounded radius/reach.

After food-animal hunting, the planner adds `collect_drops` for raw meat/fish. If furnace access and fuel are visible in state, it then opens a furnace and runs `smelt_food`.

## `collect_drops`

`collect_drops` collects matching nearby item entities and confirms collection by item disappearance or inventory count increase. For responsive voice play, planner-created pickup actions enable `magnet_fallback` and `magnet_only` by default: the executor pulls nearby drops toward the player and skips walking/sweep movement. This keeps `拾って` and post-mining pickup from wandering around after the item magnet can already handle the drop.

If an action explicitly sets `"magnet_only": false`, the executor may fall back to bounded short movement toward matching item entities. That movement uses a local waypoint planner plus steering: it searches a short standable path around the player, follows the next waypoint, then samples forward, diagonal, side, and fallback directions each tick. It avoids blocked feet/head space, unsafe fluids, unsupported drops, and one-block obstacles when no safe step-up is available. The magnet path does not use Minecraft commands; it is an explicit assist behavior for nearby dropped items.

```json
{
  "type": "collect_drops",
  "item_group": "food_drop",
  "count": 1,
  "search_radius": 8,
  "timeout_ticks": 120,
  "magnet_fallback": true,
  "magnet_only": true
}
```

`food_drop` covers raw beef, porkchop, chicken, mutton, rabbit, cod, and salmon.

This is not full long-range navigation. It is intended for short post-hunt pickup near the player, especially around shallow obstacles, small slopes, water edges, fences, and holes.

## `move`

`move` is a bounded short-distance movement action for voice commands such as `歩いて`, `前に3ブロック歩いて`, and `ダッシュで進んで`.

The Agent supplies both a time bound and an optional `distance_blocks` target. The executor closes an open handled screen before movement, samples immediate step safety, tracks progress toward the distance target, and releases keys at the end.

Planner-created voice moves are scan-aware by default. Before pressing movement keys, the executor samples the local lane ahead, can rotate the camera toward `left` / `right` / `back` requests and then move forward, shortens the movement when a cliff/fluid/blockage appears, and can extend short vague commands such as `まっすぐ進んで` when the lane is clearly safe. One-block step-up terrain triggers bounded jump movement instead of stopping immediately.

If normal movement is unsafe or progress stalls, it can try bounded movement assist:

- sprint-jump over a short gap when the landing samples are safe
- open a nearby closed door or fence gate that is blocking the local path
- place a conservative solid support block when one is available
- dig a blocking step block after selecting the required tool when available
- fall back to local path-aware steering for a few corrections

```json
{
  "type": "move",
  "direction": "forward",
  "duration_ticks": 40,
  "distance_blocks": 4,
  "sprint": false,
  "scan_aware": true,
  "adaptive_distance": true,
  "extend_if_clear": true,
  "face_move_direction": true,
  "auto_jump": true,
  "allow_assist": true,
  "allow_place": true,
  "allow_dig": true,
  "allow_sprint_jump": true
}
```

This is still local movement, not long-range route finding. It does not place unlimited bridges or dig tunnels indefinitely; blocked results include assist attempt details so the bounded replan loop can rescan and choose a next step.

## `open_passage`, `check_tool_durability`, `emergency_shelter`, and `escape_fluid`

These actions target common real-world failure points rather than broad autonomy.

`open_passage` opens a nearby closed door or fence gate. It is used both as a direct action before simple movement and inside movement assist for block/drop approach.

```json
{
  "type": "open_passage",
  "target_group": "door_or_gate",
  "search_radius": 4,
  "timeout_ticks": 80
}
```

`check_tool_durability` checks the best matching tool before selected harder mining batches. It reports `blocked` when the best available tool is below the requested durability threshold.

```json
{
  "type": "check_tool_durability",
  "tool_group": "pickaxe",
  "min_remaining": 10,
  "optional": false
}
```

`emergency_shelter` builds a small bounded solid-block shell around/above the player from available survival blocks. It is intended for hostile pressure or fall-risk recovery, not permanent building.

```json
{
  "type": "emergency_shelter",
  "material_group": "solid_block",
  "radius": 2,
  "timeout_ticks": 240
}
```

`escape_fluid` searches for nearby safe ground and steers toward it. If needed and allowed, it can place one emergency support block from inventory. It is used for water/lava/fire recovery and remains bounded by radius and timeout.

```json
{
  "type": "escape_fluid",
  "strategy": "nearest_safe_ground",
  "radius": 8,
  "timeout_ticks": 160,
  "allow_place": true
}
```

Safety policy: user interruption still becomes `abort`. Environmental danger such as nearby lava, fire, fall risk, or hostile mobs can now route to these bounded recovery actions instead of always becoming a hard stop. Ordinary actions remain blocked under abort-level danger unless they are recovery actions.

Implementation approach:

- `escape_fluid` uses a local hazard-cost search rather than an LLM decision. Candidate positions are scored by distance, vertical movement, lava/fire exposure, water exposure, nearby hostile pressure, and fall support. This is a small Dijkstra/utility hybrid: it can choose a safer but slightly longer step instead of blindly walking toward the nearest dry block.
- The escape search may traverse unsafe positions with a high cost when the player starts inside water/lava, but it only accepts destinations that are standable and not near immediate danger.
- `emergency_shelter` uses a threat-weighted placement order. The wall facing the nearest hostile and the roof are prioritized before the remaining shell, so partial builds still tend to reduce the most urgent risk.
- These actions are bounded local game-AI behaviors, closer to behavior trees plus utility scoring than free-form planning.

## `explore`

`explore` is a bounded search action for voice requests such as `村を探したい` and `構造物を探して`.

The planner first runs `scan_explore_area` as an 8-chunk summary scan. This is intentionally not a full block dump: it samples chunks for standable terrain, fluids, and target hints so voice/LLM payloads stay small and Minecraft does not lag from huge JSON.

Normal voice context stays lightweight around the player; exploration is the only path that does this wider chunk-summary pass.

For villages, `target_group: "village_hint"` covers blocks such as bells, beds, composters, job-site blocks, dirt paths, and hay blocks. Generic `target_group: "structure_hint"` also looks for loaded-world structure clues such as chests, spawners, bookshelves, mossy/cracked stone bricks, sandstone temple blocks, prismarine, purpur, and nether-brick variants. If a hint is already loaded within the 8-chunk search radius, the executor steers toward that target and may use local movement assist such as bridge placement. If no hint is nearby, world assist mode chooses a random cardinal direction whose target sector has not been visited yet, walks up to the configured bounded range, and stops. The default is 300 blocks via `koecraft.executor.programmaticExploreDistanceBlocks=300`. Visited sectors are kept in the MOD executor for the running Minecraft session so repeated search requests avoid immediately going back to already explored directions when possible.

```json
{
  "type": "scan_explore_area",
  "target_group": "village_hint",
  "radius_chunks": 8,
  "samples_per_chunk": 4
}
```

```json
{
  "type": "explore",
  "target_group": "village_hint",
  "distance_blocks": 300,
  "search_radius": 128,
  "timeout_ticks": 5200,
  "programmatic_assist": true,
  "avoid_visited": true
}
```

This is not full world-scale pathfinding. It is a safe exploratory step that can be repeated through the bounded replan / rescan loop.

## `use_boat_if_water`

`use_boat_if_water` is an optional world-assist action inserted before swim-style movement. It scans nearby water surfaces and, when running in the integrated server, spawns and mounts an oak boat through the MOD/server API rather than a Minecraft slash command. If no water is nearby or world assist is disabled, the action accepts when optional and the following `move` action continues normally.

```json
{
  "type": "use_boat_if_water",
  "search_radius": 16,
  "distance_blocks": 18,
  "optional": true,
  "programmatic_assist": true
}
```

## `smelt_food`

`smelt_food` chooses a supported raw food item from inventory, uses any supported fuel, waits for the furnace output, and quick-moves the cooked food back to inventory.

The generic `smelt` action also supports survival-critical material preparation such as `minecraft:raw_iron` -> `minecraft:iron_ingot` for nether preparation.

```json
{
  "type": "smelt_food",
  "fuel_group": "fuel",
  "count": 1,
  "timeout_ticks": 240
}
```

The furnace screen must already be open.

## `defensive_move`

`defensive_move` is used before hostile combat when the hostile mob is too close or the player is in a weak health/hunger state. It raises a shield if one is available; otherwise it picks a safe retreat waypoint away from the hostile mob and follows it with the same local path-aware steering used by drop and block approach.

```json
{
  "type": "defensive_move",
  "strategy": "retreat_or_shield",
  "duration_ticks": 30,
  "min_distance": 5
}
```

## Vanilla Coverage

Vanilla block/item/entity names are covered on the Agent side by `data/minecraft/vanilla_terms.json`, generated from Minecraft assets. This means utterance target detection can recognize vanilla names broadly.

Executor execution is intentionally narrower:

- block scanning/breaking/collection can target vanilla block ids or KoeCraft block groups
- torch placement is implemented
- a survival-critical allowlist of craft/smelt recipes is implemented
- supported `craft` actions prefer runtime RecipeBook quick craft and then fall back to bundled shaped/shapeless, stonecutting, smithing-transform, or allowlisted slot layouts
- `smithing_trim` applies armor trim via smithing table slot operations and confirms the changed `minecraft:trim` component
- `component_craft` handles allowlisted special crafting outputs by confirming the relevant item component changed and the equivalent stack count increased
- `collect_fluid`, `build_nether_portal`, and `ignite_nether_portal` cover the current nether portal entry preparation paths
- `scan_state.recipebook.entries` reports runtime `network_recipe_id`, output item, category, and craftable status in the default debug profile
- `scan_state` with `profile: "voice_default"` or `profile: "summary_only"` omits recipebook entries and returns summary counts to keep voice context small

This avoids pretending to craft arbitrary items without a verified survival UI path.

## Bounded Dependency Expansion

The Planner does not recursively call itself until success. It expands a known survival route into a finite list:

```text
collect -> craft -> ensure_hotbar -> open/use/place -> close_screen
```

If an Executor step finds that a required item is missing, it returns `blocked` with data. A future control loop should then rescan state and re-plan with a strict attempt/depth budget, for example:

- max planning depth: 6
- max replans per user request: 5
- max repeated blocked reason: 1
- hard stop on danger, user interrupt, or no state delta

This keeps "if missing, make it; if material missing, collect it" behavior without allowing an infinite loop.

The Agent setting is:

```bash
KOECRAFT_MAX_REPLANS=5
```

MOD-side runtime settings are JVM properties:

```text
-Dkoecraft.executor.port=8787
-Dkoecraft.executor.maxScanRadius=32
-Dkoecraft.executor.maxReach=5.0
-Dkoecraft.executor.defaultBreakTimeoutTicks=120
-Dkoecraft.executor.defaultCollectTimeoutTicks=160
-Dkoecraft.executor.maxApproachExposureAttempts=2
-Dkoecraft.executor.maxExposureAttempts=5
-Dkoecraft.executor.maxWatchdogRecoveryAttempts=3
-Dkoecraft.executor.occupiedWorkstationRadius=2.0
-Dkoecraft.executor.itemMagnetEnabled=true
-Dkoecraft.executor.itemMagnetRadius=8
-Dkoecraft.executor.itemMagnetStrength=0.65
-Dkoecraft.executor.voiceAssistEnabled=true
-Dkoecraft.executor.voiceAssistSupportTopUpCount=32
-Dkoecraft.executor.assistMode=world_assist
-Dkoecraft.worldAssist.enabled=true
-Dkoecraft.worldAssist.consumeItemsWhenPossible=true
-Dkoecraft.worldAssist.allowCommonMaterialTopUp=true
-Dkoecraft.worldAssist.allowRareItems=false
-Dkoecraft.worldAssist.allowWorkstationPlacement=true
-Dkoecraft.worldAssist.allowMagnetPickup=true
-Dkoecraft.worldAssist.allowDirectCraft=true
-Dkoecraft.worldAssist.allowSmallBuilds=true
-Dkoecraft.executor.programmaticExploreDistanceBlocks=300
-Dkoecraft.executor.programmaticBoatTravelDistanceBlocks=180
```

The MOD clamps these values to hard safety bounds. For example, scan radius cannot exceed 32 and reach cannot exceed 6 blocks.

The Fabric client MOD starts the Executor WebSocket automatically when the Minecraft client initializes. On success, it logs and shows:

```text
KoeCraft executor listening on ws://127.0.0.1:8787
```

If another process already owns the port, the MOD reports the failure in the Minecraft action bar and log. In that case, either stop the old process or launch Minecraft with a different `-Dkoecraft.executor.port` value and set the Agent side `KOECRAFT_EXECUTOR_URL` to the matching `ws://127.0.0.1:<port>`.
