package dev.koecraft.agentmod;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

interface KoeCraftVoiceActivityGate extends AutoCloseable {
    Decision evaluate(byte[] pcm16le, int length, boolean rmsActive, boolean detectedSpeech);

    GateStatus status();

    @Override
    default void close() {
    }

    static KoeCraftVoiceActivityGate create(KoeCraftVoiceConfig config) {
        if (!config.vadEnabled() || "rms".equalsIgnoreCase(config.vadProvider())) {
            return new RmsGate();
        }
        try {
            return new SileroOnnxGate(config);
        } catch (Throwable error) {
            return new RmsGate("silero_init_failed:" + compact(error));
        }
    }

    private static String compact(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.toString();
        }
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() <= 80 ? message : message.substring(0, 77) + "...";
    }

    record Decision(boolean speechActive, double confidence, String provider, String fallbackReason) {
    }

    record GateStatus(String provider, double confidence, String fallbackReason) {
        static GateStatus rms() {
            return new GateStatus("rms", 0.0D, "");
        }
    }

    final class RmsGate implements KoeCraftVoiceActivityGate {
        private final String fallbackReason;
        private double lastConfidence = 0.0D;

        RmsGate() {
            this("");
        }

        RmsGate(String fallbackReason) {
            this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
        }

        @Override
        public Decision evaluate(byte[] pcm16le, int length, boolean rmsActive, boolean detectedSpeech) {
            lastConfidence = rmsActive ? 1.0D : 0.0D;
            return new Decision(rmsActive, lastConfidence, "rms", fallbackReason);
        }

        @Override
        public GateStatus status() {
            return new GateStatus("rms", lastConfidence, fallbackReason);
        }
    }

    final class SileroOnnxGate implements KoeCraftVoiceActivityGate {
        private static final String MODEL_RESOURCE = "assets/koecraft-agent/models/silero_vad_op18_ifless.onnx";
        private static final int SAMPLE_RATE = 16_000;
        private static final int WINDOW_SAMPLES = 512;
        private static final int CONTEXT_SAMPLES = 64;
        private static final int EFFECTIVE_WINDOW_SAMPLES = WINDOW_SAMPLES + CONTEXT_SAMPLES;
        private static final int STATE_SIZE = 2 * 1 * 128;
        private static volatile Path bundledModelPath;

        private final OrtEnvironment environment;
        private final OrtSession session;
        private final float threshold;
        private final int minSpeechFrames;
        private final int hangoverFrames;
        private final float[] state = new float[STATE_SIZE];
        private final float[] context = new float[CONTEXT_SAMPLES];
        private final float[] pending = new float[WINDOW_SAMPLES];
        private int pendingSamples = 0;
        private int speechFrames = 0;
        private int hangoverRemaining = 0;
        private double lastConfidence = 0.0D;
        private String fallbackReason = "";
        private boolean failed = false;

        SileroOnnxGate(KoeCraftVoiceConfig config) throws IOException, OrtException {
            this.environment = OrtEnvironment.getEnvironment();
            Path modelPath = resolveModelPath(config);
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
                options.setIntraOpNumThreads(1);
                this.session = environment.createSession(modelPath.toString(), options);
            }
            this.threshold = (float) config.vadConfidenceThreshold();
            this.minSpeechFrames = Math.max(1, config.vadMinSpeechFrames());
            this.hangoverFrames = Math.max(0, config.vadHangoverFrames());
        }

        @Override
        public Decision evaluate(byte[] pcm16le, int length, boolean rmsActive, boolean detectedSpeech) {
            if (failed) {
                return new Decision(rmsActive, rmsActive ? 1.0D : 0.0D, "rms", fallbackReason);
            }
            try {
                boolean processed = false;
                double maxConfidence = 0.0D;
                for (int index = 0; index + 1 < length; index += 2) {
                    short sample = (short) ((pcm16le[index] & 0xFF) | (pcm16le[index + 1] << 8));
                    pending[pendingSamples++] = sample / 32768.0F;
                    if (pendingSamples == WINDOW_SAMPLES) {
                        float speechProbability = predict(pending);
                        processed = true;
                        maxConfidence = Math.max(maxConfidence, speechProbability);
                        updateSpeechState(speechProbability);
                        pendingSamples = 0;
                    }
                }
                if (processed) {
                    lastConfidence = maxConfidence;
                }
                boolean vadActive = speechFrames >= minSpeechFrames || (detectedSpeech && hangoverRemaining > 0);
                return new Decision(rmsActive && vadActive, lastConfidence, "silero_onnx", "");
            } catch (Throwable error) {
                failed = true;
                fallbackReason = "silero_runtime_failed:" + compact(error);
                return new Decision(rmsActive, rmsActive ? 1.0D : 0.0D, "rms", fallbackReason);
            }
        }

        @Override
        public GateStatus status() {
            return failed
                ? new GateStatus("rms", lastConfidence, fallbackReason)
                : new GateStatus("silero_onnx", lastConfidence, "");
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (OrtException ignored) {
                // Closing the voice gate should not fail the voice loop.
            }
        }

        private void updateSpeechState(float speechProbability) {
            if (speechProbability >= threshold) {
                speechFrames++;
                hangoverRemaining = hangoverFrames;
                return;
            }
            speechFrames = 0;
            if (hangoverRemaining > 0) {
                hangoverRemaining--;
            }
        }

        private float predict(float[] window) throws OrtException {
            float[] input = new float[EFFECTIVE_WINDOW_SAMPLES];
            System.arraycopy(context, 0, input, 0, CONTEXT_SAMPLES);
            System.arraycopy(window, 0, input, CONTEXT_SAMPLES, WINDOW_SAMPLES);

            try (
                OnnxTensor inputTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), new long[] {1, EFFECTIVE_WINDOW_SAMPLES});
                OnnxTensor stateTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), new long[] {2, 1, 128});
                OnnxTensor sampleRateTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[] {SAMPLE_RATE}), new long[] {1});
                OrtSession.Result result = session.run(Map.of(
                    "input", inputTensor,
                    "state", stateTensor,
                    "sr", sampleRateTensor
                ))
            ) {
                float speechProbability = firstFloat(result.get(0).getValue());
                copyState(result.get(1));
                System.arraycopy(input, input.length - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES);
                return speechProbability;
            }
        }

        private float firstFloat(Object value) {
            if (value instanceof float[] array && array.length > 0) {
                return array[0];
            }
            if (value instanceof float[][] array && array.length > 0 && array[0].length > 0) {
                return array[0][0];
            }
            if (value instanceof float[][][] array && array.length > 0 && array[0].length > 0 && array[0][0].length > 0) {
                return array[0][0][0];
            }
            return 0.0F;
        }

        private void copyState(OnnxValue value) throws OrtException {
            Object output = value.getValue();
            if (output instanceof float[] array) {
                System.arraycopy(array, 0, state, 0, Math.min(array.length, state.length));
                return;
            }
            int index = 0;
            if (output instanceof float[][][] array) {
                for (float[][] plane : array) {
                    for (float[] row : plane) {
                        for (float valueAt : row) {
                            if (index < state.length) {
                                state[index++] = valueAt;
                            }
                        }
                    }
                }
            }
        }

        private static Path resolveModelPath(KoeCraftVoiceConfig config) throws IOException {
            if (!config.vadModelPath().isBlank()) {
                Path configured = Path.of(config.vadModelPath()).toAbsolutePath().normalize();
                if (Files.exists(configured)) {
                    return configured;
                }
            }
            Path cached = bundledModelPath;
            if (cached != null && Files.exists(cached)) {
                return cached;
            }
            synchronized (SileroOnnxGate.class) {
                cached = bundledModelPath;
                if (cached != null && Files.exists(cached)) {
                    return cached;
                }
                try (InputStream input = KoeCraftVoiceActivityGate.class.getClassLoader().getResourceAsStream(MODEL_RESOURCE)) {
                    if (input == null) {
                        throw new IOException("Bundled Silero VAD model not found: " + MODEL_RESOURCE);
                    }
                    Path target = Files.createTempFile("koecraft-silero-vad-", ".onnx");
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                    bundledModelPath = target;
                    return target;
                }
            }
        }
    }
}
