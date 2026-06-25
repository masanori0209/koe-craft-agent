package dev.koecraft.agentmod;

import java.nio.file.Path;

public final class KoeCraftVoiceActivityGateSmokeMain {
    private KoeCraftVoiceActivityGateSmokeMain() {
    }

    public static void main(String[] args) {
        KoeCraftVoiceConfig config = config();
        try (KoeCraftVoiceActivityGate gate = KoeCraftVoiceActivityGate.create(config)) {
            byte[] silence = new byte[3200];
            KoeCraftVoiceActivityGate.Decision decision = gate.evaluate(silence, silence.length, false, false);
            KoeCraftVoiceActivityGate.GateStatus status = gate.status();
            if (!"silero_onnx".equals(status.provider())) {
                throw new IllegalStateException("Silero VAD did not initialize: provider=" + status.provider() + " fallback=" + status.fallbackReason());
            }
            if (decision.speechActive()) {
                throw new IllegalStateException("Silero VAD treated silence as speech: confidence=" + decision.confidence());
            }
            System.out.println("[voice-vad-smoke] provider=" + status.provider() + " confidence=" + round(status.confidence()));
        }
    }

    private static KoeCraftVoiceConfig config() {
        return new KoeCraftVoiceConfig(
            Path.of("build/tmp/voice-vad-smoke.properties"),
            "",
            "https://acp-api.amivoice.com/v1/nolog/recognize",
            "wss://acp-api.amivoice.com/v1/nolog/",
            "-a-general-input",
            "../data/amivoice/dict.txt",
            200,
            "websocket",
            500,
            "",
            "gpt-4o-mini",
            "gpt-5-nano",
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
            true,
            "http://127.0.0.1:8790/api/speak",
            false,
            true,
            8
        );
    }

    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }
}
