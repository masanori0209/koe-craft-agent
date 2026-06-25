package dev.koecraft.agentmod;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

final class KoeCraftNativeGoalPlanner {
    private static final int MAX_RECIPE_RECURSION_DEPTH = 32;
    private static final Pattern MOVE_WORDS = Pattern.compile("まっすぐ|真っ直ぐ|歩いて|歩きたい|あるいて|歩け|走って|走りたい|はしって|走れ|ダッシュ|進んで|進め|すすんで|すすめ|すすむ|前進|移動して|ずれて|ずれ|避けて|よけて|抜けて|抜けたい|出て|出たい|泳いで|泳ぎたい|およいで|泳げ|swim|move|walk|run|sprint|go", Pattern.CASE_INSENSITIVE);
    private static final Pattern SWIM_WORDS = Pattern.compile("泳いで|泳ぎたい|およいで|泳げ|swim", Pattern.CASE_INSENSITIVE);
    private static final Pattern JUMP_WORDS = Pattern.compile("ジャンプ|飛んで|跳んで|jump", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPRINT_JUMP_WORDS = Pattern.compile("走りながらジャンプ|走ってジャンプ|ダッシュジャンプ|ジャンプしながら|sprint.?jump|jump.?sprint", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICKUP_WORDS = Pattern.compile("拾って|拾いたい|拾え|拾う|取って|とって|取る|落ちてる|ドロップ|アイテム回収|回収して|pick.?up|pickup|collectitems?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE_WORDS = Pattern.compile("閉じて|閉めて|画面閉じ|インベントリ閉じ|インベントリから出|戻って|キャンセル|close|escape|esc", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIG_WORDS = Pattern.compile("階段掘り|階段ぼり|階段堀り|横穴|横掘り|横ぼり|トンネル|縦掘り|縦ぼり|下掘り|下に掘|掘って|掘りたい|掘れ");
    private static final Pattern CELEBRATE_WORDS = Pattern.compile("やった|やったー|やったあ|いえーい|いぇーい|イエーイ|よっしゃ|よっしゃー|おおー|おー+|すごい|すげえ|すげー|ナイス|nice|yeah|yay|woo|勝った|クリア|最高", Pattern.CASE_INSENSITIVE);
    private static final Pattern TORCH_WORDS = Pattern.compile("松明|たいまつ|トーチ|torch|暗い|暗すぎ|真っ暗|見えない|明るく", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOOD_WORDS = Pattern.compile("食べて|食べたい|腹減|お腹すい|空腹|ご飯|ごはん|飯|めし|食料|食べ物");
    private static final Pattern ATTACK_WORDS = Pattern.compile("倒して|倒したい|攻撃|戦って|戦いたい|狩って|狩りたい|狩猟|やっつけて");
    private static final Pattern DEFEND_WORDS = Pattern.compile("守って|防御|ガード|盾構えて|盾を構えて|耐えて");
    private static final Pattern RETREAT_WORDS = Pattern.compile("逃げて|逃げたい|離れて|距離取って|距離を取って|下がって|退避|避けて|よけて");
    private static final Pattern SEARCH_WORDS = Pattern.compile("村を探|村探|村.*見つけ|village|構造物|建物|寺院|ピラミッド|廃坑|要塞|沈没船|ダンジョン|スポナー|探して|探したい|見つけて|見つけたい", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRUCTURE_HINT_WORDS = Pattern.compile("構造物|建物|寺院|ピラミッド|廃坑|要塞|沈没船|ダンジョン|スポナー|structure|temple|mineshaft|stronghold|shipwreck|dungeon", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_WORDS = Pattern.compile("止まって|やめて|待って|ストップ|中止|キャンセル|abort|stop", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRAFT_WORDS = Pattern.compile("作って|作りたい|作れる|クラフト|生成|用意して|欲しい|ほしい|必要|お願い|頼む");
    private static final Pattern NETHER_WORDS = Pattern.compile("ネザー|nether|地獄|ゲート|ポータル|portal");
    private static final Pattern WOODEN_TOOL_ASR_CONFUSION = Pattern.compile("(昨日|きのう)(の)?(ツルハシ|つるはし|ピッケル|ぴっける|斧|おの|シャベル|しゃべる|スコップ|剣|けん|クワ|くわ)");
    private static final WoodKind[] WOOD_KINDS = {
        new WoodKind("oak", "minecraft:oak_log", "minecraft:oak_planks", 4),
        new WoodKind("spruce", "minecraft:spruce_log", "minecraft:spruce_planks", 4),
        new WoodKind("birch", "minecraft:birch_log", "minecraft:birch_planks", 4),
        new WoodKind("jungle", "minecraft:jungle_log", "minecraft:jungle_planks", 4),
        new WoodKind("acacia", "minecraft:acacia_log", "minecraft:acacia_planks", 4),
        new WoodKind("dark_oak", "minecraft:dark_oak_log", "minecraft:dark_oak_planks", 4),
        new WoodKind("mangrove", "minecraft:mangrove_log", "minecraft:mangrove_planks", 4),
        new WoodKind("cherry", "minecraft:cherry_log", "minecraft:cherry_planks", 4),
        new WoodKind("pale_oak", "minecraft:pale_oak_log", "minecraft:pale_oak_planks", 4),
        new WoodKind("bamboo", "minecraft:bamboo_block", "minecraft:bamboo_planks", 2),
        new WoodKind("crimson", "minecraft:crimson_stem", "minecraft:crimson_planks", 4),
        new WoodKind("warped", "minecraft:warped_stem", "minecraft:warped_planks", 4)
    };
    private final MinecraftClient client;
    private final KoeCraftUtteranceGoalRouter utteranceRouter = new KoeCraftUtteranceGoalRouter();
    private final VanillaRecipeCatalog recipeCatalog = new VanillaRecipeCatalog(KoeCraftNativeGoalPlanner.class);
    private final MinecraftVanillaTerms vanillaTerms = new MinecraftVanillaTerms(KoeCraftNativeGoalPlanner.class);
    private final MaterialAcquisitionRules acquisitionRules = new MaterialAcquisitionRules(KoeCraftNativeGoalPlanner.class);
    private final KoeCraftVoiceConfig voiceConfig;

    KoeCraftNativeGoalPlanner(MinecraftClient client) {
        this(client, null);
    }

    KoeCraftNativeGoalPlanner(MinecraftClient client, KoeCraftVoiceConfig voiceConfig) {
        this.client = client;
        this.voiceConfig = voiceConfig;
    }

    Optional<NativePlan> planRuleBased(String recognizedText) {
        String text = normalize(recognizedText);
        if (text.isBlank()) {
            return Optional.empty();
        }
        if (STOP_WORDS.matcher(text).find()) {
            return Optional.of(plan("abort", "rule", action("abort")));
        }
        if (CLOSE_WORDS.matcher(text).find()) {
            return Optional.of(plan("close_screen", "rule", action("close_screen")));
        }
        Optional<JsonObject> routedGoal = utteranceRouter.route(text);
        if (routedGoal.isPresent() && isSelfCorrection(text)) {
            return planLlmGoal(routedGoal.get()).map(plan -> new NativePlan(plan.goal(), "rule_router", plan.actions()));
        }
        if (CELEBRATE_WORDS.matcher(text).find()) {
            return Optional.of(planCelebrate(text, "rule"));
        }
        if (TORCH_WORDS.matcher(text).find() && Pattern.compile("置|設置|つけ|付け|照ら|明るく|灯|おいて").matcher(text).find()) {
            return Optional.of(planPlaceTorch("rule"));
        }
        if (NETHER_WORDS.matcher(text).find() && Pattern.compile("行きたい|行く|入りたい|入る|準備|作|つく|ゲート|ポータル|portal|prepare|build", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            return Optional.of(planPrepareNether(text.contains("黒曜石") || text.contains("こくようせき") || text.contains("obsidian") ? "obsidian_frame" : "lava_cast", "rule"));
        }
        if (routedGoal.isPresent() && string(routedGoal.get(), "type").equals("place_workstation")) {
            return planLlmGoal(routedGoal.get()).map(plan -> new NativePlan(plan.goal(), "rule_router", plan.actions()));
        }
        Optional<NativePlan> craftPlan = planCraftFromText(text, "rule");
        if (craftPlan.isPresent()) {
            return craftPlan;
        }
        if (PICKUP_WORDS.matcher(text).find() || (text.contains("アイテム") && (text.contains("取") || text.contains("回収")))) {
            return Optional.of(planPickup("rule"));
        }
        if (FOOD_WORDS.matcher(text).find()) {
            return Optional.of(planEatFood("rule"));
        }
        if (routedGoal.isPresent() && string(routedGoal.get(), "type").equals("move") && isExplicitMovement(text)) {
            return planLlmGoal(routedGoal.get()).map(plan -> new NativePlan(plan.goal(), "rule_router", plan.actions()));
        }
        if (RETREAT_WORDS.matcher(text).find()) {
            return Optional.of(planRetreat("rule"));
        }
        if (DEFEND_WORDS.matcher(text).find()) {
            return Optional.of(planDefend("rule"));
        }
        if (ATTACK_WORDS.matcher(text).find()) {
            return Optional.of(planAttack(text, "rule"));
        }
        if (SEARCH_WORDS.matcher(text).find() && (text.contains("村") || text.contains("village"))) {
            return Optional.of(planSearchStructure("village_hint", "rule"));
        }
        if (SEARCH_WORDS.matcher(text).find() && STRUCTURE_HINT_WORDS.matcher(text).find()) {
            return Optional.of(planSearchStructure("structure_hint", "rule"));
        }
        if (routedGoal.isPresent() && string(routedGoal.get(), "type").equals("collect_block")) {
            return planLlmGoal(routedGoal.get()).map(plan -> new NativePlan(plan.goal(), "rule_router", plan.actions()));
        }
        if (DIG_WORDS.matcher(text).find()) {
            return Optional.of(planDig(text, "rule"));
        }
        if (JUMP_WORDS.matcher(text).find()) {
            return Optional.of(planMoveFields("forward", 3, true, false, true, false, "rule"));
        }
        if (MOVE_WORDS.matcher(text).find()) {
            return Optional.of(planMove(text, "rule"));
        }
        if (routedGoal.isPresent()) {
            return planLlmGoal(routedGoal.get()).map(plan -> new NativePlan(plan.goal(), "rule_router", plan.actions()));
        }
        return Optional.empty();
    }

    Optional<NativePlan> planLlmGoal(JsonObject goal) {
        String type = string(goal, "type");
        return switch (type) {
            case "move" -> Optional.of(planMoveGoal(goal, "llm"));
            case "build_shelter" -> Optional.of(planBuildShelter(goal, "llm"));
            case "build_structure" -> Optional.of(planBuildStructure(goal, "llm"));
            case "build_bridge" -> Optional.of(planBuildBridge(goal, "llm"));
            case "pickup_items" -> Optional.of(planPickup("llm"));
            case "close_screen" -> Optional.of(plan("close_screen", "llm", action("close_screen")));
            case "dig_pattern" -> Optional.of(planDigGoal(goal, "llm"));
            case "celebrate" -> Optional.of(planCelebrateGoal(goal, "llm"));
            case "ambient_chat" -> Optional.of(planAmbientChat(goal, "llm"));
            case "place_light" -> Optional.of(planPlaceTorch("llm"));
            case "place_workstation" -> Optional.of(planPlaceWorkstation(goal, "llm"));
            case "collect_block" -> Optional.of(planCollectBlockGoal(goal, "llm"));
            case "craft_item" -> planCraftItem(string(goal, "target_item"), string(goal, "recipe_id"), "llm");
            case "get_food" -> Optional.of(planEatFood("llm"));
            case "attack_entity" -> Optional.of(planAttackGoal(goal, "llm"));
            case "prepare_nether" -> Optional.of(planPrepareNether(string(goal, "route"), "llm"));
            case "context_action" -> planContextAction(goal);
            case "search_structure" -> Optional.of(planSearchStructure(string(goal, "target_group"), "llm"));
            case "abort" -> Optional.of(plan("abort", "llm", action("abort")));
            default -> Optional.empty();
        };
    }

    private Optional<NativePlan> planContextAction(JsonObject goal) {
        return switch (string(goal, "intent")) {
            case "take" -> Optional.of(planPickup("llm"));
            case "attack" -> Optional.of(planAttackGoal(json("entity_group", "hostile", "count", 1), "llm"));
            case "mine" -> Optional.of(planDig("掘って", "llm"));
            case "open" -> Optional.of(plan("open_passage", "llm", with(action("open_passage"), "timeout_ticks", 120)));
            case "harvest" -> Optional.of(planHarvestCrops("llm"));
            case "retreat" -> Optional.of(planRetreat("llm"));
            case "defend" -> Optional.of(planDefend("llm"));
            default -> Optional.empty();
        };
    }

    private Optional<NativePlan> planCraftFromText(String text, String source) {
        if (!CRAFT_WORDS.matcher(text).find()) {
            return Optional.empty();
        }
        if (text.contains("ダイヤのツルハシ") || text.contains("ダイヤツルハシ") || text.contains("ダイヤのピッケル") || text.contains("diamondpickaxe") || text.contains("diamond_pickaxe")) {
            return planCraftItem("minecraft:diamond_pickaxe", source);
        }
        if (text.contains("鉄のツルハシ") || text.contains("鉄ツルハシ") || text.contains("鉄のピッケル") || text.contains("ironpickaxe") || text.contains("iron_pickaxe")) {
            return planCraftItem("minecraft:iron_pickaxe", source);
        }
        if (text.contains("木のツルハシ") || text.contains("木のつるはし") || text.contains("木ツルハシ") || text.contains("木つるはし") || text.contains("木ピッケル") || text.contains("木ぴっける") || text.contains("woodenpickaxe") || text.contains("wooden_pickaxe")) {
            return planCraftItem("minecraft:wooden_pickaxe", source);
        }
        if (text.contains("石のツルハシ") || text.contains("石ツルハシ") || text.contains("石ピッケル") || text.contains("stonepickaxe") || text.contains("stone_pickaxe")) {
            return planCraftItem("minecraft:stone_pickaxe", source);
        }
        if (text.contains("作業台") || text.contains("クラフト台") || text.contains("craftingtable") || text.contains("crafting_table")) {
            return planCraftItem("minecraft:crafting_table", source);
        }
        if (text.contains("棒") || text.contains("stick")) {
            return planCraftItem("minecraft:stick", source);
        }
        if (text.contains("かまど") || text.contains("竈") || text.contains("furnace")) {
            return planCraftItem("minecraft:furnace", source);
        }
        if (text.contains("松明") || text.contains("たいまつ") || text.contains("トーチ") || text.contains("torch")) {
            return planCraftItem("minecraft:torch", source);
        }
        if (text.contains("チェスト") || text.contains("chest")) {
            return planCraftItem("minecraft:chest", source);
        }
        if (text.contains("盾") || text.contains("シールド") || text.contains("shield")) {
            return planCraftItem("minecraft:shield", source);
        }
        if (text.contains("バケツ") || text.contains("bucket")) {
            return planCraftItem("minecraft:bucket", source);
        }
        if (text.contains("火打石") || text.contains("打ち金") || text.contains("flintandsteel") || text.contains("flint_and_steel")) {
            return planCraftItem("minecraft:flint_and_steel", source);
        }
        if (text.contains("ツルハシ") || text.contains("つるはし") || text.contains("ピッケル") || text.contains("ぴっける") || text.contains("pickaxe")) {
            return planCraftItem("minecraft:stone_pickaxe", source);
        }
        if (text.contains("ガラス") || text.contains("glass")) {
            return planCraftItem("minecraft:glass", source);
        }
        Optional<MinecraftVanillaTerms.TermMatch> vanilla = vanillaTerms.findCraftTarget(text);
        if (vanilla.isPresent()) {
            String target = resolveCraftTargetAlias(vanilla.get().id());
            if (!target.isBlank()) {
                return planCraftItem(target, source);
            }
        }
        return Optional.empty();
    }

    private Optional<NativePlan> planCraftItem(String item, String source) {
        return planCraftItem(item, "", source);
    }

    private Optional<NativePlan> planCraftItem(String item, String recipeId, String source) {
        String resolved = resolveCraftTargetAlias(item);
        if (!resolved.isBlank() && !resolved.equals(item)) {
            return planCraftItem(resolved, recipeId, source);
        }
        return switch (item) {
            case "minecraft:stone_pickaxe" -> Optional.of(planStonePickaxe(source));
            case "minecraft:crafting_table" -> Optional.of(planCraftingTable(source));
            case "minecraft:stick" -> Optional.of(planSticks(source));
            case "minecraft:furnace" -> Optional.of(planFurnace(source));
            case "minecraft:torch" -> Optional.of(planTorchCraft(source));
            case "minecraft:wooden_pickaxe" -> Optional.of(planWoodenPickaxe(source));
            case "minecraft:chest" -> Optional.of(planChest(source));
            case "minecraft:shield" -> Optional.of(planShield(source));
            case "minecraft:glass" -> Optional.of(planGlass(source));
            case "minecraft:honey_bottle" -> Optional.of(planHoneyBottle(source));
            default -> planGenericCatalogCraft(item, recipeId, source);
        };
    }

    private Optional<NativePlan> planGenericCatalogCraft(String item, String source) {
        return planGenericCatalogCraft(item, "", source);
    }

    private Optional<NativePlan> planGenericCatalogCraft(String item, String recipeId, String source) {
        if (item == null || item.isBlank()) {
            return Optional.empty();
        }
        Optional<NativePlan> smithing = planSmithingTransform(item, recipeId, source);
        if (smithing.isPresent()) {
            return smithing;
        }
        Optional<NativePlan> stonecutting = planStonecutting(item, recipeId, source);
        if (stonecutting.isPresent()) {
            return stonecutting;
        }
        Optional<NativePlan> furnace = planFurnaceFamilyRecipe(item, recipeId, source);
        if (furnace.isPresent()) {
            return furnace;
        }
        if (recipeCatalog.craftRecipes().stream().noneMatch(recipe -> recipe.output().equals(item))) {
            return Optional.empty();
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensureCommonRecipeMaterials(actions, item, recipeId);
        if (requiresCraftingTable(item)) {
            if (countItem("minecraft:crafting_table") <= 0) {
                ensurePlanks(actions, 4);
                ensureCraftingTableAvailable(actions);
            }
            ensureCraftingTableOpen(actions);
        }
        actions.add(craft(item, 1));
        if (requiresCraftingTable(item)) {
            actions.add(closeScreen());
        }
        return Optional.of(plan("craft_item", source, actions.toArray(JsonObject[]::new)));
    }

    private Optional<NativePlan> planStonecutting(String item, String recipeId, String source) {
        Optional<VanillaStonecuttingRecipe> selected = selectStonecuttingRecipe(item, recipeId);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensureStonecuttingMaterials(actions, selected.get());
        ensureWorkstationAvailable(actions, "minecraft:stonecutter");
        actions.add(openWorkstation("minecraft:stonecutter", 8, true, true));
        actions.add(craftAt(item, 1, "minecraft:stonecutter"));
        actions.add(closeScreen());
        return Optional.of(plan("stonecut_item", source, actions.toArray(JsonObject[]::new)));
    }

    private Optional<NativePlan> planFurnaceFamilyRecipe(String item, String recipeId, String source) {
        Optional<VanillaFurnaceRecipe> selected = selectFurnaceRecipe(item, recipeId);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        VanillaFurnaceRecipe recipe = selected.get();
        ArrayList<JsonObject> actions = new ArrayList<>();
        IngredientNeed input = chooseIngredientNeed(recipe.ingredient());
        if (input.itemOrGroup().isBlank()) {
            return Optional.empty();
        }
        ensureIngredientNeed(actions, input, new HashSet<>(), 0);
        if (countItem("minecraft:furnace") <= 0) {
            actions.addAll(planFurnace("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        ensureFuel(actions);
        actions.add(openWorkstation("minecraft:furnace", 8, true, true));
        JsonObject smelt = action("smelt");
        smelt.addProperty("input", furnaceInputItem(input));
        smelt.addProperty("fuel", "");
        smelt.addProperty("output", item);
        smelt.addProperty("count", 1);
        smelt.addProperty("timeout_ticks", furnaceTimeoutTicks(recipe));
        actions.add(smelt);
        actions.add(closeScreen());
        return Optional.of(plan("smelt_item", source, actions.toArray(JsonObject[]::new)));
    }

    private Optional<NativePlan> planSmithingTransform(String item, String recipeId, String source) {
        Optional<VanillaSmithingRecipe> selected = selectSmithingRecipe(item, recipeId);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensureSmithingTransformMaterials(actions, selected.get());
        ensureWorkstationAvailable(actions, "minecraft:smithing_table");
        actions.add(openWorkstation("minecraft:smithing_table", 8, true, true));
        actions.add(craftAt(item, 1, "minecraft:smithing_table"));
        actions.add(closeScreen());
        return Optional.of(plan("smithing_transform_item", source, actions.toArray(JsonObject[]::new)));
    }

    private void ensureCommonRecipeMaterials(ArrayList<JsonObject> actions, String item) {
        ensureCommonRecipeMaterials(actions, item, "");
    }

    private void ensureCommonRecipeMaterials(ArrayList<JsonObject> actions, String item, String recipeId) {
        WoodKind wood = woodKindForRecipe(item);
        if (wood != null) {
            int planks = requiredWoodPlanks(item);
            int sticks = requiredSticks(item);
            if (planks > 0) {
                ensureSpecificPlanks(actions, wood, planks);
            }
            if (sticks > 0 && countItem("minecraft:stick") < sticks) {
                ensureSpecificPlanks(actions, wood, planks + 2);
                actions.add(craft("minecraft:stick", 1));
            }
            if (item.endsWith("_chest_boat") || item.equals("minecraft:bamboo_chest_raft")) {
                ensureChestBoatMaterials(actions, item, wood);
            }
        }
        ensureNonWoodRecipeMaterials(actions, item, recipeId);
    }

    private Optional<VanillaStonecuttingRecipe> selectStonecuttingRecipe(String item, String recipeId) {
        if (recipeId != null && !recipeId.isBlank()) {
            return recipeCatalog.stonecuttingRecipes().stream()
                .filter(recipe -> recipe.output().equals(item))
                .filter(recipe -> recipe.recipeId().equals(recipeId))
                .findFirst();
        }
        return recipeCatalog.stonecuttingRecipes().stream()
            .filter(recipe -> recipe.output().equals(item))
            .sorted((a, b) -> Integer.compare(stonecuttingCost(a), stonecuttingCost(b)))
            .findFirst();
    }

    private Optional<VanillaFurnaceRecipe> selectFurnaceRecipe(String item, String recipeId) {
        if (recipeId != null && !recipeId.isBlank()) {
            return recipeCatalog.furnaceRecipes().stream()
                .filter(recipe -> recipe.output().equals(item))
                .filter(recipe -> recipe.recipeId().equals(recipeId))
                .findFirst();
        }
        return recipeCatalog.furnaceRecipes().stream()
            .filter(recipe -> recipe.output().equals(item))
            .sorted((a, b) -> Integer.compare(furnaceRecipeCost(a), furnaceRecipeCost(b)))
            .findFirst();
    }

    private Optional<VanillaSmithingRecipe> selectSmithingRecipe(String item, String recipeId) {
        if (recipeId != null && !recipeId.isBlank()) {
            return recipeCatalog.smithingRecipes().stream()
                .filter(recipe -> recipe.output().equals(item))
                .filter(recipe -> recipe.recipeId().equals(recipeId))
                .findFirst();
        }
        return recipeCatalog.smithingRecipes().stream()
            .filter(recipe -> recipe.output().equals(item))
            .findFirst();
    }

    private int stonecuttingCost(VanillaStonecuttingRecipe recipe) {
        IngredientNeed need = chooseIngredientNeed(recipe.ingredient());
        if (need.itemOrGroup().isBlank()) return 1000;
        if (need.kind().equals("group") && countGroup(need.itemOrGroup()) > 0) return 0;
        if (need.kind().equals("item") && countItem(need.itemOrGroup()) > 0) return 0;
        return isDirectlyAcquirableIngredient(need.itemOrGroup()) ? 1 : 2;
    }

    private int furnaceRecipeCost(VanillaFurnaceRecipe recipe) {
        IngredientNeed need = chooseIngredientNeed(recipe.ingredient());
        if (need.itemOrGroup().isBlank()) return 1000;
        if (need.kind().equals("group") && countGroup(need.itemOrGroup()) > 0) return 0;
        if (need.kind().equals("item") && countItem(need.itemOrGroup()) > 0) return 0;
        return isDirectlyAcquirableIngredient(need.itemOrGroup()) ? 1 : 2;
    }

    private String furnaceInputItem(IngredientNeed need) {
        if (need.kind().equals("item")) {
            return need.itemOrGroup();
        }
        return switch (need.itemOrGroup()) {
            case "log" -> {
                WoodKind wood = choosePreferredWoodKind();
                yield wood == null ? "minecraft:oak_log" : wood.log();
            }
            case "cobblestone" -> "minecraft:cobblestone";
            case "coal_or_charcoal" -> countItem("minecraft:coal") > 0 ? "minecraft:coal" : "minecraft:charcoal";
            default -> need.itemOrGroup();
        };
    }

    private int furnaceTimeoutTicks(VanillaFurnaceRecipe recipe) {
        return switch (recipe.type()) {
            case "minecraft:blasting", "minecraft:smoking" -> 220;
            case "minecraft:campfire_cooking" -> 700;
            default -> 360;
        };
    }

    private void ensureStonecuttingMaterials(ArrayList<JsonObject> actions, VanillaStonecuttingRecipe recipe) {
        ensureIngredientNeed(actions, chooseIngredientNeed(recipe.ingredient()), new HashSet<>(), 0);
    }

    private void ensureSmithingTransformMaterials(ArrayList<JsonObject> actions, VanillaSmithingRecipe recipe) {
        ensureIngredientNeed(actions, chooseIngredientNeed(recipe.template()), new HashSet<>(), 0);
        ensureIngredientNeed(actions, chooseIngredientNeed(recipe.base()), new HashSet<>(), 0);
        ensureIngredientNeed(actions, chooseIngredientNeed(recipe.addition()), new HashSet<>(), 0);
    }

    private void ensureChestBoatMaterials(ArrayList<JsonObject> actions, String item, WoodKind wood) {
            if (countItem("minecraft:chest") <= 0) {
                ensureSpecificPlanks(actions, wood, 8);
                ensureCraftingTableAvailable(actions);
                ensureCraftingTableOpen(actions);
                actions.add(craft("minecraft:chest", 1));
                actions.add(closeScreen());
            }
            String boat = item.equals("minecraft:bamboo_chest_raft") ? "minecraft:bamboo_raft" : item.replace("_chest_boat", "_boat");
            if (countItem(boat) <= 0) {
                ensureSpecificPlanks(actions, wood, 5);
                ensureCraftingTableAvailable(actions);
                ensureCraftingTableOpen(actions);
                actions.add(craft(boat, 1));
                actions.add(closeScreen());
            }
    }

    private void ensureNonWoodRecipeMaterials(ArrayList<JsonObject> actions, String item) {
        ensureNonWoodRecipeMaterials(actions, item, "");
    }

    private void ensureNonWoodRecipeMaterials(ArrayList<JsonObject> actions, String item, String recipeId) {
        if (ensureRecipeCatalogMaterials(actions, item, recipeId)) {
            return;
        }
        String colorKey = colorKey(item);
        if (!colorKey.isBlank()) {
            ensureColorRecipeMaterials(actions, item, colorKey);
        }
        switch (item) {
            case "minecraft:bucket" -> ensureIronIngots(actions, 3);
            case "minecraft:shears" -> ensureIronIngots(actions, 2);
            case "minecraft:flint_and_steel" -> {
                ensureIronIngots(actions, 1);
                ensureFlint(actions, 1);
            }
            case "minecraft:shield" -> {
                ensureIronIngots(actions, 1);
                ensurePlanks(actions, 6);
            }
            case "minecraft:paper" -> ensureAcquireItem(actions, "minecraft:sugar_cane", 3, "minecraft:sugar_cane", 16);
            case "minecraft:book" -> {
                ensurePaper(actions, 3);
                ensureAnimalDrop(actions, "minecraft:leather", 1);
            }
            case "minecraft:arrow" -> {
                ensureFlint(actions, 1);
                if (countItem("minecraft:stick") < 1) {
                    ensurePlanks(actions, 2);
                    actions.add(craft("minecraft:stick", 1));
                }
                ensureAnimalDrop(actions, "minecraft:feather", 1);
            }
            case "minecraft:white_bed" -> {
                ensureAnimalDrop(actions, "minecraft:white_wool", 3);
                ensurePlanks(actions, 3);
            }
            case "minecraft:white_carpet" -> ensureAnimalDrop(actions, "minecraft:white_wool", 2);
            case "minecraft:white_banner" -> {
                ensureAnimalDrop(actions, "minecraft:white_wool", 6);
                if (countItem("minecraft:stick") < 1) {
                    ensurePlanks(actions, 2);
                    actions.add(craft("minecraft:stick", 1));
                }
            }
            case "minecraft:white_wool" -> ensureAnimalDrop(actions, "minecraft:white_wool", 1);
            default -> {
                if (item.endsWith("_concrete_powder")) {
                    ensureAcquireItem(actions, "minecraft:sand", 4, "sand", 16);
                    ensureAcquireItem(actions, "minecraft:gravel", 4, "gravel", 16);
                } else if (item.endsWith("_bed")) {
                    String wool = item.replace("_bed", "_wool");
                    ensureAnimalDrop(actions, wool, 3);
                    ensurePlanks(actions, 3);
                } else if (item.endsWith("_carpet")) {
                    ensureAnimalDrop(actions, item.replace("_carpet", "_wool"), 2);
                } else if (item.endsWith("_banner")) {
                    ensureAnimalDrop(actions, item.replace("_banner", "_wool"), 6);
                    if (countItem("minecraft:stick") < 1) {
                        ensurePlanks(actions, 2);
                        actions.add(craft("minecraft:stick", 1));
                    }
                }
            }
        }
    }

    private boolean ensureRecipeCatalogMaterials(ArrayList<JsonObject> actions, String item) {
        return ensureRecipeCatalogMaterials(actions, item, "", new HashSet<>(), 0);
    }

    private boolean ensureRecipeCatalogMaterials(ArrayList<JsonObject> actions, String item, String recipeId) {
        return ensureRecipeCatalogMaterials(actions, item, recipeId, new HashSet<>(), 0);
    }

    private boolean ensureRecipeCatalogMaterials(ArrayList<JsonObject> actions, String item, Set<String> seen, int depth) {
        return ensureRecipeCatalogMaterials(actions, item, "", seen, depth);
    }

    private boolean ensureRecipeCatalogMaterials(ArrayList<JsonObject> actions, String item, String recipeId, Set<String> seen, int depth) {
        if (item == null || item.isBlank()) {
            return false;
        }
        if (depth > MAX_RECIPE_RECURSION_DEPTH) {
            actions.add(plannerBlockedReason(
                "recipe_dependency_depth_exceeded",
                item,
                "Recipe dependency expansion exceeded depth " + MAX_RECIPE_RECURSION_DEPTH + "."
            ));
            return true;
        }
        if (seen.contains(item)) {
            actions.add(plannerBlockedReason(
                "recipe_dependency_cycle_detected",
                item,
                "Recipe dependency expansion detected a cycle at " + item + "."
            ));
            return true;
        }
        Optional<VanillaCraftRecipe> recipe = preferredCraftRecipe(item, recipeId);
        if (recipe.isEmpty()) {
            return false;
        }
        seen.add(item);
        for (IngredientNeed need : aggregateIngredientNeeds(recipe.get())) {
            ensureIngredientNeed(actions, need, seen, depth + 1);
        }
        seen.remove(item);
        return true;
    }

    private Optional<VanillaCraftRecipe> preferredCraftRecipe(String item) {
        return preferredCraftRecipe(item, "");
    }

    private Optional<VanillaCraftRecipe> preferredCraftRecipe(String item, String recipeId) {
        if (recipeId != null && !recipeId.isBlank()) {
            Optional<VanillaCraftRecipe> exactRecipe = recipeCatalog.craftRecipes().stream()
                .filter(recipe -> recipe.output().equals(item))
                .filter(recipe -> recipe.recipeId().equals(recipeId))
                .findFirst();
            if (exactRecipe.isPresent()) {
                return exactRecipe;
            }
        }
        return recipeCatalog.craftRecipes().stream()
            .filter(recipe -> recipe.output().equals(item))
            .sorted((a, b) -> {
                boolean aExact = a.recipeId().equals(item);
                boolean bExact = b.recipeId().equals(item);
                if (aExact != bExact) {
                    return aExact ? -1 : 1;
                }
                boolean aSelfRef = recipeContainsItem(a, item);
                boolean bSelfRef = recipeContainsItem(b, item);
                if (aSelfRef != bSelfRef) {
                    return aSelfRef ? 1 : -1;
                }
                int aDirect = directIngredientAlternativeCount(a);
                int bDirect = directIngredientAlternativeCount(b);
                if (aDirect != bDirect) {
                    return Integer.compare(bDirect, aDirect);
                }
                return Integer.compare(a.ingredientSlots().size(), b.ingredientSlots().size());
            })
            .findFirst();
    }

    private boolean recipeContainsItem(VanillaCraftRecipe recipe, String item) {
        for (List<VanillaIngredientAlternative> alternatives : recipe.ingredientSlots()) {
            for (VanillaIngredientAlternative alternative : alternatives) {
                if (item.equals(alternative.itemId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int directIngredientAlternativeCount(VanillaCraftRecipe recipe) {
        int score = 0;
        for (List<VanillaIngredientAlternative> alternatives : recipe.ingredientSlots()) {
            for (VanillaIngredientAlternative alternative : alternatives) {
                if (isDirectlyAcquirableIngredient(alternative.itemId())) {
                    score++;
                    break;
                }
            }
        }
        return score;
    }

    private List<IngredientNeed> aggregateIngredientNeeds(VanillaCraftRecipe recipe) {
        ArrayList<IngredientNeed> needs = new ArrayList<>();
        for (List<VanillaIngredientAlternative> alternatives : recipe.ingredientSlots()) {
            IngredientNeed chosen = chooseIngredientNeed(alternatives);
            if (chosen.itemOrGroup().isBlank()) {
                continue;
            }
            int existing = -1;
            for (int i = 0; i < needs.size(); i++) {
                IngredientNeed need = needs.get(i);
                if (need.kind().equals(chosen.kind()) && need.itemOrGroup().equals(chosen.itemOrGroup())) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                IngredientNeed need = needs.get(existing);
                needs.set(existing, new IngredientNeed(need.kind(), need.itemOrGroup(), need.count() + 1));
            } else {
                needs.add(chosen);
            }
        }
        return needs;
    }

    private IngredientNeed chooseIngredientNeed(List<VanillaIngredientAlternative> alternatives) {
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (!alternative.itemId().isBlank() && countItem(alternative.itemId()) > 0) {
                return new IngredientNeed("item", alternative.itemId(), 1);
            }
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            String group = ingredientGroupForTag(alternative.tagId());
            if (!group.isBlank() && countGroup(group) > 0) {
                return new IngredientNeed("group", group, 1);
            }
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (!alternative.itemId().isBlank() && isDirectlyAcquirableIngredient(alternative.itemId())) {
                return new IngredientNeed("item", alternative.itemId(), 1);
            }
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (isPreferredCraftingIntermediate(alternative.itemId())) {
                return new IngredientNeed("item", alternative.itemId(), 1);
            }
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (!alternative.itemId().isBlank() && canAcquireOrCraftIngredient(alternative.itemId())) {
                return new IngredientNeed("item", alternative.itemId(), 1);
            }
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            String group = ingredientGroupForTag(alternative.tagId());
            if (!group.isBlank()) {
                return new IngredientNeed("group", group, 1);
            }
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (!alternative.itemId().isBlank()) {
                return new IngredientNeed("item", alternative.itemId(), 1);
            }
        }
        return new IngredientNeed("", "", 0);
    }

    private boolean isPreferredCraftingIntermediate(String item) {
        return switch (item) {
            case "minecraft:quartz_block", "minecraft:sandstone", "minecraft:red_sandstone",
                 "minecraft:purpur_block", "minecraft:prismarine", "minecraft:stone_bricks" -> true;
            default -> false;
        };
    }

    private boolean canAcquireOrCraftIngredient(String item) {
        if (isDirectlyAcquirableIngredient(item)) {
            return true;
        }
        return recipeCatalog.craftRecipes().stream().anyMatch(recipe -> recipe.output().equals(item));
    }

    private boolean isDirectlyAcquirableIngredient(String item) {
        if (acquisitionRules.find(item).isPresent()) {
            return true;
        }
        if (isWeatheredCopperIngredient(item)) {
            return true;
        }
        return switch (item) {
            case "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot",
                 "minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold",
                 "minecraft:coal", "minecraft:diamond", "minecraft:emerald", "minecraft:redstone", "minecraft:lapis_lazuli",
                 "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone", "minecraft:smooth_stone",
                 "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:mud", "minecraft:tuff", "minecraft:blackstone",
                 "minecraft:terracotta", "minecraft:obsidian", "minecraft:quartz", "minecraft:end_stone",
                 "minecraft:netherrack", "minecraft:ancient_debris", "minecraft:nether_brick", "minecraft:netherite_scrap",
                 "minecraft:baked_potato", "minecraft:smooth_quartz", "minecraft:smooth_sandstone", "minecraft:smooth_red_sandstone",
                 "minecraft:honeycomb", "minecraft:milk_bucket", "minecraft:rabbit", "minecraft:cooked_rabbit",
                 "minecraft:apple", "minecraft:vine", "minecraft:pointed_dripstone", "minecraft:mangrove_roots",
                 "minecraft:pale_moss_block", "minecraft:basalt", "minecraft:crying_obsidian", "minecraft:warped_fungus",
                 "minecraft:chorus_fruit", "minecraft:popped_chorus_fruit", "minecraft:prismarine_crystals",
                 "minecraft:shulker_shell", "minecraft:wither_skeleton_skull", "minecraft:breeze_rod",
                 "minecraft:stripped_bamboo_block", "minecraft:sculk_sensor", "minecraft:carved_pumpkin",
                 "minecraft:resin_clump", "minecraft:resin_brick",
                 "minecraft:kelp", "minecraft:dried_kelp", "minecraft:honey_bottle",
                 "minecraft:flint", "minecraft:paper", "minecraft:leather", "minecraft:feather",
                 "minecraft:white_wool", "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:wheat",
                 "minecraft:beetroot", "minecraft:carrot", "minecraft:potato", "minecraft:brown_mushroom",
                 "minecraft:red_mushroom", "minecraft:cocoa_beans", "minecraft:melon_slice", "minecraft:pumpkin",
                 "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:cactus",
                 "minecraft:clay_ball", "minecraft:brick", "minecraft:glass", "minecraft:stick" -> true;
            default -> item.endsWith("_planks") || item.endsWith("_wool") || item.endsWith("_dye")
                || item.endsWith("_log") || item.endsWith("_stem");
        };
    }

    private String ingredientGroupForTag(String tag) {
        return switch (tag) {
            case "minecraft:planks", "minecraft:wooden_tool_materials" -> "planks";
            case "minecraft:wool" -> "wool";
            case "minecraft:stone_tool_materials", "minecraft:stone_crafting_materials" -> "cobblestone";
            case "minecraft:coals" -> "coal_or_charcoal";
            case "minecraft:logs", "minecraft:logs_that_burn" -> "log";
            case "minecraft:bundles" -> "bundle";
            case "minecraft:shulker_boxes" -> "shulker_box";
            default -> "";
        };
    }

    private void ensureIngredientNeed(ArrayList<JsonObject> actions, IngredientNeed need, Set<String> seen, int depth) {
        if (need.count() <= 0) {
            return;
        }
        if (need.kind().equals("group")) {
            ensureIngredientGroup(actions, need.itemOrGroup(), need.count());
            return;
        }
        ensureIngredientItem(actions, need.itemOrGroup(), need.count(), seen, depth);
    }

    private void ensureIngredientGroup(ArrayList<JsonObject> actions, String group, int count) {
        int missing = Math.max(0, count - countGroup(group));
        if (missing <= 0) {
            return;
        }
        switch (group) {
            case "planks" -> ensurePlanks(actions, count);
            case "log" -> ensureLog(actions, count);
            case "wool" -> ensureAnimalDrop(actions, "minecraft:white_wool", missing);
            case "cobblestone" -> ensureCobblestone(actions, count);
            case "coal_or_charcoal" -> ensureCoalOrCharcoal(actions, missing);
            case "bundle" -> ensureIngredientItem(actions, "minecraft:bundle", missing, new HashSet<>(), 0);
            case "shulker_box" -> ensureIngredientItem(actions, "minecraft:shulker_box", missing, new HashSet<>(), 0);
            default -> {
            }
        }
    }

    private void ensureIngredientItem(ArrayList<JsonObject> actions, String item, int count, Set<String> seen, int depth) {
        int missing = Math.max(0, count - countItem(item));
        if (missing <= 0) {
            return;
        }
        if (depth > MAX_RECIPE_RECURSION_DEPTH) {
            actions.add(plannerBlockedReason(
                "recipe_dependency_depth_exceeded",
                item,
                "Recipe ingredient expansion exceeded depth " + MAX_RECIPE_RECURSION_DEPTH + "."
            ));
            return;
        }
        if (item.endsWith("_planks")) {
            WoodKind wood = woodKindForRecipe(item);
            if (wood != null) {
                ensureSpecificPlanks(actions, wood, count);
            }
            return;
        }
        if (item.endsWith("_wool")) {
            ensureColoredWool(actions, colorKey(item), count);
            return;
        }
        if (item.endsWith("_dye")) {
            ensureDye(actions, item, count);
            return;
        }
        if (ensureRuleBasedItem(actions, item, missing)) {
            return;
        }
        if (isSmithingTemplateItem(item) && seen.contains(item)) {
            actions.add(requiredTakeFromContainer(item, missing, 12));
            return;
        }
        if (isCompressedDecompositionSource(item, seen)) {
            actions.add(requiredTakeFromContainer(item, missing, 12));
            return;
        }
        switch (item) {
            case "minecraft:stick" -> {
                ensurePlanks(actions, 2);
                actions.add(craft("minecraft:stick", Math.max(1, (int) Math.ceil(missing / 4.0D))));
            }
            case "minecraft:iron_ingot" -> ensureIronIngots(actions, count);
            case "minecraft:copper_ingot" -> ensureCopperIngots(actions, count);
            case "minecraft:gold_ingot" -> ensureGoldIngots(actions, count);
            case "minecraft:raw_iron" -> ensureOreDrop(actions, "minecraft:raw_iron", "iron_ore", count, true);
            case "minecraft:raw_copper" -> ensureOreDrop(actions, "minecraft:raw_copper", "copper_ore", count, true);
            case "minecraft:raw_gold" -> ensureOreDrop(actions, "minecraft:raw_gold", "gold_ore", count, true);
            case "minecraft:coal" -> ensureOreDrop(actions, "minecraft:coal", "coal_ore", count, false);
            case "minecraft:diamond" -> ensureOreDrop(actions, "minecraft:diamond", "diamond_ore", count, true);
            case "minecraft:emerald" -> ensureOreDrop(actions, "minecraft:emerald", "emerald_ore", count, true);
            case "minecraft:redstone" -> ensureOreDrop(actions, "minecraft:redstone", "redstone_ore", count, true);
            case "minecraft:lapis_lazuli" -> ensureOreDrop(actions, "minecraft:lapis_lazuli", "lapis_ore", count, true);
            case "minecraft:cobblestone" -> ensureCobblestone(actions, count);
            case "minecraft:cobbled_deepslate" -> ensureCollectableItem(actions, item, "minecraft:deepslate", count, 24);
            case "minecraft:stone" -> ensureSmeltedMaterial(actions, "minecraft:cobblestone", "minecraft:stone", count);
            case "minecraft:smooth_stone" -> ensureSmeltedMaterial(actions, "minecraft:stone", "minecraft:smooth_stone", count);
            case "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:mud", "minecraft:tuff", "minecraft:blackstone",
                 "minecraft:terracotta", "minecraft:obsidian", "minecraft:end_stone", "minecraft:netherrack", "minecraft:ancient_debris" -> ensureAcquireItem(actions, item, count, item, 24);
            case "minecraft:quartz" -> ensureAcquireItem(actions, item, count, "minecraft:nether_quartz_ore", 24);
            case "minecraft:nether_brick" -> ensureSmeltedMaterial(actions, "minecraft:netherrack", "minecraft:nether_brick", count);
            case "minecraft:netherite_scrap" -> ensureSmeltedMaterial(actions, "minecraft:ancient_debris", "minecraft:netherite_scrap", count);
            case "minecraft:baked_potato" -> ensureSmeltedMaterial(actions, "minecraft:potato", "minecraft:baked_potato", count);
            case "minecraft:smooth_quartz" -> ensureSmeltedMaterial(actions, "minecraft:quartz_block", "minecraft:smooth_quartz", count);
            case "minecraft:smooth_sandstone" -> ensureSmeltedMaterial(actions, "minecraft:sandstone", "minecraft:smooth_sandstone", count);
            case "minecraft:smooth_red_sandstone" -> ensureSmeltedMaterial(actions, "minecraft:red_sandstone", "minecraft:smooth_red_sandstone", count);
            case "minecraft:honeycomb" -> ensureHoneycomb(actions, count);
            case "minecraft:milk_bucket" -> ensureMilkBucket(actions, count);
            case "minecraft:rabbit" -> ensureAnimalDrop(actions, "minecraft:rabbit", count);
            case "minecraft:beef", "minecraft:porkchop", "minecraft:chicken", "minecraft:mutton",
                 "minecraft:cod", "minecraft:salmon" -> ensureAnimalDrop(actions, item, count);
            case "minecraft:cooked_rabbit" -> ensureSmeltedMaterial(actions, "minecraft:rabbit", "minecraft:cooked_rabbit", count);
            case "minecraft:chorus_fruit" -> ensureAcquireItem(actions, item, count, "minecraft:chorus_plant", 16);
            case "minecraft:popped_chorus_fruit" -> ensureSmeltedMaterial(actions, "minecraft:chorus_fruit", "minecraft:popped_chorus_fruit", count);
            case "minecraft:resin_brick" -> ensureSmeltedMaterial(actions, "minecraft:resin_clump", "minecraft:resin_brick", count);
            case "minecraft:kelp" -> ensureAcquireItem(actions, item, count, "minecraft:kelp", 16);
            case "minecraft:dried_kelp" -> ensureSmeltedMaterial(actions, "minecraft:kelp", "minecraft:dried_kelp", count);
            case "minecraft:honey_bottle" -> ensureHoneyBottle(actions, count);
            case "minecraft:apple" -> ensureAcquireItem(actions, item, count, "leaves", 16);
            case "minecraft:vine", "minecraft:pointed_dripstone", "minecraft:mangrove_roots",
                 "minecraft:pale_moss_block", "minecraft:basalt", "minecraft:crying_obsidian", "minecraft:warped_fungus",
                 "minecraft:stripped_bamboo_block", "minecraft:sculk_sensor", "minecraft:carved_pumpkin", "minecraft:resin_clump" ->
                ensureAcquireItem(actions, item, count, item, 16);
            case "minecraft:prismarine_crystals" -> addAttackAndPickup(actions, "guardian", item, count, 16, 300);
            case "minecraft:shulker_shell" -> addAttackAndPickup(actions, "shulker", item, count, 16, 320);
            case "minecraft:wither_skeleton_skull" -> addAttackAndPickup(actions, "wither_skeleton", item, count, 16, 360);
            case "minecraft:breeze_rod" -> addAttackAndPickup(actions, "breeze", item, count, 16, 320);
            case "minecraft:heart_of_the_sea", "minecraft:heavy_core", "minecraft:enchanted_golden_apple",
                 "minecraft:disc_fragment_5", "minecraft:echo_shard" -> ensureStructureLootItem(actions, item, count);
            case "minecraft:ice" -> actions.add(plannerBlockedReason(
                "silk_touch_required",
                item,
                "Ice requires a Silk Touch tool before it can be collected as an item."
            ));
            case "minecraft:nether_star" -> actions.add(plannerBlockedReason(
                "boss_route_missing",
                item,
                "Nether star requires a Wither boss route, which is not automated yet."
            ));
            case "minecraft:creeper_head" -> actions.add(plannerBlockedReason(
                "mob_head_route_missing",
                item,
                "Creeper head requires a charged-creeper mob-head route, which is not automated yet."
            ));
            case "minecraft:flint" -> ensureFlint(actions, count);
            case "minecraft:paper" -> ensurePaper(actions, count);
            case "minecraft:leather", "minecraft:feather", "minecraft:white_wool" -> ensureAnimalDrop(actions, item, count);
            case "minecraft:glass" -> ensureGlass(actions, count);
            case "minecraft:brick" -> ensureSmeltedMaterial(actions, "minecraft:clay_ball", "minecraft:brick", count);
            case "minecraft:wheat" -> ensureAcquireItem(actions, item, count, "minecraft:wheat", 16);
            case "minecraft:beetroot" -> ensureAcquireItem(actions, item, count, "minecraft:beetroots", 16);
            case "minecraft:carrot" -> ensureAcquireItem(actions, item, count, "minecraft:carrots", 16);
            case "minecraft:potato" -> ensureAcquireItem(actions, item, count, "minecraft:potatoes", 16);
            case "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:pumpkin" -> ensureAcquireItem(actions, item, count, item, 16);
            case "minecraft:cocoa_beans" -> ensureAcquireItem(actions, item, count, "minecraft:cocoa", 16);
            case "minecraft:melon_slice" -> ensureAcquireItem(actions, item, count, "minecraft:melon", 16);
            case "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:cactus" ->
                ensureAcquireItem(actions, item, count, item, 16);
            case "minecraft:sea_pickle" -> ensureAcquireItem(actions, item, count, item, 24);
            case "minecraft:wet_sponge" -> ensureAcquireItem(actions, item, count, item, 24);
            case "minecraft:clay_ball" -> ensureAcquireItem(actions, item, count, "clay", 16);
            default -> {
                if (requiresSilkTouchOreBlock(item)) {
                    actions.add(plannerBlockedReason(
                        "silk_touch_required",
                        item,
                        "Smelting this exact ore-block recipe requires collecting " + item + " with Silk Touch first."
                    ));
                    return;
                }
                if (isWeatheredCopperIngredient(item)) {
                    ensureAcquireItem(actions, item, count, item, 24);
                    return;
                }
                if (item.endsWith("_log") || item.endsWith("_stem")) {
                    ensureAcquireItem(actions, item, count, item, 32);
                    return;
                }
                if (BlockGroups.matches("flower", item)) {
                    ensureAcquireItem(actions, item, count, item, 16);
                    return;
                }
                if (seen.contains(item)) {
                    actions.add(plannerBlockedReason(
                        "recipe_dependency_cycle_detected",
                        item,
                        "Recipe ingredient expansion detected a cycle at " + item + "."
                    ));
                } else if (recipeCatalog.craftRecipes().stream().anyMatch(recipe -> recipe.output().equals(item))) {
                    ensureRecipeCatalogMaterials(actions, item, seen, depth);
                    if (requiresCraftingTable(item)) {
                        ensureCraftingTableAvailable(actions);
                        ensureCraftingTableOpen(actions);
                    }
                    actions.add(craft(item, 1));
                    if (requiresCraftingTable(item)) {
                        actions.add(closeScreen());
                    }
                } else {
                    actions.add(plannerBlockedReason(
                        "recipe_material_route_missing",
                        item,
                        "No deterministic acquisition route is registered for recipe ingredient " + item + "."
                    ));
                }
            }
        }
    }

    private boolean requiresSilkTouchOreBlock(String item) {
        if (item == null || !item.startsWith("minecraft:")) {
            return false;
        }
        return item.endsWith("_ore") || item.equals("minecraft:ancient_debris");
    }

    private boolean isWeatheredCopperIngredient(String item) {
        return item != null
            && item.startsWith("minecraft:")
            && item.contains("copper")
            && (item.contains("exposed_") || item.contains("weathered_") || item.contains("oxidized_"));
    }

    private boolean isSmithingTemplateItem(String item) {
        return item != null
            && (item.endsWith("_armor_trim_smithing_template") || item.equals("minecraft:netherite_upgrade_smithing_template"));
    }

    private boolean isCompressedDecompositionSource(String item, Set<String> seen) {
        return (item.equals("minecraft:bone_block") && seen.contains("minecraft:bone_meal"))
            || (item.equals("minecraft:netherite_block") && seen.contains("minecraft:netherite_ingot"));
    }

    private JsonObject plannerBlockedReason(String reasonCode, String item, String message) {
        JsonObject body = action("planner_blocked_reason");
        body.addProperty("reason_code", reasonCode);
        body.addProperty("item", item);
        body.addProperty("max_depth", MAX_RECIPE_RECURSION_DEPTH);
        body.addProperty("message", message);
        return body;
    }

    private void ensureColorRecipeMaterials(ArrayList<JsonObject> actions, String item, String colorKey) {
        String wool = "minecraft:" + colorKey + "_wool";
        String dye = "minecraft:" + colorKey + "_dye";
        if (item.endsWith("_bed")) {
            ensureColoredWool(actions, colorKey, 3);
            ensurePlanks(actions, 3);
            return;
        }
        if (item.endsWith("_carpet")) {
            ensureColoredWool(actions, colorKey, 2);
            return;
        }
        if (item.endsWith("_banner")) {
            ensureColoredWool(actions, colorKey, 6);
            if (countItem("minecraft:stick") < 1) {
                ensurePlanks(actions, 2);
                actions.add(craft("minecraft:stick", 1));
            }
            return;
        }
        if (item.endsWith("_wool")) {
            ensureDye(actions, dye, 1);
            ensureAnimalDrop(actions, "minecraft:white_wool", 1);
            return;
        }
        if (item.endsWith("_concrete_powder")) {
            ensureAcquireItem(actions, "minecraft:sand", 4, "sand", 16);
            ensureAcquireItem(actions, "minecraft:gravel", 4, "gravel", 16);
            ensureDye(actions, dye, 1);
            return;
        }
        if (item.endsWith("_stained_glass")) {
            ensureGlass(actions, 8);
            ensureDye(actions, dye, 1);
            return;
        }
        if (item.endsWith("_stained_glass_pane")) {
            String glass = "minecraft:" + colorKey + "_stained_glass";
            if (countItem(glass) < 6) {
                ensureGlass(actions, 8);
                ensureDye(actions, dye, 1);
                ensureCraftingTableAvailable(actions);
                ensureCraftingTableOpen(actions);
                actions.add(craft(glass, 1));
                actions.add(closeScreen());
            }
            return;
        }
        if (item.endsWith("_candle")) {
            ensureDye(actions, dye, 1);
        }
    }

    private void ensureColoredWool(ArrayList<JsonObject> actions, String colorKey, int count) {
        String wool = "minecraft:" + colorKey + "_wool";
        int missing = Math.max(0, count - countItem(wool));
        if (missing <= 0) {
            return;
        }
        String dye = "minecraft:" + colorKey + "_dye";
        ensureDye(actions, dye, missing);
        ensureAnimalDrop(actions, "minecraft:white_wool", missing);
        actions.add(craft(wool, missing));
    }

    private void ensureDye(ArrayList<JsonObject> actions, String dye, int count) {
        int missing = Math.max(0, count - countItem(dye));
        if (missing <= 0) {
            return;
        }
        String source = dyeSourceItem(dye);
        if (source.isBlank()) {
            return;
        }
        if (dye.equals("minecraft:white_dye")) {
            ensureBoneMeal(actions, missing);
            actions.add(craft("minecraft:white_dye", missing));
            return;
        }
        if (dye.equals("minecraft:green_dye")) {
            ensureAcquireItem(actions, "minecraft:cactus", missing, "cactus", 16);
            if (countItem("minecraft:furnace") <= 0) {
                actions.addAll(planFurnace("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
            }
            ensureFuel(actions);
            JsonObject open = action("open_workstation");
            open.addProperty("station", "minecraft:furnace");
            open.addProperty("search_radius", 8);
            open.addProperty("allow_place", true);
            open.addProperty("avoid_occupied", true);
            actions.add(open);
            JsonObject smelt = action("smelt");
            smelt.addProperty("input", "minecraft:cactus");
            smelt.addProperty("fuel", "");
            smelt.addProperty("output", "minecraft:green_dye");
            smelt.addProperty("count", missing);
            smelt.addProperty("timeout_ticks", 360);
            actions.add(smelt);
            actions.add(closeScreen());
            return;
        }
        if (source.startsWith("minecraft:")) {
            ensureAcquireItem(actions, source, missing, source, 16);
            actions.add(craft(dye, missing));
        }
    }

    private void ensureBoneMeal(ArrayList<JsonObject> actions, int count) {
        int missing = Math.max(0, count - countItem("minecraft:bone_meal"));
        if (missing <= 0) {
            return;
        }
        if (countItem("minecraft:bone") < missing) {
            ensureRuleBasedItem(actions, "minecraft:bone", missing);
        }
        actions.add(craft("minecraft:bone_meal", missing));
    }

    private void ensureGlass(ArrayList<JsonObject> actions, int count) {
        int missing = Math.max(0, count - countItem("minecraft:glass"));
        if (missing <= 0) {
            return;
        }
        ensureAcquireItem(actions, "minecraft:sand", missing, "sand", 16);
        if (countItem("minecraft:furnace") <= 0) {
            actions.addAll(planFurnace("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        ensureFuel(actions);
        JsonObject open = action("open_workstation");
        open.addProperty("station", "minecraft:furnace");
        open.addProperty("search_radius", 8);
        open.addProperty("allow_place", true);
        open.addProperty("avoid_occupied", true);
        actions.add(open);
        JsonObject smelt = action("smelt");
        smelt.addProperty("input", "minecraft:sand");
        smelt.addProperty("fuel", "");
        smelt.addProperty("output", "minecraft:glass");
        smelt.addProperty("count", missing);
        smelt.addProperty("timeout_ticks", 360);
        actions.add(smelt);
        actions.add(closeScreen());
    }

    private void ensureCobblestone(ArrayList<JsonObject> actions, int count) {
        int missing = Math.max(0, count - countGroup("cobblestone"));
        if (missing <= 0) {
            return;
        }
        actions.add(optionalTakeFromContainer("cobblestone", missing, 8));
        boolean assistAllowed = isVoiceAssistCommonMaterial("minecraft:cobblestone");
        if (!hasAnyItem("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe")) {
            actions.addAll(planWoodenPickaxe("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        JsonObject durability = action("check_tool_durability");
        durability.addProperty("tool_group", "pickaxe");
        durability.addProperty("min_remaining_uses", missing);
        durability.addProperty("optional", true);
        actions.add(durability);
        JsonObject collect = action("collect_block");
        collect.addProperty("block_group", "cobblestone");
        collect.addProperty("count", missing);
        collect.addProperty("search_radius", 32);
        collect.addProperty("timeout_ticks", Math.max(180, missing * 90));
        collect.addProperty("optional", assistAllowed);
        actions.add(collect);
        JsonObject pickup = collectDropsItem("minecraft:cobblestone", missing, 10, Math.max(120, missing * 80));
        pickup.addProperty("optional", assistAllowed);
        actions.add(pickup);
        addVoiceAssistMaterialTopUpIfAllowed(actions, "minecraft:cobblestone", missing);
    }

    private void ensureCoalOrCharcoal(ArrayList<JsonObject> actions, int count) {
        if (countGroup("coal_or_charcoal") >= count) {
            return;
        }
        if (countItem("minecraft:furnace") <= 0) {
            actions.addAll(planFurnace("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        WoodKind wood = choosePreferredWoodKind();
        if (wood == null || countItem(wood.log()) <= 0) {
            ensurePlanks(actions, 4);
            wood = choosePreferredWoodKind();
        }
        ensureFuel(actions);
        JsonObject open = action("open_workstation");
        open.addProperty("station", "minecraft:furnace");
        open.addProperty("search_radius", 8);
        open.addProperty("allow_place", true);
        open.addProperty("avoid_occupied", true);
        actions.add(open);
        JsonObject smelt = action("smelt");
        smelt.addProperty("input", wood == null ? "minecraft:oak_log" : wood.log());
        smelt.addProperty("fuel", "");
        smelt.addProperty("output", "minecraft:charcoal");
        smelt.addProperty("count", count);
        smelt.addProperty("timeout_ticks", Math.max(360, count * 160));
        actions.add(smelt);
        actions.add(closeScreen());
    }

    private void ensurePaper(ArrayList<JsonObject> actions, int count) {
        int missingPaper = Math.max(0, count - countItem("minecraft:paper"));
        if (missingPaper <= 0) {
            return;
        }
        int sugarCaneNeeded = Math.max(3, missingPaper);
        ensureAcquireItem(actions, "minecraft:sugar_cane", sugarCaneNeeded, "minecraft:sugar_cane", 16);
        actions.add(craft("minecraft:paper", Math.max(1, (int) Math.ceil(missingPaper / 3.0D))));
    }

    private void ensureIronIngots(ArrayList<JsonObject> actions, int count) {
        int missingIngots = Math.max(0, count - countItem("minecraft:iron_ingot"));
        if (missingIngots <= 0) {
            return;
        }
        int rawIronAvailable = countItem("minecraft:raw_iron");
        int rawIronToCollect = Math.max(0, missingIngots - rawIronAvailable);
        if (rawIronToCollect > 0) {
            if (countItem("minecraft:stone_pickaxe") <= 0 && countItem("minecraft:iron_pickaxe") <= 0 && countItem("minecraft:diamond_pickaxe") <= 0 && countItem("minecraft:netherite_pickaxe") <= 0) {
                actions.addAll(planStonePickaxe("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
            }
            JsonObject collect = action("collect_block");
            collect.addProperty("block_group", "iron_ore");
            collect.addProperty("count", rawIronToCollect);
            collect.addProperty("search_radius", 24);
            collect.addProperty("timeout_ticks", Math.max(240, rawIronToCollect * 120));
            actions.add(collect);
            JsonObject pickup = action("collect_drops");
            pickup.addProperty("item", "minecraft:raw_iron");
            pickup.addProperty("count", rawIronToCollect);
            pickup.addProperty("search_radius", 10);
            pickup.addProperty("timeout_ticks", Math.max(160, rawIronToCollect * 80));
            pickup.addProperty("accept_if_already_present", true);
            pickup.addProperty("magnet_fallback", true);
            pickup.addProperty("magnet_only", true);
            actions.add(pickup);
        }
        if (countItem("minecraft:furnace") <= 0) {
            actions.addAll(planFurnace("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        ensureFuel(actions);
        JsonObject open = action("open_workstation");
        open.addProperty("station", "minecraft:furnace");
        open.addProperty("search_radius", 8);
        open.addProperty("allow_place", true);
        open.addProperty("avoid_occupied", true);
        actions.add(open);
        JsonObject smelt = action("smelt");
        smelt.addProperty("input", "minecraft:raw_iron");
        smelt.addProperty("fuel", "");
        smelt.addProperty("output", "minecraft:iron_ingot");
        smelt.addProperty("count", missingIngots);
        smelt.addProperty("timeout_ticks", 360);
        actions.add(smelt);
        actions.add(closeScreen());
    }

    private void ensureCopperIngots(ArrayList<JsonObject> actions, int count) {
        int missingIngots = Math.max(0, count - countItem("minecraft:copper_ingot"));
        if (missingIngots <= 0) {
            return;
        }
        int rawAvailable = countItem("minecraft:raw_copper");
        int rawToCollect = Math.max(0, missingIngots - rawAvailable);
        if (rawToCollect > 0) {
            ensureOreDrop(actions, "minecraft:raw_copper", "copper_ore", rawToCollect, true);
        }
        ensureSmeltedMaterial(actions, "minecraft:raw_copper", "minecraft:copper_ingot", missingIngots);
    }

    private void ensureGoldIngots(ArrayList<JsonObject> actions, int count) {
        int missingIngots = Math.max(0, count - countItem("minecraft:gold_ingot"));
        if (missingIngots <= 0) {
            return;
        }
        int rawAvailable = countItem("minecraft:raw_gold");
        int rawToCollect = Math.max(0, missingIngots - rawAvailable);
        if (rawToCollect > 0) {
            ensureOreDrop(actions, "minecraft:raw_gold", "gold_ore", rawToCollect, true);
        }
        ensureSmeltedMaterial(actions, "minecraft:raw_gold", "minecraft:gold_ingot", missingIngots);
    }

    private void ensureOreDrop(ArrayList<JsonObject> actions, String item, String oreGroup, int count, boolean needsStoneOrBetter) {
        int missing = Math.max(0, count - countItem(item));
        if (missing <= 0) {
            return;
        }
        actions.add(optionalTakeFromContainer(item, missing, 8));
        boolean assistAllowed = isVoiceAssistCommonMaterial(item);
        if (needsStoneOrBetter && !hasAnyItem("minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe")) {
            actions.addAll(planStonePickaxe("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        } else if (!needsStoneOrBetter && !hasAnyItem("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe")) {
            actions.addAll(planWoodenPickaxe("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        JsonObject durability = action("check_tool_durability");
        durability.addProperty("tool_group", "pickaxe");
        durability.addProperty("min_remaining_uses", missing);
        durability.addProperty("optional", true);
        actions.add(durability);
        JsonObject collect = action("collect_block");
        collect.addProperty("block_group", oreGroup);
        collect.addProperty("count", missing);
        collect.addProperty("search_radius", 32);
        collect.addProperty("timeout_ticks", Math.max(220, missing * 120));
        collect.addProperty("optional", assistAllowed);
        actions.add(collect);
        JsonObject pickup = collectDropsItem(item, missing, 10, Math.max(160, missing * 80));
        pickup.addProperty("optional", assistAllowed);
        actions.add(pickup);
        addVoiceAssistMaterialTopUpIfAllowed(actions, item, missing);
    }

    private void ensureSmeltedMaterial(ArrayList<JsonObject> actions, String input, String output, int count) {
        int missing = Math.max(0, count - countItem(output));
        if (missing <= 0) {
            return;
        }
        ensureIngredientItem(actions, input, missing, new HashSet<>(), 0);
        if (countItem("minecraft:furnace") <= 0) {
            actions.addAll(planFurnace("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        ensureFuel(actions);
        JsonObject open = action("open_workstation");
        open.addProperty("station", "minecraft:furnace");
        open.addProperty("search_radius", 8);
        open.addProperty("allow_place", true);
        open.addProperty("avoid_occupied", true);
        actions.add(open);
        JsonObject smelt = action("smelt");
        smelt.addProperty("input", input);
        smelt.addProperty("fuel", "");
        smelt.addProperty("output", output);
        smelt.addProperty("count", missing);
        smelt.addProperty("timeout_ticks", Math.max(360, missing * 120));
        actions.add(smelt);
        actions.add(closeScreen());
    }

    private void ensureFlint(ArrayList<JsonObject> actions, int count) {
        int missing = Math.max(0, count - countItem("minecraft:flint"));
        if (missing <= 0) {
            return;
        }
        ensureAcquireItem(actions, "minecraft:flint", missing, "gravel", 16);
    }

    private void ensureHoneycomb(ArrayList<JsonObject> actions, int count) {
        int missing = Math.max(0, count - countItem("minecraft:honeycomb"));
        if (missing <= 0) {
            return;
        }
        actions.add(optionalTakeFromContainer("minecraft:honeycomb", missing, 8));
        if (countItem("minecraft:shears") <= 0) {
            ensureIronIngots(actions, 2);
            actions.add(craft("minecraft:shears", 1));
        }
        actions.add(ensureHotbar("minecraft:shears", 1));
        JsonObject collect = action("collect_honeycomb");
        collect.addProperty("count", missing);
        collect.addProperty("search_radius", 18);
        collect.addProperty("timeout_ticks", Math.max(240, missing * 120));
        collect.addProperty("require_smoke", false);
        actions.add(collect);
        actions.add(collectDropsItem("minecraft:honeycomb", missing, 10, Math.max(160, missing * 80)));
    }

    private void ensureHoneyBottle(ArrayList<JsonObject> actions, int count) {
        int missing = Math.max(0, count - countItem("minecraft:honey_bottle"));
        if (missing <= 0) {
            return;
        }
        actions.add(optionalTakeFromContainer("minecraft:honey_bottle", missing, 8));
        int bottlesNeeded = Math.max(0, missing - countItem("minecraft:glass_bottle"));
        if (bottlesNeeded > 0) {
            ensureIngredientItem(actions, "minecraft:glass_bottle", bottlesNeeded, new HashSet<>(), 0);
        }
        actions.add(ensureHotbar("minecraft:glass_bottle", 1));
        for (int i = 0; i < missing; i++) {
            JsonObject collect = action("collect_honey_bottle");
            collect.addProperty("count", 1);
            collect.addProperty("search_radius", 18);
            collect.addProperty("timeout_ticks", 260);
            collect.addProperty("require_smoke", false);
            actions.add(collect);
        }
    }

    private void ensureMilkBucket(ArrayList<JsonObject> actions, int count) {
        int missing = Math.max(0, count - countItem("minecraft:milk_bucket"));
        if (missing <= 0) {
            return;
        }
        actions.add(optionalTakeFromContainer("minecraft:milk_bucket", missing, 8));
        ensureBucketContainers(actions, missing);
        actions.add(ensureHotbar("minecraft:bucket", 1));
        for (int i = 0; i < missing; i++) {
            JsonObject collect = action("collect_milk");
            collect.addProperty("search_radius", 16);
            collect.addProperty("timeout_ticks", 260);
            actions.add(collect);
        }
    }

    private void ensureStructureLootItem(ArrayList<JsonObject> actions, String item, int count) {
        int missing = Math.max(0, count - countItem(item));
        if (missing <= 0) {
            return;
        }
        actions.add(requiredTakeFromContainer(item, missing, 12));
    }

    private void ensureAnimalDrop(ArrayList<JsonObject> actions, String item, int count) {
        int missing = Math.max(0, count - countItem(item));
        if (missing <= 0) {
            return;
        }
        if (ensureRuleBasedItem(actions, item, missing)) {
            return;
        }
        addAttackAndPickup(actions, "food_animal", item, missing, 12, 180);
        addVoiceAssistMaterialTopUpIfAllowed(actions, item, missing);
    }

    private void ensureAcquireItem(ArrayList<JsonObject> actions, String item, int count, String fallbackBlockOrGroup, int radius) {
        int missing = Math.max(0, count - countItem(item));
        if (missing <= 0) {
            return;
        }
        actions.add(optionalTakeFromContainer(item, missing, 8));
        if (ensureRuleBasedItem(actions, item, missing)) {
            return;
        }
        ensureCollectableItem(actions, item, fallbackBlockOrGroup, missing, radius);
    }

    private boolean ensureRuleBasedItem(ArrayList<JsonObject> actions, String item, int count) {
        return acquisitionRules.find(item).map(rule -> {
            for (MaterialAcquisitionRules.ActionTemplate template : rule.actions()) {
                int actionCount = Math.max(count * template.countPerItem(), template.minCount());
                if (template.type().equals("attack_entity")) {
                    JsonObject attack = action("attack_entity");
                    attack.addProperty("entity_group", template.entityGroup());
                    attack.addProperty("count", Math.max(1, Math.min(actionCount, 8)));
                    attack.addProperty("search_radius", template.searchRadius());
                    attack.addProperty("timeout_ticks", template.timeoutTicks());
                    attack.addProperty("optional", isVoiceAssistCommonMaterial(item));
                    actions.add(attack);
                } else if (template.type().equals("collect_block")) {
                    boolean assistAllowed = isVoiceAssistCommonMaterial(item);
                    JsonObject collect = action("collect_block");
                    if (!template.block().isBlank()) {
                        collect.addProperty("block", template.block());
                    } else {
                        collect.addProperty("block_group", template.blockGroup());
                    }
                    collect.addProperty("count", Math.max(1, actionCount));
                    collect.addProperty("search_radius", template.searchRadius());
                    collect.addProperty("timeout_ticks", Math.max(template.timeoutTicks(), actionCount * 90));
                    collect.addProperty("optional", assistAllowed);
                    actions.add(collect);
                } else if (template.type().equals("collect_drops")) {
                    boolean assistAllowed = isVoiceAssistCommonMaterial(item);
                    JsonObject pickup = action("collect_drops");
                    pickup.addProperty("item", template.item().isBlank() ? item : template.item());
                    pickup.addProperty("count", count);
                    pickup.addProperty("search_radius", template.searchRadius());
                    pickup.addProperty("timeout_ticks", Math.max(template.timeoutTicks(), count * 80));
                    pickup.addProperty("accept_if_already_present", true);
                    pickup.addProperty("magnet_fallback", true);
                    pickup.addProperty("magnet_only", true);
                    pickup.addProperty("optional", assistAllowed);
                    actions.add(pickup);
                }
            }
            addVoiceAssistMaterialTopUpIfAllowed(actions, item, count);
            return true;
        }).orElse(false);
    }

    private void addAttackAndPickup(ArrayList<JsonObject> actions, String entityGroup, String item, int count, int radius, int timeoutTicks) {
        JsonObject attack = action("attack_entity");
        attack.addProperty("entity_group", entityGroup);
        attack.addProperty("count", Math.max(1, Math.min(count, 8)));
        attack.addProperty("search_radius", radius);
        attack.addProperty("timeout_ticks", timeoutTicks);
        attack.addProperty("optional", isVoiceAssistCommonMaterial(item));
        actions.add(attack);
        JsonObject pickup = action("collect_drops");
        pickup.addProperty("item", item);
        pickup.addProperty("count", count);
        pickup.addProperty("search_radius", 10);
        pickup.addProperty("timeout_ticks", Math.max(120, count * 80));
        pickup.addProperty("accept_if_already_present", true);
        pickup.addProperty("magnet_fallback", true);
        pickup.addProperty("magnet_only", true);
        pickup.addProperty("optional", isVoiceAssistCommonMaterial(item));
        actions.add(pickup);
    }

    private void ensureCollectableItem(ArrayList<JsonObject> actions, String item, String blockOrGroup, int count, int radius) {
        int missing = Math.max(0, count - countItem(item));
        if (missing <= 0) {
            return;
        }
        actions.add(optionalTakeFromContainer(item, missing, 8));
        boolean assistAllowed = isVoiceAssistCommonMaterial(item);
        JsonObject collect = action("collect_block");
        if (blockOrGroup.startsWith("minecraft:")) {
            collect.addProperty("block", blockOrGroup);
        } else {
            collect.addProperty("block_group", blockOrGroup);
        }
        collect.addProperty("count", missing);
        collect.addProperty("search_radius", radius);
        collect.addProperty("timeout_ticks", Math.max(160, missing * 90));
        collect.addProperty("optional", assistAllowed);
        actions.add(collect);
        JsonObject pickup = action("collect_drops");
        pickup.addProperty("item", item);
        pickup.addProperty("count", missing);
        pickup.addProperty("search_radius", 10);
        pickup.addProperty("timeout_ticks", Math.max(120, missing * 80));
        pickup.addProperty("accept_if_already_present", true);
        pickup.addProperty("magnet_fallback", true);
        pickup.addProperty("magnet_only", true);
        pickup.addProperty("optional", assistAllowed);
        actions.add(pickup);
        addVoiceAssistMaterialTopUpIfAllowed(actions, item, missing);
    }

    private JsonObject collectDropsItem(String item, int count, int radius, int timeoutTicks) {
        JsonObject pickup = action("collect_drops");
        pickup.addProperty("item", item);
        pickup.addProperty("count", count);
        pickup.addProperty("search_radius", radius);
        pickup.addProperty("timeout_ticks", timeoutTicks);
        pickup.addProperty("accept_if_already_present", true);
        pickup.addProperty("magnet_fallback", true);
        pickup.addProperty("magnet_only", true);
        return pickup;
    }

    private JsonObject collectDropsGroup(String group, int count, int radius, int timeoutTicks) {
        JsonObject pickup = action("collect_drops");
        pickup.addProperty("item_group", group);
        pickup.addProperty("count", count);
        pickup.addProperty("search_radius", radius);
        pickup.addProperty("timeout_ticks", timeoutTicks);
        pickup.addProperty("accept_if_already_present", true);
        pickup.addProperty("magnet_fallback", true);
        pickup.addProperty("magnet_only", true);
        return pickup;
    }

    private JsonObject collectDropsForBlock(String block, String group, int count, int radius, int timeoutTicks) {
        String expectedItem = expectedDropForBlock(block);
        if (!expectedItem.isBlank()) {
            return collectDropsItem(expectedItem, count, radius, timeoutTicks);
        }
        String expectedGroup = expectedDropGroupForBlockGroup(group);
        if (!expectedGroup.isBlank()) {
            return collectDropsGroup(expectedGroup, count, radius, timeoutTicks);
        }
        return collectDropsGroup("any_drop", count, radius, timeoutTicks);
    }

    private String expectedDropForBlock(String block) {
        if (block == null || block.isBlank()) {
            return "";
        }
        if (block.equals("minecraft:stone")) return "minecraft:cobblestone";
        if (block.equals("minecraft:deepslate")) return "minecraft:cobbled_deepslate";
        if (block.equals("minecraft:grass_block") || block.equals("minecraft:podzol") || block.equals("minecraft:mycelium")) return "minecraft:dirt";
        if (block.equals("minecraft:coal_ore") || block.equals("minecraft:deepslate_coal_ore")) return "minecraft:coal";
        if (block.equals("minecraft:iron_ore") || block.equals("minecraft:deepslate_iron_ore")) return "minecraft:raw_iron";
        if (block.equals("minecraft:copper_ore") || block.equals("minecraft:deepslate_copper_ore")) return "minecraft:raw_copper";
        if (block.equals("minecraft:gold_ore") || block.equals("minecraft:deepslate_gold_ore")) return "minecraft:raw_gold";
        if (block.equals("minecraft:diamond_ore") || block.equals("minecraft:deepslate_diamond_ore")) return "minecraft:diamond";
        if (block.equals("minecraft:emerald_ore") || block.equals("minecraft:deepslate_emerald_ore")) return "minecraft:emerald";
        if (block.equals("minecraft:lapis_ore") || block.equals("minecraft:deepslate_lapis_ore")) return "minecraft:lapis_lazuli";
        if (block.equals("minecraft:redstone_ore") || block.equals("minecraft:deepslate_redstone_ore")) return "minecraft:redstone";
        if (block.equals("minecraft:clay")) return "minecraft:clay_ball";
        if (block.equals("minecraft:sugar_cane") || block.equals("minecraft:cactus") || block.equals("minecraft:bamboo")) return block;
        if (MinecraftItemGroups.matches("log", block) || MinecraftItemGroups.matches("cobblestone", block) || MinecraftItemGroups.matches("planks", block) || block.equals("minecraft:dirt") || block.equals("minecraft:sand") || block.equals("minecraft:red_sand") || block.equals("minecraft:gravel")) {
            return block;
        }
        return "";
    }

    private String expectedDropGroupForBlockGroup(String group) {
        if (group == null || group.isBlank()) {
            return "";
        }
        return switch (group) {
            case "cobblestone", "log" -> group;
            case "dirt", "sand", "gravel", "clay" -> group;
            case "stone" -> "cobblestone";
            default -> "";
        };
    }

    private int requiredWoodPlanks(String item) {
        if (item.endsWith("_button")) return 1;
        if (item.endsWith("_pressure_plate") || item.endsWith("_fence_gate")) return 2;
        if (item.endsWith("_slab")) return 3;
        if (item.endsWith("_fence")) return 4;
        if (item.endsWith("_boat") || item.equals("minecraft:bamboo_raft")) return 5;
        if (item.endsWith("_stairs") || item.endsWith("_door") || item.endsWith("_trapdoor") || item.endsWith("_sign")) return 6;
        return 0;
    }

    private int requiredSticks(String item) {
        if (item.endsWith("_fence")) return 2;
        if (item.endsWith("_fence_gate")) return 4;
        if (item.endsWith("_sign")) return 1;
        return 0;
    }

    private String colorKey(String item) {
        if (item == null || !item.startsWith("minecraft:")) {
            return "";
        }
        String name = item.substring("minecraft:".length());
        for (String key : MinecraftRecipeFamilies.COLOR_KEYS) {
            if (name.startsWith(key + "_")) {
                return key;
            }
        }
        return "";
    }

    private String dyeSourceItem(String dye) {
        return switch (dye) {
            case "minecraft:white_dye" -> "minecraft:bone_meal";
            case "minecraft:yellow_dye" -> "minecraft:dandelion";
            case "minecraft:red_dye" -> "minecraft:poppy";
            case "minecraft:blue_dye" -> "minecraft:cornflower";
            case "minecraft:light_blue_dye" -> "minecraft:blue_orchid";
            case "minecraft:magenta_dye" -> "minecraft:allium";
            case "minecraft:orange_dye" -> "minecraft:orange_tulip";
            case "minecraft:pink_dye" -> "minecraft:pink_tulip";
            case "minecraft:light_gray_dye" -> "minecraft:azure_bluet";
            case "minecraft:green_dye" -> "minecraft:cactus";
            case "minecraft:black_dye" -> "minecraft:ink_sac";
            default -> "";
        };
    }

    private String resolveCraftTargetAlias(String item) {
        if (!item.startsWith("minecraft:")) {
            return "";
        }
        WoodKind wood = client == null ? null : choosePreferredWoodKind();
        String woodKey = wood == null ? "oak" : wood.key();
        return switch (item) {
            case "minecraft:boat" -> woodKey.equals("bamboo") ? "minecraft:bamboo_raft" : "minecraft:" + woodKey + "_boat";
            case "minecraft:chest_boat" -> woodKey.equals("bamboo") ? "minecraft:bamboo_chest_raft" : "minecraft:" + woodKey + "_chest_boat";
            case "minecraft:door" -> "minecraft:" + woodKey + "_door";
            case "minecraft:trapdoor" -> "minecraft:" + woodKey + "_trapdoor";
            case "minecraft:fence" -> "minecraft:" + woodKey + "_fence";
            case "minecraft:fence_gate" -> "minecraft:" + woodKey + "_fence_gate";
            case "minecraft:sign" -> "minecraft:" + woodKey + "_sign";
            case "minecraft:hanging_sign" -> "minecraft:" + woodKey + "_hanging_sign";
            case "minecraft:slab" -> "minecraft:" + woodKey + "_slab";
            case "minecraft:stairs" -> "minecraft:" + woodKey + "_stairs";
            case "minecraft:button" -> "minecraft:" + woodKey + "_button";
            case "minecraft:pressure_plate" -> "minecraft:" + woodKey + "_pressure_plate";
            case "minecraft:sword" -> "minecraft:wooden_sword";
            case "minecraft:pickaxe" -> "minecraft:wooden_pickaxe";
            case "minecraft:axe" -> "minecraft:wooden_axe";
            case "minecraft:shovel" -> "minecraft:wooden_shovel";
            case "minecraft:hoe" -> "minecraft:wooden_hoe";
            case "minecraft:bed" -> "minecraft:white_bed";
            case "minecraft:carpet" -> "minecraft:white_carpet";
            case "minecraft:banner" -> "minecraft:white_banner";
            case "minecraft:wool" -> "minecraft:white_wool";
            case "minecraft:stone_stairs_family" -> "minecraft:stone_stairs";
            default -> item;
        };
    }

    String resolveCraftTargetAliasForTesting(String item) {
        return resolveCraftTargetAlias(item);
    }

    private boolean requiresCraftingTable(String item) {
        return recipeCatalog.craftRecipes().stream()
            .filter(recipe -> recipe.output().equals(item))
            .anyMatch(recipe -> !recipe.fitsGrid(2));
    }

    private NativePlan planStonePickaxe(String source) {
        if (countItem("minecraft:stone_pickaxe") > 0) {
            return planEnsureHotbar("craft_stone_pickaxe", source, "minecraft:stone_pickaxe");
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        if (!hasAnyItem("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe")) {
            actions.addAll(planWoodenPickaxe("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        } else {
            ensureWoodToolchain(actions);
        }
        int missingCobble = Math.max(0, 3 - countGroup("cobblestone"));
        if (missingCobble > 0) {
            actions.add(optionalTakeFromContainer("cobblestone", missingCobble, 8));
            actions.add(closeScreen());
            boolean assistAllowed = isVoiceAssistCommonMaterial("minecraft:cobblestone");
            JsonObject durability = action("check_tool_durability");
            durability.addProperty("tool_group", "pickaxe");
            durability.addProperty("min_remaining_uses", missingCobble);
            durability.addProperty("optional", true);
            actions.add(durability);
            JsonObject collect = action("collect_block");
            collect.addProperty("block_group", "cobblestone");
            collect.addProperty("count", missingCobble);
            collect.addProperty("search_radius", 32);
            collect.addProperty("timeout_ticks", Math.max(160, missingCobble * 90));
            collect.addProperty("optional", assistAllowed);
            actions.add(collect);
            JsonObject pickup = collectDropsItem("minecraft:cobblestone", missingCobble, 10, Math.max(120, missingCobble * 80));
            pickup.addProperty("optional", assistAllowed);
            actions.add(pickup);
            addVoiceAssistMaterialTopUpIfAllowed(actions, "minecraft:cobblestone", missingCobble);
        }
        ensureCraftingTableOpen(actions);
        actions.add(craft("minecraft:stone_pickaxe", 1));
        actions.add(closeScreen());
        actions.add(ensureHotbar("minecraft:stone_pickaxe", 1));
        return plan("craft_stone_pickaxe", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planCraftingTable(String source) {
        if (countItem("minecraft:crafting_table") > 0) {
            return planEnsureHotbar("craft_crafting_table", source, "minecraft:crafting_table");
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensurePlanks(actions, 4);
        actions.add(craft("minecraft:crafting_table", 1));
        actions.add(ensureHotbar("minecraft:crafting_table", 1));
        return plan("craft_crafting_table", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planSticks(String source) {
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensurePlanks(actions, 2);
        actions.add(craft("minecraft:stick", 1));
        return plan("craft_stick", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planFurnace(String source) {
        if (countItem("minecraft:furnace") > 0) {
            return planEnsureHotbar("craft_furnace", source, "minecraft:furnace");
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        int missingCobble = Math.max(0, 8 - countGroup("cobblestone"));
        if (missingCobble > 0) {
            actions.add(optionalTakeFromContainer("cobblestone", missingCobble, 8));
            boolean assistAllowed = isVoiceAssistCommonMaterial("minecraft:cobblestone");
            JsonObject collect = action("collect_block");
            collect.addProperty("block_group", "cobblestone");
            collect.addProperty("count", missingCobble);
            collect.addProperty("search_radius", 32);
            collect.addProperty("timeout_ticks", Math.max(240, missingCobble * 90));
            collect.addProperty("optional", assistAllowed);
            actions.add(collect);
            JsonObject pickup = collectDropsItem("minecraft:cobblestone", missingCobble, 10, Math.max(160, missingCobble * 80));
            pickup.addProperty("optional", assistAllowed);
            actions.add(pickup);
            addVoiceAssistMaterialTopUpIfAllowed(actions, "minecraft:cobblestone", missingCobble);
        }
        ensureCraftingTableAvailable(actions);
        ensureCraftingTableOpen(actions);
        actions.add(craft("minecraft:furnace", 1));
        actions.add(closeScreen());
        actions.add(ensureHotbar("minecraft:furnace", 1));
        return plan("craft_furnace", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planTorchCraft(String source) {
        ArrayList<JsonObject> actions = new ArrayList<>();
        if (countItem("minecraft:stick") < 1) {
            ensurePlanks(actions, 2);
            actions.add(craft("minecraft:stick", 1));
        }
        actions.add(craft("minecraft:torch", 1));
        actions.add(ensureHotbar("minecraft:torch", 1));
        return plan("craft_torch", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planWoodenPickaxe(String source) {
        if (countItem("minecraft:wooden_pickaxe") > 0) {
            return planEnsureHotbar("craft_wooden_pickaxe", source, "minecraft:wooden_pickaxe");
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        int requiredPlanks = Math.max(0, 3 - countGroup("planks"));
        if (countItem("minecraft:stick") < 2) {
            requiredPlanks += 2;
        }
        if (countItem("minecraft:crafting_table") <= 0) {
            requiredPlanks += 4;
        }
        ensurePlanks(actions, requiredPlanks);
        if (countItem("minecraft:stick") < 2) {
            actions.add(craft("minecraft:stick", 1));
        }
        if (countItem("minecraft:crafting_table") <= 0) {
            actions.add(craft("minecraft:crafting_table", 1));
        }
        ensureCraftingTableOpen(actions);
        actions.add(craft("minecraft:wooden_pickaxe", 1));
        actions.add(closeScreen());
        actions.add(ensureHotbar("minecraft:wooden_pickaxe", 1));
        return plan("craft_wooden_pickaxe", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planChest(String source) {
        if (countItem("minecraft:chest") > 0) {
            return planEnsureHotbar("craft_chest", source, "minecraft:chest");
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensurePlanks(actions, countItem("minecraft:crafting_table") <= 0 ? 12 : 8);
        ensureCraftingTableAvailable(actions);
        ensureCraftingTableOpen(actions);
        actions.add(craft("minecraft:chest", 1));
        actions.add(closeScreen());
        actions.add(ensureHotbar("minecraft:chest", 1));
        return plan("craft_chest", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planShield(String source) {
        if (countItem("minecraft:shield") > 0) {
            return planEnsureHotbar("craft_shield", source, "minecraft:shield");
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensureIronIngots(actions, 1);
        ensurePlanks(actions, countItem("minecraft:crafting_table") <= 0 ? 10 : 6);
        ensureCraftingTableAvailable(actions);
        ensureCraftingTableOpen(actions);
        actions.add(craft("minecraft:shield", 1));
        actions.add(closeScreen());
        actions.add(ensureHotbar("minecraft:shield", 1));
        return plan("craft_shield", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planGlass(String source) {
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensureAcquireItem(actions, "minecraft:sand", 1, "sand", 16);
        if (countItem("minecraft:furnace") <= 0) {
            actions.addAll(planFurnace("dependency").actions().stream().map(ExecutorProtocol.Action::body).toList());
        }
        ensureFuel(actions);
        JsonObject open = action("open_workstation");
        open.addProperty("station", "minecraft:furnace");
        open.addProperty("search_radius", 8);
        open.addProperty("allow_place", true);
        open.addProperty("avoid_occupied", true);
        actions.add(open);
        JsonObject smelt = action("smelt");
        smelt.addProperty("input", "minecraft:sand");
        smelt.addProperty("fuel", "");
        smelt.addProperty("output", "minecraft:glass");
        smelt.addProperty("count", 1);
        smelt.addProperty("timeout_ticks", 360);
        actions.add(smelt);
        actions.add(closeScreen());
        return plan("smelt_glass", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planHoneyBottle(String source) {
        if (countItem("minecraft:honey_bottle") > 0) {
            return planEnsureHotbar("collect_honey_bottle", source, "minecraft:honey_bottle");
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        ensureHoneyBottle(actions, 1);
        actions.add(ensureHotbar("minecraft:honey_bottle", 1));
        return plan("collect_honey_bottle", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planEnsureHotbar(String goal, String source, String item) {
        return plan(goal, source, ensureHotbar(item, 1));
    }

    private void ensureWoodToolchain(ArrayList<JsonObject> actions) {
        int requiredPlanks = 0;
        if (countItem("minecraft:stick") < 2) {
            requiredPlanks += 2;
        }
        if (countItem("minecraft:crafting_table") <= 0) {
            requiredPlanks += 4;
        }
        ensurePlanks(actions, requiredPlanks);
        if (countItem("minecraft:stick") < 2) {
            actions.add(craft("minecraft:stick", 1));
        }
        if (countItem("minecraft:crafting_table") <= 0) {
            actions.add(craft("minecraft:crafting_table", 1));
        }
    }

    private void ensurePlanks(ArrayList<JsonObject> actions, int requiredPlanks) {
        int missingPlanks = Math.max(0, requiredPlanks - countGroup("planks"));
        if (missingPlanks <= 0) {
            return;
        }
        WoodKind wood = chooseLogKind();
        int availableLogs = wood == null ? countGroup("log") : countItem(wood.log());
        int plankOutputCount = wood == null ? 2 : wood.plankOutputCount();
        int logsNeeded = Math.max(1, (int) Math.ceil(missingPlanks / (double) plankOutputCount));
        if (availableLogs < logsNeeded) {
            int missingLogs = logsNeeded - availableLogs;
            actions.add(optionalTakeFromContainer("log", missingLogs, 8));
            String assistItem = wood == null ? "minecraft:oak_log" : wood.log();
            boolean assistAllowed = isVoiceAssistCommonMaterial(assistItem);
            JsonObject collect = action("collect_block");
            if (wood == null) {
                collect.addProperty("block_group", "log");
            } else {
                collect.addProperty("block", wood.log());
            }
            collect.addProperty("count", missingLogs);
            collect.addProperty("search_radius", 32);
            collect.addProperty("timeout_ticks", Math.max(180, missingLogs * 160));
            collect.addProperty("optional", assistAllowed);
            actions.add(collect);
            JsonObject pickup = wood == null
                ? collectDropsGroup("log", missingLogs, 10, Math.max(120, missingLogs * 80))
                : collectDropsItem(wood.log(), missingLogs, 10, Math.max(120, missingLogs * 80));
            pickup.addProperty("optional", assistAllowed);
            actions.add(pickup);
            addVoiceAssistMaterialTopUpIfAllowed(actions, assistItem, missingLogs);
        }
        if (wood == null) {
            actions.add(craftPlanksFromAvailableLog(requiredPlanks));
        } else {
            actions.add(craft(resolvePlanksRecipe(wood), logsNeeded));
        }
    }

    private void ensureLog(ArrayList<JsonObject> actions, int count) {
        int missingLogs = Math.max(0, count - countGroup("log"));
        if (missingLogs <= 0) {
            return;
        }
        WoodKind wood = chooseLogKind();
        actions.add(optionalTakeFromContainer("log", missingLogs, 8));
        String assistItem = wood == null ? "minecraft:oak_log" : wood.log();
        boolean assistAllowed = isVoiceAssistCommonMaterial(assistItem);
        JsonObject collect = action("collect_block");
        if (wood == null) {
            collect.addProperty("block_group", "log");
        } else {
            collect.addProperty("block", wood.log());
        }
        collect.addProperty("count", missingLogs);
        collect.addProperty("search_radius", 32);
        collect.addProperty("timeout_ticks", Math.max(180, missingLogs * 160));
        collect.addProperty("optional", assistAllowed);
        actions.add(collect);
        JsonObject pickup = wood == null
            ? collectDropsGroup("log", missingLogs, 10, Math.max(120, missingLogs * 80))
            : collectDropsItem(wood.log(), missingLogs, 10, Math.max(120, missingLogs * 80));
        pickup.addProperty("optional", assistAllowed);
        actions.add(pickup);
        addVoiceAssistMaterialTopUpIfAllowed(actions, assistItem, missingLogs);
    }

    private void ensureSpecificPlanks(ArrayList<JsonObject> actions, WoodKind wood, int requiredPlanks) {
        int missingPlanks = Math.max(0, requiredPlanks - countItem(wood.planks()));
        if (missingPlanks <= 0) {
            return;
        }
        int logsNeeded = Math.max(1, (int) Math.ceil(missingPlanks / (double) wood.plankOutputCount()));
        int availableLogs = countItem(wood.log());
        if (availableLogs < logsNeeded) {
            int missingLogs = logsNeeded - availableLogs;
            actions.add(optionalTakeFromContainer(wood.log(), missingLogs, 8));
            boolean assistAllowed = isVoiceAssistCommonMaterial(wood.log());
            JsonObject collect = action("collect_block");
            collect.addProperty("block", wood.log());
            collect.addProperty("count", missingLogs);
            collect.addProperty("search_radius", 32);
            collect.addProperty("timeout_ticks", Math.max(180, missingLogs * 160));
            collect.addProperty("optional", assistAllowed);
            actions.add(collect);
            JsonObject pickup = action("collect_drops");
            pickup.addProperty("item", wood.log());
            pickup.addProperty("count", missingLogs);
            pickup.addProperty("search_radius", 10);
            pickup.addProperty("timeout_ticks", Math.max(120, missingLogs * 80));
            pickup.addProperty("accept_if_already_present", true);
            pickup.addProperty("magnet_fallback", true);
            pickup.addProperty("magnet_only", true);
            pickup.addProperty("optional", assistAllowed);
            actions.add(pickup);
            addVoiceAssistMaterialTopUpIfAllowed(actions, wood.log(), missingLogs);
        }
        actions.add(craft(wood.planks(), logsNeeded));
    }

    private WoodKind chooseLogKind() {
        for (WoodKind wood : WOOD_KINDS) {
            if (countItem(wood.log()) > 0) {
                return wood;
            }
        }
        return null;
    }

    private WoodKind choosePreferredWoodKind() {
        for (WoodKind wood : WOOD_KINDS) {
            if (countItem(wood.planks()) > 0) {
                return wood;
            }
        }
        return chooseLogKind();
    }

    private WoodKind woodKindForRecipe(String item) {
        if (!item.startsWith("minecraft:")) {
            return null;
        }
        String name = item.substring("minecraft:".length());
        if (name.equals("bamboo_raft") || name.equals("bamboo_chest_raft")) {
            return woodKindByKey("bamboo");
        }
        for (WoodKind wood : WOOD_KINDS) {
            String key = wood.key();
            if (name.equals(key + "_planks") || name.startsWith(key + "_")) {
                return wood;
            }
        }
        return null;
    }

    private WoodKind woodKindByKey(String key) {
        for (WoodKind wood : WOOD_KINDS) {
            if (wood.key().equals(key)) {
                return wood;
            }
        }
        return null;
    }

    private String resolvePlanksRecipe(WoodKind wood) {
        if (wood != null) {
            return wood.planks();
        }
        for (WoodKind candidate : WOOD_KINDS) {
            if (countItem(candidate.log()) > 0) {
                return candidate.planks();
            }
        }
        return "minecraft:oak_planks";
    }

    private JsonObject craftPlanksFromAvailableLog(int minPlanks) {
        JsonObject body = action("craft_planks_from_log");
        body.addProperty("min_planks", minPlanks);
        body.addProperty("max_crafts", Math.max(1, Math.min(8, (int) Math.ceil(minPlanks / 2.0D))));
        return body;
    }

    private void ensureCraftingTableOpen(ArrayList<JsonObject> actions) {
        actions.add(ensureHotbar("minecraft:crafting_table", 1));
        JsonObject open = action("open_workstation");
        open.addProperty("station", "minecraft:crafting_table");
        open.addProperty("search_radius", 8);
        open.addProperty("allow_place", true);
        open.addProperty("avoid_occupied", true);
        actions.add(open);
    }

    private void ensureCraftingTableAvailable(ArrayList<JsonObject> actions) {
        if (countItem("minecraft:crafting_table") > 0) {
            return;
        }
        actions.add(craft("minecraft:crafting_table", 1));
    }

    private void ensureWorkstationAvailable(ArrayList<JsonObject> actions, String station) {
        if (countItem(station) > 0) {
            return;
        }
        if (station.equals("minecraft:stonecutter")) {
            ensureIngredientItem(actions, "minecraft:stone", 3, new HashSet<>(), 0);
            ensureIronIngots(actions, 1);
            ensureCraftingTableAvailable(actions);
            ensureCraftingTableOpen(actions);
            actions.add(craft("minecraft:stonecutter", 1));
            actions.add(closeScreen());
            return;
        }
        if (station.equals("minecraft:smithing_table")) {
            ensurePlanks(actions, 4);
            ensureIronIngots(actions, 2);
            ensureCraftingTableAvailable(actions);
            ensureCraftingTableOpen(actions);
            actions.add(craft("minecraft:smithing_table", 1));
            actions.add(closeScreen());
        }
    }

    private JsonObject openWorkstation(String station, int radius, boolean allowPlace, boolean avoidOccupied) {
        JsonObject open = action("open_workstation");
        open.addProperty("station", station);
        open.addProperty("search_radius", radius);
        open.addProperty("allow_place", allowPlace);
        open.addProperty("avoid_occupied", avoidOccupied);
        return open;
    }

    private void ensureFuel(ArrayList<JsonObject> actions) {
        if (countGroup("fuel") > 0) {
            return;
        }
        ensurePlanks(actions, 1);
    }

    private JsonObject craft(String recipe, int count) {
        JsonObject body = action("craft");
        body.addProperty("recipe", recipe);
        body.addProperty("count", count);
        return body;
    }

    private JsonObject craftAt(String recipe, int count, String expectedStation) {
        JsonObject body = craft(recipe, count);
        body.addProperty("expected_station", expectedStation);
        return body;
    }

    private JsonObject ensureHotbar(String item, int count) {
        JsonObject body = action("ensure_hotbar");
        body.addProperty("item", item);
        body.addProperty("count", count);
        return body;
    }

    private JsonObject closeScreen() {
        return action("close_screen");
    }

    private NativePlan planPlaceWorkstation(JsonObject goal, String source) {
        String station = string(goal, "station");
        if (!station.equals("minecraft:furnace")
            && !station.equals("minecraft:stonecutter")
            && !station.equals("minecraft:smithing_table")) {
            station = "minecraft:crafting_table";
        }
        ArrayList<JsonObject> actions = new ArrayList<>();
        actions.add(voiceAssistMaterialTopUp(station, 1, 1));
        actions.add(ensureHotbar(station, 1));
        actions.add(openWorkstation(station, 4, true, true));
        return plan("place_workstation", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planMove(String text, String source) {
        boolean swim = SWIM_WORDS.matcher(text).find();
        boolean sprint = swim || Pattern.compile("走|ダッシュ|抜け|run|sprint", Pattern.CASE_INSENSITIVE).matcher(text).find();
        String direction = "forward";
        if (text.contains("下が") || text.contains("戻") || text.contains("back")) direction = "back";
        else if (text.contains("左") || text.contains("left")) direction = "left";
        else if (text.contains("右") || text.contains("right")) direction = "right";
        boolean explicitDistance = hasExplicitMoveDistance(text);
        int fallbackDistance = swim ? 10 : sprint ? 12 : (text.contains("まっすぐ") || text.contains("真っ直ぐ") || text.contains("進") || text.contains("すす")) ? 12 : 6;
        int distance = parseBlocks(text, fallbackDistance, 1, 96);
        return planMoveFields(direction, distance, sprint, swim, SPRINT_JUMP_WORDS.matcher(text).find(), !explicitDistance, source);
    }

    private NativePlan planMoveGoal(JsonObject goal, String source) {
        String direction = string(goal, "direction");
        if (!direction.equals("back") && !direction.equals("left") && !direction.equals("right")) {
            direction = "forward";
        }
        int distance = boundedInt(goal, "distance_blocks", bool(goal, "sprint") ? 7 : 4, 1, 96);
        return planMoveFields(direction, distance, bool(goal, "sprint"), bool(goal, "swim"), bool(goal, "jump_while_sprinting"), bool(goal, "extend_if_clear"), source);
    }

    private NativePlan planMoveFields(String direction, int distance, boolean sprint, String source) {
        return planMoveFields(direction, distance, sprint, false, false, source);
    }

    private NativePlan planMoveFields(String direction, int distance, boolean sprint, boolean swim, String source) {
        return planMoveFields(direction, distance, sprint, swim, false, true, source);
    }

    private NativePlan planMoveFields(String direction, int distance, boolean sprint, boolean swim, boolean jumpWhileSprinting, String source) {
        return planMoveFields(direction, distance, sprint, swim, jumpWhileSprinting, true, source);
    }

    private NativePlan planMoveFields(String direction, int distance, boolean sprint, boolean swim, boolean jumpWhileSprinting, boolean extendIfClear, String source) {
        int maxAdaptiveDistance = swim ? 18 : sprint ? 24 : 16;
        int durationDistance = extendIfClear ? Math.max(distance, maxAdaptiveDistance) : distance;
        int duration = Math.max(20, Math.min(700, durationDistance * (sprint ? 7 : 10)));
        JsonObject move = action("move");
        move.addProperty("direction", direction);
        move.addProperty("distance_blocks", distance);
        move.addProperty("duration_ticks", duration);
        move.addProperty("sprint", sprint);
        move.addProperty("swim", swim);
        move.addProperty("jump_while_sprinting", jumpWhileSprinting);
        move.addProperty("scan_aware", true);
        move.addProperty("adaptive_distance", true);
        move.addProperty("extend_if_clear", extendIfClear);
        move.addProperty("max_adaptive_distance_blocks", maxAdaptiveDistance);
        move.addProperty("face_move_direction", true);
        move.addProperty("auto_jump", true);
        move.addProperty("allow_assist", true);
        move.addProperty("allow_place", true);
        move.addProperty("allow_dig", true);
        move.addProperty("allow_sprint_jump", true);
        if (swim) {
            JsonObject boat = action("use_boat_if_water");
            boat.addProperty("distance_blocks", Math.max(distance, maxAdaptiveDistance));
            boat.addProperty("search_radius", 16);
            boat.addProperty("timeout_ticks", 120);
            boat.addProperty("optional", true);
            boat.addProperty("programmatic_assist", true);
            return plan("move", source, boat, move);
        }
        return plan("move", source, move);
    }

    private NativePlan planBuildBridge(JsonObject goal, String source) {
        String direction = string(goal, "direction");
        if (!direction.equals("back") && !direction.equals("left") && !direction.equals("right")) {
            direction = "forward";
        }
        int distance = boundedInt(goal, "distance_blocks", 4, 1, 12);
        JsonObject support = voiceAssistSupport("minecraft:dirt", Math.max(8, distance + 2), Math.max(8, distance + 2));
        JsonObject bridge = action("build_bridge");
        bridge.addProperty("direction", direction);
        bridge.addProperty("distance_blocks", distance);
        bridge.addProperty("move_after_place", true);
        bridge.addProperty("timeout_ticks", Math.max(80, distance * 45));
        return plan("build_bridge", source, support, bridge);
    }

    private NativePlan planPickup(String source) {
        JsonObject collect = action("collect_drops");
        collect.addProperty("item_group", "any_drop");
        collect.addProperty("count", 8);
        collect.addProperty("search_radius", 12);
        collect.addProperty("timeout_ticks", 200);
        collect.addProperty("magnet_fallback", true);
        collect.addProperty("magnet_only", true);
        return plan("pickup_items", source, collect);
    }

    private NativePlan planDig(String text, String source) {
        String pattern = "stair_down";
        if (text.contains("横") || text.contains("トンネル")) pattern = "tunnel_forward";
        else if (text.contains("縦")) pattern = "shaft_down_safe";
        int steps = parseBlocks(text, text.contains("少し") || text.contains("ちょっと") ? 2 : 4, 1, 12);
        return planDigFields(pattern, steps, source);
    }

    private NativePlan planDigGoal(JsonObject goal, String source) {
        String pattern = string(goal, "pattern");
        if (!pattern.equals("tunnel_forward") && !pattern.equals("shaft_down_safe")) {
            pattern = "stair_down";
        }
        return planDigFields(pattern, boundedInt(goal, "steps", 4, 1, 12), source);
    }

    private NativePlan planDigFields(String pattern, int steps, String source) {
        JsonObject durability = action("check_tool_durability");
        durability.addProperty("tool_group", "pickaxe");
        durability.addProperty("min_remaining_uses", Math.max(1, steps));
        durability.addProperty("optional", true);
        JsonObject dig = action("dig_pattern");
        dig.addProperty("pattern", pattern);
        dig.addProperty("direction", "forward");
        dig.addProperty("steps", steps);
        dig.addProperty("timeout_ticks", Math.max(120, steps * 140));
        return plan("dig_pattern", source, durability, dig);
    }

    private NativePlan planCelebrate(String text, String source) {
        String style = "cheer";
        if (Pattern.compile("ダンス|踊|dance|いえーい|いぇーい|yeah|yay|woo", Pattern.CASE_INSENSITIVE).matcher(text).find()) style = "dance";
        else if (Pattern.compile("ポーズ|きめて|決めて|すごい|すげ|ナイス|nice|最高|勝った|クリア", Pattern.CASE_INSENSITIVE).matcher(text).find()) style = "youtuber_pose";
        return planCelebrateFields(style, text.contains("ちょっと") || text.contains("少し") ? 40 : 70, source);
    }

    private NativePlan planCelebrateGoal(JsonObject goal, String source) {
        String style = string(goal, "style");
        if (!style.equals("dance") && !style.equals("youtuber_pose")) style = "cheer";
        return planCelebrateFields(style, boundedInt(goal, "duration_ticks", 70, 20, 120), source);
    }

    private NativePlan planCelebrateFields(String style, int ticks, String source) {
        JsonObject celebrate = action("celebrate");
        celebrate.addProperty("style", style);
        celebrate.addProperty("duration_ticks", ticks);
        celebrate.addProperty("third_person", true);
        return plan("celebrate", source, celebrate);
    }

    private NativePlan planAmbientChat(JsonObject goal, String source) {
        JsonObject chat = action("ambient_chat");
        chat.addProperty("message", sanitizeAmbientMessage(string(goal, "message")));
        chat.addProperty("style", normalizeAmbientStyle(string(goal, "style")));
        chat.addProperty("duration_ticks", boundedInt(goal, "duration_ticks", 80, 30, 140));
        chat.addProperty("third_person", true);
        return plan("ambient_chat", source, chat);
    }

    private NativePlan planBuildShelter(JsonObject goal, String source) {
        String style = string(goal, "style");
        String message = switch (style) {
            case "hideout" -> "小さい秘密基地を作るよ。";
            case "safe_spot" -> "安全な場所を作るよ。";
            default -> "小さい家を作るよ。";
        };
        JsonObject chat = action("ambient_chat");
        chat.addProperty("message", message);
        chat.addProperty("style", "nod");
        chat.addProperty("duration_ticks", 50);
        chat.addProperty("third_person", true);
        ArrayList<JsonObject> actions = new ArrayList<>();
        actions.add(chat);
        int targetMaterials = childMaterialAssistEnabled() ? childShelterMaterialTarget() : 4;
        int currentMaterials = countShelterMaterials();
        if (currentMaterials < targetMaterials) {
            int missing = Math.max(4, targetMaterials - currentMaterials);
            actions.add(optionalTakeFromContainer("planks", missing, 8));
            actions.add(optionalTakeFromContainer("cobblestone", missing, 8));
            actions.add(optionalTakeFromContainer("dirt", missing, 8));
            actions.add(voiceAssistSupport("minecraft:dirt", Math.max(8, missing), targetMaterials));
            actions.add(optionalCollectBlock("dirt", Math.min(missing, 8), 10, 180));
            actions.add(optionalCollectBlock("sand", Math.min(missing, 8), 12, 180));
            actions.add(optionalCollectDrops("any_drop", Math.min(missing, 8), 8, 120));
        }
        JsonObject shelter = action("emergency_shelter");
        shelter.addProperty("radius", 2);
        shelter.addProperty("timeout_ticks", 240);
        shelter.addProperty("child_mode", childMaterialAssistEnabled());
        actions.add(shelter);
        return plan("build_shelter", source, actions.toArray(JsonObject[]::new));
    }

    private NativePlan planBuildStructure(JsonObject goal, String source) {
        String style = string(goal, "style");
        String size = string(goal, "size");
        String palette = string(goal, "palette");
        if (!style.equals("cute_house") && !style.equals("hideout")) {
            style = "small_house";
        }
        if (!size.equals("small")) {
            size = "tiny";
        }
        if (!palette.equals("wood") && !palette.equals("dirt") && !palette.equals("sand")) {
            palette = "available";
        }
        String message = switch (style) {
            case "cute_house" -> "小さくて見た目のいい家を作るよ。";
            case "hideout" -> "小さい秘密基地を作るよ。";
            default -> "小さい家を建てるよ。";
        };
        ArrayList<JsonObject> actions = new ArrayList<>();
        JsonObject chat = action("ambient_chat");
        chat.addProperty("message", message);
        chat.addProperty("style", "nod");
        chat.addProperty("duration_ticks", 50);
        chat.addProperty("third_person", true);
        actions.add(chat);

        JsonObject scan = action("scan_build_area");
        scan.addProperty("radius_chunks", 8);
        scan.addProperty("samples_per_chunk", 4);
        actions.add(scan);

        actions.add(voiceAssistSupport("minecraft:oak_planks", size.equals("small") ? 96 : 72, size.equals("small") ? 96 : 72));

        JsonObject blueprint = action("build_blueprint");
        blueprint.addProperty("style", style);
        blueprint.addProperty("size", size);
        blueprint.addProperty("palette", palette);
        blueprint.addProperty("max_blocks", size.equals("small") ? 128 : 96);
        blueprint.addProperty("timeout_ticks", 900);
        blueprint.addProperty("terrain_aware", true);
        blueprint.addProperty("programmatic_build", true);
        blueprint.addProperty("animated", true);
        blueprint.addProperty("animation_delay_ticks", 2);
        actions.add(blueprint);
        return plan("build_structure", source, actions.toArray(JsonObject[]::new));
    }

    private JsonObject optionalCollectBlock(String blockGroup, int count, int radius, int timeoutTicks) {
        JsonObject collect = action("collect_block");
        collect.addProperty("block_group", blockGroup);
        collect.addProperty("count", Math.max(1, count));
        collect.addProperty("search_radius", radius);
        collect.addProperty("timeout_ticks", timeoutTicks);
        collect.addProperty("optional", true);
        return collect;
    }

    private JsonObject optionalCollectDrops(String itemGroup, int count, int radius, int timeoutTicks) {
        JsonObject pickup = action("collect_drops");
        pickup.addProperty("item_group", itemGroup);
        pickup.addProperty("count", Math.max(1, count));
        pickup.addProperty("search_radius", radius);
        pickup.addProperty("timeout_ticks", timeoutTicks);
        pickup.addProperty("optional", true);
        pickup.addProperty("magnet_fallback", true);
        pickup.addProperty("magnet_only", true);
        return pickup;
    }

    private JsonObject voiceAssistSupport(String item, int count, int minCount) {
        JsonObject support = action("grant_item");
        support.addProperty("item", item);
        support.addProperty("count", Math.max(1, count));
        support.addProperty("min_count", Math.max(0, minCount));
        support.addProperty("optional", true);
        support.addProperty("assist_scope", "utility_support_blocks");
        return support;
    }

    private void addVoiceAssistMaterialTopUpIfAllowed(ArrayList<JsonObject> actions, String item, int missing) {
        if (missing <= 0 || !isVoiceAssistCommonMaterial(item)) {
            return;
        }
        actions.add(voiceAssistMaterialTopUp(item, missing, countItem(item) + missing));
    }

    private JsonObject voiceAssistMaterialTopUp(String item, int count, int minCount) {
        JsonObject support = action("grant_item");
        support.addProperty("item", item);
        support.addProperty("count", Math.max(1, count));
        support.addProperty("min_count", Math.max(1, minCount));
        support.addProperty("optional", true);
        support.addProperty("assist_scope", "common_resource_top_up");
        return support;
    }

    private boolean isVoiceAssistCommonMaterial(String item) {
        if (item == null || !item.startsWith("minecraft:")) {
            return false;
        }
        String name = item.substring("minecraft:".length());
        if (name.endsWith("_ore") || name.contains("diamond") || name.contains("netherite")
            || name.contains("emerald")
            || name.contains("debris") || name.contains("obsidian") || name.contains("elytra")
            || name.contains("dragon") || name.contains("wither") || name.contains("shulker")
            || name.contains("blaze") || name.contains("ender") || name.contains("ghast")
            || name.contains("totem") || name.contains("skull") || name.endsWith("_head")
            || name.endsWith("_armor_trim_smithing_template") || name.equals("netherite_upgrade_smithing_template")
            || name.equals("nether_star") || name.equals("heart_of_the_sea") || name.equals("heavy_core")
            || name.equals("echo_shard") || name.equals("disc_fragment_5") || name.startsWith("music_disc")
            || name.equals("trial_key") || name.equals("ominous_trial_key") || name.equals("breeze_rod")
            || name.equals("enchanted_golden_apple") || name.equals("bedrock") || name.equals("barrier")
            || name.equals("command_block") || name.equals("chain_command_block") || name.equals("repeating_command_block")
            || name.equals("structure_block") || name.equals("structure_void") || name.equals("jigsaw")
            || name.equals("debug_stick") || name.equals("knowledge_book") || name.equals("spawner")
            || name.equals("reinforced_deepslate")) {
            return false;
        }
        return true;
    }

    private JsonObject optionalTakeFromContainer(String itemOrGroup, int count, int radius) {
        JsonObject take = action("take_from_container");
        if (itemOrGroup.startsWith("minecraft:")) {
            take.addProperty("item", itemOrGroup);
        } else {
            take.addProperty("item_group", itemOrGroup);
        }
        take.addProperty("count", Math.max(1, count));
        take.addProperty("search_radius", Math.max(1, radius));
        take.addProperty("optional", true);
        return take;
    }

    private JsonObject requiredTakeFromContainer(String itemOrGroup, int count, int radius) {
        JsonObject take = optionalTakeFromContainer(itemOrGroup, count, radius);
        take.addProperty("optional", false);
        return take;
    }

    private boolean childMaterialAssistEnabled() {
        return voiceConfig != null && voiceConfig.childModeEnabled() && voiceConfig.childModeMaterialAssist();
    }

    private int childShelterMaterialTarget() {
        return voiceConfig == null ? 4 : Math.max(4, Math.min(voiceConfig.childModeShelterMaterialTarget(), 24));
    }

    private int countShelterMaterials() {
        if (client == null) {
            return 0;
        }
        return countItem("minecraft:dirt")
            + countItem("minecraft:sandstone")
            + countItem("minecraft:sand")
            + countItem("minecraft:cobblestone")
            + countItem("minecraft:cobbled_deepslate")
            + countItem("minecraft:stone")
            + countItem("minecraft:oak_planks")
            + countItem("minecraft:spruce_planks")
            + countItem("minecraft:birch_planks")
            + countItem("minecraft:jungle_planks")
            + countItem("minecraft:acacia_planks")
            + countItem("minecraft:dark_oak_planks")
            + countItem("minecraft:mangrove_planks")
            + countItem("minecraft:cherry_planks")
            + countItem("minecraft:pale_oak_planks")
            + countItem("minecraft:bamboo_planks")
            + countItem("minecraft:netherrack");
    }

    private String normalizeAmbientStyle(String value) {
        return switch (value) {
            case "dance", "cheer", "shrug" -> value;
            default -> "nod";
        };
    }

    private String sanitizeAmbientMessage(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return compact.length() > 48 ? compact.substring(0, 48) : compact;
    }

    private NativePlan planPlaceTorch(String source) {
        JsonObject ensure = action("ensure_hotbar");
        ensure.addProperty("item", "minecraft:torch");
        ensure.addProperty("count", 1);
        JsonObject place = action("place_block");
        place.addProperty("item", "minecraft:torch");
        JsonObject target = new JsonObject();
        target.addProperty("kind", "nearby_dark_spot");
        place.add("target", target);
        return plan("place_light", source, ensure, place);
    }

    private NativePlan planPrepareNether(String rawRoute, String source) {
        String route = rawRoute.equals("obsidian_frame") ? "obsidian_frame" : "lava_cast";
        ArrayList<JsonObject> actions = new ArrayList<>();
        if (route.equals("obsidian_frame")) {
            if (!hasAnyItem("minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe") && countItem("minecraft:obsidian") < 14) {
                JsonObject abort = action("abort");
                abort.addProperty("reason", "黒曜石フレーム方式は黒曜石14個、または黒曜石を掘れるダイヤ以上のツルハシが必要です。");
                actions.add(abort);
                return plan("prepare_nether", source, actions.toArray(JsonObject[]::new));
            }
            int missingObsidian = Math.max(0, 14 - countItem("minecraft:obsidian"));
            if (missingObsidian > 0) {
                JsonObject durability = action("check_tool_durability");
                durability.addProperty("tool_group", "pickaxe");
                durability.addProperty("min_remaining_uses", missingObsidian);
                durability.addProperty("optional", false);
                actions.add(durability);
                JsonObject collect = action("collect_block");
                collect.addProperty("block", "minecraft:obsidian");
                collect.addProperty("count", missingObsidian);
                collect.addProperty("search_radius", 24);
                collect.addProperty("timeout_ticks", Math.max(600, missingObsidian * 240));
                actions.add(collect);
                JsonObject pickup = action("collect_drops");
                pickup.addProperty("item", "minecraft:obsidian");
                pickup.addProperty("count", missingObsidian);
                pickup.addProperty("search_radius", 10);
                pickup.addProperty("timeout_ticks", Math.max(240, missingObsidian * 120));
                pickup.addProperty("accept_if_already_present", true);
                pickup.addProperty("magnet_fallback", true);
                pickup.addProperty("magnet_only", true);
                actions.add(pickup);
            }
            ensureFlintAndSteel(actions);
            actions.add(ensureHotbar("minecraft:obsidian", 14));
            JsonObject build = action("build_nether_portal");
            build.addProperty("method", "obsidian_frame");
            build.addProperty("timeout_ticks", 500);
            actions.add(build);
            actions.add(ensureHotbar("minecraft:flint_and_steel", 1));
            actions.add(ignitePortal());
            return plan("prepare_nether", source, actions.toArray(JsonObject[]::new));
        }

        ensureBucketContainers(actions, 2);
        ensureFlintAndSteel(actions);
        if (countItem("minecraft:water_bucket") <= 0) {
            JsonObject water = action("collect_fluid");
            water.addProperty("fluid", "minecraft:water");
            water.addProperty("bucket_item", "minecraft:bucket");
            water.addProperty("search_radius", 16);
            actions.add(water);
        }
        if (countItem("minecraft:lava_bucket") <= 0) {
            JsonObject lava = action("collect_fluid");
            lava.addProperty("fluid", "minecraft:lava");
            lava.addProperty("bucket_item", "minecraft:bucket");
            lava.addProperty("search_radius", 16);
            actions.add(lava);
        }
        JsonObject build = action("build_nether_portal");
        build.addProperty("method", "lava_cast");
        build.addProperty("timeout_ticks", 700);
        actions.add(build);
        actions.add(ensureHotbar("minecraft:flint_and_steel", 1));
        actions.add(ignitePortal());
        return plan("prepare_nether", source, actions.toArray(JsonObject[]::new));
    }

    private void ensureBucketContainers(ArrayList<JsonObject> actions, int count) {
        int containers = countItem("minecraft:bucket") + countItem("minecraft:water_bucket") + countItem("minecraft:lava_bucket");
        int missing = Math.max(0, count - containers);
        if (missing <= 0) {
            return;
        }
        ensureIronIngots(actions, missing * 3);
        actions.add(craft("minecraft:bucket", missing));
    }

    private void ensureFlintAndSteel(ArrayList<JsonObject> actions) {
        if (countItem("minecraft:flint_and_steel") > 0) {
            return;
        }
        ensureIronIngots(actions, 1);
        ensureFlint(actions, 1);
        actions.add(craft("minecraft:flint_and_steel", 1));
    }

    private JsonObject ignitePortal() {
        JsonObject ignite = action("ignite_nether_portal");
        ignite.addProperty("item", "minecraft:flint_and_steel");
        ignite.addProperty("search_radius", 8);
        return ignite;
    }

    private NativePlan planCollectBlockGoal(JsonObject goal, String source) {
        int count = boundedInt(goal, "count", 1, 1, 32);
        JsonObject collect = action("collect_block");
        String block = string(goal, "block");
        if (!block.isBlank()) {
            collect.addProperty("block", block);
        } else {
            String group = string(goal, "block_group");
            collect.addProperty("block_group", group.isBlank() ? "dirt" : group);
        }
        collect.addProperty("count", count);
        collect.addProperty("search_radius", 16);
        collect.addProperty("timeout_ticks", Math.max(160, count * 90));

        String group = string(goal, "block_group");
        JsonObject pickup = collectDropsForBlock(block, group.isBlank() ? "dirt" : group, count, 10, Math.max(120, count * 80));
        return plan("collect_block", source, collect, pickup);
    }

    private NativePlan planHarvestCrops(String source) {
        JsonObject harvest = action("collect_block");
        harvest.addProperty("block_group", "crop");
        harvest.addProperty("count", 8);
        harvest.addProperty("search_radius", 16);
        harvest.addProperty("timeout_ticks", 300);

        JsonObject pickup = action("collect_drops");
        pickup.addProperty("item_group", "any_drop");
        pickup.addProperty("count", 8);
        pickup.addProperty("search_radius", 10);
        pickup.addProperty("timeout_ticks", 180);
        pickup.addProperty("magnet_fallback", true);
        pickup.addProperty("magnet_only", true);

        JsonObject replant = action("replant_crop");
        replant.addProperty("seed_group", "crop_seed");
        replant.addProperty("count", 8);
        replant.addProperty("search_radius", 8);
        replant.addProperty("timeout_ticks", 220);
        return plan("harvest_crops", source, harvest, pickup, replant);
    }

    private NativePlan planEatFood(String source) {
        JsonObject eat = action("eat_food");
        eat.addProperty("min_hunger", 14);
        eat.addProperty("timeout_ticks", 180);
        return plan("eat_food", source, eat);
    }

    private NativePlan planRetreat(String source) {
        JsonObject defensive = action("defensive_move");
        defensive.addProperty("duration_ticks", 80);
        defensive.addProperty("prefer_shield", true);
        return plan("retreat", source, defensive);
    }

    private NativePlan planDefend(String source) {
        JsonObject gear = action("equip_gear");
        gear.addProperty("gear_group", "combat");
        JsonObject defensive = action("defensive_move");
        defensive.addProperty("duration_ticks", 90);
        defensive.addProperty("prefer_shield", true);
        return plan("defend", source, gear, defensive);
    }

    private NativePlan planAttack(String text, String source) {
        String group = Pattern.compile("牛|豚|ブタ|鶏|ニワトリ|羊|ウサギ|鮭|サケ|鱈|タラ|動物|肉").matcher(text).find()
            ? "food_animal"
            : "hostile";
        return planAttackFields(group, 1, source);
    }

    private NativePlan planAttackGoal(JsonObject goal, String source) {
        String group = string(goal, "entity_group").equals("food_animal") ? "food_animal" : "hostile";
        return planAttackFields(group, boundedInt(goal, "count", 1, 1, 8), source);
    }

    private NativePlan planAttackFields(String group, int count, String source) {
        JsonObject gear = action("equip_gear");
        gear.addProperty("gear_group", "combat");
        JsonObject attack = action("attack_entity");
        attack.addProperty("entity_group", group);
        attack.addProperty("count", count);
        attack.addProperty("search_radius", 10);
        attack.addProperty("timeout_ticks", 160);
        if (group.equals("food_animal")) {
            JsonObject pickup = action("collect_drops");
            pickup.addProperty("item_group", "food_drop");
            pickup.addProperty("count", Math.max(1, count));
            pickup.addProperty("search_radius", 12);
            pickup.addProperty("timeout_ticks", 200);
            pickup.addProperty("accept_if_already_present", true);
            pickup.addProperty("magnet_fallback", true);
            pickup.addProperty("magnet_only", true);
            JsonObject cook = action("smelt_food");
            cook.addProperty("count", Math.max(1, count));
            cook.addProperty("timeout_ticks", 360);
            cook.addProperty("optional", true);
            return plan("attack_entity", source, gear, attack, pickup, cook);
        }
        return plan("attack_entity", source, gear, attack);
    }

    private NativePlan planSearchStructure(String targetGroup, String source) {
        if (!targetGroup.equals("structure_hint")) {
            targetGroup = "village_hint";
        }
        JsonObject support = voiceAssistSupport("minecraft:dirt", 32, 32);
        JsonObject scan = action("scan_explore_area");
        scan.addProperty("target_group", targetGroup);
        scan.addProperty("radius_chunks", 8);
        scan.addProperty("samples_per_chunk", 2);
        JsonObject explore = action("explore");
        explore.addProperty("target_group", targetGroup);
        explore.addProperty("distance_blocks", 300);
        explore.addProperty("search_radius", 128);
        explore.addProperty("timeout_ticks", 5200);
        explore.addProperty("avoid_visited", true);
        explore.addProperty("programmatic_assist", true);
        return plan("search_structure", source, support, scan, explore);
    }

    private NativePlan plan(String goal, String source, JsonObject... actions) {
        return new NativePlan(goal, source, List.of(toActions(actions)));
    }

    private ExecutorProtocol.Action[] toActions(JsonObject[] bodies) {
        ExecutorProtocol.Action[] actions = new ExecutorProtocol.Action[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            actions[i] = new ExecutorProtocol.Action(string(bodies[i], "type"), bodies[i]);
        }
        return actions;
    }

    private JsonObject action(String type) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        return body;
    }

    private JsonObject with(JsonObject body, String key, int value) {
        body.addProperty(key, value);
        return body;
    }

    private JsonObject json(String stringKey, String stringValue, String intKey, int intValue) {
        JsonObject object = new JsonObject();
        object.addProperty(stringKey, stringValue);
        object.addProperty(intKey, intValue);
        return object;
    }

    private int parseBlocks(String text, int fallback, int min, int max) {
        Matcher matcher = Pattern.compile("(\\d{1,3})(ブロック|段|マス|歩)?").matcher(text);
        if (matcher.find()) {
            return clamp(Integer.parseInt(matcher.group(1)), min, max);
        }
        String[] words = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 0; i < words.length; i++) {
            if (text.contains(words[i] + "ブロック") || text.contains(words[i] + "段") || text.contains(words[i] + "マス") || text.contains(words[i] + "歩")) {
                return clamp(i + 1, min, max);
            }
        }
        if (text.contains("少し") || text.contains("ちょっと")) return clamp(2, min, max);
        if (text.contains("しばらく") || text.contains("長く") || text.contains("深く")) return clamp(8, min, max);
        return clamp(fallback, min, max);
    }

    private boolean hasExplicitMoveDistance(String text) {
        if (Pattern.compile("\\d{1,3}(ブロック|段|マス|歩)?").matcher(text).find()) {
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

    private int boundedInt(JsonObject object, String key, int fallback, int min, int max) {
        if (object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()) {
            return clamp(object.get(key).getAsInt(), min, max);
        }
        return clamp(fallback, min, max);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private String normalize(String text) {
        String normalized = KoeCraftAsrPostNormalizer.normalize(text).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!Pattern.compile("作|つく|クラフト|ほしい|欲しい|お願い|頼む").matcher(normalized).find()) {
            return normalized;
        }
        return WOODEN_TOOL_ASR_CONFUSION.matcher(normalized).replaceAll("木の$3");
    }

    private boolean isSelfCorrection(String text) {
        return text.contains("いや") || text.contains("やっぱ") || text.contains("先に");
    }

    private boolean isExplicitMovement(String text) {
        return text.contains("後ろ") || text.contains("前") || text.contains("右") || text.contains("左") || Pattern.compile("\\d{1,2}(ブロック|段|マス|個|こ|つ)?").matcher(text).find();
    }

    private int countItem(String itemId) {
        if (client == null) {
            return 0;
        }
        return callOnClientThread(() -> {
            if (client.player == null) {
                return 0;
            }
            int count = 0;
            for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && itemId(stack).equals(itemId)) {
                    count += stack.getCount();
                }
            }
            return count;
        });
    }

    private int countGroup(String group) {
        if (client == null) {
            return 0;
        }
        return callOnClientThread(() -> {
            if (client.player == null) {
                return 0;
            }
            int count = 0;
            for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && MinecraftItemGroups.matches(group, itemId(stack))) {
                    count += stack.getCount();
                }
            }
            return count;
        });
    }

    private boolean hasAnyItem(String... itemIds) {
        for (String itemId : itemIds) {
            if (countItem(itemId) > 0) {
                return true;
            }
        }
        return false;
    }

    private String itemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private <T> T callOnClientThread(java.util.function.Supplier<T> supplier) {
        if (client.isOnThread()) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> future.complete(supplier.get()));
        return future.join();
    }

    record NativePlan(String goal, String source, List<ExecutorProtocol.Action> actions) {
    }

    private record WoodKind(String key, String log, String planks, int plankOutputCount) {
    }

    private record IngredientNeed(String kind, String itemOrGroup, int count) {
    }
}
