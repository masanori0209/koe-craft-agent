package dev.koecraft.agentmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class KoeCraftAsrComparisonReportMain {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HTML_NAME = "asr-comparison-report.html";
    private static final String JSON_NAME = "asr-comparison-report.json";

    private KoeCraftAsrComparisonReportMain() {
    }

    public static void main(String[] args) throws Exception {
        Path scenarioPath = args.length > 0
            ? Path.of(args[0])
            : Path.of("../examples/asr_comparison_scenarios.json");
        Path outputDir = args.length > 1
            ? Path.of(args[1])
            : Path.of("../logs/reports");
        Files.createDirectories(outputDir);

        JsonArray scenarios = JsonParser.parseString(Files.readString(scenarioPath)).getAsJsonArray();
        KoeCraftUtteranceGoalRouter router = new KoeCraftUtteranceGoalRouter();
        KoeCraftNativeGoalPlanner planner = new KoeCraftNativeGoalPlanner(null);

        Set<String> engines = new LinkedHashSet<>();
        boolean liveAudio = false;
        for (JsonElement element : scenarios) {
            JsonObject scenario = element.getAsJsonObject();
            if (scenario.has("recognition") && scenario.get("recognition").isJsonObject()) {
                liveAudio = true;
            }
            JsonObject asr = scenario.getAsJsonObject("asr");
            asr.keySet().forEach(engines::add);
        }

        Map<String, Summary> summaries = new LinkedHashMap<>();
        engines.forEach(engine -> summaries.put(engine, new Summary(engine)));
        JsonArray rows = new JsonArray();

        for (JsonElement element : scenarios) {
            JsonObject scenario = element.getAsJsonObject();
            JsonObject row = new JsonObject();
            row.addProperty("id", string(scenario, "id"));
            row.addProperty("source_fixture", string(scenario, "source_fixture"));
            row.addProperty("category", string(scenario, "category"));
            row.addProperty("utterance", string(scenario, "utterance"));
            row.addProperty("expected_behavior", stringOrDefault(scenario, "expected_behavior", "plan"));
            row.addProperty("amivoice_value", string(scenario, "amivoice_value"));
            if (scenario.has("focus") && scenario.get("focus").isJsonArray()) {
                row.add("focus", scenario.getAsJsonArray("focus").deepCopy());
            }
            if (scenario.has("recognition") && scenario.get("recognition").isJsonObject()) {
                row.add("recognition", scenario.getAsJsonObject("recognition").deepCopy());
            }
            JsonObject expected = scenario.getAsJsonObject("expected_goal");
            row.add("expected_goal", expected);

            JsonObject results = new JsonObject();
            JsonObject asr = scenario.getAsJsonObject("asr");
            JsonObject recognition = scenario.has("recognition") && scenario.get("recognition").isJsonObject()
                ? scenario.getAsJsonObject("recognition")
                : new JsonObject();
            for (String engine : engines) {
                String recognized = asr.has(engine) ? asr.get(engine).getAsString() : "";
                JsonObject result = evaluate(engine, recognized, expected, stringOrDefault(scenario, "expected_behavior", "plan"), router, planner);
                if (recognition.has(engine) && recognition.get(engine).isJsonObject()) {
                    result.add("recognition", recognition.getAsJsonObject(engine).deepCopy());
                }
                results.add(engine, result);
                summaries.get(engine).add(result);
            }
            row.add("results", results);
            rows.add(row);
        }

        JsonObject report = new JsonObject();
        report.addProperty("generated_at", Instant.now().toString());
        report.addProperty("scenario_path", scenarioPath.toString());
        report.addProperty("scenario_count", scenarios.size());
        report.addProperty("live_audio", liveAudio);
        report.addProperty(
            "method",
            liveAudio
                ? "Generated audio files -> AmiVoice/OpenAI Whisper APIs -> KoeCraftRecognizedTextProcessor normalization -> KoeCraftUtteranceGoalRouter -> KoeCraftNativeGoalPlanner."
                : "Fixture ASR text -> KoeCraftRecognizedTextProcessor normalization -> KoeCraftUtteranceGoalRouter -> KoeCraftNativeGoalPlanner. This is not a live ASR API benchmark."
        );
        JsonObject summaryJson = new JsonObject();
        summaries.forEach((engine, summary) -> summaryJson.add(engine, summary.toJson()));
        report.add("summary", summaryJson);
        report.add("focus_summary", focusSummary(rows, engines));
        report.add("rows", rows);

        Path jsonPath = outputDir.resolve(JSON_NAME);
        Path htmlPath = outputDir.resolve(HTML_NAME);
        Files.writeString(jsonPath, GSON.toJson(report));
        Files.writeString(htmlPath, renderHtml(report, engines));
        System.out.println("[asr-comparison-report] wrote " + htmlPath);
        System.out.println("[asr-comparison-report] wrote " + jsonPath);
    }

    private static JsonObject evaluate(
        String engine,
        String recognized,
        JsonObject expected,
        String expectedBehavior,
        KoeCraftUtteranceGoalRouter router,
        KoeCraftNativeGoalPlanner planner
    ) {
        String normalized = KoeCraftRecognizedTextProcessor.normalizeRecognizedTextForTesting(recognized);
        boolean garbled = KoeCraftRecognizedTextProcessor.looksGarbledRecognitionForTesting(normalized);
        boolean filler = KoeCraftRecognizedTextProcessor.isFillerOnlyUtteranceForTesting(normalized);

        JsonObject result = new JsonObject();
        result.addProperty("engine", engine);
        result.addProperty("recognized_text", recognized);
        result.addProperty("normalized_text", normalized);
        result.addProperty("garbled", garbled);
        result.addProperty("filler_only", filler);
        result.addProperty("expected_behavior", expectedBehavior);

        Optional<JsonObject> routed = Optional.empty();
        Optional<KoeCraftNativeGoalPlanner.NativePlan> plan = Optional.empty();
        if (!garbled && !filler) {
            routed = router.route(normalized);
            plan = planner.planRuleBased(normalized);
            if (plan.isEmpty() && routed.isPresent()) {
                plan = planner.planLlmGoal(routed.get());
            }
        }

        boolean expectsIgnore = "ignore".equals(expectedBehavior);
        boolean ignored = expectsIgnore && routed.isEmpty() && plan.isEmpty();
        boolean understood = expectsIgnore ? ignored : routed.isPresent() && goalMatches(expected, routed.get());
        boolean planned = plan.isPresent() && !plan.get().actions().isEmpty();
        boolean success = expectsIgnore ? ignored : understood && planned;
        result.addProperty("understood", understood);
        result.addProperty("planned", planned);
        result.addProperty("success", success);
        if (routed.isPresent()) {
            result.add("routed_goal", routed.get());
        }
        if (plan.isPresent()) {
            JsonObject planJson = new JsonObject();
            planJson.addProperty("goal", plan.get().goal());
            planJson.addProperty("source", plan.get().source());
            planJson.addProperty("action_count", plan.get().actions().size());
            JsonArray actionTypes = new JsonArray();
            for (ExecutorProtocol.Action action : plan.get().actions()) {
                actionTypes.add(action.type());
            }
            planJson.add("action_types", actionTypes);
            result.add("plan", planJson);
        }
        result.addProperty("status", status(garbled, filler, routed, understood, planned, expectsIgnore, ignored));
        return result;
    }

    private static String status(boolean garbled, boolean filler, Optional<JsonObject> routed, boolean understood, boolean planned, boolean expectsIgnore, boolean ignored) {
        if (expectsIgnore) {
            if (ignored) return "ignored";
            if (planned || routed.isPresent()) return "false_positive";
        }
        if (garbled) return "garbled";
        if (filler) return "filtered";
        if (routed.isEmpty()) return "unrouted";
        if (!understood) return "wrong_goal";
        if (!planned) return "not_planned";
        return "planned";
    }

    private static boolean goalMatches(JsonObject expected, JsonObject actual) {
        if (expected == null || actual == null) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            JsonElement actualValue = actual.get(entry.getKey());
            if (actualValue == null || !primitiveEquals(entry.getValue(), actualValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean primitiveEquals(JsonElement expected, JsonElement actual) {
        if (expected == null || actual == null || !expected.isJsonPrimitive() || !actual.isJsonPrimitive()) {
            return false;
        }
        if (expected.getAsJsonPrimitive().isNumber() && actual.getAsJsonPrimitive().isNumber()) {
            return Double.compare(expected.getAsDouble(), actual.getAsDouble()) == 0;
        }
        if (expected.getAsJsonPrimitive().isBoolean() && actual.getAsJsonPrimitive().isBoolean()) {
            return expected.getAsBoolean() == actual.getAsBoolean();
        }
        return expected.getAsString().equals(actual.getAsString());
    }

    private static JsonObject focusSummary(JsonArray rows, Set<String> engines) {
        JsonObject summary = new JsonObject();
        for (JsonElement rowElement : rows) {
            JsonObject row = rowElement.getAsJsonObject();
            if (!row.has("focus") || !row.get("focus").isJsonArray()) {
                continue;
            }
            JsonObject results = row.getAsJsonObject("results");
            for (JsonElement focusElement : row.getAsJsonArray("focus")) {
                String focus = focusElement.getAsString();
                JsonObject one = summary.has(focus) ? summary.getAsJsonObject(focus) : new JsonObject();
                one.addProperty("scenario_count", intValue(one, "scenario_count") + 1);
                for (String engine : engines) {
                    String key = engine + "_success";
                    one.addProperty(key, intValue(one, key) + (bool(results.getAsJsonObject(engine), "success") ? 1 : 0));
                }
                summary.add(focus, one);
            }
        }
        return summary;
    }

    private static String renderHtml(JsonObject report, Set<String> engines) {
        StringBuilder html = new StringBuilder();
        html.append("""
            <!doctype html>
            <html lang="ja">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>KoeCraft ASR Operational Report</title>
              <style>
                :root { color-scheme: light dark; --bg:#f7f8fa; --panel:#ffffff; --text:#18202a; --muted:#667085; --line:#d6dbe2; --good:#0f7b45; --bad:#b42318; --warn:#9a6700; --info:#175cd3; --accent:#206a5d; }
                @media (prefers-color-scheme: dark) { :root { --bg:#111418; --panel:#1a2027; --text:#edf2f7; --muted:#a7b0bd; --line:#303946; --good:#6fd69a; --bad:#ff8a80; --warn:#f4c430; --info:#8bb7ff; --accent:#7bdcb5; } }
                * { box-sizing: border-box; }
                body { margin:0; background:var(--bg); color:var(--text); font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
                main { max-width:1180px; margin:0 auto; padding:28px; }
                h1 { margin:0 0 4px; font-size:28px; letter-spacing:0; }
                h2 { margin:28px 0 12px; font-size:18px; letter-spacing:0; }
                p { color:var(--muted); margin:6px 0; }
                .summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:12px; margin:20px 0; }
                .metric { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; }
                .metric strong { display:block; font-size:24px; margin-top:6px; }
                .metric span { color:var(--muted); }
                .insights { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:12px; margin:14px 0 24px; }
                .insight { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; }
                .insight h3 { font-size:14px; margin:0 0 6px; letter-spacing:0; }
                .insight strong { color:var(--accent); }
                table { width:100%; border-collapse:separate; border-spacing:0; background:var(--panel); border:1px solid var(--line); border-radius:8px; overflow:hidden; }
                th, td { vertical-align:top; text-align:left; border-bottom:1px solid var(--line); padding:10px; }
                th { font-size:12px; color:var(--muted); background:color-mix(in srgb, var(--panel), var(--line) 30%); }
                tr:last-child td { border-bottom:0; }
                code { background:color-mix(in srgb, var(--panel), var(--line) 45%); padding:1px 4px; border-radius:4px; }
                .pill { display:inline-block; padding:2px 8px; border-radius:999px; font-size:12px; font-weight:600; border:1px solid var(--line); }
                .chip { display:inline-block; margin:3px 4px 0 0; padding:2px 7px; border-radius:999px; background:color-mix(in srgb, var(--panel), var(--accent) 10%); color:var(--accent); border:1px solid color-mix(in srgb, var(--line), var(--accent) 30%); font-size:11px; }
                .planned,.ignored { color:var(--good); }
                .filtered,.unrouted,.wrong_goal,.not_planned,.garbled,.false_positive { color:var(--bad); }
                .cell { min-width:250px; }
                .small { color:var(--muted); font-size:12px; }
                .mono { font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:12px; }
                .note { border-left:4px solid var(--info); padding:8px 12px; background:color-mix(in srgb, var(--panel), var(--info) 8%); border-radius:4px; }
                .value { margin-top:8px; color:var(--text); }
              </style>
            </head>
            <body><main>
            """);
        html.append("<h1>KoeCraft ASR Operational Report</h1>\n");
        boolean liveAudio = bool(report, "live_audio");
        html.append("<p>KoeCraft の既存 speech fixture から選んだ20シナリオで、AmiVoice と Whisper の認識結果テキストが理解・計画・安全無視まで到達するかを比較します。</p>\n");
        html.append("<p class=\"small\">Generated: ").append(escape(string(report, "generated_at"))).append("</p>\n");
        if (liveAudio) {
            html.append("<div class=\"note\">この版は実際の音声ファイルを AmiVoice と OpenAI Whisper API に送信した実測レポートです。音声は macOS の日本語TTSで生成しているため、人間のマイク発話そのものではありません。</div>\n");
        } else {
            html.append("<div class=\"note\">主眼はASR単体の勝敗ではなく、AmiVoice の profileWords / WebSocket partial / 日本語短命令 / KoeCraft post-normalizer を組み合わせた運用評価です。実録音で比較する場合は <code>examples/asr_comparison_scenarios.json</code> の <code>asr</code> 欄を実認識結果に差し替えて再生成します。</div>\n");
        }

        html.append("<section class=\"summary\">\n");
        JsonObject summary = report.getAsJsonObject("summary");
        for (String engine : engines) {
            JsonObject one = summary.getAsJsonObject(engine);
            html.append("<div class=\"metric\"><span>").append(escape(displayEngine(engine))).append("</span>");
            html.append("<strong>").append(percent(one, "success_rate")).append("</strong>");
            html.append("<div class=\"small\">処理成功 ").append(intValue(one, "success")).append(" / ").append(intValue(one, "total"))
                .append("・理解 ").append(percent(one, "understood_rate"))
                .append("・プラン ").append(percent(one, "planned_rate"));
            if (intValue(one, "latency_count") > 0) {
                html.append("・平均応答 ").append(intValue(one, "avg_latency_ms")).append("ms");
            }
            html.append("</div></div>\n");
        }
        html.append("</section>\n");

        html.append("<h2>AmiVoice Leverage</h2>\n");
        html.append(renderFocusSummary(report.getAsJsonObject("focus_summary"), engines));

        html.append("<h2>Project Scenario Results</h2>\n");
        html.append("<table><thead><tr><th>Project Scenario</th><th>AmiVoice Use</th><th>Expected</th>");
        for (String engine : engines) {
            html.append("<th>").append(escape(displayEngine(engine))).append("</th>");
        }
        html.append("</tr></thead><tbody>\n");

        JsonArray rows = report.getAsJsonArray("rows");
        for (JsonElement rowElement : rows) {
            JsonObject row = rowElement.getAsJsonObject();
            html.append("<tr>");
            html.append("<td><strong>").append(escape(string(row, "id"))).append("</strong><br>")
                .append("<span class=\"small\">fixture: ").append(escape(string(row, "source_fixture"))).append("</span><br>")
                .append("<span class=\"small\">").append(escape(string(row, "category"))).append("</span><br>")
                .append(escape(string(row, "utterance")))
                .append(renderFocusChips(row))
                .append("</td>");
            html.append("<td><div class=\"value\">").append(escape(string(row, "amivoice_value"))).append("</div></td>");
            html.append("<td><span class=\"small\">").append(escape(string(row, "expected_behavior"))).append("</span><br><span class=\"mono\">").append(escape(GSON.toJson(row.get("expected_goal")))).append("</span></td>");
            JsonObject results = row.getAsJsonObject("results");
            for (String engine : engines) {
                html.append(renderResultCell(results.getAsJsonObject(engine)));
            }
            html.append("</tr>\n");
        }
        html.append("</tbody></table>\n");
        html.append("</main></body></html>\n");
        return html.toString();
    }

    private static String renderFocusSummary(JsonObject focusSummary, Set<String> engines) {
        if (focusSummary == null || focusSummary.size() == 0) {
            return "<p class=\"small\">No focus summary.</p>\n";
        }
        StringBuilder html = new StringBuilder();
        html.append("<section class=\"insights\">\n");
        for (String focus : focusSummary.keySet()) {
            JsonObject one = focusSummary.getAsJsonObject(focus);
            html.append("<div class=\"insight\"><h3>").append(escape(focusLabel(focus))).append("</h3>");
            html.append("<p>").append(escape(focusDescription(focus))).append("</p>");
            html.append("<div class=\"small\">").append(intValue(one, "scenario_count")).append(" scenarios");
            for (String engine : engines) {
                html.append(" / ").append(escape(displayEngine(engine))).append(" ")
                    .append("<strong>").append(intValue(one, engine + "_success")).append("</strong>");
            }
            html.append("</div></div>\n");
        }
        html.append("</section>\n");
        return html.toString();
    }

    private static String renderFocusChips(JsonObject row) {
        if (!row.has("focus") || !row.get("focus").isJsonArray()) {
            return "";
        }
        StringBuilder chips = new StringBuilder("<div>");
        for (JsonElement element : row.getAsJsonArray("focus")) {
            chips.append("<span class=\"chip\">").append(escape(focusLabel(element.getAsString()))).append("</span>");
        }
        chips.append("</div>");
        return chips.toString();
    }

    private static String renderResultCell(JsonObject result) {
        String status = string(result, "status");
        StringBuilder cell = new StringBuilder();
        cell.append("<td class=\"cell\">");
        cell.append("<span class=\"pill ").append(escape(status)).append("\">").append(escape(status)).append("</span><br>");
        if (result.has("recognition") && result.get("recognition").isJsonObject()) {
            JsonObject recognition = result.getAsJsonObject("recognition");
            cell.append("<div class=\"small\">");
            if (!string(recognition, "model").isBlank()) {
                cell.append(escape(string(recognition, "model")));
            }
            if (recognition.has("latency_ms")) {
                cell.append(" / ").append(intValue(recognition, "latency_ms")).append("ms");
            }
            if (recognition.has("audio_duration_sec")) {
                cell.append(" / audio ").append(String.format(Locale.ROOT, "%.2fs", doubleValue(recognition, "audio_duration_sec")));
            }
            cell.append("</div>");
            if (!string(recognition, "error").isBlank()) {
                cell.append("<div class=\"small ").append("false_positive").append("\">").append(escape(string(recognition, "error"))).append("</div>");
            }
        }
        cell.append("<div><span class=\"small\">recognized</span><br>").append(escape(string(result, "recognized_text"))).append("</div>");
        cell.append("<div><span class=\"small\">normalized</span><br>").append(escape(string(result, "normalized_text"))).append("</div>");
        if (result.has("routed_goal")) {
            cell.append("<div class=\"mono\"><span class=\"small\">goal</span><br>").append(escape(GSON.toJson(result.get("routed_goal")))).append("</div>");
        }
        if (result.has("plan")) {
            JsonObject plan = result.getAsJsonObject("plan");
            cell.append("<div><span class=\"small\">plan</span><br>").append(escape(string(plan, "goal")))
                .append(" / ").append(intValue(plan, "action_count")).append(" actions</div>");
        }
        cell.append("</td>");
        return cell.toString();
    }

    private static String focusLabel(String focus) {
        return switch (focus) {
            case "domain_terms" -> "固有語";
            case "profile_words" -> "profileWords";
            case "rule_fast_path" -> "Rule fast path";
            case "vague_language" -> "言葉の揺れ";
            case "craft_planning" -> "クラフト";
            case "asr_repair" -> "ASR補正";
            case "default_target" -> "既定ターゲット";
            case "collect_planning" -> "素材調達";
            case "short_command" -> "短命令";
            case "partial_fast_path" -> "partial即実行";
            case "movement" -> "移動";
            case "numeric_command" -> "数値命令";
            case "exploration" -> "探索";
            case "long_goal" -> "長期Goal";
            case "route_selection" -> "ルート選択";
            case "child_mode" -> "子供向け";
            case "build" -> "建築";
            case "ambient_chat" -> "会話応答";
            case "false_positive_guard" -> "誤爆防止";
            case "safety_ignore" -> "安全無視";
            default -> focus;
        };
    }

    private static String focusDescription(String focus) {
        return switch (focus) {
            case "domain_terms" -> "Minecraft固有語を辞書・profileWordsで拾い、LLM前に安定してGoal化する領域。";
            case "profile_words" -> "AmiVoiceの登録語/フレーズ寄せが効く前提のシナリオ。";
            case "rule_fast_path" -> "認識後にOpenAIを呼ばず、ルールベースで即NativePlanへ進む領域。";
            case "asr_repair" -> "ハッシュ/きのう/私など、観測済み誤認識をMinecraft文脈で補正する領域。";
            case "short_command", "partial_fast_path" -> "WebSocket partialと相性がよい短い操作命令。体感テンポに効く。";
            case "false_positive_guard", "safety_ignore" -> "ため息や相づちを建築・移動に誤爆させないための安全領域。";
            case "child_mode" -> "子供が言いそうな大きめ・曖昧な依頼を小さな安全Goalへ縮退する領域。";
            case "movement" -> "歩く、泳ぐ、ダッシュジャンプ、橋などゲーム操作の即応領域。";
            case "craft_planning" -> "音声から決定的レシピ解決へつなぐ領域。";
            case "exploration" -> "村・ネザーなど、周辺scanやルート選択につながる領域。";
            default -> "KoeCraftの既存シナリオで評価している観点。";
        };
    }

    private static String displayEngine(String engine) {
        return switch (engine) {
            case "amivoice" -> "AmiVoice";
            case "whisper" -> "Whisper";
            default -> engine;
        };
    }

    private static String percent(JsonObject object, String key) {
        return String.format(Locale.ROOT, "%.0f%%", doubleValue(object, key) * 100.0D);
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static String stringOrDefault(JsonObject object, String key, String fallback) {
        String value = string(object, key);
        return value.isBlank() ? fallback : value;
    }

    private static int intValue(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : 0;
    }

    private static double doubleValue(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : 0.0D;
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static final class Summary {
        private final String engine;
        private int total;
        private int understood;
        private int planned;
        private int success;
        private int filtered;
        private int unrouted;
        private long latencyTotalMs;
        private int latencyCount;

        private Summary(String engine) {
            this.engine = engine;
        }

        private void add(JsonObject result) {
            total++;
            if (bool(result, "understood")) understood++;
            if (bool(result, "planned")) planned++;
            if (bool(result, "success")) success++;
            String status = string(result, "status");
            if ("filtered".equals(status) || "garbled".equals(status)) filtered++;
            if ("unrouted".equals(status)) unrouted++;
            if (result.has("recognition") && result.get("recognition").isJsonObject()) {
                JsonObject recognition = result.getAsJsonObject("recognition");
                if (recognition.has("latency_ms") && recognition.get("latency_ms").isJsonPrimitive()) {
                    latencyTotalMs += recognition.get("latency_ms").getAsLong();
                    latencyCount++;
                }
            }
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("engine", engine);
            object.addProperty("total", total);
            object.addProperty("understood", understood);
            object.addProperty("planned", planned);
            object.addProperty("success", success);
            object.addProperty("filtered", filtered);
            object.addProperty("unrouted", unrouted);
            object.addProperty("latency_count", latencyCount);
            object.addProperty("avg_latency_ms", latencyCount == 0 ? 0 : Math.round(latencyTotalMs / (double) latencyCount));
            object.addProperty("understood_rate", rate(understood));
            object.addProperty("planned_rate", rate(planned));
            object.addProperty("success_rate", rate(success));
            return object;
        }

        private double rate(int value) {
            return total <= 0 ? 0.0D : value / (double) total;
        }

        private boolean bool(JsonObject object, String key) {
            return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
        }
    }
}
