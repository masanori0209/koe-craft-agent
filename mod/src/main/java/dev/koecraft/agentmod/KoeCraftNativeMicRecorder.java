package dev.koecraft.agentmod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

final class KoeCraftNativeMicRecorder implements AutoCloseable {
    static final String CONTENT_TYPE = "audio/wav";
    private static final AudioFormat FORMAT = new AudioFormat(16_000.0F, 16, 1, true, false);
    private static final int BUFFER_SIZE = 3200;
    private static final int PRE_ROLL_BUFFERS = 6;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "KoeCraft Native Mic");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile MicStatus status = MicStatus.idle();

    CompletableFuture<RecordedUtterance> recordUntilSilence(KoeCraftVoiceConfig config) {
        stopRequested.set(false);
        return CompletableFuture.supplyAsync(() -> recordBlocking(config), executor);
    }

    CompletableFuture<PcmStreamCapture> streamPcmUntilSilence(KoeCraftVoiceConfig config, PcmSink sink) {
        stopRequested.set(false);
        return CompletableFuture.supplyAsync(() -> streamPcmBlocking(config, sink), executor);
    }

    void requestStop() {
        stopRequested.set(true);
    }

    MicStatus status() {
        return status;
    }

    private RecordedUtterance recordBlocking(KoeCraftVoiceConfig config) {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalStateException("Native microphone line is not available for 16kHz mono PCM.");
        }

        try (TargetDataLine line = openLine(info, config)) {
            line.open(FORMAT);
            line.start();
            return capture(line, config);
        } catch (LineUnavailableException error) {
            String mixer = config.micMixerName().isBlank() ? "default" : config.micMixerName();
            throw new IllegalStateException("Native microphone is unavailable for mixer `" + mixer + "`. Check macOS microphone permission for Minecraft/Java, or change voice.micMixerName.", error);
        }
    }

    private PcmStreamCapture streamPcmBlocking(KoeCraftVoiceConfig config, PcmSink sink) {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalStateException("Native microphone line is not available for 16kHz mono PCM.");
        }

        try (TargetDataLine line = openLine(info, config)) {
            line.open(FORMAT);
            line.start();
            return streamPcm(line, config, sink);
        } catch (LineUnavailableException error) {
            String mixer = config.micMixerName().isBlank() ? "default" : config.micMixerName();
            throw new IllegalStateException("Native microphone is unavailable for mixer `" + mixer + "`. Check macOS microphone permission for Minecraft/Java, or change voice.micMixerName.", error);
        }
    }

    private TargetDataLine openLine(DataLine.Info info, KoeCraftVoiceConfig config) throws LineUnavailableException {
        String preferred = config.micMixerName();
        if (preferred != null && !preferred.isBlank()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                String name = mixerInfo.getName();
                String description = mixerInfo.getDescription();
                if ((name != null && name.contains(preferred)) || (description != null && description.contains(preferred))) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) {
                        status = status.withMixer(name);
                        return (TargetDataLine) mixer.getLine(info);
                    }
                }
            }
            throw new LineUnavailableException("Configured microphone mixer was not found or unsupported: " + preferred + ". Available inputs: " + availableInputMixers());
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        status = status.withMixer("Default Audio Device");
        return line;
    }

    private String availableInputMixers() {
        StringBuilder builder = new StringBuilder();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.getTargetLineInfo().length == 0) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(mixerInfo.getName());
        }
        return builder.toString();
    }

    private RecordedUtterance capture(TargetDataLine line, KoeCraftVoiceConfig config) {
        byte[] buffer = new byte[BUFFER_SIZE];
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long startedAt = System.nanoTime();
        long lastSpeechAt = startedAt;
        boolean detectedSpeech = false;
        double maxRms = 0.0D;
        AdaptiveNoiseGate noiseGate = new AdaptiveNoiseGate(config);
        double threshold = noiseGate.threshold();
        Deque<byte[]> preRoll = new ArrayDeque<>();
        try (KoeCraftVoiceActivityGate voiceGate = KoeCraftVoiceActivityGate.create(config)) {
        KoeCraftVoiceActivityGate.GateStatus gateStatus = voiceGate.status();
        status = status.withRecording(true, false, false, 0.0D, maxRms, threshold, gateStatus.confidence(), gateStatus.provider(), gateStatus.fallbackReason(), Duration.ZERO, Duration.ZERO);

        while (!stopRequested.get()) {
            int bytesRead = line.read(buffer, 0, buffer.length);
            if (bytesRead <= 0) {
                continue;
            }
            double rms = rms16le(buffer, bytesRead);
            maxRms = Math.max(maxRms, rms);
            long now = System.nanoTime();
            Duration elapsed = Duration.ofNanos(now - startedAt);
            threshold = noiseGate.updateAndThreshold(rms, elapsed, detectedSpeech);
            boolean rmsActive = rms >= threshold;
            KoeCraftVoiceActivityGate.Decision voice = voiceGate.evaluate(buffer, bytesRead, rmsActive, detectedSpeech);
            boolean speechActive = voice.speechActive();
            if (!detectedSpeech) {
                preRoll.addLast(Arrays.copyOf(buffer, bytesRead));
                while (preRoll.size() > PRE_ROLL_BUFFERS) {
                    preRoll.removeFirst();
                }
            }
            if (speechActive) {
                if (!detectedSpeech) {
                    for (byte[] chunk : preRoll) {
                        byte[] processed = processPcmForAsr(chunk, chunk.length, config);
                        pcm.write(processed, 0, processed.length);
                    }
                    preRoll.clear();
                }
                detectedSpeech = true;
                lastSpeechAt = now;
            }
            if (detectedSpeech) {
                byte[] processed = processPcmForAsr(buffer, bytesRead, config);
                pcm.write(processed, 0, processed.length);
            }

            Duration silence = Duration.ofNanos(now - lastSpeechAt);
            status = status.withRecording(true, speechActive, detectedSpeech, rms, maxRms, threshold, voice.confidence(), voice.provider(), voice.fallbackReason(), elapsed, silence);

            if (detectedSpeech && silence.toMillis() >= config.silenceMillis()) {
                break;
            }
            if (elapsed.toMillis() >= config.maxRecordingMillis()) {
                break;
            }
        }

        line.stop();
        line.flush();
        gateStatus = voiceGate.status();
        status = status.withRecording(false, false, detectedSpeech, 0.0D, maxRms, threshold, gateStatus.confidence(), gateStatus.provider(), gateStatus.fallbackReason(), Duration.ofNanos(System.nanoTime() - startedAt), Duration.ofNanos(System.nanoTime() - lastSpeechAt));
        if (!detectedSpeech || pcm.size() == 0) {
            return RecordedUtterance.empty();
        }
        return new RecordedUtterance(toWav(pcm.toByteArray()), CONTENT_TYPE, true);
        }
    }

    private PcmStreamCapture streamPcm(TargetDataLine line, KoeCraftVoiceConfig config, PcmSink sink) {
        byte[] buffer = new byte[BUFFER_SIZE];
        long startedAt = System.nanoTime();
        long lastSpeechAt = startedAt;
        boolean detectedSpeech = false;
        int streamedBytes = 0;
        double maxRms = 0.0D;
        AdaptiveNoiseGate noiseGate = new AdaptiveNoiseGate(config);
        double threshold = noiseGate.threshold();
        Deque<byte[]> preRoll = new ArrayDeque<>();
        try (KoeCraftVoiceActivityGate voiceGate = KoeCraftVoiceActivityGate.create(config)) {
        KoeCraftVoiceActivityGate.GateStatus gateStatus = voiceGate.status();
        status = status.withRecording(true, false, false, 0.0D, maxRms, threshold, gateStatus.confidence(), gateStatus.provider(), gateStatus.fallbackReason(), Duration.ZERO, Duration.ZERO);

        while (!stopRequested.get()) {
            int bytesRead = line.read(buffer, 0, buffer.length);
            if (bytesRead <= 0) {
                continue;
            }
            double rms = rms16le(buffer, bytesRead);
            maxRms = Math.max(maxRms, rms);
            long now = System.nanoTime();
            Duration elapsed = Duration.ofNanos(now - startedAt);
            threshold = noiseGate.updateAndThreshold(rms, elapsed, detectedSpeech);
            boolean rmsActive = rms >= threshold;
            KoeCraftVoiceActivityGate.Decision voice = voiceGate.evaluate(buffer, bytesRead, rmsActive, detectedSpeech);
            boolean speechActive = voice.speechActive();
            if (!detectedSpeech) {
                preRoll.addLast(Arrays.copyOf(buffer, bytesRead));
                while (preRoll.size() > PRE_ROLL_BUFFERS) {
                    preRoll.removeFirst();
                }
            }
            if (speechActive) {
                if (!detectedSpeech) {
                    for (byte[] chunk : preRoll) {
                        byte[] processed = processPcmForAsr(chunk, chunk.length, config);
                        sink.accept(processed, processed.length);
                        streamedBytes += chunk.length;
                    }
                    preRoll.clear();
                }
                detectedSpeech = true;
                lastSpeechAt = now;
            }
            if (detectedSpeech) {
                byte[] processed = processPcmForAsr(buffer, bytesRead, config);
                sink.accept(processed, processed.length);
                streamedBytes += bytesRead;
            }

            Duration silence = Duration.ofNanos(now - lastSpeechAt);
            status = status.withRecording(true, speechActive, detectedSpeech, rms, maxRms, threshold, voice.confidence(), voice.provider(), voice.fallbackReason(), elapsed, silence);

            if (detectedSpeech && silence.toMillis() >= config.silenceMillis()) {
                break;
            }
            if (elapsed.toMillis() >= config.maxRecordingMillis()) {
                break;
            }
        }

        line.stop();
        line.flush();
        gateStatus = voiceGate.status();
        status = status.withRecording(false, false, detectedSpeech, 0.0D, maxRms, threshold, gateStatus.confidence(), gateStatus.provider(), gateStatus.fallbackReason(), Duration.ofNanos(System.nanoTime() - startedAt), Duration.ofNanos(System.nanoTime() - lastSpeechAt));
        return new PcmStreamCapture(detectedSpeech, streamedBytes, maxRms);
        }
    }

    private byte[] toWav(byte[] pcm) {
        try (
            ByteArrayInputStream input = new ByteArrayInputStream(pcm);
            AudioInputStream audioInput = new AudioInputStream(input, FORMAT, pcm.length / FORMAT.getFrameSize());
            ByteArrayOutputStream wav = new ByteArrayOutputStream()
        ) {
            AudioSystem.write(audioInput, AudioFileFormat.Type.WAVE, wav);
            return wav.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to encode microphone audio as WAV.", error);
        }
    }

    private double rms16le(byte[] bytes, int length) {
        long sumSquares = 0L;
        int samples = length / 2;
        for (int index = 0; index + 1 < length; index += 2) {
            int sample = (short) ((bytes[index] & 0xFF) | (bytes[index + 1] << 8));
            sumSquares += (long) sample * sample;
        }
        if (samples == 0) {
            return 0.0D;
        }
        return Math.sqrt(sumSquares / (double) samples) / 32768.0D;
    }

    private byte[] processPcmForAsr(byte[] input, int length, KoeCraftVoiceConfig config) {
        byte[] output = Arrays.copyOf(input, length);
        if (!config.pcmNormalizeEnabled() && !config.pcmSoftClipEnabled()) {
            return output;
        }
        double rms = rms16le(output, output.length);
        double gain = 1.0D;
        if (config.pcmNormalizeEnabled() && rms > 0.0001D && rms < config.pcmNormalizeTargetRms()) {
            gain = Math.min(config.pcmNormalizeMaxGain(), config.pcmNormalizeTargetRms() / rms);
        }
        if (gain <= 1.0001D && !config.pcmSoftClipEnabled()) {
            return output;
        }
        for (int index = 0; index + 1 < output.length; index += 2) {
            short sample = (short) ((output[index] & 0xFF) | (output[index + 1] << 8));
            double scaled = sample / 32768.0D * gain;
            if (config.pcmSoftClipEnabled() && Math.abs(scaled) > 0.95D) {
                scaled = Math.tanh(scaled);
            }
            int clipped = (int) Math.round(Math.max(-1.0D, Math.min(1.0D, scaled)) * 32767.0D);
            output[index] = (byte) (clipped & 0xFF);
            output[index + 1] = (byte) ((clipped >> 8) & 0xFF);
        }
        return output;
    }

    @Override
    public void close() {
        requestStop();
        executor.shutdownNow();
    }

    record RecordedUtterance(byte[] audio, String contentType, boolean detectedSpeech) {
        static RecordedUtterance empty() {
            return new RecordedUtterance(new byte[0], CONTENT_TYPE, false);
        }
    }

    record PcmStreamCapture(boolean detectedSpeech, int streamedBytes, double maxRms) {
    }

    interface PcmSink {
        void accept(byte[] buffer, int length);
    }

    private static final class AdaptiveNoiseGate {
        private final KoeCraftVoiceConfig config;
        private double sumRms = 0.0D;
        private int samples = 0;

        AdaptiveNoiseGate(KoeCraftVoiceConfig config) {
            this.config = config;
        }

        double updateAndThreshold(double rms, Duration elapsed, boolean detectedSpeech) {
            double current = threshold();
            if (
                config.adaptiveNoiseEnabled()
                    && !detectedSpeech
                    && elapsed.toMillis() <= config.adaptiveNoiseWarmupMillis()
                    && rms < Math.max(config.speechRmsThreshold() * 1.8D, current * 1.2D)
            ) {
                sumRms += rms;
                samples++;
            }
            return threshold();
        }

        double threshold() {
            if (!config.adaptiveNoiseEnabled() || samples == 0) {
                return config.speechRmsThreshold();
            }
            double noiseFloor = sumRms / samples;
            return Math.max(
                config.speechRmsThreshold(),
                Math.max(config.adaptiveNoiseMinThreshold(), noiseFloor * config.adaptiveNoiseMultiplier())
            );
        }
    }

    record MicStatus(
        boolean recording,
        boolean speechActive,
        boolean detectedSpeech,
        double lastRms,
        double maxRms,
        double threshold,
        double vadConfidence,
        String vadProvider,
        String vadFallbackReason,
        String mixerName,
        Duration elapsed,
        Duration silence
    ) {
        static MicStatus idle() {
            return new MicStatus(false, false, false, 0.0D, 0.0D, 0.0D, 0.0D, "rms", "", "", Duration.ZERO, Duration.ZERO);
        }

        MicStatus withMixer(String mixerName) {
            return new MicStatus(recording, speechActive, detectedSpeech, lastRms, maxRms, threshold, vadConfidence, vadProvider, vadFallbackReason, mixerName == null ? "" : mixerName, elapsed, silence);
        }

        MicStatus withRecording(boolean recording, boolean speechActive, boolean detectedSpeech, double lastRms, double maxRms, double threshold, double vadConfidence, String vadProvider, String vadFallbackReason, Duration elapsed, Duration silence) {
            return new MicStatus(recording, speechActive, detectedSpeech, lastRms, maxRms, threshold, vadConfidence, vadProvider == null || vadProvider.isBlank() ? "rms" : vadProvider, vadFallbackReason == null ? "" : vadFallbackReason, mixerName, elapsed, silence);
        }
    }
}
