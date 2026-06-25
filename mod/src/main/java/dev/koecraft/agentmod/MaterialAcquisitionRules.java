package dev.koecraft.agentmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class MaterialAcquisitionRules {
    private final Class<?> resourceOwner;
    private List<Rule> rules;

    MaterialAcquisitionRules(Class<?> resourceOwner) {
        this.resourceOwner = resourceOwner;
    }

    Optional<Rule> find(String itemId) {
        return rules().stream().filter(rule -> rule.item().equals(itemId)).findFirst();
    }

    private List<Rule> rules() {
        if (rules != null) {
            return rules;
        }
        try (InputStream stream = resourceOwner.getResourceAsStream("/koecraft/material_acquisition_rules.json")) {
            if (stream == null) {
                rules = List.of();
                return rules;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            ArrayList<Rule> parsed = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("rules")) {
                JsonObject raw = element.getAsJsonObject();
                String item = string(raw, "item");
                JsonArray actionArray = raw.has("actions") && raw.get("actions").isJsonArray() ? raw.getAsJsonArray("actions") : new JsonArray();
                ArrayList<ActionTemplate> actions = new ArrayList<>();
                for (JsonElement actionElement : actionArray) {
                    JsonObject action = actionElement.getAsJsonObject();
                    String type = string(action, "type");
                    if (type.equals("attack_entity")) {
                        actions.add(new ActionTemplate(
                            type,
                            string(action, "entity_group"),
                            "",
                            "",
                            "",
                            intField(action, "count_per_item", 1),
                            intField(action, "min_count", 0),
                            intField(action, "search_radius", 12),
                            intField(action, "timeout_ticks", 180)
                        ));
                    } else if (type.equals("collect_block")) {
                        actions.add(new ActionTemplate(
                            type,
                            "",
                            "",
                            string(action, "block"),
                            string(action, "block_group"),
                            intField(action, "count_per_item", 1),
                            intField(action, "min_count", 0),
                            intField(action, "search_radius", 16),
                            intField(action, "timeout_ticks", 180)
                        ));
                    } else if (type.equals("collect_drops")) {
                        actions.add(new ActionTemplate(
                            type,
                            "",
                            string(action, "item"),
                            "",
                            "",
                            1,
                            0,
                            intField(action, "search_radius", 10),
                            intField(action, "timeout_ticks", 160)
                        ));
                    }
                }
                if (!item.isBlank() && !actions.isEmpty()) {
                    parsed.add(new Rule(item, List.copyOf(actions)));
                }
            }
            rules = List.copyOf(parsed);
            return rules;
        } catch (Exception ignored) {
            rules = List.of();
            return rules;
        }
    }

    private String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private int intField(JsonObject object, String key, int fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()
            ? object.get(key).getAsInt()
            : fallback;
    }

    record Rule(String item, List<ActionTemplate> actions) {
    }

    record ActionTemplate(
        String type,
        String entityGroup,
        String item,
        String block,
        String blockGroup,
        int countPerItem,
        int minCount,
        int searchRadius,
        int timeoutTicks
    ) {
    }
}
