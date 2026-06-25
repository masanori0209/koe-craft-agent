package dev.koecraft.agentmod;

import java.util.List;

record VanillaSmithingTrimRecipe(
    String recipeId,
    List<VanillaIngredientAlternative> template,
    List<VanillaIngredientAlternative> base,
    List<VanillaIngredientAlternative> addition
) {
}
