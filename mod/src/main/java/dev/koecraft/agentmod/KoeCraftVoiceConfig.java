package dev.koecraft.agentmod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

public record KoeCraftVoiceConfig(
    Path path,
    String amivoiceApiKey,
    String amivoiceEndpoint,
    String amivoiceWebSocketEndpoint,
    String amivoiceEngine,
    String amivoiceDictPath,
    int amivoiceProfileWordsLimit,
    String amivoiceTransport,
    int amivoiceResultUpdatedIntervalMillis,
    String openaiApiKey,
    String openaiModel,
    String openaiNormalizerModel,
    boolean openaiNormalizerEnabled,
    boolean llmFallbackEnabled,
    boolean nativeMicEnabled,
    String micMixerName,
    double speechRmsThreshold,
    boolean adaptiveNoiseEnabled,
    int adaptiveNoiseWarmupMillis,
    double adaptiveNoiseMultiplier,
    double adaptiveNoiseMinThreshold,
    boolean vadEnabled,
    String vadProvider,
    String vadModelPath,
    double vadConfidenceThreshold,
    int vadMinSpeechFrames,
    int vadHangoverFrames,
    boolean pcmNormalizeEnabled,
    double pcmNormalizeTargetRms,
    double pcmNormalizeMaxGain,
    boolean pcmSoftClipEnabled,
    int silenceMillis,
    int maxRecordingMillis,
    boolean keepListeningOnError,
    String agentUtteranceUrl,
    boolean ttsEnabled,
    String ttsUrl,
    boolean childModeEnabled,
    boolean childModeMaterialAssist,
    int childModeShelterMaterialTarget
) {
    private static final String FILE_NAME = "koecraft-agent.properties";

    public static KoeCraftVoiceConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = new Properties();
        ensureTemplate(path);
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException ignored) {
            // Fall back to environment/default values when the config file is unreadable.
        }

        return new KoeCraftVoiceConfig(
            path,
            secret(properties, "amivoice.apiKey", "AMIVOICE_API_KEY"),
            stringProp(properties, "amivoice.endpoint", "https://acp-api.amivoice.com/v1/nolog/recognize"),
            stringProp(properties, "amivoice.websocketEndpoint", ""),
            stringProp(properties, "amivoice.engine", "-a-general-input"),
            stringProp(properties, "amivoice.dictPath", "../data/amivoice/dict.txt"),
            intProp(properties, "amivoice.profileWordsLimit", 200, 0, 1000),
            normalizeAmivoiceTransport(stringProp(properties, "amivoice.transport", stringProp(properties, "voice.transport", "websocket"))),
            intProp(properties, "amivoice.resultUpdatedIntervalMillis", 500, 100, 5000),
            secret(properties, "openai.apiKey", "OPENAI_API_KEY"),
            stringProp(properties, "openai.model", "gpt-4o-mini"),
            stringProp(properties, "openai.normalizer.model", "gpt-5-nano"),
            booleanProp(properties, "openai.normalizer.enabled", true),
            booleanProp(properties, "openai.fallbackEnabled", true),
            booleanProp(properties, "voice.nativeMicEnabled", true),
            stringProp(properties, "voice.micMixerName", ""),
            doubleProp(properties, "voice.speechRmsThreshold", 0.004D, 0.001D, 0.08D),
            booleanProp(properties, "voice.adaptiveNoise.enabled", true),
            intProp(properties, "voice.adaptiveNoise.warmupMillis", 450, 100, 2000),
            doubleProp(properties, "voice.adaptiveNoise.multiplier", 3.0D, 1.2D, 8.0D),
            doubleProp(properties, "voice.adaptiveNoise.minThreshold", 0.0035D, 0.001D, 0.08D),
            booleanProp(properties, "voice.vad.enabled", true),
            normalizeVadProvider(stringProp(properties, "voice.vad.provider", "silero_onnx")),
            stringProp(properties, "voice.vad.modelPath", ""),
            doubleProp(properties, "voice.vad.confidenceThreshold", 0.50D, 0.05D, 0.95D),
            intProp(properties, "voice.vad.minSpeechFrames", 2, 1, 10),
            intProp(properties, "voice.vad.hangoverFrames", 6, 0, 30),
            booleanProp(properties, "voice.pcmNormalize.enabled", true),
            doubleProp(properties, "voice.pcmNormalize.targetRms", 0.035D, 0.005D, 0.2D),
            doubleProp(properties, "voice.pcmNormalize.maxGain", 3.0D, 1.0D, 8.0D),
            booleanProp(properties, "voice.pcmSoftClip.enabled", true),
            intProp(properties, "voice.silenceMillis", 900, 250, 10000),
            intProp(properties, "voice.maxRecordingMillis", 6000, 1000, 60000),
            booleanProp(properties, "voice.keepListeningOnError", true),
            stringProp(properties, "agent.utteranceUrl", ""),
            booleanProp(properties, "tts.enabled", true),
            stringProp(properties, "tts.url", "http://127.0.0.1:8790/api/speak"),
            booleanProp(properties, "childMode.enabled", false),
            booleanProp(properties, "childMode.materialAssist", true),
            intProp(properties, "childMode.shelterMaterialTarget", 8, 4, 24)
        );
    }

    public boolean amivoiceConfigured() {
        return !amivoiceApiKey.isBlank();
    }

    public boolean openaiConfigured() {
        return !openaiApiKey.isBlank();
    }

    public boolean amivoiceWebSocketEnabled() {
        return !"http".equalsIgnoreCase(amivoiceTransport);
    }

    public String effectiveAmivoiceWebSocketEndpoint() {
        if (!amivoiceWebSocketEndpoint.isBlank()) {
            return amivoiceWebSocketEndpoint;
        }
        if (amivoiceEndpoint.contains("/nolog/")) {
            return "wss://acp-api.amivoice.com/v1/nolog/";
        }
        return "wss://acp-api.amivoice.com/v1/";
    }

    private static void ensureTemplate(Path path) {
        if (Files.exists(path)) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Properties template = new Properties();
            template.setProperty("amivoice.apiKey", "");
            template.setProperty("amivoice.endpoint", "https://acp-api.amivoice.com/v1/nolog/recognize");
            template.setProperty("amivoice.websocketEndpoint", "");
            template.setProperty("amivoice.engine", "-a-general-input");
            template.setProperty("amivoice.dictPath", "../data/amivoice/dict.txt");
            template.setProperty("amivoice.profileWordsLimit", "200");
            template.setProperty("amivoice.transport", "websocket");
            template.setProperty("amivoice.resultUpdatedIntervalMillis", "500");
            template.setProperty("openai.apiKey", "");
            template.setProperty("openai.model", "gpt-4o-mini");
            template.setProperty("openai.normalizer.model", "gpt-5-nano");
            template.setProperty("openai.normalizer.enabled", "true");
            template.setProperty("openai.fallbackEnabled", "true");
            template.setProperty("voice.nativeMicEnabled", "true");
            template.setProperty("voice.micMixerName", "");
            template.setProperty("voice.speechRmsThreshold", "0.004");
            template.setProperty("voice.adaptiveNoise.enabled", "true");
            template.setProperty("voice.adaptiveNoise.warmupMillis", "450");
            template.setProperty("voice.adaptiveNoise.multiplier", "3.0");
            template.setProperty("voice.adaptiveNoise.minThreshold", "0.0035");
            template.setProperty("voice.vad.enabled", "true");
            template.setProperty("voice.vad.provider", "silero_onnx");
            template.setProperty("voice.vad.modelPath", "");
            template.setProperty("voice.vad.confidenceThreshold", "0.50");
            template.setProperty("voice.vad.minSpeechFrames", "2");
            template.setProperty("voice.vad.hangoverFrames", "6");
            template.setProperty("voice.pcmNormalize.enabled", "true");
            template.setProperty("voice.pcmNormalize.targetRms", "0.035");
            template.setProperty("voice.pcmNormalize.maxGain", "3.0");
            template.setProperty("voice.pcmSoftClip.enabled", "true");
            template.setProperty("voice.silenceMillis", "900");
            template.setProperty("voice.maxRecordingMillis", "6000");
            template.setProperty("voice.keepListeningOnError", "true");
            template.setProperty("agent.utteranceUrl", "");
            template.setProperty("tts.enabled", "true");
            template.setProperty("tts.url", "http://127.0.0.1:8790/api/speak");
            template.setProperty("childMode.enabled", "false");
            template.setProperty("childMode.materialAssist", "true");
            template.setProperty("childMode.shelterMaterialTarget", "8");
            try (OutputStream output = Files.newOutputStream(path)) {
                template.store(output, "KoeCraft Agent local voice/LLM settings. Do not commit this file.");
            }
        } catch (IOException ignored) {
            // A missing template should not prevent the executor from starting.
        }
    }

    private static String secret(Properties properties, String key, String envKey) {
        String fromFile = stringProp(properties, key, "");
        if (!fromFile.isBlank()) {
            return fromFile;
        }
        String fromEnv = System.getenv(envKey);
        return fromEnv == null ? "" : fromEnv.trim();
    }

    private static String stringProp(Properties properties, String key, String fallback) {
        return properties.getProperty(key, fallback).trim();
    }

    private static String normalizeAmivoiceTransport(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("http") || normalized.equals("http_wav")) {
            return "http";
        }
        return "websocket";
    }

    private static String normalizeVadProvider(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("off") || normalized.equals("none") || normalized.equals("rms")) {
            return "rms";
        }
        if (normalized.equals("silero") || normalized.equals("silero_onnx")) {
            return "silero_onnx";
        }
        return "silero_onnx";
    }

    private static boolean booleanProp(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static int intProp(Properties properties, String key, int fallback, int min, int max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(Integer.parseInt(raw.trim()), max));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleProp(Properties properties, String key, double fallback, double min, double max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(Double.parseDouble(raw.trim()), max));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
