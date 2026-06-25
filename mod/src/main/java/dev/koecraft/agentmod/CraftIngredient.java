package dev.koecraft.agentmod;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

record CraftIngredient(int slot, String itemId, String group, String fallbackGroup, List<VanillaIngredientAlternative> alternatives) {
    static CraftIngredient item(int slot, String itemId) {
        return new CraftIngredient(slot, itemId, "", "", List.of());
    }

    static CraftIngredient group(int slot, String group) {
        return new CraftIngredient(slot, "", group, "", List.of());
    }

    static CraftIngredient itemOrGroup(int slot, String itemId, String fallbackGroup) {
        return new CraftIngredient(slot, itemId, "", fallbackGroup, List.of());
    }

    static CraftIngredient vanilla(int slot, List<VanillaIngredientAlternative> alternatives) {
        return new CraftIngredient(slot, "", "", "", alternatives);
    }

    boolean matches(ItemStack stack) {
        String candidateItemId = Registries.ITEM.getId(stack.getItem()).toString();
        if (!itemId.isBlank() && itemId.equals(candidateItemId)) {
            return true;
        }
        if (!group.isBlank()) {
            return MinecraftItemGroups.matches(group, candidateItemId);
        }
        if (!fallbackGroup.isBlank() && MinecraftItemGroups.matches(fallbackGroup, candidateItemId)) {
            return true;
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (alternative.matches(stack, candidateItemId)) {
                return true;
            }
        }
        return false;
    }
}
