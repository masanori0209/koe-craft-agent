package dev.koecraft.agentmod;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KoeCraftUtteranceGoalRouter {
    private static final Pattern STOP_WORDS = Pattern.compile("止まって|やめて|待って|ストップ|中止|キャンセル|abort|stop", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIGHT_WORDS = Pattern.compile("松明|たいまつ|トーチ|torch|あかり|明かり|灯り|暗い|暗く|真っ暗|見えない|明るく", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACE_WORDS = Pattern.compile("置|おい|設置|つけ|付け|照ら|明るく|灯|して|お願い|頼む");
    private static final Pattern CRAFT_WORDS = Pattern.compile("作って|作る|つくって|つくる|作りたい|作れる|クラフト|生成|用意して|欲しい|ほしい|必要|お願い|頼む");
    private static final Pattern WORKSTATION_WORDS = Pattern.compile("作業台|クラフト台|craftingtable|crafting_table", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKSTATION_PLACE_WORDS = Pattern.compile("近く|そば|置|おい|開|出して|用意|お願い|頼む", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELEBRATE_WORDS = Pattern.compile("やった|やったー|やったあ|いえーい|いぇーい|イエーイ|よっしゃ|よっしゃー|おおー|おー+|すごい|すげえ|すげー|ナイス|nice|yeah|yay|woo|勝った|クリア|最高", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVE_WORDS = Pattern.compile("まっすぐ|真っ直ぐ|歩いて|歩きたい|あるいて|歩け|走って|走りたい|はしって|走れ|ダッシュ|進んで|進め|すすんで|すすめ|すすむ|前進|移動して|下がって|下がりたい|ずれて|ずれ|避けて|よけて|抜けて|抜けたい|出て|出たい|泳いで|泳ぎたい|およいで|泳げ|swim|move|walk|run|sprint|go", Pattern.CASE_INSENSITIVE);
    private static final Pattern SWIM_WORDS = Pattern.compile("泳いで|泳ぎたい|およいで|泳げ|swim", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPRINT_JUMP_WORDS = Pattern.compile("走りながらジャンプ|走ってジャンプ|ダッシュジャンプ|ジャンプしながら|sprint.?jump|jump.?sprint", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICKUP_WORDS = Pattern.compile("拾って|拾いたい|拾え|拾う|取って|とって|取る|落ちてる|ドロップ|アイテム回収|回収して|pick.?up|pickup|collectitems?", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_WORDS = Pattern.compile("開けて|開いて|ドア|扉|ゲート|フェンスゲート|通って|通りたい|open", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOOD_WORDS = Pattern.compile("食べて|食べたい|腹減|お腹すい|空腹|ご飯|ごはん|飯|めし|食料|食べ物|ベリー|リンゴ|りんご|肉|パン|ほしい|欲しい");
    private static final Pattern HARVEST_WORDS = Pattern.compile("収穫|刈って|作物|畑|成熟");
    private static final Pattern ATTACK_WORDS = Pattern.compile("倒して|倒したい|攻撃|戦って|戦いたい|狩って|狩りたい|狩猟|やっつけて");
    private static final Pattern DEFEND_WORDS = Pattern.compile("守って|防御|ガード|盾構えて|盾を構えて|耐えて");
    private static final Pattern RETREAT_WORDS = Pattern.compile("逃げて|逃げたい|離れて|距離取って|距離を取って|下がって|退避|避けて|よけて");
    private static final Pattern NETHER_WORDS = Pattern.compile("ネザー|nether|地獄|ゲート|ポータル|portal");
    private static final Pattern ENDER_DRAGON_WORDS = Pattern.compile("エンドラ|エンダードラゴン|エンド.*ドラゴン|ender.?dragon|dragon.*fight|ドラゴン.*倒|討伐", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH_VILLAGE_WORDS = Pattern.compile("村を探|村探|村.*見つけ|village");
    private static final Pattern SEARCH_STRUCTURE_WORDS = Pattern.compile("構造物|建物|寺院|ピラミッド|砂漠の寺院|ジャングルの寺院|廃坑|要塞|砦|沈没船|海底神殿|遺跡|ダンジョン|スポナー|チェスト|structure|temple|mineshaft|stronghold|shipwreck|dungeon", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH_ACTION_WORDS = Pattern.compile("探して|探したい|探せ|探索して|見つけて|見つけたい|行きたい|向かって|search|find|locate", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRIDGE_WORDS = Pattern.compile("橋|はし|ハシ|ハッシュ|bridge", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRIDGE_ACTION_WORDS = Pattern.compile("架け|かけ|掛け|作|つく|置|渡|伸ば|前|forward|build|place", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIG_WORDS = Pattern.compile("階段掘り|階段ぼり|階段堀り|横穴|横掘り|横ぼり|トンネル|縦掘り|縦ぼり|下掘り|下に掘|掘って|掘りたい|掘れ");
    private static final Pattern CHILD_SHELTER_WORDS = Pattern.compile("家|おうち|お家|秘密基地|基地|隠れ家|かくれが|部屋|お部屋|小屋");
    private static final Pattern PRETTY_BUILD_WORDS = Pattern.compile("きれい|綺麗|かわいい|可愛い|ちゃんと|おしゃれ|いい感じ|立派|建築");
    private static final Pattern BIG_BUILD_WORDS = Pattern.compile("城|お城|町|街|村を作|でっかい|大きい|大きな|巨大|すごい家|豪邸");
    private static final Pattern CHILD_HELP_WORDS = Pattern.compile("助けて|たすけて|こわい|怖い|やばい|危ない|あぶない|守って|まもって");
    private static final Pattern CHILD_CONFUSED_WORDS = Pattern.compile("わからない|分からない|わかんない|分かんない|なにすれば|何すれば|何したら|どうしたら|どうすれば|迷った|まよった");
    private static final Pattern WOODEN_TOOL_ASR_CONFUSION = Pattern.compile("(昨日|きのう)(の)?(ツルハシ|つるはし|ピッケル|ぴっける|斧|おの|シャベル|しゃべる|スコップ|剣|けん|クワ|くわ)");

    Optional<JsonObject> route(String recognizedText) {
        String text = normalize(recognizedText);
        if (text.isBlank()) {
            return Optional.empty();
        }
        if (STOP_WORDS.matcher(text).find()) {
            return Optional.of(goal("abort"));
        }
        Optional<JsonObject> corrected = routeSelfCorrection(text);
        if (corrected.isPresent()) {
            return corrected;
        }
        if (CELEBRATE_WORDS.matcher(text).find()) {
            JsonObject goal = goal("celebrate");
            goal.addProperty("style", text.contains("やった") || text.contains("いえ") || text.contains("yeah") || text.contains("yay") ? "dance" : "youtuber_pose");
            return Optional.of(goal);
        }
        if (LIGHT_WORDS.matcher(text).find() && PLACE_WORDS.matcher(text).find()) {
            JsonObject goal = goal("place_light");
            goal.addProperty("target_item", "minecraft:torch");
            return Optional.of(goal);
        }
        if (WORKSTATION_WORDS.matcher(text).find() && WORKSTATION_PLACE_WORDS.matcher(text).find()) {
            JsonObject goal = goal("place_workstation");
            goal.addProperty("station", "minecraft:crafting_table");
            return Optional.of(goal);
        }
        if (ENDER_DRAGON_WORDS.matcher(text).find()) {
            JsonObject goal = goal("prepare_nether");
            goal.addProperty("route", "bucket_portal");
            return Optional.of(goal);
        }
        if (NETHER_WORDS.matcher(text).find() && !text.contains("フェンスゲート")) {
            JsonObject goal = goal("prepare_nether");
            if (text.contains("黒曜石") || text.contains("こくようせき") || text.contains("obsidian")) {
                goal.addProperty("route", "obsidian_frame");
            } else if (text.contains("溶岩") || text.contains("ようがん") || text.contains("lava")) {
                goal.addProperty("route", "lava_cast");
            } else {
                goal.addProperty("route", "bucket_portal");
            }
            return Optional.of(goal);
        }
        Optional<JsonObject> childFriendly = routeChildFriendly(text);
        if (childFriendly.isPresent()) {
            return childFriendly;
        }
        Optional<JsonObject> craft = routeCraft(text);
        if (craft.isPresent()) {
            return craft;
        }
        if (isMinecraftFoodRequest(text)) {
            return Optional.of(goal("get_food"));
        }
        if (SEARCH_VILLAGE_WORDS.matcher(text).find()) {
            JsonObject goal = goal("search_structure");
            goal.addProperty("target_group", "village_hint");
            return Optional.of(goal);
        }
        if (SEARCH_STRUCTURE_WORDS.matcher(text).find() && SEARCH_ACTION_WORDS.matcher(text).find()) {
            JsonObject goal = goal("search_structure");
            goal.addProperty("target_group", "structure_hint");
            return Optional.of(goal);
        }
        if (BRIDGE_WORDS.matcher(text).find() && BRIDGE_ACTION_WORDS.matcher(text).find() && !isToolRequest(text)) {
            JsonObject goal = goal("build_bridge");
            goal.addProperty("direction", moveDirection(text));
            goal.addProperty("distance_blocks", parseBlocks(text, 4, 1, 12));
            return Optional.of(goal);
        }
        if (HARVEST_WORDS.matcher(text).find()) {
            JsonObject goal = goal("context_action");
            goal.addProperty("intent", "harvest");
            return Optional.of(goal);
        }
        if (DEFEND_WORDS.matcher(text).find()) {
            JsonObject goal = goal("context_action");
            goal.addProperty("intent", "defend");
            return Optional.of(goal);
        }
        if (isExplicitMovement(text) && MOVE_WORDS.matcher(text).find()) {
            return Optional.of(moveGoal(text));
        }
        if (RETREAT_WORDS.matcher(text).find()) {
            JsonObject goal = goal("context_action");
            goal.addProperty("intent", "retreat");
            return Optional.of(goal);
        }
        if (ATTACK_WORDS.matcher(text).find()) {
            JsonObject goal = goal("attack_entity");
            goal.addProperty("entity_group", isFoodAnimal(text) ? "food_animal" : "hostile");
            return Optional.of(goal);
        }
        if (isMinecraftOpenRequest(text)) {
            JsonObject goal = goal("context_action");
            goal.addProperty("intent", "open");
            return Optional.of(goal);
        }
        Optional<JsonObject> collect = routeCollectBlock(text);
        if (collect.isPresent()) {
            return collect;
        }
        if (DIG_WORDS.matcher(text).find()) {
            JsonObject goal = goal("dig_pattern");
            goal.addProperty("pattern", digPattern(text));
            goal.addProperty("distance_blocks", parseBlocks(text, 4, 1, 16));
            return Optional.of(goal);
        }
        if (PICKUP_WORDS.matcher(text).find() || (text.contains("アイテム") && (text.contains("取") || text.contains("回収")))) {
            return Optional.of(goal("pickup_items"));
        }
        if (MOVE_WORDS.matcher(text).find()) {
            return Optional.of(moveGoal(text));
        }
        if (Pattern.compile("探して|探したい|探せ|探索して|見つけて|見つけたい").matcher(text).find()) {
            JsonObject goal = goal("ambient_chat");
            goal.addProperty("message", "何を探す？村・食料・素材なら言ってね。");
            goal.addProperty("style", "shrug");
            goal.addProperty("duration_ticks", 90);
            return Optional.of(goal);
        }
        return Optional.empty();
    }

    private Optional<JsonObject> routeChildFriendly(String text) {
        if (CHILD_CONFUSED_WORDS.matcher(text).find()) {
            JsonObject goal = goal("ambient_chat");
            goal.addProperty("message", "まず三つ。木を集める、家を作る、村を探す。どれにする？");
            goal.addProperty("style", "nod");
            goal.addProperty("duration_ticks", 120);
            return Optional.of(goal);
        }
        if (BIG_BUILD_WORDS.matcher(text).find() && (text.contains("作") || text.contains("つく") || text.contains("建") || text.contains("ほしい") || text.contains("欲しい"))) {
            JsonObject goal = goal("ambient_chat");
            goal.addProperty("message", "まず小さい家から作る？そう言ってくれたら作るよ。");
            goal.addProperty("style", "nod");
            goal.addProperty("duration_ticks", 100);
            return Optional.of(goal);
        }
        if (CHILD_HELP_WORDS.matcher(text).find() && !DEFEND_WORDS.matcher(text).find() && !Pattern.compile("敵|モンスター|ゾンビ|スケルトン|クリーパー|盾|ガード").matcher(text).find()) {
            JsonObject goal = goal("build_shelter");
            goal.addProperty("style", "safe_spot");
            return Optional.of(goal);
        }
        if (CHILD_SHELTER_WORDS.matcher(text).find() && (text.contains("作") || text.contains("つく") || text.contains("建") || text.contains("ほしい") || text.contains("欲しい") || text.contains("お願い"))) {
            JsonObject goal = goal("build_structure");
            goal.addProperty("style", text.contains("秘密") || text.contains("基地") ? "hideout" : PRETTY_BUILD_WORDS.matcher(text).find() ? "cute_house" : "small_house");
            goal.addProperty("size", text.contains("小さ") || text.contains("ちいさ") ? "tiny" : "small");
            goal.addProperty("palette", "available");
            return Optional.of(goal);
        }
        if (Pattern.compile("キラキラ|ぴかぴか|光る|光ってる").matcher(text).find() && Pattern.compile("探|ほしい|欲しい|見つけ").matcher(text).find()) {
            JsonObject goal = goal("ambient_chat");
            goal.addProperty("message", "光るもの？松明・鉱石・村、どれを探す？");
            goal.addProperty("style", "shrug");
            goal.addProperty("duration_ticks", 100);
            return Optional.of(goal);
        }
        return Optional.empty();
    }

    private Optional<JsonObject> routeSelfCorrection(String text) {
        if (!(text.contains("いや") || text.contains("やっぱ") || text.contains("先に"))) {
            return Optional.empty();
        }
        String active = text;
        int index = Math.max(text.lastIndexOf("いや"), Math.max(text.lastIndexOf("やっぱ"), text.lastIndexOf("先に")));
        if (index >= 0 && index + 2 < text.length()) {
            active = text.substring(index);
        }
        return routeCraft(active);
    }

    private Optional<JsonObject> routeCraft(String text) {
        if (!CRAFT_WORDS.matcher(text).find()) {
            return Optional.empty();
        }
        String item = craftTarget(text);
        if (item.isBlank()) {
            return Optional.empty();
        }
        JsonObject goal = goal("craft_item");
        goal.addProperty("target_item", item);
        return Optional.of(goal);
    }

    private boolean isToolRequest(String text) {
        return text.contains("ツルハシ")
            || text.contains("つるはし")
            || text.contains("ピッケル")
            || text.contains("ぴっける")
            || text.contains("pickaxe");
    }

    private String craftTarget(String text) {
        if (text.contains("ダイヤのツルハシ") || text.contains("ダイヤツルハシ") || text.contains("ダイヤのピッケル") || text.contains("diamondpickaxe") || text.contains("diamond_pickaxe")) {
            return "minecraft:diamond_pickaxe";
        }
        if (text.contains("鉄のツルハシ") || text.contains("鉄ツルハシ") || text.contains("鉄のピッケル") || text.contains("ironpickaxe") || text.contains("iron_pickaxe")) {
            return "minecraft:iron_pickaxe";
        }
        if (text.contains("木のツルハシ") || text.contains("木のつるはし") || text.contains("木ツルハシ") || text.contains("木つるはし") || text.contains("木のピッケル") || text.contains("木のぴっける") || text.contains("woodenpickaxe") || text.contains("wooden_pickaxe")) {
            return "minecraft:wooden_pickaxe";
        }
        if (text.contains("石のツルハシ") || text.contains("石ツルハシ") || text.contains("石ピッケル") || text.contains("石のピッケル") || text.contains("stonepickaxe") || text.contains("stone_pickaxe")) {
            return "minecraft:stone_pickaxe";
        }
        if (text.contains("作業台") || text.contains("クラフト台") || text.contains("craftingtable") || text.contains("crafting_table")) {
            return "minecraft:crafting_table";
        }
        if (text.contains("棒") || text.contains("stick")) {
            return "minecraft:stick";
        }
        if (text.contains("かまど") || text.contains("竈") || text.contains("furnace")) {
            return "minecraft:furnace";
        }
        if (text.contains("松明") || text.contains("たいまつ") || text.contains("トーチ") || text.contains("torch")) {
            return "minecraft:torch";
        }
        if (text.contains("盾") || text.contains("シールド") || text.contains("shield")) {
            return "minecraft:shield";
        }
        if (text.contains("チェスト") || text.contains("chest")) {
            return "minecraft:chest";
        }
        if (text.contains("ツルハシ") || text.contains("つるはし") || text.contains("ピッケル") || text.contains("ぴっける") || text.contains("pickaxe")) {
            return "minecraft:stone_pickaxe";
        }
        if (text.contains("ガラス") || text.contains("glass")) {
            return "minecraft:glass";
        }
        if (text.contains("ボート") || text.contains("boat")) {
            return "minecraft:boat";
        }
        if (text.contains("剣") || text.contains("sword")) {
            return "minecraft:sword";
        }
        if (text.contains("ベッド") || text.contains("bed")) {
            return "minecraft:bed";
        }
        if ((text.contains("石") || text.contains("stone")) && (text.contains("階段") || text.contains("stairs"))) {
            return "minecraft:stone_stairs_family";
        }
        if (text.contains("バケツ") || text.contains("bucket")) {
            return "minecraft:bucket";
        }
        if (text.contains("火打石") || text.contains("打ち金") || text.contains("flintandsteel") || text.contains("flint_and_steel")) {
            return "minecraft:flint_and_steel";
        }
        return "";
    }

    private Optional<JsonObject> routeCollectBlock(String text) {
        String group = blockGroup(text);
        if (group.isBlank()) {
            return Optional.empty();
        }
        if (!(text.contains("掘") || text.contains("集め") || text.contains("取") || text.contains("切") || text.contains("伐採") || text.contains("ほしい") || text.contains("欲しい") || text.contains("collect"))) {
            return Optional.empty();
        }
        JsonObject goal = goal("collect_block");
        goal.addProperty("block_group", group);
        goal.addProperty("count", parseBlocks(text, 1, 1, 32));
        return Optional.of(goal);
    }

    private String blockGroup(String text) {
        if (text.contains("石炭") || text.contains("coal")) {
            return "coal_ore";
        }
        if (text.contains("土") || text.contains("dirt")) {
            return "dirt";
        }
        if (text.contains("砂利") || text.contains("gravel")) {
            return "gravel";
        }
        if (text.contains("砂") || text.contains("sand")) {
            return "sand";
        }
        if (text.contains("丸石") || text.contains("石") || text.contains("cobblestone")) {
            return "cobblestone";
        }
        if (text.contains("原木") || text.contains("木") || text.contains("log")) {
            return "log";
        }
        return "";
    }

    private boolean isFoodAnimal(String text) {
        return Pattern.compile("牛|豚|ブタ|鶏|ニワトリ|羊|ウサギ|鮭|サケ|鱈|タラ|動物|肉").matcher(text).find();
    }

    private boolean isMinecraftFoodRequest(String text) {
        if (!FOOD_WORDS.matcher(text).find()) {
            return false;
        }
        if (Pattern.compile("ラーメン|寿司|すし|カレー|ピザ|コーヒー|お茶|酒|ビール|レストラン|外食").matcher(text).find()) {
            return false;
        }
        if (Pattern.compile("ベリー|リンゴ|りんご|肉|パン|食料|食べ物|空腹|腹減|お腹すい|ハート|満腹度").matcher(text).find()) {
            return true;
        }
        return Pattern.compile("ゲーム内|マイクラ|サバイバル|食べられるもの|食べれるもの").matcher(text).find();
    }

    private boolean isMinecraftOpenRequest(String text) {
        if (!OPEN_WORDS.matcher(text).find()) {
            return false;
        }
        if (Pattern.compile("ブラウザ|ニュース|アプリ|サイト|url|URL|ページ|メール|予定|カレンダー").matcher(text).find()) {
            return false;
        }
        return Pattern.compile("ドア|扉|ゲート|フェンスゲート|通路|柵|村|チェスト|かまど|作業台").matcher(text).find();
    }

    private String digPattern(String text) {
        if (text.contains("階段")) {
            return "staircase_down";
        }
        if (text.contains("横") || text.contains("トンネル")) {
            return "tunnel_forward";
        }
        if (text.contains("縦") || text.contains("下")) {
            return "shaft_down_safe";
        }
        return "staircase_down";
    }

    private String moveDirection(String text) {
        if (text.contains("右")) return "right";
        if (text.contains("左")) return "left";
        if (text.contains("後ろ") || text.contains("戻")) return "back";
        return "forward";
    }

    private JsonObject moveGoal(String text) {
        JsonObject goal = goal("move");
        goal.addProperty("direction", moveDirection(text));
        boolean swim = SWIM_WORDS.matcher(text).find();
        boolean sprint = Pattern.compile("走|ダッシュ|抜け|run|sprint", Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean explicitDistance = hasExplicitMoveDistance(text);
        int fallbackDistance = swim ? 10 : sprint ? 12 : (text.contains("まっすぐ") || text.contains("真っ直ぐ") || text.contains("進") || text.contains("すす")) ? 12 : 6;
        goal.addProperty("distance_blocks", parseBlocks(text, fallbackDistance, 1, 96));
        goal.addProperty("scan_aware", true);
        goal.addProperty("adaptive_distance", true);
        goal.addProperty("extend_if_clear", !explicitDistance);
        goal.addProperty("max_adaptive_distance_blocks", swim ? 18 : sprint ? 24 : 16);
        goal.addProperty("face_move_direction", true);
        goal.addProperty("auto_jump", true);
        boolean sprintJump = SPRINT_JUMP_WORDS.matcher(text).find();
        if (swim) {
            goal.addProperty("sprint", true);
            goal.addProperty("swim", true);
        }
        if (sprint) {
            goal.addProperty("sprint", true);
        }
        if (sprintJump) {
            goal.addProperty("sprint", true);
            goal.addProperty("jump_while_sprinting", true);
        }
        return goal;
    }

    private boolean isExplicitMovement(String text) {
        return text.contains("まっすぐ") || text.contains("真っ直ぐ") || text.contains("後ろ") || text.contains("前") || text.contains("右") || text.contains("左") || Pattern.compile("\\d{1,3}(ブロック|段|マス|歩|個|こ|つ)?").matcher(text).find();
    }

    private int parseBlocks(String text, int fallback, int min, int max) {
        Matcher matcher = Pattern.compile("(\\d{1,3})(ブロック|段|マス|歩|個|こ|つ|本)?").matcher(text);
        if (matcher.find()) {
            return clamp(Integer.parseInt(matcher.group(1)), min, max);
        }
        String[] words = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 0; i < words.length; i++) {
            if (text.contains(words[i] + "ブロック") || text.contains(words[i] + "段") || text.contains(words[i] + "マス")) {
                return clamp(i + 1, min, max);
            }
        }
        if (text.contains("少し") || text.contains("ちょっと")) {
            return clamp(2, min, max);
        }
        if (text.contains("しばらく") || text.contains("長く") || text.contains("深く")) {
            return clamp(8, min, max);
        }
        return clamp(fallback, min, max);
    }

    private boolean hasExplicitMoveDistance(String text) {
        if (Pattern.compile("\\d{1,3}(ブロック|段|マス|歩|個|こ|つ|本)?").matcher(text).find()) {
            return true;
        }
        String[] words = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (String word : words) {
            if (text.contains(word + "ブロック") || text.contains(word + "段") || text.contains(word + "マス") || text.contains(word + "歩")) {
                return true;
            }
        }
        return text.contains("少し") || text.contains("ちょっと") || text.contains("しばらく") || text.contains("長く");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private JsonObject goal(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }

    private String normalize(String text) {
        String normalized = KoeCraftAsrPostNormalizer.normalize(text).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!Pattern.compile("作|つく|クラフト|ほしい|欲しい|お願い|頼む").matcher(normalized).find()) {
            return normalized;
        }
        return WOODEN_TOOL_ASR_CONFUSION.matcher(normalized).replaceAll("木の$3");
    }
}
