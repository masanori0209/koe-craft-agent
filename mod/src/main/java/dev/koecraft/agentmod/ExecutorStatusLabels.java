package dev.koecraft.agentmod;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExecutorStatusLabels {
    private static final Pattern MINECRAFT_ID = Pattern.compile("minecraft:[a-z0-9_]+");
    private static final Map<String, String> FRIENDLY_NAMES = Map.ofEntries(
        Map.entry("acacia_log", "アカシアの原木"),
        Map.entry("apple", "りんご"),
        Map.entry("beef", "牛肉"),
        Map.entry("birch_log", "シラカバの原木"),
        Map.entry("bread", "パン"),
        Map.entry("bucket", "バケツ"),
        Map.entry("charcoal", "木炭"),
        Map.entry("chest", "チェスト"),
        Map.entry("coal", "石炭"),
        Map.entry("cobblestone", "丸石"),
        Map.entry("cooked_beef", "焼いた牛肉"),
        Map.entry("cooked_chicken", "焼いた鶏肉"),
        Map.entry("cooked_mutton", "焼いた羊肉"),
        Map.entry("cooked_porkchop", "焼いた豚肉"),
        Map.entry("crafting_table", "作業台"),
        Map.entry("dark_oak_log", "ダークオークの原木"),
        Map.entry("dirt", "土"),
        Map.entry("flint_and_steel", "火打ち石"),
        Map.entry("furnace", "かまど"),
        Map.entry("iron_ingot", "鉄インゴット"),
        Map.entry("jungle_log", "ジャングルの原木"),
        Map.entry("lava_bucket", "溶岩バケツ"),
        Map.entry("oak_boat", "オークのボート"),
        Map.entry("oak_log", "オークの原木"),
        Map.entry("oak_planks", "オークの板材"),
        Map.entry("porkchop", "豚肉"),
        Map.entry("raw_iron", "鉄の原石"),
        Map.entry("shield", "盾"),
        Map.entry("spruce_log", "トウヒの原木"),
        Map.entry("stick", "棒"),
        Map.entry("stone", "石"),
        Map.entry("stone_axe", "石の斧"),
        Map.entry("stone_pickaxe", "石のツルハシ"),
        Map.entry("stone_shovel", "石のシャベル"),
        Map.entry("torch", "松明"),
        Map.entry("water_bucket", "水入りバケツ"),
        Map.entry("wheat", "小麦"),
        Map.entry("wooden_axe", "木の斧"),
        Map.entry("wooden_pickaxe", "木のツルハシ"),
        Map.entry("wooden_shovel", "木のシャベル")
    );

    private ExecutorStatusLabels() {
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "accepted" -> "できた";
            case "partial" -> "途中までできた";
            case "blocked" -> "できなかった";
            case "rejected" -> "やめました";
            case "aborted" -> "とめました";
            default -> status;
        };
    }

    static String actionLabel(ExecutorProtocol.Action action) {
        return switch (action.type()) {
            case "scan_state" -> "周囲を確認";
            case "scan_build_area" -> "建築場所を確認";
            case "scan_explore_area" -> "探索範囲を確認";
            case "collect_block" -> "ブロックを集める " + nonBlank(action.stringField("block"), action.stringField("block_group"));
            case "collect_honeycomb" -> "ハニカム採取";
            case "collect_honey_bottle" -> "蜂蜜瓶を採取";
            case "take_from_container" -> "チェストから探す " + nonBlank(action.stringField("item"), action.stringField("item_group"));
            case "grant_item" -> "足りない材料を用意 " + friendlyMinecraftName(action.stringField("item"));
            case "dig_pattern" -> "掘る " + action.stringField("pattern");
            case "collect_drops" -> "アイテム拾い " + nonBlank(action.stringField("item"), action.stringField("item_group"));
            case "craft" -> "作る " + friendlyMinecraftName(action.stringField("recipe"));
            case "smelt" -> "焼く " + friendlyMinecraftName(action.stringField("input")) + " -> " + friendlyMinecraftName(action.stringField("output"));
            case "smelt_food" -> "食料を焼く";
            case "open_workstation" -> "作業台や設備を開く " + friendlyMinecraftName(action.stringField("station"));
            case "close_screen" -> "画面を閉じる";
            case "ensure_hotbar" -> "手に持つ準備 " + friendlyMinecraftName(action.stringField("item"));
            case "place_block" -> "置く " + friendlyMinecraftName(action.stringField("item"));
            case "build_blueprint" -> "建物をつくる " + action.stringField("style");
            case "collect_fluid" -> "液体を汲む " + shortId(action.stringField("fluid"));
            case "build_nether_portal" -> "ネザーゲートを作る " + action.stringField("method");
            case "ignite_nether_portal" -> "ネザーゲートに火をつける";
            case "move" -> "移動 " + action.stringField("direction");
            case "build_bridge" -> "橋をかける " + action.stringField("direction");
            case "explore" -> "探索 " + action.stringField("target_group");
            case "attack_entity" -> "たたかう " + action.stringField("entity_group");
            case "defensive_move" -> "防御/後退";
            case "open_passage" -> "ドア/ゲートを開ける";
            case "celebrate" -> "リアクション " + action.stringField("style");
            case "ambient_chat" -> "会話リアクション";
            case "check_tool_durability" -> "道具耐久確認 " + action.stringField("tool_group");
            case "emergency_shelter" -> "安全な小屋を作る";
            case "escape_fluid" -> "水/溶岩から脱出";
            case "use_boat_if_water" -> "水場ならボート準備";
            case "eat_food" -> "食べる";
            case "replant_crop" -> "植え直し";
            case "drop_inventory" -> "インベントリ整理";
            case "smithing_trim" -> "防具をかざる";
            case "component_craft" -> "特別な作り方 " + action.stringField("recipe_kind");
            case "equip_gear" -> "装備準備";
            case "planner_blocked_reason" -> "止まった理由 " + blockedReasonLabel(action.stringField("reason_code"));
            case "abort" -> "停止";
            default -> action.type();
        };
    }

    private static String blockedReasonLabel(String reasonCode) {
        return switch (reasonCode) {
            case "silk_touch_required" -> "専用の道具が必要";
            case "boss_route_missing" -> "強い敵の準備が必要";
            case "mob_head_route_missing" -> "特別な素材が必要";
            case "recipe_dependency_cycle_detected" -> "作り方がぐるぐるしています";
            case "recipe_dependency_depth_exceeded" -> "作る手順が長すぎます";
            case "recipe_material_route_missing" -> "材料の集め方がまだありません";
            default -> reasonCode;
        };
    }

    static boolean isTerminalStepStatus(String status) {
        return status.equals("blocked")
            || status.equals("partial")
            || status.equals("rejected")
            || status.equals("aborted")
            || status.equals("not_implemented");
    }

    private static String nonBlank(String first, String second) {
        return !first.isBlank() ? shortId(first) : shortId(second);
    }

    private static String shortId(String value) {
        return friendlyMinecraftName(value);
    }

    static String friendlyMinecraftName(String value) {
        if (value == null || value.isBlank()) return "";
        String stripped = value.replace("minecraft:", "");
        return FRIENDLY_NAMES.getOrDefault(stripped, stripped.replace('_', ' '));
    }

    static String friendlyMessageText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = MINECRAFT_ID.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(friendlyMinecraftName(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString()
            .replace("agent task", "おねがい")
            .replace("blocked", "できない")
            .replace("partial", "途中まで")
            .replace("accepted", "できた")
            .replace("rejected", "やめた")
            .replace("aborted", "とめた");
    }
}
