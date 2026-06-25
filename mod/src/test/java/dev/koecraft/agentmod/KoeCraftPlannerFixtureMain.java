package dev.koecraft.agentmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class KoeCraftPlannerFixtureMain {
    private KoeCraftPlannerFixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        Path fixturePath = args.length > 0 ? Path.of(args[0]) : Path.of("../examples/speech_fixtures.json");
        JsonElement root = JsonParser.parseString(Files.readString(fixturePath));
        KoeCraftUtteranceGoalRouter router = new KoeCraftUtteranceGoalRouter();
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject fixture = element.getAsJsonObject();
            String id = string(fixture, "id");
            String text = string(fixture, "recognized_text");
            Optional<JsonObject> routed = router.route(text);
            JsonObject expected = expectedGoal(fixture);
            if (expected == null) {
                continue;
            }
            checked++;
            if ("unknown".equals(string(expected, "type"))) {
                if (routed.isPresent()) {
                    failures.add(id + ": expected no route for `" + text + "` but got " + routed.get());
                }
                continue;
            }
            if (routed.isEmpty()) {
                failures.add(id + ": no route for `" + text + "`, expected " + expected);
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
                String key = entry.getKey();
                if (!routed.get().has(key)) {
                    failures.add(id + ": missing key `" + key + "` in " + routed.get());
                    continue;
                }
                if (!routed.get().get(key).equals(entry.getValue())) {
                    failures.add(id + ": key `" + key + "` expected " + entry.getValue() + " but got " + routed.get().get(key) + " in " + routed.get());
                }
            }
        }
        checked += checkAmbientChatPlanning(failures);
        checked += checkContainerSupplyPlanning(failures);
        checked += checkExploreSummaryPlanning(failures);
        checked += checkAdaptiveMovePlanning(failures);
        checked += checkToolTierAndJumpPlanning(failures);
        checked += checkExpectedDropPlanning(failures);
        checked += checkExecutorPartialContinuation(failures);
        checked += checkBlockedReactionRouting(failures);
        checked += checkInterventionChoicePlanning(failures);
        checked += checkWorkstationTimingPlanning(failures);
        checked += checkVoiceAssistPlanning(failures);
        checked += checkCommonResourceTopUpPlanning(failures);
        checked += checkOpenAiFallbackNormalization(failures);
        checked += checkOpenAiSpeechNormalizer(failures);
        checked += checkRecognizedTextNormalization(failures);
        checked += checkAsrDomainPhraseNormalization(failures);
        checked += checkWoodMaterialGroups(failures);

        if (!failures.isEmpty()) {
            System.err.println("[planner-fixtures] failed " + failures.size() + " checks");
            failures.forEach(System.err::println);
            System.exit(1);
        }
        System.out.println("[planner-fixtures] passed " + checked + " routed fixture checks");
    }

    private static JsonObject expectedGoal(JsonObject fixture) {
        if (fixture.has("expected_goal") && fixture.get("expected_goal").isJsonObject()) {
            return fixture.getAsJsonObject("expected_goal");
        }
        if (fixture.has("expected_features") && fixture.get("expected_features").isJsonObject()) {
            JsonObject features = fixture.getAsJsonObject("expected_features");
            if ("craft_stone_pickaxe".equals(string(features, "active_goal"))) {
                JsonObject expected = new JsonObject();
                expected.addProperty("type", "craft_item");
                expected.addProperty("target_item", "minecraft:stone_pickaxe");
                return expected;
            }
        }
        return null;
    }

    private static int checkAmbientChatPlanning(List<String> failures) {
        JsonObject goal = new JsonObject();
        goal.addProperty("type", "ambient_chat");
        goal.addProperty("message", "この世界では、まず足元を見て進もう。");
        goal.addProperty("style", "nod");
        goal.addProperty("duration_ticks", 80);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> plan = new KoeCraftNativeGoalPlanner(null).planLlmGoal(goal);
        if (plan.isEmpty()) {
            failures.add("ambient_chat_planner: no plan was produced");
            return 1;
        }
        if (!"ambient_chat".equals(plan.get().goal())) {
            failures.add("ambient_chat_planner: expected goal ambient_chat but got " + plan.get().goal());
        }
        if (plan.get().actions().size() != 1 || !"ambient_chat".equals(plan.get().actions().get(0).type())) {
            failures.add("ambient_chat_planner: expected one ambient_chat action but got " + plan.get().actions());
        }
        JsonObject shelter = new JsonObject();
        shelter.addProperty("type", "build_shelter");
        shelter.addProperty("style", "small_house");
        Optional<KoeCraftNativeGoalPlanner.NativePlan> shelterPlan = new KoeCraftNativeGoalPlanner(null).planLlmGoal(shelter);
        if (shelterPlan.isEmpty()) {
            failures.add("build_shelter_planner: no plan was produced");
        } else if (!"build_shelter".equals(shelterPlan.get().goal())) {
            failures.add("build_shelter_planner: expected goal build_shelter but got " + shelterPlan.get().goal());
        } else if (shelterPlan.get().actions().stream().noneMatch(action -> "emergency_shelter".equals(action.type()))) {
            failures.add("build_shelter_planner: expected an emergency_shelter action but got " + shelterPlan.get().actions());
        } else if (shelterPlan.get().actions().stream().noneMatch(action -> "collect_block".equals(action.type()))) {
            failures.add("build_shelter_planner: expected a material collect_block action but got " + shelterPlan.get().actions());
        } else if (shelterPlan.get().actions().stream()
            .filter(action -> "collect_block".equals(action.type()))
            .noneMatch(action -> bool(action.body(), "optional"))) {
            failures.add("build_shelter_planner: expected optional material collect_block actions but got " + shelterPlan.get().actions());
        } else if (shelterPlan.get().actions().stream()
            .filter(action -> "collect_drops".equals(action.type()))
            .noneMatch(action -> bool(action.body(), "optional"))) {
            failures.add("build_shelter_planner: expected optional material collect_drops actions but got " + shelterPlan.get().actions());
        }
        JsonObject structure = new JsonObject();
        structure.addProperty("type", "build_structure");
        structure.addProperty("style", "cute_house");
        structure.addProperty("size", "tiny");
        structure.addProperty("palette", "available");
        Optional<KoeCraftNativeGoalPlanner.NativePlan> structurePlan = new KoeCraftNativeGoalPlanner(null).planLlmGoal(structure);
        if (structurePlan.isEmpty()) {
            failures.add("build_structure_planner: no plan was produced");
        } else if (!"build_structure".equals(structurePlan.get().goal())) {
            failures.add("build_structure_planner: expected goal build_structure but got " + structurePlan.get().goal());
        } else if (structurePlan.get().actions().stream().noneMatch(action -> "scan_build_area".equals(action.type()))) {
            failures.add("build_structure_planner: expected scan_build_area action but got " + structurePlan.get().actions());
        } else if (structurePlan.get().actions().stream().noneMatch(action -> "build_blueprint".equals(action.type()))) {
            failures.add("build_structure_planner: expected build_blueprint action but got " + structurePlan.get().actions());
        } else if (structurePlan.get().actions().stream()
            .filter(action -> "build_blueprint".equals(action.type()))
            .noneMatch(action -> bool(action.body(), "programmatic_build") && bool(action.body(), "terrain_aware") && bool(action.body(), "animated"))) {
            failures.add("build_structure_planner: expected programmatic terrain-aware animated build_blueprint but got " + structurePlan.get().actions());
        }
        return 1;
    }

    private static int checkOpenAiFallbackNormalization(List<String> failures) {
        KoeCraftOpenAiGoalFallback fallback = new KoeCraftOpenAiGoalFallback(null);
        int checked = 0;
        checked += checkFallbackGoal(
            fallback,
            failures,
            "fallback_generic_boat",
            json("type", "craft_item", "target_item", "minecraft:boat"),
            "craft_item",
            "target_item",
            "minecraft:boat"
        );
        checked += checkFallbackGoal(
            fallback,
            failures,
            "fallback_generic_sword",
            json("type", "craft_item", "target_item", "minecraft:sword"),
            "craft_item",
            "target_item",
            "minecraft:sword"
        );
        checked += checkFallbackGoal(
            fallback,
            failures,
            "fallback_collect_sand",
            json("type", "collect_block", "block_group", "sand", "count", 3),
            "collect_block",
            "block_group",
            "sand"
        );
        checked += checkFallbackGoal(
            fallback,
            failures,
            "fallback_context_mine",
            json("type", "context_action", "intent", "mine"),
            "context_action",
            "intent",
            "mine"
        );
        checked += checkFallbackGoal(
            fallback,
            failures,
            "fallback_search_structure",
            json("type", "search_structure", "target_group", "structure_hint"),
            "search_structure",
            "target_group",
            "structure_hint"
        );
        checked += checkFallbackGoal(
            fallback,
            failures,
            "fallback_build_shelter",
            json("type", "build_shelter", "style", "hideout"),
            "build_shelter",
            "style",
            "hideout"
        );
        checked += checkFallbackGoal(
            fallback,
            failures,
            "fallback_build_structure",
            json("type", "build_structure", "style", "cute_house", "size", "tiny", "palette", "wood"),
            "build_structure",
            "style",
            "cute_house"
        );
        checked += checkPlannerAlias(
            failures,
            "fallback_planner_alias_generic_sword",
            "minecraft:sword",
            "minecraft:wooden_sword"
        );
        checked += checkPlannerAlias(
            failures,
            "fallback_planner_alias_generic_boat",
            "minecraft:boat",
            "minecraft:oak_boat"
        );
        return checked;
    }

    private static int checkOpenAiSpeechNormalizer(List<String> failures) {
        KoeCraftOpenAiSpeechNormalizer normalizer = new KoeCraftOpenAiSpeechNormalizer(null);
        JsonObject raw = json(
            "normalized_text", "前に橋を架けて",
            "intent_hint", "build_bridge",
            "confidence", 0.86D
        );
        Optional<KoeCraftOpenAiSpeechNormalizer.Result> result = normalizer.normalizeResultForTesting(raw);
        if (result.isEmpty()) {
            failures.add("speech_normalizer: normalized result was empty");
            return 1;
        }
        if (!"前に橋を架けて".equals(result.get().normalizedText())) {
            failures.add("speech_normalizer: expected normalized bridge text but got `" + result.get().normalizedText() + "`");
        }
        if (!"build_bridge".equals(result.get().intentHint())) {
            failures.add("speech_normalizer: expected build_bridge hint but got `" + result.get().intentHint() + "`");
        }
        return 1;
    }

    private static int checkRecognizedTextNormalization(List<String> failures) {
        String decoded = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting("u6628u65e5u306eu591cu306fu3069u3046u3060u3063u305f");
        if (!"昨日の夜はどうだった".equals(decoded)) {
            failures.add("recognized_text_unicode_decode: expected Japanese text but got `" + decoded + "`");
        }
        if (!KoeCraftRecognizedTextProcessor.looksGarbledRecognitionForTesting("uZZZZu123")) {
            failures.add("recognized_text_garbled_guard: expected malformed unicode-like text to be garbled");
        }
        String corrected = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting("きのうつるはし作って");
        if (!"木のつるはし作って".equals(corrected)) {
            failures.add("recognized_text_wooden_tool_correction: expected 木のつるはし作って but got `" + corrected + "`");
        }
        String pickaxe = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting("私作って");
        if (!"ツルハシ作って".equals(pickaxe)) {
            failures.add("recognized_text_watashi_pickaxe_correction: expected ツルハシ作って but got `" + pickaxe + "`");
        }
        String stonePickaxe = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting("石野鶴橋作る");
        if (!"石のツルハシ作る".equals(stonePickaxe)) {
            failures.add("recognized_text_stone_pickaxe_correction: expected 石のツルハシ作る but got `" + stonePickaxe + "`");
        }
        String nether = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting("溶岩でレザーゲート作って");
        if (!"溶岩でネザーゲート作って".equals(nether)) {
            failures.add("recognized_text_nether_correction: expected 溶岩でネザーゲート作って but got `" + nether + "`");
        }
        String pickup = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting("撮って");
        if (!"取って".equals(pickup)) {
            failures.add("recognized_text_pickup_correction: expected 取って but got `" + pickup + "`");
        }
        String hundredSteps = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting("百歩吠えて");
        if (!"100歩歩いて".equals(hundredSteps)) {
            failures.add("recognized_text_hundred_steps_correction: expected 100歩歩いて but got `" + hundredSteps + "`");
        }
        if (!KoeCraftRecognizedTextProcessor.isFillerOnlyUtteranceForTesting("はあ")) {
            failures.add("recognized_text_filler_guard: expected はあ to be treated as filler-only");
        }
        if (KoeCraftRecognizedTextProcessor.isFillerOnlyUtteranceForTesting("おおーすごい")) {
            failures.add("recognized_text_filler_guard: expected celebration utterance not to be treated as filler-only");
        }
        return 10;
    }

    private static int checkAsrDomainPhraseNormalization(List<String> failures) {
        JsonObject digRaw = amivoiceRaw("彫って", "ほって", 0.93D);
        if (!"ほって".equals(KoeCraftAsrDomainPhraseNormalizer.spokenTextForTesting(digRaw))) {
            failures.add("asr_domain_phrase_spoken: expected spoken evidence ほって but got `" + KoeCraftAsrDomainPhraseNormalizer.spokenTextForTesting(digRaw) + "`");
        }
        List<KoeCraftAsrDomainPhraseNormalizer.Candidate> digCandidates = KoeCraftAsrDomainPhraseNormalizer.candidatesForTesting("彫って", digRaw);
        if (digCandidates.isEmpty() || !"掘って".equals(digCandidates.get(0).text())) {
            failures.add("asr_domain_phrase_dig: expected 彫って/spoken=ほって to prefer 掘って but got " + digCandidates);
        }
        Optional<KoeCraftNativeGoalPlanner.NativePlan> digPlan = digCandidates.stream()
            .flatMap(candidate -> new KoeCraftNativeGoalPlanner(null).planRuleBased(candidate.text()).stream())
            .findFirst();
        if (digPlan.isEmpty() || !"dig_pattern".equals(digPlan.get().goal())) {
            failures.add("asr_domain_phrase_dig_plan: expected spoken dig candidate to produce dig_pattern but got " + digPlan);
        }

        JsonObject pickaxeRaw = amivoiceRaw("気のツルハシ作って", "きのつるはしつくって", 0.88D);
        List<KoeCraftAsrDomainPhraseNormalizer.Candidate> pickaxeCandidates = KoeCraftAsrDomainPhraseNormalizer.candidatesForTesting("気のツルハシ作って", pickaxeRaw);
        if (pickaxeCandidates.stream().noneMatch(candidate -> "木のツルハシ作って".equals(candidate.text()))) {
            failures.add("asr_domain_phrase_wood_pickaxe: expected reading candidate for 木のツルハシ作って but got " + pickaxeCandidates);
        }

        JsonObject fillerRaw = amivoiceRaw("はあ", "はあ", 0.95D);
        if (!KoeCraftAsrDomainPhraseNormalizer.candidatesForTesting("はあ", fillerRaw).isEmpty()) {
            failures.add("asr_domain_phrase_filler_guard: expected はあ to produce no domain phrase candidates");
        }

        JsonObject bridgeRaw = amivoiceRaw("ハッシュかけて", "はしかけて", 0.9D);
        List<KoeCraftAsrDomainPhraseNormalizer.Candidate> bridgeCandidates = KoeCraftAsrDomainPhraseNormalizer.candidatesForTesting("ハッシュかけて", bridgeRaw);
        if (bridgeCandidates.stream().noneMatch(candidate -> "橋かけて".equals(candidate.text()) || "橋をかけて".equals(candidate.text()))) {
            failures.add("asr_domain_phrase_bridge: expected bridge candidate from spoken=はしかけて but got " + bridgeCandidates);
        }
        return 4;
    }

    private static int checkWoodMaterialGroups(List<String> failures) {
        if (!MinecraftItemGroups.matches("log", "minecraft:oak_wood")) {
            failures.add("wood_material_group: expected minecraft:oak_wood to match log item group");
        }
        if (!MinecraftItemGroups.matches("log", "minecraft:stripped_spruce_wood")) {
            failures.add("wood_material_group: expected minecraft:stripped_spruce_wood to match log item group");
        }
        if (!BlockGroups.matches("log", "minecraft:stripped_oak_wood")) {
            failures.add("wood_material_group: expected minecraft:stripped_oak_wood to match log block group");
        }
        return 3;
    }

    private static int checkContainerSupplyPlanning(List<String> failures) {
        Optional<KoeCraftNativeGoalPlanner.NativePlan> plan = new KoeCraftNativeGoalPlanner(null).planLlmGoal(json("type", "build_shelter", "style", "small_house"));
        if (plan.isEmpty()) {
            failures.add("container_supply_planner: no build shelter plan was produced");
            return 1;
        }
        boolean hasContainerCheck = plan.get().actions().stream()
            .anyMatch(action -> "take_from_container".equals(action.type()) && bool(action.body(), "optional"));
        if (!hasContainerCheck) {
            failures.add("container_supply_planner: expected optional take_from_container before fallback collection but got " + plan.get().actions());
        }
        return 1;
    }

    private static int checkExploreSummaryPlanning(List<String> failures) {
        Optional<KoeCraftNativeGoalPlanner.NativePlan> plan = new KoeCraftNativeGoalPlanner(null).planLlmGoal(json("type", "search_structure", "target_group", "village_hint"));
        if (plan.isEmpty()) {
            failures.add("explore_summary_planner: no search plan was produced");
            return 1;
        }
        boolean hasSummaryScan = plan.get().actions().stream()
            .anyMatch(action -> "scan_explore_area".equals(action.type()) && action.body().has("radius_chunks") && action.body().get("radius_chunks").getAsInt() == 8);
        if (!hasSummaryScan) {
            failures.add("explore_summary_planner: expected scan_explore_area radius_chunks=8 before explore but got " + plan.get().actions());
        }
        boolean hasVoiceAssistSupport = plan.get().actions().stream()
            .anyMatch(action -> "grant_item".equals(action.type())
                && "minecraft:dirt".equals(string(action.body(), "item"))
                && action.body().has("min_count")
                && action.body().get("min_count").getAsInt() >= 32
                && bool(action.body(), "optional"));
        if (!hasVoiceAssistSupport) {
            failures.add("explore_summary_planner: expected optional dirt grant for voice bridge assist but got " + plan.get().actions());
        }
        boolean hasWideExplore = plan.get().actions().stream()
            .anyMatch(action -> "explore".equals(action.type())
                && action.body().has("search_radius")
                && action.body().get("search_radius").getAsInt() == 128
                && action.body().has("distance_blocks")
                && action.body().get("distance_blocks").getAsInt() == 300
                && action.body().has("programmatic_assist")
                && bool(action.body(), "programmatic_assist"));
        if (!hasWideExplore) {
            failures.add("explore_summary_planner: expected explore search_radius=128 and programmatic distance_blocks=300 but got " + plan.get().actions());
        }
        Optional<JsonObject> routedStructure = new KoeCraftUtteranceGoalRouter().route("構造物を探して");
        if (routedStructure.isEmpty() || !"structure_hint".equals(string(routedStructure.get(), "target_group"))) {
            failures.add("explore_summary_planner: expected structure search to target structure_hint but got " + routedStructure);
        }
        Optional<KoeCraftNativeGoalPlanner.NativePlan> structurePlan = new KoeCraftNativeGoalPlanner(null).planLlmGoal(json("type", "search_structure", "target_group", "structure_hint"));
        if (structurePlan.isEmpty() || structurePlan.get().actions().stream()
            .noneMatch(action -> "explore".equals(action.type()) && "structure_hint".equals(string(action.body(), "target_group")))) {
            failures.add("explore_summary_planner: expected structure_hint explore plan but got " + structurePlan);
        }
        Optional<KoeCraftNativeGoalPlanner.NativePlan> swimPlan = new KoeCraftNativeGoalPlanner(null).planRuleBased("泳いで");
        if (swimPlan.isEmpty() || swimPlan.get().actions().stream().noneMatch(action -> "use_boat_if_water".equals(action.type()) && bool(action.body(), "optional"))) {
            failures.add("explore_summary_planner: expected optional use_boat_if_water before swim move but got " + swimPlan);
        }
        return 1;
    }

    private static int checkExpectedDropPlanning(List<String> failures) {
        Optional<KoeCraftNativeGoalPlanner.NativePlan> plan = new KoeCraftNativeGoalPlanner(null).planRuleBased("石のツルハシ作って");
        if (plan.isEmpty()) {
            failures.add("expected_drop_planner: no stone pickaxe plan was produced");
            return 1;
        }
        boolean hasCobblestoneDrop = plan.get().actions().stream()
            .filter(action -> "collect_drops".equals(action.type()))
            .anyMatch(action -> "minecraft:cobblestone".equals(string(action.body(), "item"))
                && bool(action.body(), "magnet_only"));
        if (!hasCobblestoneDrop) {
            failures.add("expected_drop_planner: expected cobblestone-specific magnet-only collect_drops but got " + plan.get().actions());
        }
        boolean hasAnyDrop = plan.get().actions().stream()
            .filter(action -> "collect_drops".equals(action.type()))
            .anyMatch(action -> "any_drop".equals(string(action.body(), "item_group")));
        if (hasAnyDrop) {
            failures.add("expected_drop_planner: stone pickaxe material route should not use any_drop, got " + plan.get().actions());
        }
        return 1;
    }

    private static int checkAdaptiveMovePlanning(List<String> failures) {
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> left = planner.planRuleBased("左にすすんで");
        if (left.isEmpty()) {
            failures.add("adaptive_move_left: no move plan was produced");
        } else {
            boolean hasAdaptiveLeft = left.get().actions().stream()
                .anyMatch(action -> "move".equals(action.type())
                    && "left".equals(string(action.body(), "direction"))
                    && intValue(action.body(), "distance_blocks") >= 12
                    && bool(action.body(), "scan_aware")
                    && bool(action.body(), "adaptive_distance")
                    && bool(action.body(), "extend_if_clear")
                    && bool(action.body(), "face_move_direction")
                    && bool(action.body(), "auto_jump"));
            if (!hasAdaptiveLeft) {
                failures.add("adaptive_move_left: expected scan-aware face-turning adaptive left move but got " + left.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> exact = planner.planRuleBased("100歩あるいて");
        if (exact.isEmpty()) {
            failures.add("adaptive_move_exact_distance: no move plan was produced");
        } else {
            boolean hasBoundedExact = exact.get().actions().stream()
                .anyMatch(action -> "move".equals(action.type())
                    && intValue(action.body(), "distance_blocks") == 96
                    && bool(action.body(), "adaptive_distance")
                    && !bool(action.body(), "extend_if_clear")
                    && bool(action.body(), "face_move_direction")
                    && bool(action.body(), "auto_jump"));
            if (!hasBoundedExact) {
                failures.add("adaptive_move_exact_distance: expected exact-distance move to shrink for terrain but not extend, got " + exact.get().actions());
            }
        }
        return 2;
    }

    private static int checkToolTierAndJumpPlanning(List<String> failures) {
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> ironPickaxe = planner.planRuleBased("鉄のツルハシ作って");
        if (ironPickaxe.isEmpty()) {
            failures.add("tool_tier_iron_pickaxe: no plan was produced");
        } else {
            boolean craftsIron = ironPickaxe.get().actions().stream()
                .anyMatch(action -> "craft".equals(action.type()) && "minecraft:iron_pickaxe".equals(string(action.body(), "recipe")));
            boolean craftsStone = ironPickaxe.get().actions().stream()
                .anyMatch(action -> "craft".equals(action.type()) && "minecraft:stone_pickaxe".equals(string(action.body(), "recipe")));
            if (!craftsIron || craftsStone) {
                failures.add("tool_tier_iron_pickaxe: expected iron pickaxe craft without stone pickaxe fallback but got " + ironPickaxe.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> diamondPickaxe = planner.planRuleBased("ダイヤのツルハシ作って");
        if (diamondPickaxe.isEmpty()) {
            failures.add("tool_tier_diamond_pickaxe: no plan was produced");
        } else {
            boolean craftsDiamond = diamondPickaxe.get().actions().stream()
                .anyMatch(action -> "craft".equals(action.type()) && "minecraft:diamond_pickaxe".equals(string(action.body(), "recipe")));
            boolean craftsStone = diamondPickaxe.get().actions().stream()
                .anyMatch(action -> "craft".equals(action.type()) && "minecraft:stone_pickaxe".equals(string(action.body(), "recipe")));
            if (!craftsDiamond || craftsStone) {
                failures.add("tool_tier_diamond_pickaxe: expected diamond pickaxe craft without stone pickaxe fallback but got " + diamondPickaxe.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> jump = planner.planRuleBased("ジャンプジャンプ");
        if (jump.isEmpty() || jump.get().actions().stream()
            .noneMatch(action -> "move".equals(action.type())
                && bool(action.body(), "jump_while_sprinting")
                && bool(action.body(), "sprint")
                && !bool(action.body(), "extend_if_clear"))) {
            failures.add("short_jump_command: expected bounded sprint-jump move plan but got " + jump);
        }
        return 3;
    }

    private static int checkExecutorPartialContinuation(List<String> failures) {
        JsonObject craftedPlanks = json("crafted_count", 1, "ending_planks", 3);
        if (!SurvivalActionExecutor.shouldContinueAfterPartialStep("craft_planks_from_log", "craft", craftedPlanks)) {
            failures.add("executor_partial_continuation: expected partial plank crafting with progress to continue");
        }

        JsonObject existingPlanks = json("crafted_count", 0, "ending_planks", 3);
        if (!SurvivalActionExecutor.shouldContinueAfterPartialStep("craft_planks_from_log", "craft", existingPlanks)) {
            failures.add("executor_partial_continuation: expected partial plank crafting with existing planks to continue");
        }

        JsonObject noProgress = json("crafted_count", 0, "ending_planks", 0);
        if (SurvivalActionExecutor.shouldContinueAfterPartialStep("craft_planks_from_log", "craft", noProgress)) {
            failures.add("executor_partial_continuation: expected no-progress plank crafting partial to stop");
        }
        return 3;
    }

    private static int checkBlockedReactionRouting(List<String> failures) {
        if (!SurvivalActionExecutor.shouldPlayBlockedReaction(new ExecutorProtocol.StepResult("move", "blocked", "No safe path."))) {
            failures.add("blocked_reaction_routing: expected blocked steps to trigger the visible stop reaction");
        }
        if (SurvivalActionExecutor.shouldPlayBlockedReaction(new ExecutorProtocol.StepResult("move", "partial", "Stopped early."))) {
            failures.add("blocked_reaction_routing: partial steps should not trigger the blocked reaction");
        }
        if (SurvivalActionExecutor.shouldPlayBlockedReaction(new ExecutorProtocol.StepResult("craft", "rejected", "Unsupported."))) {
            failures.add("blocked_reaction_routing: rejected steps should not trigger the blocked reaction");
        }
        return 3;
    }

    private static int checkInterventionChoicePlanning(List<String> failures) {
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> workstation = planner.planRuleBased("作業台を近くに置いて");
        if (workstation.isEmpty()) {
            failures.add("intervention_choice_workstation: no plan was produced");
        } else {
            boolean hasWorkstationAssist = workstation.get().actions().stream()
                .anyMatch(action -> "grant_item".equals(action.type())
                    && "minecraft:crafting_table".equals(string(action.body(), "item"))
                    && "common_resource_top_up".equals(string(action.body(), "assist_scope"))
                    && bool(action.body(), "optional"));
            boolean opensWorkstation = workstation.get().actions().stream()
                .anyMatch(action -> "open_workstation".equals(action.type())
                    && "minecraft:crafting_table".equals(string(action.body(), "station"))
                    && bool(action.body(), "allow_place")
                    && bool(action.body(), "avoid_occupied"));
            if (!hasWorkstationAssist || !opensWorkstation) {
                failures.add("intervention_choice_workstation: expected crafting table assist and open_workstation action but got " + workstation.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> sidestep = planner.planRuleBased("右にずれて");
        if (sidestep.isEmpty() || sidestep.get().actions().stream()
            .noneMatch(action -> "move".equals(action.type()) && "right".equals(string(action.body(), "direction")) && bool(action.body(), "scan_aware"))) {
            failures.add("intervention_choice_move_right: expected scan-aware right move but got " + sidestep);
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> digFront = planner.planRuleBased("手前を掘って");
        if (digFront.isEmpty() || digFront.get().actions().stream().noneMatch(action -> "dig_pattern".equals(action.type()))) {
            failures.add("intervention_choice_dig_front: expected dig_pattern plan but got " + digFront);
        }
        return 3;
    }

    private static int checkWorkstationTimingPlanning(List<String> failures) {
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> stonecut = planner.planLlmGoal(json("type", "craft_item", "target_item", "minecraft:stone_stairs"));
        if (stonecut.isEmpty()) {
            failures.add("workstation_timing_stonecut: no plan was produced");
        } else {
            boolean hasExpectedStonecutter = stonecut.get().actions().stream()
                .anyMatch(action -> "craft".equals(action.type())
                    && "minecraft:stone_stairs".equals(string(action.body(), "recipe"))
                    && "minecraft:stonecutter".equals(string(action.body(), "expected_station")));
            if (!hasExpectedStonecutter) {
                failures.add("workstation_timing_stonecut: expected craft action with expected_station=stonecutter but got " + stonecut.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> smithing = planner.planLlmGoal(json("type", "craft_item", "target_item", "minecraft:netherite_pickaxe"));
        if (smithing.isPresent()) {
            boolean hasExpectedSmithing = smithing.get().actions().stream()
                .anyMatch(action -> "craft".equals(action.type())
                    && "minecraft:netherite_pickaxe".equals(string(action.body(), "recipe"))
                    && "minecraft:smithing_table".equals(string(action.body(), "expected_station")));
            if (!hasExpectedSmithing) {
                failures.add("workstation_timing_smithing: expected craft action with expected_station=smithing_table but got " + smithing.get().actions());
            }
        }
        return 2;
    }

    private static int checkVoiceAssistPlanning(List<String> failures) {
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> bridge = planner.planRuleBased("橋かけて");
        if (bridge.isEmpty()) {
            failures.add("voice_assist_bridge_planner: no bridge plan was produced");
        } else {
            boolean hasSupportGrant = bridge.get().actions().stream()
                .anyMatch(action -> "grant_item".equals(action.type())
                    && "minecraft:dirt".equals(string(action.body(), "item"))
                    && "utility_support_blocks".equals(string(action.body(), "assist_scope"))
                    && bool(action.body(), "optional"));
            if (!hasSupportGrant) {
                failures.add("voice_assist_bridge_planner: expected optional utility dirt grant before bridge action but got " + bridge.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> pickup = planner.planRuleBased("落ちてるアイテム拾って");
        if (pickup.isEmpty()) {
            failures.add("voice_assist_pickup_planner: no pickup plan was produced");
        } else {
            boolean hasMagnetPickup = pickup.get().actions().stream()
                .anyMatch(action -> "collect_drops".equals(action.type())
                    && "any_drop".equals(string(action.body(), "item_group"))
                    && bool(action.body(), "magnet_fallback")
                    && bool(action.body(), "magnet_only")
                    && intValue(action.body(), "count") >= 8);
            if (!hasMagnetPickup) {
                failures.add("voice_assist_pickup_planner: expected any_drop pickup with magnet-only fallback and count>=8 but got " + pickup.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> hunt = planner.planRuleBased("牛を狩って");
        if (hunt.isEmpty()) {
            failures.add("voice_assist_hunt_planner: no hunt plan was produced");
        } else {
            boolean hasFoodPickup = hunt.get().actions().stream()
                .anyMatch(action -> "collect_drops".equals(action.type())
                    && "food_drop".equals(string(action.body(), "item_group"))
                    && bool(action.body(), "magnet_fallback")
                    && bool(action.body(), "magnet_only"));
            boolean hasOptionalCook = hunt.get().actions().stream()
                .anyMatch(action -> "smelt_food".equals(action.type()) && bool(action.body(), "optional"));
            if (!hasFoodPickup || !hasOptionalCook) {
                failures.add("voice_assist_hunt_planner: expected food drop pickup and optional smelt_food after hunting but got " + hunt.get().actions());
            }
        }
        return 3;
    }

    private static int checkCommonResourceTopUpPlanning(List<String> failures) {
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);
        Optional<KoeCraftNativeGoalPlanner.NativePlan> stonePickaxe = planner.planRuleBased("石のツルハシ作って");
        if (stonePickaxe.isEmpty()) {
            failures.add("common_resource_top_up_stone_pickaxe: no plan was produced");
        } else {
            boolean hasCobbleTopUp = stonePickaxe.get().actions().stream()
                .anyMatch(action -> "grant_item".equals(action.type())
                    && "minecraft:cobblestone".equals(string(action.body(), "item"))
                    && "common_resource_top_up".equals(string(action.body(), "assist_scope"))
                    && bool(action.body(), "optional"));
            if (!hasCobbleTopUp) {
                failures.add("common_resource_top_up_stone_pickaxe: expected optional cobblestone top-up but got " + stonePickaxe.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> chest = planner.planRuleBased("チェスト作って");
        if (chest.isEmpty()) {
            failures.add("common_resource_top_up_chest: no plan was produced");
        } else {
            boolean hasLogTopUp = chest.get().actions().stream()
                .anyMatch(action -> "grant_item".equals(action.type())
                    && "minecraft:oak_log".equals(string(action.body(), "item"))
                    && "common_resource_top_up".equals(string(action.body(), "assist_scope"))
                    && bool(action.body(), "optional"));
            if (!hasLogTopUp) {
                failures.add("common_resource_top_up_chest: expected optional log top-up for chest crafting but got " + chest.get().actions());
            }
        }

        Optional<KoeCraftNativeGoalPlanner.NativePlan> diamondSword = planner.planLlmGoal(json("type", "craft_item", "target_item", "minecraft:diamond_sword"));
        if (diamondSword.isEmpty()) {
            failures.add("common_resource_top_up_diamond_guard: no plan was produced");
        } else {
            boolean hasDiamondGrant = diamondSword.get().actions().stream()
                .anyMatch(action -> "grant_item".equals(action.type())
                    && string(action.body(), "item").contains("diamond"));
            if (hasDiamondGrant) {
                failures.add("common_resource_top_up_diamond_guard: rare diamond materials must not be granted, got " + diamondSword.get().actions());
            }
        }
        return 3;
    }

    private static int checkFallbackGoal(
        KoeCraftOpenAiGoalFallback fallback,
        List<String> failures,
        String id,
        JsonObject raw,
        String expectedType,
        String expectedKey,
        String expectedValue
    ) {
        Optional<JsonObject> normalized = fallback.normalizeGoalForTesting(raw);
        if (normalized.isEmpty()) {
            failures.add(id + ": normalized goal was empty for " + raw);
            return 1;
        }
        if (!expectedType.equals(string(normalized.get(), "type"))) {
            failures.add(id + ": expected type " + expectedType + " but got " + normalized.get());
        }
        if (!expectedValue.equals(string(normalized.get(), expectedKey))) {
            failures.add(id + ": expected " + expectedKey + "=" + expectedValue + " but got " + normalized.get());
        }
        return 1;
    }

    private static int checkPlannerAlias(List<String> failures, String id, String input, String expected) {
        String actual = new KoeCraftNativeGoalPlanner(null).resolveCraftTargetAliasForTesting(input);
        if (!expected.equals(actual)) {
            failures.add(id + ": expected alias " + expected + " but got " + actual);
        }
        return 1;
    }

    private static JsonObject json(Object... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value instanceof Number number) {
                object.addProperty(key, number);
            } else if (value instanceof Boolean bool) {
                object.addProperty(key, bool);
            } else {
                object.addProperty(key, String.valueOf(value));
            }
        }
        return object;
    }

    private static JsonObject amivoiceRaw(String written, String spoken, double confidence) {
        JsonObject token = json("written", written, "spoken", spoken, "confidence", confidence);
        JsonArray tokens = new JsonArray();
        tokens.add(token);
        JsonObject result = json("text", written, "confidence", confidence);
        result.add("tokens", tokens);
        JsonArray results = new JsonArray();
        results.add(result);
        JsonObject root = json("text", written);
        root.add("results", results);
        return root;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private static int intValue(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()
            ? object.get(key).getAsInt()
            : 0;
    }
}
