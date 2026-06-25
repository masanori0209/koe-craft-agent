package dev.koecraft.agentmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class KoeCraftAmiVoiceStreamingRecognizer {
    private static final int START_TIMEOUT_SECONDS = 10;
    private static final int FINAL_TIMEOUT_SECONDS = 12;
    private final HttpClient httpClient;
    private final Consumer<String> partialSink;

    KoeCraftAmiVoiceStreamingRecognizer(HttpClient httpClient, Consumer<String> partialSink) {
        this.httpClient = httpClient;
        this.partialSink = partialSink == null ? ignored -> {} : partialSink;
    }

    CompletableFuture<StreamingResult> recognizeFromMic(KoeCraftNativeMicRecorder recorder, KoeCraftVoiceConfig config) {
        if (!config.amivoiceConfigured()) {
            return CompletableFuture.failedFuture(new IllegalStateException("AMIVOICE_API_KEY is not configured."));
        }

        StreamingListener listener = new StreamingListener();
        return httpClient.newWebSocketBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .buildAsync(URI.create(config.effectiveAmivoiceWebSocketEndpoint()), listener)
            .thenCompose(socket -> {
                StreamingSession session = new StreamingSession(socket, listener);
                return session.start(config)
                    .thenCompose(ignored -> recorder.streamPcmUntilSilence(config, (buffer, length) -> {
                        session.sendPcm(buffer, length);
                        if (session.shouldStopForEarlyShortCommand()) {
                            recorder.requestStop();
                        }
                    }))
                    .thenCompose(capture -> {
                        if (!capture.detectedSpeech()) {
                            return session.closeWithoutResult()
                                .thenApply(ignored -> StreamingResult.noSpeech(capture));
                        }
                        return session.finishPreferEarly()
                            .thenApply(result -> new StreamingResult(true, capture.streamedBytes(), capture.maxRms(), result.text(), result.confidence(), result.raw()));
                    })
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            session.abort();
                        }
                    });
            });
    }

    private String buildStartCommand(KoeCraftVoiceConfig config) {
        StringBuilder command = new StringBuilder("s LSB16K ")
            .append(config.amivoiceEngine())
            .append(" authorization=")
            .append(config.amivoiceApiKey())
            .append(" resultUpdatedInterval=")
            .append(config.amivoiceResultUpdatedIntervalMillis());
        String profileWords = KoeCraftAmiVoiceProfileWords.load(config);
        if (!profileWords.isBlank()) {
            command.append(" profileWords=\"")
                .append(profileWords.replace("\"", "\"\""))
                .append("\"");
        }
        return command.toString();
    }

    record StreamingResult(
        boolean detectedSpeech,
        int streamedBytes,
        double maxRms,
        String text,
        Double confidence,
        JsonObject raw
    ) {
        static StreamingResult noSpeech(KoeCraftNativeMicRecorder.PcmStreamCapture capture) {
            return new StreamingResult(false, capture.streamedBytes(), capture.maxRms(), "", null, new JsonObject());
        }
    }

    private final class StreamingSession {
        private final WebSocket socket;
        private final StreamingListener listener;

        private StreamingSession(WebSocket socket, StreamingListener listener) {
            this.socket = socket;
            this.listener = listener;
        }

        CompletableFuture<Void> start(KoeCraftVoiceConfig config) {
            socket.sendText(buildStartCommand(config), true);
            return listener.started.orTimeout(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void sendPcm(byte[] buffer, int length) {
            byte[] frame = new byte[length + 1];
            frame[0] = 'p';
            System.arraycopy(buffer, 0, frame, 1, length);
            socket.sendBinary(ByteBuffer.wrap(frame), true).toCompletableFuture().join();
        }

        CompletableFuture<KoeCraftAmiVoiceRecognizer.RecognitionResult> finish() {
            socket.sendText("e", true);
            return listener.finalResult
                .orTimeout(FINAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> close());
        }

        CompletableFuture<KoeCraftAmiVoiceRecognizer.RecognitionResult> finishPreferEarly() {
            socket.sendText("e", true);
            if (listener.earlyShortCommand.isDone()) {
                return listener.earlyShortCommand.whenComplete((ignored, error) -> close());
            }
            return listener.finalResult
                .orTimeout(FINAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionallyCompose(error -> listener.earlyShortCommand.isDone()
                    ? listener.earlyShortCommand
                    : CompletableFuture.failedFuture(error))
                .whenComplete((ignored, error) -> close());
        }

        boolean shouldStopForEarlyShortCommand() {
            return listener.earlyShortCommand.isDone();
        }

        CompletableFuture<Void> closeWithoutResult() {
            socket.sendText("e", true);
            return listener.ended
                .completeOnTimeout(null, 3, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> close());
        }

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }

        void abort() {
            socket.abort();
        }
    }

    private final class StreamingListener implements WebSocket.Listener {
        private final CompletableFuture<Void> started = new CompletableFuture<>();
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private final CompletableFuture<KoeCraftAmiVoiceRecognizer.RecognitionResult> finalResult = new CompletableFuture<>();
        private final CompletableFuture<KoeCraftAmiVoiceRecognizer.RecognitionResult> earlyShortCommand = new CompletableFuture<>();
        private final AtomicReference<String> textBuffer = new AtomicReference<>("");
        private final AtomicReference<String> latestPartial = new AtomicReference<>("");

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.updateAndGet(previous -> previous + data);
            if (last) {
                String message = textBuffer.getAndSet("");
                handleMessage(message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            started.completeExceptionally(error);
            ended.completeExceptionally(error);
            finalResult.completeExceptionally(error);
            earlyShortCommand.completeExceptionally(error);
        }

        private void handleMessage(String message) {
            if (message == null || message.isBlank()) {
                return;
            }
            char event = message.charAt(0);
            String content = message.length() > 2 ? message.substring(2).trim() : "";
            if (event == 's') {
                if (content.isBlank()) {
                    started.complete(null);
                } else {
                    started.completeExceptionally(new IOException("AmiVoice WebSocket start failed: " + content));
                }
                return;
            }
            if (event == 'U' || event == 'R') {
                KoeCraftAmiVoiceRecognizer.RecognitionResult result = parseRecognitionJson(content);
                if (!result.text().isBlank()) {
                    latestPartial.set(result.text());
                    partialSink.accept(result.text());
                    if (KoeCraftShortCommandMode.canEarlyAcceptPartial(result.text())) {
                        earlyShortCommand.complete(result);
                    }
                }
                return;
            }
            if (event == 'A') {
                KoeCraftAmiVoiceRecognizer.RecognitionResult result = parseRecognitionJson(content);
                if (!result.text().isBlank()) {
                    finalResult.complete(result);
                }
                return;
            }
            if (event == 'e') {
                ended.complete(null);
                if (!finalResult.isDone()) {
                    if (earlyShortCommand.isDone() && !earlyShortCommand.isCompletedExceptionally()) {
                        finalResult.complete(earlyShortCommand.join());
                    } else if (!latestPartial.get().isBlank()) {
                        JsonObject raw = new JsonObject();
                        raw.addProperty("text", latestPartial.get());
                        finalResult.complete(new KoeCraftAmiVoiceRecognizer.RecognitionResult(latestPartial.get(), null, raw));
                    } else {
                        finalResult.completeExceptionally(new IllegalStateException("AmiVoice WebSocket returned no final recognition result."));
                    }
                }
            }
        }

        private KoeCraftAmiVoiceRecognizer.RecognitionResult parseRecognitionJson(String content) {
            JsonObject root;
            try {
                JsonElement parsed = JsonParser.parseString(content == null || content.isBlank() ? "{}" : content);
                root = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (RuntimeException error) {
                root = new JsonObject();
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
            return new KoeCraftAmiVoiceRecognizer.RecognitionResult(text, confidence, root);
        }

        private static String stringField(JsonObject object, String key, String fallback) {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
        }
    }
}
