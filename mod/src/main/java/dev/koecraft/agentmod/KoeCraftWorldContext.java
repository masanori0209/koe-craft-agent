package dev.koecraft.agentmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

final class KoeCraftWorldContext {
    private static final int MAX_RADIUS = 12;
    private static final Set<String> FOOD_ANIMALS = Set.of(
        "minecraft:cow",
        "minecraft:mooshroom",
        "minecraft:pig",
        "minecraft:chicken",
        "minecraft:sheep",
        "minecraft:rabbit",
        "minecraft:cod",
        "minecraft:salmon"
    );
    private static final Set<String> HOSTILES = Set.of(
        "minecraft:zombie",
        "minecraft:zombie_villager",
        "minecraft:husk",
        "minecraft:drowned",
        "minecraft:skeleton",
        "minecraft:stray",
        "minecraft:spider",
        "minecraft:cave_spider",
        "minecraft:creeper",
        "minecraft:enderman",
        "minecraft:witch",
        "minecraft:slime",
        "minecraft:magma_cube",
        "minecraft:phantom",
        "minecraft:pillager",
        "minecraft:vindicator",
        "minecraft:evoker",
        "minecraft:ravager",
        "minecraft:blaze",
        "minecraft:ghast",
        "minecraft:wither_skeleton",
        "minecraft:piglin_brute",
        "minecraft:warden"
    );

    private KoeCraftWorldContext() {
    }

