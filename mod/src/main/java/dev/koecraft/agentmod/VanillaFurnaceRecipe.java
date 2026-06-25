package dev.koecraft.agentmod;

import java.util.List;

record VanillaFurnaceRecipe(
    String recipeId,
    String type,
    String output,
    int count,
    List<VanillaIngredientAlternative> ingredient
) {
}
