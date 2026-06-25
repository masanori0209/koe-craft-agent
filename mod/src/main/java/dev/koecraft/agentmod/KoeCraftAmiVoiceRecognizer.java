package dev.koecraft.agentmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class KoeCraftAmiVoiceRecognizer {
    private final HttpClient httpClient;

    KoeCraftAmiVoiceRecognizer(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    CompletableFuture<RecognitionResult> recognize(byte[] audio, String contentType, KoeCraftVoiceConfig config) {
        if (!config.amivoiceConfigured()) {
            return CompletableFuture.failedFuture(new IllegalStateException("AMIVOICE_API_KEY is not configured."));
        }
        if (audio.length == 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Audio payload is empty."));
        }

        String boundary = "KoeCraftBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, audio, contentType, config);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.amivoiceEndpoint()))
            .header("content-type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> parseResponse(response.statusCode(), response.body()));
    }

    private byte[] multipartBody(String boundary, byte[] audio, String contentType, KoeCraftVoiceConfig config) {
        byte[] delimiter = ("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] close = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        try (var output = new java.io.ByteArrayOutputStream()) {
            output.write(delimiter);
            writeTextPart(output, "u", config.amivoiceApiKey());
            output.write(delimiter);
            writeTextPart(output, "d", buildDParameter(config));
            output.write(delimiter);
            output.write(("Content-Disposition: form-data; name=\"a\"; filename=\"koecraft-utterance.wav\"\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Type: " + (contentType.isBlank() ? "audio/wav" : contentType) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(audio);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(close);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to build AmiVoice multipart request.", error);
        }
    }

    private void writeTextPart(java.io.ByteArrayOutputStream output, String name, String value) throws IOException {
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String buildDParameter(KoeCraftVoiceConfig config) {
        StringBuilder builder = new StringBuilder("grammarFileNames=")
            .append(URLEncoder.encode(config.amivoiceEngine(), StandardCharsets.UTF_8));
        String profileWords = KoeCraftAmiVoiceProfileWords.load(config);
        if (!profileWords.isBlank()) {
            builder.append(" profileWords=").append(URLEncoder.encode(profileWords, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private RecognitionResult parseResponse(int statusCode, String body) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "{}" : body);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("AmiVoice returned a non-JSON response: HTTP " + statusCode);
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IllegalStateException("AmiVoice returned a non-JSON response: HTTP " + statusCode, error);
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("AmiVoice HTTP " + statusCode + ": " + stringField(root, "message", "request failed"));
        }
        String code = stringField(root, "code", "");
        if (!code.isBlank()) {
            throw new IllegalStateException("AmiVoice recognition failed: " + code + " " + stringField(root, "message", ""));
        }

        String text = stringField(root, "text", "").trim();
        Double confidence = null;
        if (text.isBlank() && root.has("results") && root.get("results").isJsonArray() && !root.getAsJsonArray("results").isEmpty()) {
            JsonElement first = root.getAsJsonArray("results").get(0);
            if (first.isJsonObject()) {
                JsonObject result = first.getAsJsonObject();
                text = stringField(result, "text", "").trim();
                if (result.has("confidence") && result.get("confidence").isJsonPrimitive()) {
                    confidence = result.get("confidence").getAsDouble();
                }
            }
        }
        if (text.isBlank()) {
            throw new IllegalStateException("AmiVoice returned an empty recognition result.");
        }
        return new RecognitionResult(text, confidence, root);
    }

    private static String stringField(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    record RecognitionResult(String text, Double confidence, JsonObject raw) {
    }
}