    static CompletableFuture<JsonObject> captureAsync(MinecraftClient client, int requestedRadius) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        if (client == null) {
            future.complete(empty("client_not_ready"));
            return future;
        }
        client.execute(() -> {
            try {
                future.complete(capture(client, requestedRadius));
            } catch (RuntimeException error) {
                JsonObject fallback = empty("capture_failed");
                fallback.addProperty("error", error.getMessage() == null ? error.toString() : error.getMessage());
                future.complete(fallback);
            }
        });
        return future;
    }

    private static JsonObject capture(MinecraftClient client, int requestedRadius) {
        if (client.player == null || client.world == null) {
            return empty("player_or_world_not_ready");
        }
        int radius = Math.max(1, Math.min(requestedRadius, MAX_RADIUS));
        JsonObject root = new JsonObject();
        root.addProperty("status", "ready");
        root.addProperty("radius", radius);

        BlockPos origin = client.player.getBlockPos();
        Vec3d playerPos = client.player.getPos();
        JsonObject player = new JsonObject();
        player.addProperty("health", client.player.getHealth());
        player.addProperty("hunger", client.player.getHungerManager().getFoodLevel());
        player.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
        player.addProperty("y", origin.getY());
        player.addProperty("light", client.world.getLightLevel(origin));
        player.addProperty("sky_light", client.world.getLightLevel(LightType.SKY, origin));
        player.addProperty("block_light", client.world.getLightLevel(LightType.BLOCK, origin));
        root.add("player", player);

        JsonObject world = new JsonObject();
        long time = client.world.getTimeOfDay() % 24000L;
        world.addProperty("time_of_day", time);
        world.addProperty("time_label", timeLabel(time));
        world.addProperty("raining", client.world.isRaining());
        world.addProperty("thundering", client.world.isThundering());
        root.add("world", world);

        BlockSummary blocks = summarizeBlocks(client, origin, radius);
        root.add("blocks", blocks.toJson());
        EntitySummary entities = summarizeEntities(client, playerPos, radius);
        root.add("entities", entities.toJson());
        root.add("signals", signals(player, world, blocks, entities));
        return root;
    }

    private static BlockSummary summarizeBlocks(MinecraftClient client, BlockPos origin, int radius) {
        BlockSummary summary = new BlockSummary();
        int verticalRadius = Math.min(radius, 6);
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    double distance = Math.sqrt(origin.getSquaredDistance(pos));
                    summary.seenBlocks++;
                    summary.add("water", blockId.equals("minecraft:water"), distance);
                    summary.add("lava", blockId.equals("minecraft:lava"), distance);
                    summary.add("log", BlockGroups.matches("log", blockId), distance);
                    summary.add("dirt", BlockGroups.matches("dirt", blockId), distance);
                    summary.add("stone", BlockGroups.matches("stone", blockId), distance);
                    summary.add("cobblestone", BlockGroups.matches("cobblestone", blockId), distance);
                    summary.add("sand", BlockGroups.matches("sand", blockId), distance);
                    summary.add("gravel", BlockGroups.matches("gravel", blockId), distance);
                    summary.add("coal_ore", BlockGroups.matches("coal_ore", blockId), distance);
                    summary.add("iron_ore", BlockGroups.matches("iron_ore", blockId), distance);
                    summary.add("crop", BlockGroups.matches("crop", blockId), distance);
                    summary.add("village_hint", BlockGroups.matches("village_hint", blockId), distance);
                    summary.add("light_source", BlockGroups.matches("light_source", blockId), distance);
                    summary.add("noncombat_food", BlockGroups.matches("noncombat_food", blockId), distance);
                }
            }
        }
        return summary;
    }

    private static EntitySummary summarizeEntities(MinecraftClient client, Vec3d origin, int radius) {
        EntitySummary summary = new EntitySummary();
        Box box = new Box(client.player.getBlockPos()).expand(radius);
        for (Entity entity : client.world.getOtherEntities(client.player, box)) {
            String id = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            double distance = origin.distanceTo(entity.getPos());
            if (entity instanceof ItemEntity itemEntity && !itemEntity.getStack().isEmpty()) {
                String itemId = Registries.ITEM.getId(itemEntity.getStack().getItem()).toString();
                summary.itemDrops++;
                summary.addNearest("item_drop", itemId, distance);
                continue;
            }
            if (HOSTILES.contains(id)) {
                summary.hostiles++;
                summary.addNearest("hostile", id, distance);
            } else if (FOOD_ANIMALS.contains(id)) {
                summary.foodAnimals++;
                summary.addNearest("food_animal", id, distance);
            } else {
                summary.passiveOrOther++;
                summary.addNearest("other", id, distance);
            }
        }
        return summary;
    }

    private static JsonArray signals(JsonObject player, JsonObject world, BlockSummary blocks, EntitySummary entities) {
        JsonArray signals = new JsonArray();
        if (player.get("light").getAsInt() <= 7) signals.add("dark_here");
        if ("night".equals(world.get("time_label").getAsString())) signals.add("night");
        if (entities.hostiles > 0) signals.add("hostile_nearby");
        if (entities.foodAnimals > 0) signals.add("food_animal_nearby");
        if (entities.itemDrops > 0) signals.add("item_drop_nearby");
        if (blocks.count("village_hint") > 0) signals.add("village_hint_nearby");
        if (blocks.count("water") > 0) signals.add("water_nearby");
        if (blocks.count("lava") > 0) signals.add("lava_nearby");
        if (blocks.count("log") > 0) signals.add("trees_nearby");
        if (blocks.count("crop") > 0) signals.add("crop_nearby");
        if (blocks.count("noncombat_food") > 0) signals.add("noncombat_food_nearby");
        return signals;
    }

    private static String timeLabel(long time) {
        if (time >= 23000L || time < 1000L) return "sunrise";
        if (time < 12000L) return "day";
        if (time < 13000L) return "sunset";
        return "night";
    }

    private static JsonObject empty(String status) {
        JsonObject root = new JsonObject();
        root.addProperty("status", status);
        return root;
    }

    private static final class BlockSummary {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Map<String, Double> nearest = new LinkedHashMap<>();
        private int seenBlocks;

        void add(String key, boolean matches, double distance) {
            if (!matches) {
                return;
            }
            counts.put(key, count(key) + 1);
            nearest.merge(key, distance, Math::min);
        }

        int count(String key) {
            return counts.getOrDefault(key, 0);
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("seen_blocks", seenBlocks);
            JsonObject countJson = new JsonObject();
            JsonObject nearestJson = new JsonObject();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                countJson.addProperty(entry.getKey(), entry.getValue());
                nearestJson.addProperty(entry.getKey(), Math.round(nearest.get(entry.getKey()) * 10.0D) / 10.0D);
            }
            root.add("counts", countJson);
            root.add("nearest_distance", nearestJson);
            return root;
        }
    }

    private static final class EntitySummary {
        private final Map<String, String> nearestType = new LinkedHashMap<>();
        private final Map<String, Double> nearestDistance = new LinkedHashMap<>();
        private int hostiles;
        private int foodAnimals;
        private int passiveOrOther;
        private int itemDrops;

        void addNearest(String key, String type, double distance) {
            if (!nearestDistance.containsKey(key) || distance < nearestDistance.get(key)) {
                nearestType.put(key, type);
                nearestDistance.put(key, distance);
            }
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("hostile_count", hostiles);
            root.addProperty("food_animal_count", foodAnimals);
            root.addProperty("other_entity_count", passiveOrOther);
            root.addProperty("item_drop_count", itemDrops);
            JsonObject nearest = new JsonObject();
            for (Map.Entry<String, String> entry : nearestType.entrySet()) {
                JsonObject item = new JsonObject();
                item.addProperty("type", entry.getValue());
                item.addProperty("distance", Math.round(nearestDistance.get(entry.getKey()) * 10.0D) / 10.0D);
                nearest.add(entry.getKey(), item);
            }
            root.add("nearest", nearest);
            return root;
        }
    }
}
