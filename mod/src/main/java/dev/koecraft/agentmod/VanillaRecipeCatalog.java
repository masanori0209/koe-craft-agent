package dev.koecraft.agentmod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class VanillaRecipeCatalog {
    private final Class<?> resourceOwner;
    private List<VanillaCraftRecipe> craftRecipes;
    private List<VanillaFurnaceRecipe> furnaceRecipes;
    private List<VanillaStonecuttingRecipe> stonecuttingRecipes;
    private List<VanillaSmithingRecipe> smithingRecipes;
    private List<VanillaSmithingTrimRecipe> smithingTrimRecipes;

    VanillaRecipeCatalog(Class<?> resourceOwner) {
        this.resourceOwner = resourceOwner;
    }

    List<VanillaCraftRecipe> craftRecipes() {
        if (craftRecipes == null) {
            craftRecipes = loadRecipes(
                "/koecraft/vanilla_crafting_recipes.json",
                raw -> parseVanillaCraftRecipe(raw)
            );
        }
        return craftRecipes;
    }

    List<VanillaFurnaceRecipe> furnaceRecipes() {
        if (furnaceRecipes == null) {
            furnaceRecipes = loadRecipes(
                "/koecraft/vanilla_furnace_recipes.json",
                raw -> parseVanillaFurnaceRecipe(raw)
            );
        }
        return furnaceRecipes;
    }

    List<VanillaStonecuttingRecipe> stonecuttingRecipes() {
        if (stonecuttingRecipes == null) {
            stonecuttingRecipes = loadRecipes(
                "/koecraft/vanilla_stonecutting_recipes.json",
                raw -> parseVanillaStonecuttingRecipe(raw)
            );
        }
        return stonecuttingRecipes;
    }

    List<VanillaSmithingRecipe> smithingRecipes() {
        if (smithingRecipes == null) {
            smithingRecipes = loadRecipes(
                "/koecraft/vanilla_smithing_recipes.json",
                raw -> parseVanillaSmithingRecipe(raw)
            );
        }
        return smithingRecipes;
    }

    List<VanillaSmithingTrimRecipe> smithingTrimRecipes() {
        if (smithingTrimRecipes == null) {
            smithingTrimRecipes = loadRecipes(
                "/koecraft/vanilla_smithing_trim_recipes.json",
                raw -> parseVanillaSmithingTrimRecipe(raw)
            );
        }
        return smithingTrimRecipes;
    }

    private <T> List<T> loadRecipes(String path, RecipeParser<T> parser) {
        try (InputStream stream = resourceOwner.getResourceAsStream(path)) {
            if (stream == null) {
                return List.of();
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray recipes = root.getAsJsonArray("recipes");
            List<T> parsed = new ArrayList<>();
            for (JsonElement element : recipes) {
                T recipe = parser.parse(element.getAsJsonObject());
                if (recipe != null) {
                    parsed.add(recipe);
                }
            }
            return Collections.unmodifiableList(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    private VanillaCraftRecipe parseVanillaCraftRecipe(JsonObject raw) {
        String recipeId = raw.get("recipe_id").getAsString();
        String type = raw.get("type").getAsString();
        JsonObject result = raw.getAsJsonObject("result");
        String output = result.get("id").getAsString();
        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        if ("minecraft:crafting_shaped".equals(type)) {
            List<String> pattern = new ArrayList<>();
            for (JsonElement row : raw.getAsJsonArray("pattern")) {
                pattern.add(row.getAsString());
            }
            Map<Character, List<VanillaIngredientAlternative>> key = new HashMap<>();
            JsonObject rawKey = raw.getAsJsonObject("key");
            for (Map.Entry<String, JsonElement> entry : rawKey.entrySet()) {
                if (entry.getKey().isEmpty()) {
                    continue;
                }
                key.put(entry.getKey().charAt(0), parseIngredientAlternatives(entry.getValue().getAsJsonArray()));
            }
            return new VanillaCraftRecipe(recipeId, type, output, count, pattern, key, List.of());
        }
        if ("minecraft:crafting_shapeless".equals(type) || "minecraft:crafting_transmute".equals(type)) {
            List<List<VanillaIngredientAlternative>> ingredients = new ArrayList<>();
            for (JsonElement ingredient : raw.getAsJsonArray("ingredients")) {
                ingredients.add(parseIngredientAlternatives(ingredient.getAsJsonArray()));
            }
            return new VanillaCraftRecipe(recipeId, type, output, count, List.of(), Map.of(), ingredients);
        }
        return null;
    }

    private List<VanillaIngredientAlternative> parseIngredientAlternatives(JsonArray alternatives) {
        List<VanillaIngredientAlternative> parsed = new ArrayList<>();
        for (JsonElement alternativeElement : alternatives) {
            JsonObject alternative = alternativeElement.getAsJsonObject();
            parsed.add(new VanillaIngredientAlternative(
                alternative.has("item") ? alternative.get("item").getAsString() : "",
                alternative.has("tag") ? alternative.get("tag").getAsString() : ""
            ));
        }
        return parsed;
    }

    private VanillaStonecuttingRecipe parseVanillaStonecuttingRecipe(JsonObject raw) {
        String recipeId = raw.get("recipe_id").getAsString();
        JsonObject result = raw.getAsJsonObject("result");
        String output = result.get("id").getAsString();
        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        return new VanillaStonecuttingRecipe(
            recipeId,
            output,
            count,
            parseIngredientAlternatives(raw.getAsJsonArray("ingredient"))
        );
    }

    private VanillaFurnaceRecipe parseVanillaFurnaceRecipe(JsonObject raw) {
        String recipeId = raw.get("recipe_id").getAsString();
        String type = raw.get("type").getAsString();
        JsonObject result = raw.getAsJsonObject("result");
        String output = result.get("id").getAsString();
        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        return new VanillaFurnaceRecipe(
            recipeId,
            type,
            output,
            count,
            parseIngredientAlternatives(raw.getAsJsonArray("ingredient"))
        );
    }

    private VanillaSmithingRecipe parseVanillaSmithingRecipe(JsonObject raw) {
        String recipeId = raw.get("recipe_id").getAsString();
        JsonObject result = raw.getAsJsonObject("result");
        String output = result.get("id").getAsString();
        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        return new VanillaSmithingRecipe(
            recipeId,
            output,
            count,
            parseIngredientAlternatives(raw.getAsJsonArray("template")),
            parseIngredientAlternatives(raw.getAsJsonArray("base")),
            parseIngredientAlternatives(raw.getAsJsonArray("addition"))
        );
    }

    private VanillaSmithingTrimRecipe parseVanillaSmithingTrimRecipe(JsonObject raw) {
        return new VanillaSmithingTrimRecipe(
            raw.get("recipe_id").getAsString(),
            parseIngredientAlternatives(raw.getAsJsonArray("template")),
            parseIngredientAlternatives(raw.getAsJsonArray("base")),
            parseIngredientAlternatives(raw.getAsJsonArray("addition"))
        );
    }

    @FunctionalInterface
    private interface RecipeParser<T> {
        T parse(JsonObject raw);
    }
}
