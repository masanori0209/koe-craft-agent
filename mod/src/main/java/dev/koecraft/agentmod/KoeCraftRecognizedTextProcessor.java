package dev.koecraft.agentmod;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

final class KoeCraftRecognizedTextProcessor {
    private static final Pattern BARE_UNICODE_ESCAPE = Pattern.compile("u([0-9a-fA-F]{4})");
    private static final Pattern MANY_UNICODE_ESCAPES = Pattern.compile("(?:u[0-9a-fA-F]{4}){2,}");
    private static final Pattern MANY_UNICODE_LIKE_FRAGMENTS = Pattern.compile("(?:u[0-9A-Za-z]{3,4}){2,}");
    private static final Pattern WOODEN_TOOL_ASR_CONFUSION = Pattern.compile("(昨日|きのう)(の)?(ツルハシ|つるはし|ピッケル|ぴっける|斧|おの|シャベル|しゃべる|スコップ|剣|けん|クワ|くわ)");
    private static final Pattern FILLER_ONLY_UTTERANCE = Pattern.compile(
        "^(?:は+|はあ+|はぁ+|はー+|あ+|え+|えっと+|うーん+|うん+|はい+|へ+|へえ+|ふん+|ふーん+|ふぅ+|なるほどね?|ん+|ok|okay)$",
        Pattern.CASE_INSENSITIVE
    );
    private final KoeCraftVoiceConfig config;
    private final HttpClient httpClient;
    private final SurvivalActionExecutor executor;
    private final MinecraftClient client;
    private final Consumer<String> overlaySink;
    private final Logger logger;
    private final KoeCraftOpenAiGoalFallback openAiGoalFallback;
    private final KoeCraftOpenAiSpeechNormalizer speechNormalizer;
    private final KoeCraftNativeGoalPlanner planner;
    private final ExecutorService planExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "KoeCraft Native Plan");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong utteranceSequence = new AtomicLong();
    private final AtomicLong planSequence = new AtomicLong();
    private final Map<String, KoeCraftNativeGoalPlanner.NativePlan> rulePlanCache = new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, KoeCraftNativeGoalPlanner.NativePlan> eldest) {
            return size() > 64;
        }
    };

    KoeCraftRecognizedTextProcessor(
        KoeCraftVoiceConfig config,
        HttpClient httpClient,
        SurvivalActionExecutor executor,
        MinecraftClient client,
        Consumer<String> overlaySink,
        Logger logger
    ) {
        this.config = config;
        this.httpClient = httpClient;
        this.executor = executor;
        this.client = client;
        this.overlaySink = overlaySink;
        this.logger = logger;
        this.openAiGoalFallback = new KoeCraftOpenAiGoalFallback(httpClient);
        this.speechNormalizer = new KoeCraftOpenAiSpeechNormalizer(httpClient);
        this.planner = new KoeCraftNativeGoalPlanner(client, config);
    }

    CompletableFuture<Void> handleRecognizedText(String text) {
        return handleRecognizedText(text, new JsonObject(), null);
    }

    CompletableFuture<Void> handleRecognizedText(KoeCraftAmiVoiceRecognizer.RecognitionResult recognition) {
        if (recognition == null) {
            return handleRecognizedText("", new JsonObject(), null);
        }
        return handleRecognizedText(recognition.text(), recognition.raw(), recognition.confidence());
    }

    private CompletableFuture<Void> handleRecognizedText(String text, JsonObject rawRecognition, Double asrConfidence) {
        long utteranceId = utteranceSequence.incrementAndGet();
        String normalizedText = normalizeRecognizedText(text);
        logger.info("[KoeCraft] recognized_text={} asr_confidence={}", normalizedText, asrConfidence);
        show("KoeCraft heard: " + normalizedText);
        if (looksGarbledRecognition(normalizedText)) {
            show("うまく聞き取れなかったので、もう一回いってね");
            return CompletableFuture.completedFuture(null);
        }
        Optional<DomainPhrasePlanMatch> confidentDomainPhrasePlan = planDomainPhraseCandidate(text, rawRecognition, normalizedText, 0.92D);
        if (confidentDomainPhrasePlan.isPresent()) {
            DomainPhrasePlanMatch match = confidentDomainPhrasePlan.get();
            show("こう聞こえた: " + match.candidateText());
            logger.info(
                "[KoeCraft] high-confidence asr domain phrase accepted: original=`{}` candidate=`{}` intent_hint={} score={} spoken=`{}` goal={} actions={}",
                normalizedText,
                match.candidateText(),
                match.candidate().intentHint(),
                String.format(Locale.ROOT, "%.3f", match.candidate().score()),
                match.candidate().spokenEvidence(),
                match.plan().goal(),
                match.plan().actions().size()
            );
            return executeNativePlan(match.plan(), utteranceId);
        }
        Optional<KoeCraftNativeGoalPlanner.NativePlan> rulePlan = cachedRulePlan(normalizedText);
        if (rulePlan.isPresent()) {
            logger.info("[KoeCraft] rule plan accepted: goal={} source={} actions={}", rulePlan.get().goal(), rulePlan.get().source(), rulePlan.get().actions().size());
            return executeNativePlan(rulePlan.get(), utteranceId);
        }
        Optional<DomainPhrasePlanMatch> domainPhrasePlan = planDomainPhraseCandidate(text, rawRecognition, normalizedText, 0.0D);
        if (domainPhrasePlan.isPresent()) {
            DomainPhrasePlanMatch match = domainPhrasePlan.get();
            show("こう聞こえた: " + match.candidateText());
            logger.info(
                "[KoeCraft] asr domain phrase accepted: original=`{}` candidate=`{}` intent_hint={} score={} spoken=`{}` goal={} actions={}",
                normalizedText,
                match.candidateText(),
                match.candidate().intentHint(),
                String.format(Locale.ROOT, "%.3f", match.candidate().score()),
                match.candidate().spokenEvidence(),
                match.plan().goal(),
                match.plan().actions().size()
            );
            return executeNativePlan(match.plan(), utteranceId);
        }
        if (isFillerOnlyUtterance(normalizedText)) {
            logger.info("[KoeCraft] filler-only utterance ignored: `{}`", normalizedText);
            show("聞いています");
            return CompletableFuture.completedFuture(null);
        }
        if (shouldDeferUnsupportedUtteranceDuringExecution(normalizedText)) {
            logger.info("[KoeCraft] unsupported utterance ignored while plan is running: `{}`", normalizedText);
            show("いま進めています。止める時は「止まって」といってね");
            return CompletableFuture.completedFuture(null);
        }
        if (shouldTrySpeechNormalizer(normalizedText)) {
            return trySpeechNormalizer(normalizedText)
                .thenCompose(normalizerResult -> {
                    if (!isLatestUtterance(utteranceId)) {
                        return ignoreStaleUtterance(utteranceId, normalizedText);
                    }
                    String routedText = normalizerResult
                        .filter(result -> shouldUseNormalizedText(normalizedText, result))
                        .map(KoeCraftOpenAiSpeechNormalizer.Result::normalizedText)
                        .orElse(normalizedText);
                    if (!routedText.equals(normalizedText)) {
                        show("こう聞こえた: " + routedText);
                        logger.info(
                            "[KoeCraft] speech normalizer original=`{}` normalized=`{}` intent_hint={} confidence={}",
                            normalizedText,
                            routedText,
                            normalizerResult.map(KoeCraftOpenAiSpeechNormalizer.Result::intentHint).orElse("unknown"),
                            normalizerResult.map(KoeCraftOpenAiSpeechNormalizer.Result::confidence).orElse(0.0D)
                        );
                        Optional<KoeCraftNativeGoalPlanner.NativePlan> normalizedRulePlan = cachedRulePlan(routedText);
                        if (normalizedRulePlan.isPresent()) {
                            logger.info("[KoeCraft] normalized rule plan accepted: goal={} source={} actions={}", normalizedRulePlan.get().goal(), normalizedRulePlan.get().source(), normalizedRulePlan.get().actions().size());
                            return executeNativePlan(normalizedRulePlan.get(), utteranceId);
                        }
                    }
                    if (isFillerOnlyUtterance(routedText)) {
                        logger.info("[KoeCraft] normalized filler-only utterance ignored: `{}` -> `{}`", normalizedText, routedText);
                        show("聞いています");
                        return CompletableFuture.completedFuture(null);
                    }
                    return continueAfterRuleFailure(routedText, utteranceId);
                });
        }
        return continueAfterRuleFailure(normalizedText, utteranceId);
    }

    private boolean shouldDeferUnsupportedUtteranceDuringExecution(String text) {
        return executor.isExecuting() && !containsExplicitInterventionWord(text);
    }

    private boolean containsExplicitInterventionWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("止")
            || text.contains("やめ")
            || text.contains("待")
            || text.contains("歩")
            || text.contains("進")
            || text.contains("すす")
            || text.contains("走")
            || text.contains("泳")
            || text.contains("ジャンプ")
            || text.contains("跳")
            || text.contains("右")
            || text.contains("左")
            || text.contains("前")
            || text.contains("後")
            || text.contains("上")
            || text.contains("下")
            || text.contains("掘")
            || text.contains("彫")
            || text.contains("置")
            || text.contains("作")
            || text.contains("拾")
            || text.contains("取")
            || text.contains("探")
            || text.contains("橋")
            || text.contains("作業台")
            || text.contains("クラフト")
            || text.contains("スタック")
            || text.contains("助け")
            || text.contains("困")
            || text.contains("無理");
    }

    private Optional<DomainPhrasePlanMatch> planDomainPhraseCandidate(String recognizedText, JsonObject rawRecognition, String normalizedText, double minScore) {
        for (KoeCraftAsrDomainPhraseNormalizer.Candidate candidate : KoeCraftAsrDomainPhraseNormalizer.candidates(recognizedText, rawRecognition)) {
            if (candidate.score() < minScore) {
                continue;
            }
            String candidateText = normalizeRecognizedText(candidate.text());
            if (candidateText.isBlank() || candidateText.equals(normalizedText)) {
                continue;
            }
            Optional<KoeCraftNativeGoalPlanner.NativePlan> plan = cachedRulePlan(candidateText);
            if (plan.isPresent()) {
                return Optional.of(new DomainPhrasePlanMatch(candidateText, candidate, plan.get()));
            }
        }
        return Optional.empty();
    }

    private CompletableFuture<Void> continueAfterRuleFailure(String normalizedText, long utteranceId) {
        if (!isLatestUtterance(utteranceId)) {
            return ignoreStaleUtterance(utteranceId, normalizedText);
        }
        if (config.llmFallbackEnabled() && config.openaiConfigured()) {
            show("お願いを考えています");
            return KoeCraftWorldContext.captureAsync(client, 8)
                .thenCompose(worldContext -> {
                    if (!isLatestUtterance(utteranceId)) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    return openAiGoalFallback.parseGoal(normalizedText, config, worldContext);
                })
                .thenCompose(goal -> {
                    if (!isLatestUtterance(utteranceId)) {
                        return ignoreStaleUtterance(utteranceId, normalizedText);
                    }
                    logger.info("[KoeCraft] llm fallback normalized_goal={}", goal.map(JsonObject::toString).orElse("empty"));
                    return goal.flatMap(planner::planLlmGoal)
                        .map(plan -> {
                            logger.info("[KoeCraft] llm plan accepted: goal={} actions={}", plan.goal(), plan.actions().size());
                            return executeNativePlan(plan, utteranceId);
                        })
                        .orElseGet(() -> fallbackToAgentUtterance(normalizedText));
                });
        }
        return fallbackToAgentUtterance(normalizedText);
    }

    private boolean shouldTrySpeechNormalizer(String text) {
        return config.openaiNormalizerEnabled() && config.openaiConfigured() && text != null && !text.isBlank();
    }

    private CompletableFuture<Optional<KoeCraftOpenAiSpeechNormalizer.Result>> trySpeechNormalizer(String text) {
        show("ことばを整えています");
        return speechNormalizer.normalize(text, config)
            .exceptionally(error -> {
                logger.warn("[KoeCraft] OpenAI speech normalizer failed: {}", error.toString());
                return Optional.empty();
            });
    }

    private boolean shouldUseNormalizedText(String originalText, KoeCraftOpenAiSpeechNormalizer.Result result) {
        if (result == null || result.normalizedText().isBlank()) {
            return false;
        }
        if (looksGarbledRecognition(result.normalizedText())) {
            return false;
        }
        if (result.normalizedText().equals(originalText)) {
            return false;
        }
        return result.confidence() >= 0.45D || !"unknown".equals(result.intentHint());
    }

    private Optional<KoeCraftNativeGoalPlanner.NativePlan> cachedRulePlan(String text) {
        synchronized (rulePlanCache) {
            KoeCraftNativeGoalPlanner.NativePlan cached = rulePlanCache.get(text);
            if (cached != null) {
                logger.info("[KoeCraft] rule plan cache hit: goal={}", cached.goal());
                return Optional.of(clonePlan(cached, "rule_cache"));
            }
        }
        Optional<KoeCraftNativeGoalPlanner.NativePlan> planned = planner.planRuleBased(text);
        planned.filter(this::isCacheablePlan).ifPresent(plan -> {
            synchronized (rulePlanCache) {
                rulePlanCache.put(text, clonePlan(plan, plan.source()));
            }
        });
        return planned;
    }

    private boolean isCacheablePlan(KoeCraftNativeGoalPlanner.NativePlan plan) {
        if (plan.actions().isEmpty()) {
            return false;
        }
        return switch (plan.goal()) {
            case "move", "pickup_items", "dig_pattern", "build_bridge", "abort", "close_screen", "celebrate", "ambient_chat" -> true;
            default -> false;
        };
    }

    private record DomainPhrasePlanMatch(
        String candidateText,
        KoeCraftAsrDomainPhraseNormalizer.Candidate candidate,
        KoeCraftNativeGoalPlanner.NativePlan plan
    ) {
    }

    private KoeCraftNativeGoalPlanner.NativePlan clonePlan(KoeCraftNativeGoalPlanner.NativePlan plan, String source) {
        List<ExecutorProtocol.Action> actions = plan.actions().stream()
            .map(action -> new ExecutorProtocol.Action(action.type(), action.body().deepCopy()))
            .toList();
        return new KoeCraftNativeGoalPlanner.NativePlan(plan.goal(), source, actions);
    }

    private CompletableFuture<Void> executeNativePlan(KoeCraftNativeGoalPlanner.NativePlan plan, long utteranceId) {
        if ("abort".equals(plan.goal())) {
            planSequence.incrementAndGet();
            executor.abort("voice_abort");
            show("止めました");
            return CompletableFuture.completedFuture(null);
        }
        if (!isLatestUtterance(utteranceId)) {
            return ignoreStaleUtterance(utteranceId, plan.goal());
        }
        long planId = planSequence.incrementAndGet();
        if (executor.isExecuting()) {
            logger.info("[KoeCraft] aborting active plan before starting new plan: goal={} source={}", plan.goal(), plan.source());
            executor.abort("new_voice_plan");
        }
        return CompletableFuture.runAsync(() -> {
            if (planId != planSequence.get()) {
                logger.info("[KoeCraft] skipped superseded native plan: goal={} source={}", plan.goal(), plan.source());
                return;
            }
            show("やることを決めました: " + plan.goal());
            List<ExecutorProtocol.StepResult> results = executor.execute(plan.goal(), plan.actions());
            if (planId != planSequence.get()) {
                logger.info("[KoeCraft] native plan finished after being superseded: goal={} source={}", plan.goal(), plan.source());
                return;
            }
            ExecutorProtocol.StepResult last = results.isEmpty() ? null : results.get(results.size() - 1);
            if (last == null) {
                show("やることが見つかりませんでした");
                return;
            }
            show(ExecutorStatusLabels.statusLabel(last.status()) + ": 最後のステップ");
        }, planExecutor);
    }

    private boolean isLatestUtterance(long utteranceId) {
        return utteranceSequence.get() == utteranceId;
    }

    private CompletableFuture<Void> ignoreStaleUtterance(long utteranceId, String detail) {
        logger.info("[KoeCraft] stale utterance ignored: id={} latest={} detail={}", utteranceId, utteranceSequence.get(), detail);
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> fallbackToAgentUtterance(String text) {
        if (config.agentUtteranceUrl().isBlank()) {
            show("まだできないお願いかも。別の言い方で試してね");
            return CompletableFuture.completedFuture(null);
        }
        show("別の考え方で試しています");
        String payload = "{\"recognized_text\":\"" + jsonEscape(text) + "\",\"source\":\"minecraft_text_processor\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.agentUtteranceUrl()))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    show("うまく送れませんでした。もう一回いってね");
                }
            })
            .exceptionally(error -> {
                show("考える係につながりませんでした");
                logger.warn("[KoeCraft] Native voice utterance delivery failed: {}", error.toString());
                return null;
            });
    }

    private void show(String message) {
        client.execute(() -> overlaySink.accept(message));
    }

    private String jsonEscape(String text) {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    static String normalizeRecognizedTextForTesting(String text) {
        return normalizeRecognizedText(text);
    }

    static boolean looksGarbledRecognitionForTesting(String text) {
        return looksGarbledRecognition(text);
    }

    static boolean isFillerOnlyUtteranceForTesting(String text) {
        return isFillerOnlyUtterance(normalizeRecognizedText(text));
    }

    private static String normalizeRecognizedText(String text) {
        String compact = KoeCraftAsrPostNormalizer.normalize(text);
        Matcher matcher = BARE_UNICODE_ESCAPE.matcher(compact);
        int matches = 0;
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            matches++;
            char value = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(decoded);
        String normalized = matches >= 2 ? decoded.toString().trim() : compact;
        return fixMinecraftAsrConfusions(normalized);
    }

    private static String fixMinecraftAsrConfusions(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (!Pattern.compile("作|つく|クラフト|ほしい|欲しい|お願い|頼む").matcher(text).find()) {
            return text;
        }
        return WOODEN_TOOL_ASR_CONFUSION.matcher(text).replaceAll("木の$3");
    }

    private static boolean looksGarbledRecognition(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return MANY_UNICODE_ESCAPES.matcher(text).find() || MANY_UNICODE_LIKE_FRAGMENTS.matcher(text).find();
    }

    private static boolean isFillerOnlyUtterance(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String compact = text
            .replaceAll("[\\s　、。,.!！?？…~〜ー]+", "")
            .trim()
            .toLowerCase(Locale.ROOT);
        return compact.isBlank() || FILLER_ONLY_UTTERANCE.matcher(compact).matches();
    }
}
