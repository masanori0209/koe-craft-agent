package dev.koecraft.agentmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

record VanillaCraftRecipe(
    String recipeId,
    String type,
    String output,
    int count,
    List<String> pattern,
    Map<Character, List<VanillaIngredientAlternative>> key,
    List<List<VanillaIngredientAlternative>> ingredients
) {
    boolean fitsGrid(int grid) {
        if ("minecraft:crafting_shaped".equals(type)) {
            return pattern.size() <= grid && pattern.stream().allMatch(row -> row.length() <= grid);
        }
        return ingredients.size() <= grid * grid;
    }

    CraftIngredient[] toCraftIngredients(int grid) {
        List<CraftIngredient> slots = new ArrayList<>();
        if ("minecraft:crafting_shaped".equals(type)) {
            for (int row = 0; row < pattern.size(); row++) {
                String line = pattern.get(row);
                for (int col = 0; col < line.length(); col++) {
                    char symbol = line.charAt(col);
                    if (symbol == ' ') {
                        continue;
                    }
                    List<VanillaIngredientAlternative> alternatives = key.get(symbol);
                    if (alternatives == null || alternatives.isEmpty()) {
                        return new CraftIngredient[0];
                    }
                    slots.add(CraftIngredient.vanilla(1 + row * grid + col, alternatives));
                }
            }
            return slots.toArray(CraftIngredient[]::new);
        }
        for (int i = 0; i < ingredients.size(); i++) {
            List<VanillaIngredientAlternative> alternatives = ingredients.get(i);
            if (alternatives.isEmpty()) {
                return new CraftIngredient[0];
            }
            slots.add(CraftIngredient.vanilla(1 + i, alternatives));
        }
        return slots.toArray(CraftIngredient[]::new);
    }

    List<List<VanillaIngredientAlternative>> ingredientSlots() {
        List<List<VanillaIngredientAlternative>> slots = new ArrayList<>();
        if ("minecraft:crafting_shaped".equals(type)) {
            for (String line : pattern) {
                for (int col = 0; col < line.length(); col++) {
                    char symbol = line.charAt(col);
                    if (symbol == ' ') {
                        continue;
                    }
                    List<VanillaIngredientAlternative> alternatives = key.get(symbol);
                    if (alternatives == null || alternatives.isEmpty()) {
                        return List.of();
                    }
                    slots.add(alternatives);
                }
            }
            return slots;
        }
        return ingredients;
    }
}
