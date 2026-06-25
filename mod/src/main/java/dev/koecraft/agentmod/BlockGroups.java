package dev.koecraft.agentmod;

import java.util.Map;
import java.util.Set;

public final class BlockGroups {
    private static final Map<String, Set<String>> GROUPS = Map.ofEntries(
        Map.entry("dirt", Set.of("minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium", "minecraft:mud", "minecraft:muddy_mangrove_roots")),
        Map.entry("log", Set.of(
            "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:pale_oak_log",
            "minecraft:oak_wood", "minecraft:spruce_wood", "minecraft:birch_wood", "minecraft:jungle_wood", "minecraft:acacia_wood", "minecraft:dark_oak_wood", "minecraft:mangrove_wood", "minecraft:cherry_wood", "minecraft:pale_oak_wood",
            "minecraft:stripped_oak_log", "minecraft:stripped_spruce_log", "minecraft:stripped_birch_log", "minecraft:stripped_jungle_log", "minecraft:stripped_acacia_log", "minecraft:stripped_dark_oak_log", "minecraft:stripped_mangrove_log", "minecraft:stripped_cherry_log", "minecraft:stripped_pale_oak_log",
            "minecraft:stripped_oak_wood", "minecraft:stripped_spruce_wood", "minecraft:stripped_birch_wood", "minecraft:stripped_jungle_wood", "minecraft:stripped_acacia_wood", "minecraft:stripped_dark_oak_wood", "minecraft:stripped_mangrove_wood", "minecraft:stripped_cherry_wood", "minecraft:stripped_pale_oak_wood",
            "minecraft:crimson_stem", "minecraft:warped_stem", "minecraft:crimson_hyphae", "minecraft:warped_hyphae", "minecraft:stripped_crimson_stem", "minecraft:stripped_warped_stem", "minecraft:stripped_crimson_hyphae", "minecraft:stripped_warped_hyphae",
            "minecraft:bamboo_block", "minecraft:stripped_bamboo_block"
        )),
        Map.entry("leaves", Set.of("minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves", "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves", "minecraft:mangrove_leaves", "minecraft:cherry_leaves", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves")),
        Map.entry("stone", Set.of("minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff", "minecraft:calcite", "minecraft:dripstone_block")),
        Map.entry("cobblestone", Set.of("minecraft:cobblestone", "minecraft:cobbled_deepslate")),
        Map.entry("sand", Set.of("minecraft:sand", "minecraft:red_sand", "minecraft:suspicious_sand")),
        Map.entry("gravel", Set.of("minecraft:gravel", "minecraft:suspicious_gravel")),
        Map.entry("clay", Set.of("minecraft:clay")),
        Map.entry("flower", Set.of(
            "minecraft:dandelion",
            "minecraft:poppy",
            "minecraft:blue_orchid",
            "minecraft:allium",
            "minecraft:azure_bluet",
            "minecraft:red_tulip",
            "minecraft:orange_tulip",
            "minecraft:white_tulip",
            "minecraft:pink_tulip",
            "minecraft:oxeye_daisy",
            "minecraft:cornflower",
            "minecraft:lily_of_the_valley",
            "minecraft:sunflower",
            "minecraft:lilac",
            "minecraft:rose_bush",
            "minecraft:peony",
            "minecraft:torchflower",
            "minecraft:pink_petals",
            "minecraft:open_eyeblossom",
            "minecraft:closed_eyeblossom"
        )),
        Map.entry("cactus", Set.of("minecraft:cactus")),
        Map.entry("coal_ore", Set.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore")),
        Map.entry("iron_ore", Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")),
        Map.entry("copper_ore", Set.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore")),
        Map.entry("gold_ore", Set.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore")),
        Map.entry("redstone_ore", Set.of("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore")),
        Map.entry("lapis_ore", Set.of("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore")),
        Map.entry("diamond_ore", Set.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")),
        Map.entry("emerald_ore", Set.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore")),
        Map.entry("light_source", Set.of("minecraft:torch", "minecraft:wall_torch", "minecraft:lantern", "minecraft:soul_torch", "minecraft:soul_wall_torch", "minecraft:soul_lantern", "minecraft:campfire")),
        Map.entry("village_hint", Set.of("minecraft:bell", "minecraft:bed", "minecraft:white_bed", "minecraft:yellow_bed", "minecraft:brown_bed", "minecraft:composter", "minecraft:barrel", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:cartography_table", "minecraft:fletching_table", "minecraft:grindstone", "minecraft:lectern", "minecraft:loom", "minecraft:smithing_table", "minecraft:stonecutter", "minecraft:dirt_path", "minecraft:hay_block")),
        Map.entry("structure_hint", Set.of(
            "minecraft:bell", "minecraft:bed", "minecraft:white_bed", "minecraft:yellow_bed", "minecraft:brown_bed", "minecraft:composter", "minecraft:barrel", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:cartography_table", "minecraft:fletching_table", "minecraft:grindstone", "minecraft:lectern", "minecraft:loom", "minecraft:smithing_table", "minecraft:stonecutter", "minecraft:dirt_path", "minecraft:hay_block",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:spawner", "minecraft:bookshelf", "minecraft:chiseled_bookshelf",
            "minecraft:mossy_cobblestone", "minecraft:cobweb", "minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:infested_stone_bricks", "minecraft:infested_mossy_stone_bricks", "minecraft:infested_cracked_stone_bricks",
            "minecraft:cut_sandstone", "minecraft:chiseled_sandstone", "minecraft:smooth_sandstone", "minecraft:cut_red_sandstone", "minecraft:chiseled_red_sandstone", "minecraft:smooth_red_sandstone",
            "minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:sea_lantern",
            "minecraft:purpur_block", "minecraft:end_stone_bricks", "minecraft:nether_bricks", "minecraft:chiseled_nether_bricks", "minecraft:cracked_nether_bricks"
        )),
        Map.entry("crop", Set.of("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots", "minecraft:pumpkin", "minecraft:melon", "minecraft:sugar_cane", "minecraft:sweet_berry_bush", "minecraft:cave_vines", "minecraft:cave_vines_plant")),
        Map.entry("noncombat_food", Set.of("minecraft:sweet_berry_bush", "minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:oak_leaves", "minecraft:dark_oak_leaves"))
    );

    private BlockGroups() {}

    public static boolean hasGroup(String group) {
        return GROUPS.containsKey(group);
    }

    public static boolean matches(String group, String blockId) {
        return GROUPS.getOrDefault(group, Set.of()).contains(blockId);
    }
}
