package dev.koecraft.agentmod;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

final class KoeCraftNativeVoiceLoop implements AutoCloseable {
    private final KoeCraftVoiceConfig config;
    private final HttpClient httpClient;
    private final KoeCraftNativeMicRecorder recorder;
    private final KoeCraftAmiVoiceRecognizer recognizer;
    private final KoeCraftAmiVoiceStreamingRecognizer streamingRecognizer;
    private final KoeCraftRecognizedTextProcessor textProcessor;
    private final MinecraftClient client;
    private final Consumer<String> overlaySink;
    private final Logger logger;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);
    private volatile VoiceLoopStatus status = VoiceLoopStatus.off();
    private volatile CompletableFuture<?> activeCycle = CompletableFuture.completedFuture(null);

    KoeCraftNativeVoiceLoop(
        KoeCraftVoiceConfig config,
        HttpClient httpClient,
        SurvivalActionExecutor executor,
        MinecraftClient client,
        Consumer<String> overlaySink,
        Logger logger
    ) {
        this.config = config;
        this.httpClient = httpClient;
        this.client = client;
        this.overlaySink = overlaySink;
        this.logger = logger;
        this.recorder = new KoeCraftNativeMicRecorder();
        this.recognizer = new KoeCraftAmiVoiceRecognizer(httpClient);
        this.streamingRecognizer = new KoeCraftAmiVoiceStreamingRecognizer(httpClient, partial -> show("KoeCraft hearing: " + partial));
        this.textProcessor = new KoeCraftRecognizedTextProcessor(config, httpClient, executor, client, overlaySink, logger);
    }

    void toggle() {
        if (enabled.get()) {
            stop();
        } else {
            start();
        }
    }

    void start() {
        if (!config.amivoiceConfigured()) {
            show("KoeCraft native voice needs AMIVOICE_API_KEY");
            status = VoiceLoopStatus.error("missing_amivoice_key", recorder.status());
            return;
        }
        if (!enabled.compareAndSet(false, true)) {
            muted.set(false);
            status = VoiceLoopStatus.listening(recorder.status());
            show("KoeCraft native mic UNMUTED");
            return;
        }
        muted.set(false);
        status = VoiceLoopStatus.listening(recorder.status());
        show("KoeCraft native mic ON");
        runNextCycle();
    }

    void stop() {
        enabled.set(false);
        muted.set(false);
        recorder.requestStop();
        status = VoiceLoopStatus.off();
        show("KoeCraft native mic OFF");
    }

    void toggleMuted() {
        if (!enabled.get()) {
            enabled.set(true);
            muted.set(true);
            recorder.requestStop();
            status = VoiceLoopStatus.muted(recorder.status());
            show("KoeCraft native mic MUTED");
            return;
        }
        boolean mutedNow = !muted.get();
        muted.set(mutedNow);
        if (mutedNow) {
            recorder.requestStop();
            status = VoiceLoopStatus.muted(recorder.status());
            show("マイクをミュートしました");
            return;
        }
        status = VoiceLoopStatus.listening(recorder.status());
        show("マイクのミュートを解除しました");
        runNextCycle();
    }

    VoiceLoopStatus status() {
        KoeCraftNativeMicRecorder.MicStatus mic = recorder.status();
        if (enabled.get() && muted.get()) {
            return VoiceLoopStatus.muted(mic);
        }
        if (status.phase() == Phase.LISTENING) {
            return VoiceLoopStatus.listening(mic);
        }
        return status.withMicStatus(mic);
    }

    private void runNextCycle() {
        if (!enabled.get() || muted.get()) {
            return;
        }
        if (config.amivoiceWebSocketEnabled()) {
            runNextStreamingCycle();
        } else {
            runNextHttpCycle();
        }
    }

    private void runNextHttpCycle() {
        if (!enabled.get() || muted.get()) {
            return;
        }
        status = VoiceLoopStatus.listening(recorder.status());
        activeCycle = recorder.recordUntilSilence(config)
            .thenCompose(recording -> {
                if (!enabled.get() || muted.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!recording.detectedSpeech()) {
                    show("うまく聞こえませんでした。もう一回いってね");
                    return CompletableFuture.completedFuture(null);
                }
                status = VoiceLoopStatus.recognizing(recorder.status());
                show("ことばを確認中");
                return recognizer.recognize(recording.audio(), recording.contentType(), config)
                    .thenCompose(recognized -> handleRecognizedText(recognized, "http"));
            })
            .whenComplete(this::completeCycle);
    }

    private void runNextStreamingCycle() {
        if (!enabled.get() || muted.get()) {
            return;
        }
        status = VoiceLoopStatus.listening(recorder.status());
        show("聞いています");
        activeCycle = streamingRecognizer.recognizeFromMic(recorder, config)
            .thenCompose(recognized -> {
                if (!enabled.get() || muted.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!recognized.detectedSpeech()) {
                    show("うまく聞こえませんでした。もう一回いってね");
                    return CompletableFuture.completedFuture(null);
                }
                status = VoiceLoopStatus.recognizing(recorder.status());
                if (recognized.text().isBlank()) {
                    throw new IllegalStateException("AmiVoice WebSocket returned an empty recognition result.");
                }
                return handleRecognizedText(new KoeCraftAmiVoiceRecognizer.RecognitionResult(recognized.text(), recognized.confidence(), recognized.raw()), "websocket");
            })
            .whenComplete(this::completeCycle);
    }

    private CompletableFuture<Void> handleRecognizedText(KoeCraftAmiVoiceRecognizer.RecognitionResult recognition, String transport) {
        status = VoiceLoopStatus.executing(recognition.text(), recorder.status());
        show("KoeCraft recognized (" + transport + "): " + recognition.text());
        return textProcessor.handleRecognizedText(recognition);
    }

    private void completeCycle(Object ignored, Throwable error) {
        if (error != null) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            logger.warn("[KoeCraft] Native voice cycle failed: {}", cause.toString());
            status = VoiceLoopStatus.error(cause.getMessage() == null ? cause.toString() : cause.getMessage(), recorder.status());
            if (config.keepListeningOnError() && isRecoverableVoiceError(cause)) {
                show("マイクを続けます。もう一回いってね");
                client.execute(this::runNextCycle);
                return;
            }
            enabled.set(false);
            recorder.requestStop();
            show("マイクが止まりました。設定を見てね");
            return;
        }
        if (enabled.get() && !muted.get()) {
            client.execute(this::runNextCycle);
        }
    }

    private void show(String message) {
        client.execute(() -> overlaySink.accept(message));
    }

    private String compactError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.toString();
        }
        return message.length() <= 90 ? message : message.substring(0, 87) + "...";
    }

    private boolean isRecoverableVoiceError(Throwable error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("empty recognition")
            || lower.contains("no final recognition")
            || lower.contains("timeout")
            || lower.contains("http 5")
            || lower.contains("temporarily")
            || lower.contains("closed");
    }

    @Override
    public void close() {
        enabled.set(false);
        recorder.requestStop();
        activeCycle.cancel(true);
        recorder.close();
    }

    enum Phase {
        OFF,
        LISTENING,
        RECOGNIZING,
        EXECUTING,
        ERROR,
        MUTED
    }

    record VoiceLoopStatus(Phase phase, String detail, KoeCraftNativeMicRecorder.MicStatus micStatus) {
        static VoiceLoopStatus off() {
            return new VoiceLoopStatus(Phase.OFF, "", KoeCraftNativeMicRecorder.MicStatus.idle());
        }

        static VoiceLoopStatus listening(KoeCraftNativeMicRecorder.MicStatus micStatus) {
            return new VoiceLoopStatus(Phase.LISTENING, "", micStatus);
        }

        static VoiceLoopStatus muted(KoeCraftNativeMicRecorder.MicStatus micStatus) {
            return new VoiceLoopStatus(Phase.MUTED, "", micStatus);
        }

        static VoiceLoopStatus recognizing(KoeCraftNativeMicRecorder.MicStatus micStatus) {
            return new VoiceLoopStatus(Phase.RECOGNIZING, "", micStatus);
        }

        static VoiceLoopStatus executing(String text, KoeCraftNativeMicRecorder.MicStatus micStatus) {
            return new VoiceLoopStatus(Phase.EXECUTING, text, micStatus);
        }

        static VoiceLoopStatus error(String detail, KoeCraftNativeMicRecorder.MicStatus micStatus) {
            return new VoiceLoopStatus(Phase.ERROR, detail == null ? "" : detail, micStatus);
        }

        VoiceLoopStatus withMicStatus(KoeCraftNativeMicRecorder.MicStatus micStatus) {
            return new VoiceLoopStatus(phase, detail, micStatus);
        }
    }
}
