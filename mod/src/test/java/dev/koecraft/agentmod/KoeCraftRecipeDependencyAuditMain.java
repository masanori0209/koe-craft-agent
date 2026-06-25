package dev.koecraft.agentmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class KoeCraftRecipeDependencyAuditMain {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private KoeCraftRecipeDependencyAuditMain() {
    }

    public static void main(String[] args) throws Exception {
        Path recipeCatalogPath = args.length > 0
            ? Path.of(args[0])
            : Path.of("../data/minecraft/vanilla_recipes.json");
        Path reportDir = args.length > 1
            ? Path.of(args[1])
            : Path.of("../logs/reports");
        Files.createDirectories(reportDir);

        JsonObject root = JsonParser.parseString(Files.readString(recipeCatalogPath)).getAsJsonObject();
        JsonArray recipes = root.getAsJsonArray("recipes");
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);

        ArrayList<AuditRow> rows = new ArrayList<>();
        for (JsonElement element : recipes) {
            JsonObject recipe = element.getAsJsonObject();
            rows.add(auditRecipe(planner, recipe));
        }

        JsonObject report = buildJsonReport(recipeCatalogPath, rows);
        Files.writeString(reportDir.resolve("recipe-dependency-audit.json"), GSON.toJson(report));
        Files.writeString(reportDir.resolve("recipe-dependency-audit.md"), buildMarkdownReport(recipeCatalogPath, rows));

        StringBuilder summary = new StringBuilder("[recipe-dependency-audit] total=").append(rows.size());
        counts(rows).forEach((status, count) -> summary.append(' ').append(status).append('=').append(count));
        summary.append(" report=").append(reportDir.resolve("recipe-dependency-audit.md"));
        System.out.println(summary);
    }

    private static AuditRow auditRecipe(KoeCraftNativeGoalPlanner planner, JsonObject recipe) {
        String recipeId = string(recipe, "recipe_id");
        String type = string(recipe, "type");
        JsonObject result = recipe.has("result") && recipe.get("result").isJsonObject()
            ? recipe.getAsJsonObject("result")
            : new JsonObject();
        String output = string(result, "id");
        int outputCount = intValue(result, "count", 1);

        if (output.isBlank()) {
            return new AuditRow(recipeId, type, output, outputCount, "unsupported_recipe_shape", "Recipe result item id is missing.", List.of());
        }
        boolean plannerExpandableType = type.equals("minecraft:crafting_shaped")
            || type.equals("minecraft:crafting_shapeless")
            || type.equals("minecraft:crafting_transmute")
            || type.equals("minecraft:stonecutting")
            || type.equals("minecraft:smelting")
            || type.equals("minecraft:blasting")
            || type.equals("minecraft:smoking")
            || type.equals("minecraft:campfire_cooking")
            || type.equals("minecraft:smithing_transform");
        if (!plannerExpandableType) {
            return new AuditRow(recipeId, type, output, outputCount, classifyNonCraftingRecipe(type), nonCraftingReason(type), List.of());
        }

        JsonObject goal = new JsonObject();
        goal.addProperty("type", "craft_item");
        goal.addProperty("target_item", output);
        goal.addProperty("recipe_id", recipeId);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> plan = planner.planLlmGoal(goal);
        if (plan.isEmpty()) {
            return new AuditRow(recipeId, type, output, outputCount, "special_crafting_unsupported", "No deterministic planner route was produced for this crafting output.", List.of());
        }

        List<String> actionTypes = plan.get().actions().stream().map(ExecutorProtocol.Action::type).toList();
        Optional<ExecutorProtocol.Action> blocked = plan.get().actions().stream()
            .filter(action -> action.type().equals("planner_blocked_reason"))
            .findFirst();
        if (blocked.isPresent()) {
            String reasonCode = string(blocked.get().body(), "reason_code");
            String blockedItem = string(blocked.get().body(), "item");
            String status = classifyBlockedRecipe(recipeId, output, blockedItem, reasonCode, string(blocked.get().body(), "message"));
            return new AuditRow(recipeId, type, output, outputCount, status, string(blocked.get().body(), "message"), actionTypes);
        }
        return new AuditRow(recipeId, type, output, outputCount, "planned", "Recipe dependency tree expands into deterministic actions.", actionTypes);
    }

    private static String classifyBlockedRecipe(String recipeId, String output, String blockedItem, String reasonCode, String message) {
        if (Set.of("silk_touch_required", "boss_route_missing", "mob_head_route_missing").contains(reasonCode)) {
            return reasonCode;
        }
        if (reasonCode.equals("recipe_material_route_missing")) {
            if (blockedItem.equals("minecraft:ice")) {
                return "silk_touch_required";
            }
            if (blockedItem.equals("minecraft:nether_star")) {
                return "boss_route_missing";
            }
            if (blockedItem.equals("minecraft:creeper_head")) {
                return "mob_head_route_missing";
            }
            if (Set.of(
                "minecraft:heart_of_the_sea",
                "minecraft:heavy_core",
                "minecraft:enchanted_golden_apple",
                "minecraft:disc_fragment_5",
                "minecraft:echo_shard"
            ).contains(blockedItem)) {
                return "structure_loot_route_missing";
            }
            return "material_route_missing";
        }
        if (output.endsWith("_armor_trim_smithing_template") || output.equals("minecraft:netherite_upgrade_smithing_template")) {
            return "template_duplication_requires_source";
        }
        if (message.contains("detected a cycle")) {
            return "reciprocal_recipe_cycle";
        }
        return "planner_blocked";
    }

    private static String classifyNonCraftingRecipe(String type) {
        return switch (type) {
            case "minecraft:stonecutting" -> "stonecutting_route_cataloged";
            case "minecraft:smelting" -> "smelting_route_cataloged";
            case "minecraft:blasting" -> "blasting_route_cataloged";
            case "minecraft:smoking", "minecraft:campfire_cooking" -> "cooking_route_cataloged";
            case "minecraft:smithing_transform" -> "smithing_transform_route_cataloged";
            default -> "non_crafting_route_missing";
        };
    }

    private static String nonCraftingReason(String type) {
        return switch (type) {
            case "minecraft:stonecutting" -> "Recipe is cataloged as a stonecutter route; dependency expansion is tracked separately from crafting-grid recipes.";
            case "minecraft:smelting" -> "Recipe is cataloged as a furnace smelting route; dependency expansion is tracked separately from crafting-grid recipes.";
            case "minecraft:blasting" -> "Recipe is cataloged as a blast furnace route; dependency expansion is tracked separately from crafting-grid recipes.";
            case "minecraft:smoking", "minecraft:campfire_cooking" -> "Recipe is cataloged as a food/campfire cooking route; dependency expansion is tracked separately from crafting-grid recipes.";
            case "minecraft:smithing_transform" -> "Recipe is cataloged as a smithing transform route; dependency expansion is tracked separately from crafting-grid recipes.";
            default -> "Planner audit currently expands crafting dependency trees only.";
        };
    }

    private static JsonObject buildJsonReport(Path recipeCatalogPath, List<AuditRow> rows) {
        JsonObject report = new JsonObject();
        report.addProperty("generated_at", Instant.now().toString());
        report.addProperty("recipe_catalog", recipeCatalogPath.toString());
        JsonObject summary = new JsonObject();
        counts(rows).forEach(summary::addProperty);
        report.add("summary", summary);
        JsonArray rowArray = new JsonArray();
        for (AuditRow row : rows) {
            JsonObject raw = new JsonObject();
            raw.addProperty("recipe_id", row.recipeId());
            raw.addProperty("type", row.type());
            raw.addProperty("output", row.output());
            raw.addProperty("output_count", row.outputCount());
            raw.addProperty("status", row.status());
            raw.addProperty("reason", row.reason());
            JsonArray actions = new JsonArray();
            row.actionTypes().forEach(actions::add);
            raw.add("action_types", actions);
            rowArray.add(raw);
        }
        report.add("recipes", rowArray);
        return report;
    }

    private static String buildMarkdownReport(Path recipeCatalogPath, List<AuditRow> rows) {
        StringBuilder out = new StringBuilder();
        out.append("# Recipe Dependency Audit\n\n");
        out.append("- generated_at: ").append(Instant.now()).append('\n');
        out.append("- recipe_catalog: `").append(recipeCatalogPath).append("`\n\n");
        out.append("## Summary\n\n");
        out.append("| status | count |\n");
        out.append("| --- | ---: |\n");
        counts(rows).forEach((status, count) -> out.append("| `").append(status).append("` | ").append(count).append(" |\n"));
        out.append("\n## Examples By Status\n\n");
        for (String status : counts(rows).keySet()) {
            out.append("### ").append(status).append("\n\n");
            rows.stream()
                .filter(row -> row.status().equals(status))
                .limit(30)
                .forEach(row -> out.append("- `")
                    .append(row.output())
                    .append("` via `")
                    .append(row.recipeId())
                    .append("` (`")
                    .append(row.type())
                    .append("`): ")
                    .append(row.reason())
                    .append('\n'));
            out.append('\n');
        }
        return out.toString();
    }

    private static Map<String, Integer> counts(List<AuditRow> rows) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (AuditRow row : rows) {
            counts.merge(row.status(), 1, Integer::sum);
        }
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (String key : List.of("planned", "material_route_missing", "silk_touch_required", "boss_route_missing", "mob_head_route_missing", "structure_loot_route_missing", "special_crafting_unsupported", "template_duplication_requires_source", "reciprocal_recipe_cycle", "stonecutting_route_cataloged", "smelting_route_cataloged", "blasting_route_cataloged", "cooking_route_cataloged", "smithing_transform_route_cataloged", "non_crafting_route_missing", "planner_blocked", "unsupported_recipe_shape")) {
            if (counts.containsKey(key)) {
                ordered.put(key, counts.remove(key));
            }
        }
        ordered.putAll(counts);
        return ordered;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : fallback;
    }

    private record AuditRow(String recipeId, String type, String output, int outputCount, String status, String reason, List<String> actionTypes) {
    }
}
