package dev.koecraft.agentmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class KoeCraftOpenAiGoalFallback {
    private static final URI CHAT_COMPLETIONS_URI = URI.create("https://api.openai.com/v1/chat/completions");
    private static final Set<String> CRAFT_TARGET_ALIASES = Set.of(
        "minecraft:boat",
        "minecraft:chest_boat",
        "minecraft:sword",
        "minecraft:pickaxe",
        "minecraft:axe",
        "minecraft:shovel",
        "minecraft:hoe",
        "minecraft:bed",
        "minecraft:carpet",
        "minecraft:banner",
        "minecraft:stone_stairs_family"
    );
    private static final Set<String> BLOCK_GROUPS = Set.of(
        "coal_ore",
        "dirt",
        "gravel",
        "sand",
        "cobblestone",
        "log",
        "crop"
    );
    private final HttpClient httpClient;
    private final VanillaRecipeCatalog recipeCatalog = new VanillaRecipeCatalog(KoeCraftOpenAiGoalFallback.class);

    KoeCraftOpenAiGoalFallback(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    CompletableFuture<Optional<JsonObject>> parseGoal(String text, KoeCraftVoiceConfig config) {
        return parseGoal(text, config, new JsonObject());
    }

    CompletableFuture<Optional<JsonObject>> parseGoal(String text, KoeCraftVoiceConfig config, JsonObject worldContext) {
        if (!config.llmFallbackEnabled() || !config.openaiConfigured()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest request = HttpRequest.newBuilder(CHAT_COMPLETIONS_URI)
            .header("authorization", "Bearer " + config.openaiApiKey())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody(text, config, worldContext), StandardCharsets.UTF_8))
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> parseResponse(response.statusCode(), response.body()))
            .thenApply(this::normalizeGoal);
    }

    private String requestBody(String text, KoeCraftVoiceConfig config, JsonObject worldContext) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.openaiModel());
        root.addProperty("temperature", 0);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        root.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt()));
        JsonObject user = new JsonObject();
        user.addProperty("recognized_text", text);
        user.add("world_context", worldContext == null ? new JsonObject() : worldContext);
        messages.add(message("user", user.toString()));
        root.add("messages", messages);
        return root.toString();
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private JsonObject parseResponse(int statusCode, String body) {
        JsonObject root = JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
        if (statusCode < 200 || statusCode >= 300) {
            String message = root.has("error") && root.get("error").isJsonObject()
                ? string(root.getAsJsonObject("error"), "message")
                : "request failed";
            throw new IllegalStateException("OpenAI goal fallback failed: HTTP " + statusCode + " " + message);
        }
        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray() ? root.getAsJsonArray("choices") : new JsonArray();
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw new IllegalStateException("OpenAI goal fallback returned no choices.");
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = first.has("message") && first.get("message").isJsonObject() ? first.getAsJsonObject("message") : new JsonObject();
        String content = string(message, "content");
        if (content.isBlank()) {
            throw new IllegalStateException("OpenAI goal fallback returned empty content.");
        }
        JsonElement parsed = JsonParser.parseString(content);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private Optional<JsonObject> normalizeGoal(JsonObject raw) {
        JsonObject goal = raw;
        if (raw.has("candidates") && raw.get("candidates").isJsonArray() && !raw.getAsJsonArray("candidates").isEmpty()) {
            JsonElement first = raw.getAsJsonArray("candidates").get(0);
            if (first.isJsonObject() && first.getAsJsonObject().has("goal") && first.getAsJsonObject().get("goal").isJsonObject()) {
                goal = first.getAsJsonObject().getAsJsonObject("goal");
            }
        } else if (raw.has("goal") && raw.get("goal").isJsonObject()) {
            goal = raw.getAsJsonObject("goal");
        }

        String type = string(goal, "type");
        JsonObject normalized = new JsonObject();
        switch (type) {
            case "place_light" -> normalized.addProperty("type", "place_light");
            case "craft_item" -> {
                String target = normalizeCraftTarget(string(goal, "target_item"));
                if (target.isBlank()) {
                    return Optional.empty();
                }
                normalized.addProperty("type", "craft_item");
                normalized.addProperty("target_item", target);
            }
            case "pickup_items" -> normalized.addProperty("type", "pickup_items");
            case "build_shelter" -> {
                normalized.addProperty("type", "build_shelter");
                normalized.addProperty("style", normalizeShelterStyle(string(goal, "style")));
            }
            case "build_structure" -> {
                normalized.addProperty("type", "build_structure");
                normalized.addProperty("style", normalizeStructureStyle(string(goal, "style")));
                normalized.addProperty("size", normalizeStructureSize(string(goal, "size")));
                normalized.addProperty("palette", normalizeStructurePalette(string(goal, "palette")));
            }
            case "build_bridge" -> {
                normalized.addProperty("type", "build_bridge");
                normalized.addProperty("direction", normalizeDirection(string(goal, "direction")));
                normalized.addProperty("distance_blocks", boundedInt(goal, "distance_blocks", 4, 1, 12));
            }
            case "collect_block" -> {
                String block = string(goal, "block");
                String group = normalizeBlockGroup(string(goal, "block_group"));
                if (block.startsWith("minecraft:")) {
                    normalized.addProperty("type", "collect_block");
                    normalized.addProperty("block", block);
                    normalized.addProperty("count", boundedInt(goal, "count", 1, 1, 32));
                } else if (!group.isBlank()) {
                    normalized.addProperty("type", "collect_block");
                    normalized.addProperty("block_group", group);
                    normalized.addProperty("count", boundedInt(goal, "count", 1, 1, 32));
                } else {
                    return Optional.empty();
                }
            }
            case "close_screen" -> normalized.addProperty("type", "close_screen");
            case "get_food" -> normalized.addProperty("type", "get_food");
            case "abort" -> normalized.addProperty("type", "abort");
            case "move" -> {
                normalized.addProperty("type", "move");
                normalized.addProperty("direction", normalizeDirection(string(goal, "direction")));
                normalized.addProperty("distance_blocks", boundedInt(goal, "distance_blocks", 4, 1, 96));
                normalized.addProperty("sprint", bool(goal, "sprint"));
                normalized.addProperty("swim", bool(goal, "swim"));
                normalized.addProperty("jump_while_sprinting", bool(goal, "jump_while_sprinting"));
            }
            case "dig_pattern" -> {
                normalized.addProperty("type", "dig_pattern");
                normalized.addProperty("pattern", normalizeDigPattern(string(goal, "pattern")));
                normalized.addProperty("steps", boundedInt(goal, "steps", 4, 1, 12));
            }
            case "celebrate" -> {
                normalized.addProperty("type", "celebrate");
                normalized.addProperty("style", normalizeCelebrateStyle(string(goal, "style")));
                normalized.addProperty("duration_ticks", boundedInt(goal, "duration_ticks", 70, 20, 120));
            }
            case "ambient_chat" -> {
                String message = sanitizeAmbientMessage(string(goal, "message"));
                if (message.isBlank()) {
                    return Optional.empty();
                }
                normalized.addProperty("type", "ambient_chat");
                normalized.addProperty("message", message);
                normalized.addProperty("style", normalizeAmbientStyle(string(goal, "style")));
                normalized.addProperty("duration_ticks", boundedInt(goal, "duration_ticks", 80, 30, 140));
            }
            case "attack_entity" -> {
                normalized.addProperty("type", "attack_entity");
                normalized.addProperty("entity_group", string(goal, "entity_group").equals("food_animal") ? "food_animal" : "hostile");
                normalized.addProperty("count", boundedInt(goal, "count", 1, 1, 8));
            }
            case "prepare_nether" -> {
                normalized.addProperty("type", "prepare_nether");
                normalized.addProperty("route", normalizeNetherRoute(string(goal, "route")));
            }
            case "context_action" -> {
                String intent = normalizeContextIntent(string(goal, "intent"));
                if (intent.isBlank()) {
                    return Optional.empty();
                }
                normalized.addProperty("type", "context_action");
                normalized.addProperty("intent", intent);
            }
            case "search_structure" -> {
                normalized.addProperty("type", "search_structure");
                normalized.addProperty("target_group", normalizeSearchTargetGroup(string(goal, "target_group")));
            }
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(normalized);
    }

    Optional<JsonObject> normalizeGoalForTesting(JsonObject raw) {
        return normalizeGoal(raw);
    }

    private String systemPrompt() {
        return """
            You map a Japanese Minecraft voice-control utterance to one JSON goal only.
            Never output Minecraft commands. Never output action DSL. Never invent recipes.
            Return {"type":"unknown"} when unsupported.
            Supported goals:
            {"type":"place_light"}
            {"type":"build_shelter","style":"small_house|hideout|safe_spot"}
            {"type":"build_structure","style":"small_house|cute_house|hideout","size":"tiny|small","palette":"available|wood|dirt|sand"}
            {"type":"build_bridge","direction":"forward|back|left|right","distance_blocks":1-12}
            {"type":"craft_item","target_item":"minecraft item id or allowed generic alias"}
            Allowed generic craft aliases when wood/color/material is omitted:
            minecraft:boat, minecraft:chest_boat, minecraft:sword, minecraft:pickaxe, minecraft:axe, minecraft:shovel, minecraft:hoe, minecraft:bed, minecraft:carpet, minecraft:banner, minecraft:stone_stairs_family.
            {"type":"collect_block","block_group":"coal_ore|dirt|gravel|sand|cobblestone|log|crop","count":1-32}
            {"type":"collect_block","block":"minecraft block id","count":1-32}
            {"type":"move","direction":"forward|back|left|right","distance_blocks":1-96,"sprint":boolean,"swim":boolean,"jump_while_sprinting":boolean}
            {"type":"pickup_items"}
            {"type":"dig_pattern","pattern":"stair_down|tunnel_forward|shaft_down_safe","steps":1-12}
            {"type":"celebrate","style":"cheer|dance|youtuber_pose","duration_ticks":20-120}
            {"type":"ambient_chat","message":"short Japanese in-world reply, max 48 chars","style":"nod|shrug|dance|cheer","duration_ticks":30-140}
            {"type":"context_action","intent":"take|attack|mine|open|retreat|defend"}
            {"type":"search_structure","target_group":"village_hint|structure_hint"}
            {"type":"get_food"}
            {"type":"attack_entity","entity_group":"food_animal|hostile","count":1-8}
            {"type":"prepare_nether","route":"lava_cast|obsidian_frame"}
            {"type":"close_screen"}
            {"type":"abort"}

            If recognized_text contains literal unicode escape fragments such as u6628 or \\u6628, return {"type":"unknown"}.
            For non-task smalltalk or questions that should not control Minecraft, use ambient_chat only when it is clearly an in-world reaction. Otherwise return {"type":"unknown"}.
            ambient_chat must not ask unrelated follow-up questions or discuss real-world events. Keep it tied to what the player can see or do in Minecraft.
            If world_context suggests immediate danger such as night, low health, nearby hostile mobs, lava, water flow, or falling risk, prefer a safe Minecraft action such as build_shelter, retreat, defend, or close_screen over ambient_chat.
            For vague Minecraft tasks, choose a safe context goal instead of ambient_chat:
            "掘って" -> {"type":"context_action","intent":"mine"}
            "取って" -> {"type":"context_action","intent":"take"}
            "近くのアイテム拾って" -> {"type":"pickup_items"}
            "木を集めて" -> {"type":"collect_block","block_group":"log","count":1}
            "砂を集めて" -> {"type":"collect_block","block_group":"sand","count":1}
            "石を掘って" -> {"type":"collect_block","block_group":"cobblestone","count":1}
            "村を探して" -> {"type":"search_structure","target_group":"village_hint"}
            "構造物を探して" -> {"type":"search_structure","target_group":"structure_hint"}
            "橋を架けて" -> {"type":"build_bridge","direction":"forward","distance_blocks":4}
            "泳いで" -> {"type":"move","direction":"forward","distance_blocks":8,"sprint":true,"swim":true}
            "ダッシュジャンプして" -> {"type":"move","direction":"forward","distance_blocks":7,"sprint":true,"jump_while_sprinting":true}
            For vague search with no target, prefer ambient_chat asking what target to search for.
            Child-friendly routing:
            If a child asks for a cute, pretty, proper, or nice small house/home/base, use build_structure.
            If a child asks for a simple shelter or says they are scared/help, use build_shelter.
            If a child asks for a huge house, castle, town, or very large build, do not attempt a huge build. Use ambient_chat to propose starting with a small house first.
            If a child uses vague words like "キラキラ" or "すごいやつ", ask a short in-world clarification instead of inventing an item.
            If a child says they do not know what to do, use ambient_chat with: "まず三つ。木を集める、家を作る、村を探す。どれにする？"
            Use world_context for ambient_chat. It contains only a small local scan around the player.
            ambient_chat must not claim hidden facts beyond world_context. Use cautious phrasing when context status is not ready.
            Mention nearby concrete signals when useful: darkness, night, hostile mobs, item drops, animals, crops, village hints, water/lava, trees, light sources.
            If the utterance asks for a real-world app, schedule, web browsing, math, or device control, ambient_chat should gently say it can only react inside this Minecraft world.
            """;
    }

    private int boundedInt(JsonObject object, String key, int fallback, int min, int max) {
        if (object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()) {
            return Math.max(min, Math.min(object.get(key).getAsInt(), max));
        }
        return Math.max(min, Math.min(fallback, max));
    }

    private boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private String normalizeDirection(String value) {
        return value.equals("back") || value.equals("left") || value.equals("right") ? value : "forward";
    }

    private String normalizeDigPattern(String value) {
        return value.equals("tunnel_forward") || value.equals("shaft_down_safe") ? value : "stair_down";
    }

    private String normalizeCelebrateStyle(String value) {
        return value.equals("dance") || value.equals("youtuber_pose") ? value : "cheer";
    }

    private String normalizeAmbientStyle(String value) {
        return switch (value) {
            case "dance", "cheer", "shrug" -> value;
            default -> "nod";
        };
    }

    private String normalizeStructureStyle(String value) {
        return switch (value) {
            case "cute_house", "hideout" -> value;
            default -> "small_house";
        };
    }

    private String normalizeStructureSize(String value) {
        return value.equals("small") ? "small" : "tiny";
    }

    private String normalizeStructurePalette(String value) {
        return switch (value) {
            case "wood", "dirt", "sand" -> value;
            default -> "available";
        };
    }

    private String sanitizeAmbientMessage(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        if (compact.length() > 48) {
            return compact.substring(0, 48);
        }
        return compact;
    }

    private String normalizeContextIntent(String value) {
        return switch (value) {
            case "take", "attack", "mine", "open", "retreat", "defend" -> value;
            default -> "";
        };
    }

    private String normalizeNetherRoute(String value) {
        return value.equals("obsidian_frame") ? "obsidian_frame" : "lava_cast";
    }

    private String normalizeSearchTargetGroup(String value) {
        return value.equals("structure_hint") ? "structure_hint" : "village_hint";
    }

    private String normalizeCraftTarget(String value) {
        if (value == null || !value.startsWith("minecraft:")) {
            return "";
        }
        if (recipeCatalog.craftRecipes().stream().anyMatch(recipe -> recipe.output().equals(value))) {
            return value;
        }
        return CRAFT_TARGET_ALIASES.contains(value) ? value : "";
    }

    private String normalizeBlockGroup(String value) {
        return BLOCK_GROUPS.contains(value) ? value : "";
    }

    private String normalizeShelterStyle(String value) {
        return switch (value) {
            case "hideout", "safe_spot" -> value;
            default -> "small_house";
        };
    }
}
