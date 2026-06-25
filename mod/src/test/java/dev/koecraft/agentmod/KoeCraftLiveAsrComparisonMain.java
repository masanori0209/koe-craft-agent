package dev.koecraft.agentmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class KoeCraftLiveAsrComparisonMain {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WHISPER_MODEL = "whisper-1";

    private KoeCraftLiveAsrComparisonMain() {
    }

    public static void main(String[] args) throws Exception {
        Path scenarioPath = args.length > 0
            ? Path.of(args[0]).toAbsolutePath().normalize()
            : Path.of("../examples/asr_comparison_scenarios.json").toAbsolutePath().normalize();
        Path outputDir = args.length > 1
            ? Path.of(args[1]).toAbsolutePath().normalize()
            : Path.of("../logs/reports").toAbsolutePath().normalize();
        Path audioDir = args.length > 2
            ? Path.of(args[2]).toAbsolutePath().normalize()
            : Path.of("../logs/asr-live-audio").toAbsolutePath().normalize();
        Path repoRoot = args.length > 3
            ? Path.of(args[3]).toAbsolutePath().normalize()
            : scenarioPath.getParent().getParent();

        Files.createDirectories(outputDir);
        Files.createDirectories(audioDir);
        Map<String, String> env = loadEnvironment(repoRoot.resolve(".env"));
        String amivoiceKey = firstNonBlank(env.get("AMIVOICE_API_KEY"));
        String openaiKey = firstNonBlank(env.get("OPENAI_API_KEY"), env.get("LLM_API_KEY"));
        if (amivoiceKey.isBlank()) {
            throw new IllegalStateException("AMIVOICE_API_KEY is missing in environment or .env.");
        }
        if (openaiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY or LLM_API_KEY is missing in environment or .env.");
        }

        JsonArray source = JsonParser.parseString(Files.readString(scenarioPath)).getAsJsonArray();
        JsonArray live = new JsonArray();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        KoeCraftAmiVoiceRecognizer amiVoice = new KoeCraftAmiVoiceRecognizer(httpClient);
        KoeCraftVoiceConfig config = config(env, amivoiceKey, openaiKey, repoRoot);

        for (JsonElement element : source) {
            JsonObject scenario = element.getAsJsonObject().deepCopy();
            String id = string(scenario, "id");
            String utterance = string(scenario, "utterance");
            Path wav = audioDir.resolve(safeFileName(id) + ".wav");
            generateJapaneseWav(utterance, wav);
            double durationSec = wavDurationSeconds(wav);

            JsonObject asr = new JsonObject();
            JsonObject recognition = new JsonObject();
            RecognitionMeasurement ami = recognizeAmiVoice(amiVoice, config, wav, durationSec);
            RecognitionMeasurement whisper = recognizeWhisper(httpClient, openaiKey, wav, durationSec);
            asr.addProperty("amivoice", ami.text());
            asr.addProperty("whisper", whisper.text());
            recognition.add("amivoice", ami.toJson("AmiVoice " + config.amivoiceEngine(), audioDir, wav));
            recognition.add("whisper", whisper.toJson(WHISPER_MODEL, audioDir, wav));
            scenario.add("asr", asr);
            scenario.add("recognition", recognition);
            live.add(scenario);

            System.out.printf(
                Locale.ROOT,
                "[live-asr] %-34s audio=%.2fs amivoice=%dms whisper=%dms%n",
                id,
                durationSec,
                ami.latencyMs(),
                whisper.latencyMs()
            );
        }

        Path liveScenarioPath = outputDir.resolve("asr-live-recognitions.json");
        Files.writeString(liveScenarioPath, GSON.toJson(live));
        KoeCraftAsrComparisonReportMain.main(new String[] {liveScenarioPath.toString(), outputDir.toString()});
    }

    private static RecognitionMeasurement recognizeAmiVoice(
        KoeCraftAmiVoiceRecognizer recognizer,
        KoeCraftVoiceConfig config,
        Path wav,
        double durationSec
    ) {
        long start = System.nanoTime();
        try {
            KoeCraftAmiVoiceRecognizer.RecognitionResult result = recognizer
                .recognize(Files.readAllBytes(wav), "audio/wav", config)
                .get(60, TimeUnit.SECONDS);
            return new RecognitionMeasurement(result.text(), elapsedMs(start), durationSec, null, result.confidence());
        } catch (Exception error) {
            return new RecognitionMeasurement("", elapsedMs(start), durationSec, safeError(error), null);
        }
    }

    private static RecognitionMeasurement recognizeWhisper(HttpClient httpClient, String openaiKey, Path wav, double durationSec) {
        long start = System.nanoTime();
        try {
            String boundary = "KoeCraftOpenAiBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = openAiMultipartBody(boundary, wav);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + openaiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new RecognitionMeasurement("", elapsedMs(start), durationSec, "OpenAI HTTP " + response.statusCode(), null);
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return new RecognitionMeasurement(string(root, "text").trim(), elapsedMs(start), durationSec, null, null);
        } catch (Exception error) {
            return new RecognitionMeasurement("", elapsedMs(start), durationSec, safeError(error), null);
        }
    }

    private static byte[] openAiMultipartBody(String boundary, Path wav) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writePart(output, boundary, "model", WHISPER_MODEL);
            writePart(output, boundary, "language", "ja");
            writePart(output, boundary, "response_format", "json");
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + wav.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            output.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(Files.readAllBytes(wav));
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        }
    }

    private static void writePart(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void generateJapaneseWav(String utterance, Path wav) throws IOException, InterruptedException {
        Files.createDirectories(wav.getParent());
        Path aiff = wav.resolveSibling(wav.getFileName().toString().replaceFirst("\\.wav$", ".aiff"));
        run("say", "-v", "Kyoko", "-r", "185", "-o", aiff.toString(), utterance);
        run("afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff.toString(), wav.toString());
        Files.deleteIfExists(aiff);
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException(command[0] + " failed: " + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static double wavDurationSeconds(Path wav) throws IOException {
        byte[] bytes = Files.readAllBytes(wav);
        if (bytes.length < 44) {
            return 0.0D;
        }
        int offset = 12;
        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int dataSize = 0;
        while (offset + 8 <= bytes.length) {
            String chunk = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int dataOffset = offset + 8;
            if ("fmt ".equals(chunk) && dataOffset + 16 <= bytes.length) {
                channels = Short.toUnsignedInt(ByteBuffer.wrap(bytes, dataOffset + 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
                sampleRate = ByteBuffer.wrap(bytes, dataOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                bitsPerSample = Short.toUnsignedInt(ByteBuffer.wrap(bytes, dataOffset + 14, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
            } else if ("data".equals(chunk)) {
                dataSize = size;
            }
            offset = dataOffset + size + (size % 2);
        }
        int bytesPerSecond = sampleRate * Math.max(1, channels) * Math.max(1, bitsPerSample) / 8;
        return bytesPerSecond <= 0 ? 0.0D : dataSize / (double) bytesPerSecond;
    }

    private static KoeCraftVoiceConfig config(Map<String, String> env, String amivoiceKey, String openaiKey, Path repoRoot) {
        return new KoeCraftVoiceConfig(
            repoRoot.resolve("logs/reports/asr-live-config.properties"),
            amivoiceKey,
            firstNonBlank(env.get("AMIVOICE_ENDPOINT"), "https://acp-api.amivoice.com/v1/nolog/recognize"),
            firstNonBlank(env.get("AMIVOICE_WEBSOCKET_ENDPOINT"), "wss://acp-api.amivoice.com/v1/nolog/"),
            firstNonBlank(env.get("AMIVOICE_ENGINE"), "-a-general-input"),
            firstNonBlank(env.get("AMIVOICE_DICT_PATH"), repoRoot.resolve("data/amivoice/dict.txt").toString()),
            intValue(firstNonBlank(env.get("AMIVOICE_PROFILE_WORDS_LIMIT"), "200"), 200),
            "http",
            500,
            openaiKey,
            firstNonBlank(env.get("OPENAI_MODEL"), "gpt-4o-mini"),
            firstNonBlank(env.get("OPENAI_NORMALIZER_MODEL"), "gpt-5-nano"),
            true,
            true,
            true,
            "",
            0.004D,
            true,
            450,
            3.0D,
            0.0035D,
            true,
            "silero_onnx",
            "",
            0.50D,
            2,
            6,
            true,
            0.035D,
            3.0D,
            true,
            900,
            6000,
            true,
            "",
            false,
            "",
            false,
            true,
            8
        );
    }

    private static Map<String, String> loadEnvironment(Path dotenv) throws IOException {
        Map<String, String> values = new LinkedHashMap<>(System.getenv());
        if (!Files.exists(dotenv)) {
            return values;
        }
        for (String line : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int index = trimmed.indexOf('=');
            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.putIfAbsent(key, value);
        }
        return values;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static int intValue(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }

    private static String safeError(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static String safeFileName(String value) {
        String normalized = value == null ? "scenario" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return normalized.isBlank() ? "scenario" : normalized;
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private record RecognitionMeasurement(String text, long latencyMs, double audioDurationSec, String error, Double confidence) {
        private JsonObject toJson(String model, Path audioDir, Path wav) {
            JsonObject json = new JsonObject();
            json.addProperty("model", model);
            json.addProperty("latency_ms", latencyMs);
            json.addProperty("audio_duration_sec", audioDurationSec);
            json.addProperty("audio_file", audioDir.relativize(wav).toString());
            if (confidence != null) {
                json.addProperty("confidence", confidence);
            }
            if (error != null && !error.isBlank()) {
                json.addProperty("error", error);
            }
            return json;
        }
    }
}
