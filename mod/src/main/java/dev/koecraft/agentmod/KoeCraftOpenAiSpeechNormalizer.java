package dev.koecraft.agentmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class KoeCraftOpenAiSpeechNormalizer {
    private static final URI CHAT_COMPLETIONS_URI = URI.create("https://api.openai.com/v1/chat/completions");
    private final HttpClient httpClient;

    KoeCraftOpenAiSpeechNormalizer(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    CompletableFuture<Optional<Result>> normalize(String text, KoeCraftVoiceConfig config) {
        if (!config.openaiNormalizerEnabled() || !config.openaiConfigured() || text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest request = HttpRequest.newBuilder(CHAT_COMPLETIONS_URI)
            .header("authorization", "Bearer " + config.openaiApiKey())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody(text, config), StandardCharsets.UTF_8))
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> parseResponse(response.statusCode(), response.body()))
            .thenApply(this::normalizeResult);
    }

    private String requestBody(String text, KoeCraftVoiceConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.openaiNormalizerModel());
        if (!config.openaiNormalizerModel().startsWith("gpt-5")) {
            root.addProperty("temperature", 0);
        }
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        root.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt()));
        JsonObject user = new JsonObject();
        user.addProperty("recognized_text", text);
        messages.add(message("user", user.toString()));
        root.add("messages", messages);
        return root.toString();
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private JsonObject parseResponse(int statusCode, String body) {
        JsonObject root = JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
        if (statusCode < 200 || statusCode >= 300) {
            String message = root.has("error") && root.get("error").isJsonObject()
                ? string(root.getAsJsonObject("error"), "message")
                : "request failed";
            throw new IllegalStateException("OpenAI speech normalizer failed: HTTP " + statusCode + " " + message);
        }
        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray() ? root.getAsJsonArray("choices") : new JsonArray();
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw new IllegalStateException("OpenAI speech normalizer returned no choices.");
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = first.has("message") && first.get("message").isJsonObject() ? first.getAsJsonObject("message") : new JsonObject();
        String content = string(message, "content");
        if (content.isBlank()) {
            throw new IllegalStateException("OpenAI speech normalizer returned empty content.");
        }
        JsonElement parsed = JsonParser.parseString(content);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private Optional<Result> normalizeResult(JsonObject raw) {
        String normalizedText = sanitizeText(string(raw, "normalized_text"));
        if (normalizedText.isBlank()) {
            return Optional.empty();
        }
        String intentHint = sanitizeHint(string(raw, "intent_hint"));
        double confidence = boundedDouble(raw, "confidence", 0.0D, 0.0D, 1.0D);
        return Optional.of(new Result(normalizedText, intentHint, confidence));
    }

    Optional<Result> normalizeResultForTesting(JsonObject raw) {
        return normalizeResult(raw);
    }

    private String systemPrompt() {
        return """
            You are a Japanese ASR post-correction layer for a Minecraft Java voice-control agent.
            Return one compact JSON object only:
            {"normalized_text":"...", "intent_hint":"...", "confidence":0.0}

            You may correct speech recognition mistakes only when Minecraft context makes the correction likely.
            Do not create a task that is not present in the utterance.
            Do not output Minecraft commands. Do not output action DSL. Do not solve recipes.
            Keep normalized_text in Japanese unless the user clearly used English.
            intent_hint must be one of the allowed values exactly. Never output minecraft_command.

            Minecraft ASR hints:
            - "ハッシュをかけて" near 前/橋/架ける/かける usually means "橋を架けて".
            - "きのうつるはし" before 作って usually means "木のつるはし".
            - A sentence ending only as "です" after 村/探す is likely a partial recognition of "村を探して".

            Good corrections:
            "前にハッシュをかけて" -> "前に橋を架けて", intent_hint "build_bridge"
            "きのうつるはし作って" -> "木のつるはし作って", intent_hint "craft_item"
            "村を探すがです" -> "村を探して", intent_hint "search_structure"
            "まっすぐ" -> "まっすぐ歩いて", intent_hint "move"

            If the text is already clear, return it unchanged with high confidence.
            If it is unrelated to Minecraft or too ambiguous, return it unchanged with intent_hint "unknown" and low confidence.
            Allowed intent_hint values:
            place_light, craft_item, collect_block, pickup_items, move, dig_pattern, build_bridge,
            build_shelter, build_structure, get_food, attack_entity, search_structure, context_action,
            celebrate, ambient_chat, close_screen, abort, unknown.
            """;
    }

    private String sanitizeText(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return compact.length() > 80 ? compact.substring(0, 80) : compact;
    }

    private String sanitizeHint(String value) {
        return switch (value) {
            case "place_light", "craft_item", "collect_block", "pickup_items", "move", "dig_pattern",
                "build_bridge", "build_shelter", "build_structure", "get_food", "attack_entity",
                "search_structure", "context_action", "celebrate", "ambient_chat", "close_screen", "abort" -> value;
            default -> "unknown";
        };
    }

    private double boundedDouble(JsonObject object, String key, double fallback, double min, double max) {
        if (object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()) {
            return Math.max(min, Math.min(object.get(key).getAsDouble(), max));
        }
        return Math.max(min, Math.min(fallback, max));
    }

    private String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    record Result(String normalizedText, String intentHint, double confidence) {
    }
}
