package dev.koecraft.agentmod;

import java.util.List;

record VanillaStonecuttingRecipe(
    String recipeId,
    String output,
    int count,
    List<VanillaIngredientAlternative> ingredient
) {
}
