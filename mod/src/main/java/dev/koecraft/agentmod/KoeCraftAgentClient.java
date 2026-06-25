package dev.koecraft.agentmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KoeCraftAgentClient implements ClientModInitializer {
    public static final String MOD_ID = "koecraft_agent";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String VOICE_CONTROL_URL = System.getProperty("koecraft.voiceControlUrl", "http://127.0.0.1:8790/api/mic/toggle");
    private static final String VOICE_OFF_URL = System.getProperty("koecraft.voiceOffUrl", "http://127.0.0.1:8790/api/mic/off");
    private static final String VOICE_MUTE_URL = System.getProperty("koecraft.voiceMuteUrl", "http://127.0.0.1:8790/api/mic/mute/toggle");
    private static final String VOICE_HEALTH_URL = System.getProperty("koecraft.voiceHealthUrl", "http://127.0.0.1:8790/api/health");
    private static final int UTTERANCE_PORT = Integer.getInteger("koecraft.utterancePort", 8791);
    private KoeCraftExecutorServer server;
    private KoeCraftUtteranceHttpServer utteranceServer;
    private KoeCraftVoiceConfig voiceConfig;
    private KoeCraftNativeVoiceLoop nativeVoiceLoop;
    private KeyBinding toggleVoiceKey;
    private KeyBinding muteVoiceKey;
    private KeyBinding stopVoiceKey;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private volatile String pendingStatusMessage;
    private volatile VoiceHudStatus voiceHudStatus = VoiceHudStatus.off();
    private volatile RecognizedHudStatus recognizedHudStatus = RecognizedHudStatus.hidden();
    private volatile AgentHudStatus agentHudStatus = AgentHudStatus.hidden();
    private volatile BubbleHudStatus bubbleHudStatus = BubbleHudStatus.hidden();
    private volatile WorldAssistHudStatus worldAssistHudStatus = WorldAssistHudStatus.hidden();
    private volatile ChoiceHudStatus choiceHudStatus = ChoiceHudStatus.hidden();
    private long lastVoiceHealthPollMs = 0L;
    private boolean voiceHealthPollInFlight = false;
    private long lastTtsRequestMs = 0L;

    @Override
    public void onInitializeClient() {
        KoeCraftModConfig config = KoeCraftModConfig.load();
        voiceConfig = KoeCraftVoiceConfig.load();
        int port = config.port();
        MinecraftClient minecraftClient = MinecraftClient.getInstance();
        SurvivalActionExecutor executor = new SurvivalActionExecutor(minecraftClient, config, message -> minecraftClient.execute(() -> showAgentStatus(minecraftClient, message)));
        KoeCraftRecognizedTextProcessor textProcessor = new KoeCraftRecognizedTextProcessor(voiceConfig, httpClient, executor, minecraftClient, message -> showOverlay(minecraftClient, message), LOGGER);
        server = new KoeCraftExecutorServer(port, executor, message -> minecraftClient.execute(() -> queueOrShowAgentStatus(minecraftClient, message)));
        server.start();
        utteranceServer = new KoeCraftUtteranceHttpServer(UTTERANCE_PORT, textProcessor, message -> minecraftClient.execute(() -> queueOrShowAgentStatus(minecraftClient, message)), LOGGER);
        utteranceServer.start();
        if (voiceConfig.nativeMicEnabled()) {
            nativeVoiceLoop = new KoeCraftNativeVoiceLoop(voiceConfig, httpClient, executor, minecraftClient, message -> showOverlay(minecraftClient, message), LOGGER);
        }
        LOGGER.info("[KoeCraft] Executor WebSocket starting on ws://127.0.0.1:{}", port);
        LOGGER.info(
            "[KoeCraft] Voice config loaded from {} (AmiVoice={}, transport={}, OpenAI={}, model={}, nativeMic={})",
            voiceConfig.path(),
            voiceConfig.amivoiceConfigured() ? "configured" : "missing",
            voiceConfig.amivoiceTransport(),
            voiceConfig.openaiConfigured() ? "configured" : "missing",
            voiceConfig.openaiModel(),
            voiceConfig.nativeMicEnabled()
        );
        registerVoiceKeys();
        registerPendingStatusOverlay();
        registerVoiceStatusHud();
        if (!voiceConfig.nativeMicEnabled()) {
            registerVoiceHealthPolling();
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (nativeVoiceLoop != null) {
                nativeVoiceLoop.close();
            }
            if (server != null) {
                server.stopGracefully();
            }
            if (utteranceServer != null) {
                utteranceServer.stopGracefully();
            }
        });
    }

    private void registerVoiceKeys() {
        toggleVoiceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.koecraft_agent.toggle_voice",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "category.koecraft_agent"
        ));
        muteVoiceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.koecraft_agent.mute_voice",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "category.koecraft_agent"
        ));
        stopVoiceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.koecraft_agent.stop_voice",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "category.koecraft_agent"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleVoiceKey.wasPressed()) {
                toggleVoice(client);
            }
            while (muteVoiceKey.wasPressed()) {
                muteVoice(client);
            }
            while (stopVoiceKey.wasPressed()) {
                stopVoice(client);
            }
        });
    }

    private void registerPendingStatusOverlay() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            String message = pendingStatusMessage;
            if (message != null && client.player != null) {
                pendingStatusMessage = null;
                showAgentStatus(client, message);
            }
        });
    }

    private void registerVoiceStatusHud() {
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> renderVoiceStatusHud(MinecraftClient.getInstance(), drawContext));
    }

    private void registerVoiceHealthPolling() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (voiceHealthPollInFlight || now - lastVoiceHealthPollMs < 500L) {
                return;
            }
            lastVoiceHealthPollMs = now;
            voiceHealthPollInFlight = true;
            HttpRequest request = HttpRequest.newBuilder(URI.create(VOICE_HEALTH_URL)).GET().build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    voiceHealthPollInFlight = false;
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        voiceHudStatus = parseVoiceHudStatus(response.body());
                    } else {
                        voiceHudStatus = VoiceHudStatus.unreachable();
                    }
                })
                .exceptionally(error -> {
                    voiceHealthPollInFlight = false;
                    voiceHudStatus = VoiceHudStatus.unreachable();
                    return null;
                });
        });
    }

    private void toggleVoice(MinecraftClient client) {
        if (nativeVoiceLoop != null) {
            nativeVoiceLoop.toggle();
            voiceHudStatus = voiceHudStatusFromNative();
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(VOICE_CONTROL_URL))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"minecraft_keybinding\"}"))
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
                if (ok) {
                    voiceHudStatus = parseVoiceHudStatus(response.body());
                }
                client.execute(() -> showOverlay(client, voiceToggleMessage(response.body(), response.statusCode(), ok)));
            })
            .exceptionally(error -> {
                client.execute(() -> showOverlay(client, "マイクアプリを起動してね"));
                LOGGER.warn("[KoeCraft] Voice toggle failed: {}", error.toString());
                return null;
            });
    }

    private void postVoiceControl(MinecraftClient client, String url, String source, String successMessage, String failureMessage) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"" + jsonEscape(source) + "\"}"))
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
                if (ok) {
                    voiceHudStatus = parseVoiceHudStatus(response.body());
                }
                client.execute(() -> showOverlay(client, ok ? successMessage : failureMessage + ": HTTP " + response.statusCode()));
            })
            .exceptionally(error -> {
                client.execute(() -> showOverlay(client, "マイクアプリを起動してね"));
                LOGGER.warn("[KoeCraft] Voice control failed: {}", error.toString());
                return null;
            });
    }

    private String voiceToggleMessage(String body, int statusCode, boolean ok) {
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "{}" : body);
            if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (ok && root.has("mic_enabled") && root.get("mic_enabled").isJsonPrimitive()) {
                    return root.get("mic_enabled").getAsBoolean() ? "マイクON 聞いています" : "マイクOFF おやすみ中";
                }
                if (!ok && root.has("error") && root.get("error").isJsonPrimitive()) {
                    return "マイクを切り替えられませんでした";
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the generic HTTP status message.
        }
        return ok ? "マイクを切り替えました" : "マイクを切り替えられませんでした";
    }

    private VoiceHudStatus parseVoiceHudStatus(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "{}" : body);
            if (!parsed.isJsonObject()) {
                return VoiceHudStatus.unreachable();
            }
            JsonObject root = parsed.getAsJsonObject();
            boolean ok = booleanField(root, "ok", true);
            if (!ok) {
                return VoiceHudStatus.unreachable();
            }
            boolean micEnabled = booleanField(root, "mic_enabled", false);
            boolean micMuted = booleanField(root, "mic_muted", false);
            boolean recording = booleanField(root, "native_mic_recording", micEnabled);
            boolean recognizing = booleanField(root, "native_mic_auto_stop_in_flight", false);
            String voicePhase = stringField(root, "voice_phase", "");
            JsonObject status = root.has("native_mic_status") && root.get("native_mic_status").isJsonObject()
                ? root.getAsJsonObject("native_mic_status")
                : new JsonObject();
            boolean speaking = booleanField(status, "speech_active", false);
            boolean detectedSpeech = booleanField(status, "detected_speech", false);
            double rms = doubleField(status, "last_rms", 0.0D);
            double vadConfidence = doubleField(status, "vad_confidence", 0.0D);
            String vadProvider = stringField(status, "vad_provider", "");
            String vadFallback = stringField(status, "vad_fallback_reason", "");
            String voiceHint = voiceHint(rms, vadConfidence, vadFallback);
            if (micMuted || "muted".equals(voicePhase)) {
                return VoiceHudStatus.muted();
            }
            if ("executing".equals(voicePhase)) {
                return new VoiceHudStatus("こえ", "おねがいを実行中", 0xFF7DB8FF, true);
            }
            if ("recognizing".equals(voicePhase) || recognizing) {
                return new VoiceHudStatus("こえ", "ことばを確認中", 0xFFE7B84B, true);
            }
            if (recording && speaking) {
                return new VoiceHudStatus("こえ", "声が入っています " + voiceHint, 0xFFFF6B4A, true);
            }
            if (recording && detectedSpeech) {
                return new VoiceHudStatus("こえ", "話し終わりを待っています " + voiceHint, 0xFFFFB15C, true);
            }
            if (recording) {
                return new VoiceHudStatus("こえ", "聞いています " + voiceHint, 0xFF6EE7A8, true);
            }
            return VoiceHudStatus.off();
        } catch (RuntimeException ignored) {
            return VoiceHudStatus.unreachable();
        }
    }

    private VoiceHudStatus voiceHudStatusFromNative() {
        if (nativeVoiceLoop == null) {
            return VoiceHudStatus.off();
        }
        KoeCraftNativeVoiceLoop.VoiceLoopStatus nativeStatus = nativeVoiceLoop.status();
        KoeCraftNativeMicRecorder.MicStatus mic = nativeStatus.micStatus();
        double rms = mic.lastRms();
        String voiceHint = voiceHint(rms, mic.vadConfidence(), mic.vadFallbackReason());
        return switch (nativeStatus.phase()) {
            case OFF -> VoiceHudStatus.off();
            case MUTED -> VoiceHudStatus.muted();
            case ERROR -> new VoiceHudStatus("マイク", "うまく聞けません。設定を見てね", 0xFFFF5555, true);
            case RECOGNIZING -> new VoiceHudStatus("こえ", "ことばを確認中", 0xFFE7B84B, true);
            case EXECUTING -> new VoiceHudStatus("こえ", "おねがいを実行中", 0xFF7DB8FF, true);
            case LISTENING -> {
                if (mic.speechActive()) {
                    yield new VoiceHudStatus("こえ", "声が入っています " + voiceHint, 0xFFFF6B4A, true);
                }
                if (mic.detectedSpeech()) {
                    yield new VoiceHudStatus("こえ", "話し終わりを待っています " + voiceHint, 0xFFFFB15C, true);
                }
                yield new VoiceHudStatus("こえ", "聞いています " + voiceHint, 0xFF6EE7A8, true);
            }
        };
    }

    private static String voiceHint(double rms, double confidence, String fallbackReason) {
        String level = "音" + levelBars(rms);
        if (confidence <= 0.0D) {
            return level;
        }
        String fallback = fallbackReason == null || fallbackReason.isBlank() ? "" : " かんたん判定";
        return level + " 声らしさ" + Math.round(Math.max(0.0D, Math.min(1.0D, confidence)) * 100.0D) + "%" + fallback;
    }

    private void muteVoice(MinecraftClient client) {
        if (nativeVoiceLoop != null) {
            nativeVoiceLoop.toggleMuted();
            voiceHudStatus = voiceHudStatusFromNative();
            return;
        }
        postVoiceControl(client, VOICE_MUTE_URL, "minecraft_keybinding_mute", "マイクのミュートを切り替えました", "マイクのミュートを切り替えられませんでした");
    }

    private void stopVoice(MinecraftClient client) {
        if (nativeVoiceLoop != null) {
            nativeVoiceLoop.stop();
            voiceHudStatus = voiceHudStatusFromNative();
            return;
        }
        postVoiceControl(client, VOICE_OFF_URL, "minecraft_keybinding_stop", "マイクOFF おやすみ中", "マイクをOFFにできませんでした");
    }

    private static boolean booleanField(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsBoolean() : fallback;
    }

    private static double doubleField(JsonObject object, String key, double fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : fallback;
    }

    private static String stringField(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static String levelBars(double rms) {
        int bars = Math.max(0, Math.min(5, (int) Math.round(rms * 180.0D)));
        return "[" + "#".repeat(bars) + "-".repeat(5 - bars) + "]";
    }

    private void renderVoiceStatusHud(MinecraftClient client, DrawContext drawContext) {
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        VoiceHudStatus status = voiceHudStatus;
        if (nativeVoiceLoop != null) {
            status = voiceHudStatusFromNative();
            voiceHudStatus = status;
        }
        if (!status.visible()) {
            return;
        }
        int nextStatusY = renderHudCard(client, drawContext, 6, 6, status.title(), status.detail(), status.color(), 520, -1.0D);
        RecognizedHudStatus recognized = recognizedHudStatus;
        if (recognized.visible()) {
            nextStatusY = renderHudCard(client, drawContext, 6, nextStatusY, recognized.title(), recognized.detail(), recognized.color(), 520, -1.0D);
        }

        AgentHudStatus agent = agentHudStatus;
        if (agent.visible()) {
            renderHudCard(client, drawContext, 6, nextStatusY, agent.title(), agent.detail(), agent.color(), 520, agent.progress());
        }

        BubbleHudStatus bubble = bubbleHudStatus;
        if (bubble.visible()) {
            int maxWidth = Math.max(180, client.getWindow().getScaledWidth() - 40);
            int bubbleWidth = Math.max(160, Math.min(maxWidth, client.textRenderer.getWidth(bubble.label()) + 24));
            int x = (client.getWindow().getScaledWidth() - bubbleWidth) / 2;
            int y = Math.max(48, client.getWindow().getScaledHeight() / 4);
            drawContext.fill(x, y, x + bubbleWidth, y + 24, 0xCCFFFFFF);
            drawContext.fill(x + 10, y + 24, x + 22, y + 28, 0xCCFFFFFF);
            drawContext.drawText(client.textRenderer, bubble.label(), x + 12, y + 8, 0xFF202020, false);
        }

        ChoiceHudStatus choices = choiceHudStatus;
        if (choices.visible()) {
            renderChoiceHud(client, drawContext, choices);
        }

        WorldAssistHudStatus assist = worldAssistHudStatus;
        if (assist.visible()) {
            int windowWidth = client.getWindow().getScaledWidth();
            int labelWidth = client.textRenderer.getWidth(assist.label());
            int detailWidth = client.textRenderer.getWidth(assist.detail());
            int assistWidth = Math.max(190, Math.min(360, Math.max(labelWidth, detailWidth) + 26));
            int x = Math.max(6, windowWidth - assistWidth - 6);
            int y = 6;
            drawContext.fill(x, y, x + assistWidth, y + 38, 0xD809111B);
            drawContext.fill(x, y, x + 5, y + 38, assist.color());
            drawContext.drawText(client.textRenderer, assist.label(), x + 13, y + 7, assist.color(), false);
            drawContext.drawText(client.textRenderer, assist.detail(), x + 13, y + 21, 0xFFEFEFEF, false);
            int pulse = (int) ((System.currentTimeMillis() / 180L) % 5L);
            for (int i = 0; i < 5; i++) {
                int dotColor = i <= pulse ? assist.color() : 0x665E7188;
                drawContext.fill(x + assistWidth - 42 + i * 7, y + 31, x + assistWidth - 38 + i * 7, y + 34, dotColor);
            }
        }
    }

    private int renderHudCard(MinecraftClient client, DrawContext drawContext, int x, int y, String title, String detail, int color, int maxWidth, double progress) {
        String safeTitle = title == null || title.isBlank() ? "KoeCraft" : title;
        String safeDetail = detail == null ? "" : detail;
        int titleWidth = client.textRenderer.getWidth(safeTitle);
        int detailWidth = safeDetail.isBlank() ? 0 : client.textRenderer.getWidth(safeDetail);
        int width = Math.max(176, Math.min(maxWidth, Math.max(titleWidth, detailWidth) + 26));
        int height = safeDetail.isBlank() ? 22 : 36;
        if (progress >= 0.0D) {
            height += 6;
        }
        drawContext.fill(x, y, x + width, y + height, 0xD80B1220);
        drawContext.fill(x, y, x + 5, y + height, color);
        drawContext.drawText(client.textRenderer, safeTitle, x + 12, y + 7, color, false);
        if (!safeDetail.isBlank()) {
            drawContext.drawText(client.textRenderer, safeDetail, x + 12, y + 21, 0xFFEFEFEF, false);
        }
        if (progress >= 0.0D) {
            int barLeft = x + 12;
            int barRight = x + width - 12;
            int barY = y + height - 6;
            drawContext.fill(barLeft, barY, barRight, barY + 2, 0x445E7188);
            drawContext.fill(barLeft, barY, barLeft + (int) Math.round((barRight - barLeft) * Math.max(0.0D, Math.min(1.0D, progress))), barY + 2, color);
        }
        return y + height + 4;
    }

    private void showOverlay(MinecraftClient client, String message) {
        String overlayMessage = message;
        if (message != null && (message.startsWith("KoeCraft heard: ") || message.startsWith("KoeCraft recognized "))) {
            recognizedHudStatus = RecognizedHudStatus.from(message);
            overlayMessage = "きこえた: " + recognizedHudStatus.detail();
        }
        if (client.player != null) {
            client.player.sendMessage(Text.literal(overlayMessage), true);
        }
    }

    private void showAgentStatus(MinecraftClient client, String message) {
        if (message != null && message.startsWith("KoeCraft bridge recognized: ")) {
            recognizedHudStatus = RecognizedHudStatus.from(message);
            showOverlay(client, "きこえた: " + recognizedHudStatus.detail());
            return;
        }
        if (message != null && message.startsWith("[KoeCraft] [World Assist] ")) {
            String assist = message.replaceFirst("^\\[KoeCraft\\] \\[World Assist\\] ", "");
            worldAssistHudStatus = WorldAssistHudStatus.from(assist);
            showOverlay(client, "おたすけ: " + worldAssistHudStatus.detail());
            return;
        }
        if (message != null && message.startsWith("[KoeCraft] [KoeCraft Choices] ")) {
            String payload = message.replaceFirst("^\\[KoeCraft\\] \\[KoeCraft Choices\\] ", "");
            ChoiceHudStatus choices = ChoiceHudStatus.from(payload);
            choiceHudStatus = choices;
            showOverlay(client, choices.overlayText());
            speakAmbientBubble(choices.speechText(), true, "minecraft_intervention_choices");
            return;
        }
        if (message != null && message.startsWith("[KoeCraft] [KoeCraft Bubble] ")) {
            String bubble = message.replaceFirst("^\\[KoeCraft\\] \\[KoeCraft Bubble\\] ", "");
            bubbleHudStatus = BubbleHudStatus.from(bubble);
            showOverlay(client, bubble);
            speakAmbientBubble(bubble);
            return;
        }
        agentHudStatus = AgentHudStatus.from(message);
        showOverlay(client, agentHudStatus.title() + ": " + agentHudStatus.detail());
    }

    private void queueOrShowAgentStatus(MinecraftClient client, String message) {
        if (client.player == null) {
            pendingStatusMessage = message;
            return;
        }
        showAgentStatus(client, message);
    }

    private void speakAmbientBubble(String text) {
        speakAmbientBubble(text, false, "minecraft_ambient_chat");
    }

    private void speakAmbientBubble(String text, boolean force, String source) {
        if (voiceConfig == null || !voiceConfig.ttsEnabled() || voiceConfig.ttsUrl().isBlank()) {
            return;
        }
        String clean = text == null ? "" : text.trim();
        if (clean.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastTtsRequestMs < 900L) {
            return;
        }
        lastTtsRequestMs = now;
        String payload = "{\"text\":\"" + jsonEscape(clean) + "\",\"source\":\"" + jsonEscape(source) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(voiceConfig.ttsUrl()))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .exceptionally(error -> {
                LOGGER.warn("[KoeCraft] TTS request failed: {}", error.toString());
                return null;
            });
    }

    private String jsonEscape(String text) {
        return text == null ? "" : text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String taskDetail(String message) {
        String friendly = ExecutorStatusLabels.friendlyMessageText(message == null ? "" : message)
            .replaceFirst("^計画開始\\s*", "")
            .replaceFirst("^計画\\s*", "")
            .replaceFirst("^復旧中\\s*", "")
            .replaceFirst("^完了\\s*", "")
            .replaceFirst("^できた:\\s*", "")
            .replaceFirst("^途中までできた:\\s*", "")
            .replaceFirst("^できなかった:\\s*", "")
            .replace("steps", "ステップ")
            .replace("step", "ステップ")
            .trim();
        if (friendly.isBlank()) {
            return "少し待ってね";
        }
        return truncate(friendly, 58);
    }

    static String taskDetailForTesting(String message) {
        return taskDetail(message);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record VoiceHudStatus(String title, String detail, int color, boolean visible) {
        static VoiceHudStatus off() {
            return new VoiceHudStatus("マイク", "おやすみ中 Vでオン", 0xFF9A9A9A, true);
        }

        static VoiceHudStatus unreachable() {
            return new VoiceHudStatus("マイク", "マイクアプリを起動してね", 0xFFFF5555, true);
        }

        static VoiceHudStatus muted() {
            return new VoiceHudStatus("マイク", "ミュート中 Mで戻す", 0xFF9A9A9A, true);
        }
    }

    private record RecognizedHudStatus(String title, String detail, int color, long visibleUntilMs) {
        static RecognizedHudStatus hidden() {
            return new RecognizedHudStatus("", "", 0xFFE7B84B, 0L);
        }

        static RecognizedHudStatus from(String message) {
            String clean = message == null ? "" : message.trim()
                .replaceFirst("^KoeCraft bridge recognized:\\s*", "")
                .replaceFirst("^KoeCraft heard:\\s*", "")
                .replaceFirst("^KoeCraft recognized \\([^)]*\\):\\s*", "");
            clean = clean.isBlank() ? "もう一回いってね" : truncate(clean, 54);
            return new RecognizedHudStatus("きこえた", clean, 0xFFE7B84B, System.currentTimeMillis() + 10_000L);
        }

        boolean visible() {
            return !title.isBlank() && System.currentTimeMillis() <= visibleUntilMs;
        }
    }

    private record AgentHudStatus(String title, String detail, int color, long visibleUntilMs, double progress) {
        static AgentHudStatus hidden() {
            return new AgentHudStatus("", "", 0xFFFFFFFF, 0L, -1.0D);
        }

        static AgentHudStatus from(String message) {
            String clean = message == null ? "" : message.replace("[KoeCraft] ", "");
            int color = 0xFFEFEFEF;
            long durationMs = 6000L;
            double progress = progressFrom(clean);
            String title = "やること";
            if (clean.startsWith("計画開始")) {
                title = "じゅんび中";
                color = 0xFFE7B84B;
                durationMs = 9000L;
            } else if (clean.startsWith("計画") || clean.startsWith("実行中")) {
                title = "やってる";
                color = 0xFF7DB8FF;
                durationMs = 30_000L;
            } else if (clean.startsWith("復旧中") || clean.contains("recovery")) {
                title = "こまったので直しています";
                color = 0xFFFFB15C;
                durationMs = 30_000L;
            } else if (clean.startsWith("完了")) {
                title = "できた";
                color = 0xFF6EE7A8;
                durationMs = 5000L;
            } else if (clean.startsWith("途中までできた")) {
                title = "途中までできた";
                color = 0xFFFFB15C;
                durationMs = 9000L;
            } else if (clean.startsWith("停止") || clean.startsWith("拒否") || clean.startsWith("中断") || clean.startsWith("できなかった") || clean.startsWith("やめました") || clean.startsWith("とめました")) {
                title = "できませんでした";
                color = 0xFFFF6B4A;
                durationMs = 9000L;
            } else if (clean.contains("executor listening") || clean.contains("utterance HTTP listening")) {
                title = "準備OK";
                clean = "声で操作できます";
                color = 0xFF6EE7A8;
                durationMs = 5000L;
            }
            return new AgentHudStatus(title, taskDetail(clean), color, System.currentTimeMillis() + durationMs, progress);
        }

        private static double progressFrom(String message) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(message);
            if (!matcher.find()) {
                return -1.0D;
            }
            int current = Integer.parseInt(matcher.group(1));
            int total = Math.max(1, Integer.parseInt(matcher.group(2)));
            return Math.max(0.0D, Math.min(1.0D, current / (double) total));
        }

        private static String truncate(String text, int maxLength) {
            if (text == null || text.length() <= maxLength) {
                return text == null ? "" : text;
            }
            return text.substring(0, Math.max(0, maxLength - 3)) + "...";
        }

        boolean visible() {
            return !title.isBlank() && System.currentTimeMillis() <= visibleUntilMs;
        }
    }

    private record BubbleHudStatus(String label, long visibleUntilMs) {
        static BubbleHudStatus hidden() {
            return new BubbleHudStatus("", 0L);
        }

        static BubbleHudStatus from(String message) {
            String clean = message == null ? "" : message.replace("[KoeCraft Bubble] ", "").trim();
            if (clean.length() > 48) {
                clean = clean.substring(0, 48);
            }
            return new BubbleHudStatus(clean, System.currentTimeMillis() + 7000L);
        }

        boolean visible() {
            return !label.isBlank() && System.currentTimeMillis() <= visibleUntilMs;
        }
    }

    private void renderChoiceHud(MinecraftClient client, DrawContext drawContext, ChoiceHudStatus choices) {
        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();
        int width = Math.min(360, Math.max(230, windowWidth - 36));
        int x = (windowWidth - width) / 2;
        int y = Math.max(46, windowHeight / 4 - 8);
        long now = System.currentTimeMillis();
        long popElapsed = now - choices.popAtMs();
        if (popElapsed >= 0L) {
            renderChoicePop(drawContext, x + width / 2, y + 36, popElapsed);
            return;
        }
        drawContext.fill(x, y, x + width, y + 94, 0xEAF9FCFF);
        drawContext.fill(x + 10, y + 94, x + 24, y + 101, 0xEAF9FCFF);
        drawContext.fill(x, y, x + width, y + 5, 0xFF6EE7A8);
        drawContext.drawText(client.textRenderer, choices.title(), x + 14, y + 14, 0xFF102033, false);
        drawContext.drawText(client.textRenderer, "声でどれかを言ってね", x + width - 118, y + 14, 0xFF5E7188, false);
        int rowY = y + 34;
        for (int i = 0; i < choices.options().length; i++) {
            int optionY = rowY + i * 18;
            int stripe = switch (i) {
                case 0 -> 0xFF7DB8FF;
                case 1 -> 0xFFE7B84B;
                default -> 0xFFFFB15C;
            };
            drawContext.fill(x + 12, optionY - 2, x + width - 12, optionY + 14, 0x1A102033);
            drawContext.fill(x + 12, optionY - 2, x + 16, optionY + 14, stripe);
            drawContext.drawText(client.textRenderer, (i + 1) + ". " + choices.options()[i], x + 22, optionY + 2, 0xFF102033, false);
        }
        int progressWidth = Math.max(0, Math.min(width - 24, (int) ((choices.popAtMs() - now) * (width - 24) / Math.max(1L, choices.speechDurationMs()))));
        drawContext.fill(x + 12, y + 85, x + width - 12, y + 88, 0x335E7188);
        drawContext.fill(x + 12, y + 85, x + 12 + progressWidth, y + 88, 0xFF6EE7A8);
    }

    private void renderChoicePop(DrawContext drawContext, int centerX, int centerY, long popElapsed) {
        if (popElapsed > 720L) {
            return;
        }
        double t = popElapsed / 720.0D;
        int radius = 8 + (int) Math.round(t * 42.0D);
        int alpha = Math.max(0, 220 - (int) Math.round(t * 220.0D));
        int color = (alpha << 24) | 0x00F6D365;
        for (int i = 0; i < 14; i++) {
            double angle = (Math.PI * 2.0D * i) / 14.0D;
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int y = centerY + (int) Math.round(Math.sin(angle) * radius);
            int size = i % 3 == 0 ? 4 : 3;
            drawContext.fill(x - size, y - size, x + size, y + size, color);
        }
        int flash = (Math.max(0, 130 - (int) Math.round(t * 130.0D)) << 24) | 0x00FFFFFF;
        drawContext.fill(centerX - 22, centerY - 3, centerX + 22, centerY + 3, flash);
        drawContext.fill(centerX - 3, centerY - 22, centerX + 3, centerY + 22, flash);
    }

    private record ChoiceHudStatus(String title, String[] options, long createdAtMs, long popAtMs, long visibleUntilMs, long speechDurationMs) {
        static ChoiceHudStatus hidden() {
            return new ChoiceHudStatus("", new String[0], 0L, 0L, 0L, 1L);
        }

        static ChoiceHudStatus from(String payload) {
            String[] parts = (payload == null ? "" : payload).split("\\|");
            String title = parts.length > 0 && !parts[0].isBlank() ? parts[0].trim() : "ちょっとつまったよ";
            String[] options = new String[] {
                parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "右にずれて",
                parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : "手前を掘って",
                parts.length > 3 && !parts[3].isBlank() ? parts[3].trim() : "一回やめて"
            };
            long now = System.currentTimeMillis();
            long speechMs = Math.max(3200L, Math.min(7800L, 1800L + speechText(title, options).length() * 90L));
            return new ChoiceHudStatus(title, options, now, now + speechMs, now + speechMs + 760L, speechMs);
        }

        String speechText() {
            return speechText(title, options);
        }

        private static String speechText(String title, String[] options) {
            return title + "。選べるよ。"
                + "一つ目、" + options[0] + "。"
                + "二つ目、" + options[1] + "。"
                + "三つ目、" + options[2] + "。";
        }

        String overlayText() {
            return title + ": " + String.join(" / ", options);
        }

        boolean visible() {
            return !title.isBlank() && System.currentTimeMillis() <= visibleUntilMs;
        }
    }

    private record WorldAssistHudStatus(String label, String detail, int color, long visibleUntilMs) {
        static WorldAssistHudStatus hidden() {
            return new WorldAssistHudStatus("", "", 0xFF6EE7A8, 0L);
        }

        static WorldAssistHudStatus from(String message) {
            String clean = message == null ? "" : message.trim();
            String label = "おたすけ";
            String detail = clean;
            int split = clean.indexOf(": ");
            if (split > 0) {
                label = worldAssistLabel(clean.substring(0, split).trim());
                detail = clean.substring(split + 2).trim();
            }
            detail = truncate(ExecutorStatusLabels.friendlyMessageText(detail), 48);
            int color = clean.contains("blocked") || clean.contains("拒否") || clean.contains("失敗")
                ? 0xFFFFB15C
                : 0xFF6EE7A8;
            return new WorldAssistHudStatus(label.isBlank() ? "おたすけ" : label, detail, color, System.currentTimeMillis() + 6500L);
        }

        private static String worldAssistLabel(String label) {
            return switch (label) {
                case "素材補充" -> "材料を用意";
                case "作業台配置" -> "作業台を近くに置いた";
                case "クラフト高速化" -> "作るのを手伝った";
                case "クラフト補助" -> "作る準備を手伝った";
                default -> truncate(label == null || label.isBlank() ? "おたすけ" : label, 22);
            };
        }

        boolean visible() {
            return !label.isBlank() && System.currentTimeMillis() <= visibleUntilMs;
        }
    }
}
