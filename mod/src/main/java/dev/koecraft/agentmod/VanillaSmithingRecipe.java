package dev.koecraft.agentmod;

import java.util.List;

record VanillaSmithingRecipe(
    String recipeId,
    String output,
    int count,
    List<VanillaIngredientAlternative> template,
    List<VanillaIngredientAlternative> base,
    List<VanillaIngredientAlternative> addition
) {
    CraftIngredient[] toCraftIngredients() {
        return new CraftIngredient[] {
            CraftIngredient.vanilla(0, template),
            CraftIngredient.vanilla(1, base),
            CraftIngredient.vanilla(2, addition)
        };
    }
}
