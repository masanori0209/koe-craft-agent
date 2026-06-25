package dev.koecraft.agentmod;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

record VanillaIngredientAlternative(String itemId, String tagId) {
    boolean matches(ItemStack stack, String candidateItemId) {
        if (!itemId.isBlank()) {
            return itemId.equals(candidateItemId);
        }
        if (tagId.isBlank()) {
            return false;
        }
        try {
            return stack.isIn(TagKey.of(RegistryKeys.ITEM, Identifier.of(tagId)));
        } catch (Exception e) {
            return false;
        }
    }
}
