package dev.koecraft.agentmod;

final class MinecraftItemGroups {
    private MinecraftItemGroups() {
    }

    static boolean hasGroup(String group) {
        return switch (group) {
            case "log", "planks", "wool", "cobblestone", "dirt", "sand", "gravel", "clay", "coal_or_charcoal", "fuel", "bundle", "shulker_box" -> true;
            default -> false;
        };
    }

    static boolean matches(String group, String itemId) {
        return switch (group) {
            case "log" -> isWoodLogLike(itemId);
            case "planks" -> itemId.endsWith("_planks");
            case "wool" -> itemId.endsWith("_wool");
            case "cobblestone" -> itemId.equals("minecraft:cobblestone") || itemId.equals("minecraft:cobbled_deepslate");
            case "dirt" -> itemId.equals("minecraft:dirt") || itemId.equals("minecraft:coarse_dirt") || itemId.equals("minecraft:rooted_dirt") || itemId.equals("minecraft:mud");
            case "sand" -> itemId.equals("minecraft:sand") || itemId.equals("minecraft:red_sand");
            case "gravel" -> itemId.equals("minecraft:gravel") || itemId.equals("minecraft:flint");
            case "clay" -> itemId.equals("minecraft:clay_ball");
            case "coal_or_charcoal" -> itemId.equals("minecraft:coal") || itemId.equals("minecraft:charcoal");
            case "fuel" -> itemId.endsWith("_planks") || isWoodLogLike(itemId) || itemId.equals("minecraft:coal") || itemId.equals("minecraft:charcoal");
            case "bundle" -> itemId.equals("minecraft:bundle") || itemId.endsWith("_bundle");
            case "shulker_box" -> itemId.equals("minecraft:shulker_box") || itemId.endsWith("_shulker_box");
            default -> false;
        };
    }

    private static boolean isWoodLogLike(String itemId) {
        return itemId.endsWith("_log")
            || itemId.endsWith("_wood")
            || itemId.endsWith("_stem")
            || itemId.endsWith("_hyphae")
            || itemId.equals("minecraft:bamboo_block")
            || itemId.equals("minecraft:stripped_bamboo_block");
    }
}
