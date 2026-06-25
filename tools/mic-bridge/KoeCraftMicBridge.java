package dev.koecraft.micbridge;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public final class KoeCraftMicBridge {
    private static final AudioFormat FORMAT = new AudioFormat(16_000.0F, 16, 1, true, false);
    private static final int BUFFER_SIZE = 3200;
    private static final int PRE_ROLL_BUFFERS = 6;
    private static final String DEFAULT_MIC_DEVICE_LABEL = "Default system microphone";
    private static final Pattern BARE_UNICODE_ESCAPE = Pattern.compile("u([0-9a-fA-F]{4})");
    private static final Color APP_BG = new Color(0xF6F7F9);
    private static final Color SURFACE = new Color(0xFFFFFF);
    private static final Color SURFACE_MUTED = new Color(0xEAECF0);
    private static final Color BORDER = new Color(0xD0D5DD);
    private static final Color TEXT = new Color(0x101828);
    private static final Color TEXT_MUTED = new Color(0x667085);
    private static final Color GREEN = new Color(0x16A34A);
    private static final Color RED = new Color(0xB42318);
    private static final DemoPhrase[] DEMO_PHRASES = {
        new DemoPhrase("歩く", "まっすぐ歩いて"),
        new DemoPhrase("走る", "走りながらジャンプして"),
        new DemoPhrase("泳ぐ", "泳いで"),
        new DemoPhrase("拾う", "アイテム拾って"),
        new DemoPhrase("木を取る", "木を取って"),
        new DemoPhrase("石を掘る", "石を掘って"),
        new DemoPhrase("階段掘り", "階段掘りして"),
        new DemoPhrase("作業台", "作業台作って"),
        new DemoPhrase("棒", "棒を作って"),
        new DemoPhrase("石ピッケル", "石のツルハシ作って"),
        new DemoPhrase("松明", "暗いから松明置いて"),
        new DemoPhrase("食料", "食べ物を探して"),
        new DemoPhrase("狩り", "動物を狩って"),
        new DemoPhrase("村探し", "村を探して"),
        new DemoPhrase("小さい家", "小さい家を作るよ"),
        new DemoPhrase("橋", "橋をかけて"),
        new DemoPhrase("退避", "危ないから逃げて"),
        new DemoPhrase("盾", "盾を構えて"),
        new DemoPhrase("お祝い", "やったー、すごい")
    };
    private volatile Config config;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ExecutorService recorderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "KoeCraft Mic Bridge Recorder");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);
    private final AtomicBoolean ttsEnabled;
    private final AtomicBoolean ttsSpeaking = new AtomicBoolean(false);
    private volatile Status status = Status.off();
    private volatile String lastSpokenText = "";
    private volatile String currentMicDeviceName = DEFAULT_MIC_DEVICE_LABEL;
    private volatile int consecutiveRecoverableErrors = 0;
    private volatile long suppressMicUntilMs = 0L;
    private volatile long lastTtsEndedMs = 0L;
    private volatile Process activeTtsProcess = null;

    private KoeCraftMicBridge(Config config) {
        this.config = config;
        this.ttsEnabled = new AtomicBoolean(config.ttsEnabled);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("apple.awt.application.name", "KoeCraft Mic Bridge");
        Config config = Config.load();
        KoeCraftMicBridge bridge = new KoeCraftMicBridge(config);
        bridge.startHttpServer();
        bridge.showWindow();
        System.out.println("[KoeCraft Mic Bridge] listening on http://127.0.0.1:" + config.bridgePort);
        System.out.println("[KoeCraft Mic Bridge] Minecraft utterance endpoint: " + config.modUtteranceUrl);
        Thread.currentThread().join();
    }

    private void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.bridgePort), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/mic/on", this::handleOn);
        server.createContext("/api/mic/off", this::handleOff);
        server.createContext("/api/mic/toggle", this::handleToggle);
        server.createContext("/api/mic/mute/toggle", this::handleMuteToggle);
        server.createContext("/api/speak", this::handleSpeak);
        server.createContext("/api/tts/toggle", this::handleTtsToggle);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "KoeCraft Mic Bridge HTTP");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, healthJson());
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        respondHtml(exchange, 200, indexHtml());
    }

    private void handleOn(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        startMic();
        respond(exchange, 200, healthJson());
    }

    private void handleOff(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        stopMic();
        respond(exchange, 200, healthJson());
    }

    private void handleToggle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        if (enabled.get()) {
            stopMic();
            respond(exchange, 200, healthJson());
            return;
        }
        startMic();
        respond(exchange, 200, healthJson());
    }

    private void handleMuteToggle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        toggleMute();
        respond(exchange, 200, healthJson());
    }

    private void startMic() {
        if (enabled.compareAndSet(false, true)) {
            muted.set(false);
            consecutiveRecoverableErrors = 0;
            status = Status.listening();
            recorderExecutor.submit(this::recordingLoop);
        } else {
            muted.set(false);
            status = Status.listening("mic unmuted");
        }
    }

    private void stopMic() {
        enabled.set(false);
        muted.set(false);
        status = Status.off();
    }

    private void toggleMute() {
        if (!enabled.get()) {
            enabled.set(true);
            muted.set(true);
            consecutiveRecoverableErrors = 0;
            status = Status.muted();
            recorderExecutor.submit(this::recordingLoop);
            return;
        }
        boolean mutedNow = !muted.get();
        muted.set(mutedNow);
        status = mutedNow ? Status.muted() : Status.listening("mic unmuted");
    }

    private void showWindow() {
        EventQueue.invokeLater(() -> {
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("Label.foreground", TEXT);
            UIManager.put("Button.foreground", TEXT);
            UIManager.put("TabbedPane.foreground", TEXT);
            UIManager.put("TabbedPane.selectedForeground", TEXT);
            JFrame frame = new JFrame("KoeCraft Mic Bridge");
            frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            frame.setMinimumSize(new Dimension(720, 560));
            frame.setIconImage(appIcon());

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(APP_BG);
            root.setBorder(new EmptyBorder(18, 18, 18, 18));

            JPanel header = new JPanel(new BorderLayout(14, 0));
            header.setOpaque(false);
            JLabel icon = new JLabel(new javax.swing.ImageIcon(appIcon().getScaledInstance(44, 44, Image.SCALE_SMOOTH)));
            JLabel title = new JLabel("KoeCraft Mic Bridge");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0F));
            title.setForeground(TEXT);
            JLabel subtitle = new JLabel("Voice controls, ASR, and Minecraft agent settings");
            subtitle.setForeground(TEXT_MUTED);
            JPanel titleBlock = new JPanel();
            titleBlock.setOpaque(false);
            titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
            titleBlock.add(title);
            titleBlock.add(Box.createVerticalStrut(3));
            titleBlock.add(subtitle);
            header.add(icon, BorderLayout.WEST);
            header.add(titleBlock, BorderLayout.CENTER);
            root.add(header, BorderLayout.NORTH);

            JLabel micLabel = metricValue("OFF");
            JLabel muteLabel = metricValue("OFF");
            JLabel phaseLabel = metricValue("off");
            JLabel speechLabel = metricValue("idle");
            JLabel rmsLabel = metricValue("0.0000");
            JLabel providerLabel = metricValue(config.displaySpeechProvider());
            JLabel ttsLabel = metricValue(ttsEnabled.get() ? "ON" : "OFF");
            JLabel deviceLabel = new JLabel(compactDeviceName(currentMicDeviceName));
            deviceLabel.setFont(deviceLabel.getFont().deriveFont(Font.BOLD, 13.0F));
            deviceLabel.setForeground(TEXT);
            JLabel detailLabel = new JLabel("-");
            detailLabel.setFont(detailLabel.getFont().deriveFont(13.0F));
            detailLabel.setForeground(TEXT_MUTED);

            JPanel statusGrid = new JPanel(new GridBagLayout());
            statusGrid.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(6, 6, 6, 6);
            gc.fill = GridBagConstraints.BOTH;
            gc.weightx = 1;
            addMetricCard(statusGrid, gc, 0, 0, "Microphone", micLabel);
            addMetricCard(statusGrid, gc, 1, 0, "Phase", phaseLabel);
            addMetricCard(statusGrid, gc, 2, 0, "Speech", speechLabel);
            addMetricCard(statusGrid, gc, 0, 1, "Muted", muteLabel);
            addMetricCard(statusGrid, gc, 1, 1, "RMS", rmsLabel);
            addMetricCard(statusGrid, gc, 2, 1, "ASR", providerLabel);
            addMetricCard(statusGrid, gc, 0, 2, "TTS", ttsLabel);
            addMetricCard(statusGrid, gc, 1, 2, "Detail", detailLabel);
            addMetricCard(statusGrid, gc, 2, 2, "Input", deviceLabel);

            JButton onButton = primaryButton("Mic ON");
            JButton muteButton = neutralButton("Mic MUTE");
            JButton offButton = dangerButton("Mic OFF");
            JButton ttsButton = neutralButton("TTS OFF");
            JButton micCheckButton = neutralButton("Mic Test");
            onButton.addActionListener(event -> startMic());
            muteButton.addActionListener(event -> toggleMute());
            offButton.addActionListener(event -> stopMic());
            ttsButton.addActionListener(event -> {
                ttsEnabled.set(!ttsEnabled.get());
                config = config.withTtsEnabled(ttsEnabled.get());
                saveConfig(frame, false);
            });
            micCheckButton.addActionListener(event -> runMicCheck(frame));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            buttons.setOpaque(false);
            buttons.setBorder(new EmptyBorder(12, 2, 8, 2));
            buttons.add(onButton);
            buttons.add(muteButton);
            buttons.add(offButton);
            buttons.add(ttsButton);
            buttons.add(micCheckButton);

            JPanel statusTab = new JPanel(new BorderLayout());
            statusTab.setOpaque(false);
            statusTab.setBorder(new EmptyBorder(14, 0, 0, 0));
            statusTab.add(statusGrid, BorderLayout.CENTER);
            JPanel statusBottom = new JPanel();
            statusBottom.setOpaque(false);
            statusBottom.setLayout(new BoxLayout(statusBottom, BoxLayout.Y_AXIS));
            statusBottom.add(buttons);
            statusBottom.add(demoVoicePanel());
            statusTab.add(statusBottom, BorderLayout.SOUTH);

            SettingsFields fields = createSettingsFields();
            JPanel settingsTab = settingsPanel(fields, frame);

            JTabbedPane tabs = new JTabbedPane();
            tabs.setForeground(TEXT);
            tabs.setBackground(APP_BG);
            tabs.setBorder(new EmptyBorder(16, 0, 0, 0));
            tabs.addTab("Status", statusTab);
            tabs.addTab("Settings", new JScrollPane(settingsTab));
            root.add(tabs, BorderLayout.CENTER);

            frame.setContentPane(root);

            Timer timer = new Timer(400, event -> {
                Status current = status;
                boolean micOn = enabled.get();
                boolean mutedNow = muted.get();
                micLabel.setText(micOn ? "ON" : "OFF");
                micLabel.setForeground(micOn && !mutedNow ? GREEN : TEXT_MUTED);
                muteLabel.setText(mutedNow ? "ON" : "OFF");
                muteLabel.setForeground(mutedNow ? RED : TEXT_MUTED);
                phaseLabel.setText(current.phase);
                speechLabel.setText(current.speechActive ? "speaking" : current.detectedSpeech ? "captured" : "idle");
                speechLabel.setForeground(current.speechActive ? new Color(0xB54708) : TEXT);
                rmsLabel.setText(Double.toString(round(current.lastRms)));
                providerLabel.setText(config.displaySpeechProvider());
                ttsLabel.setText(ttsEnabled.get() ? "ON" : "OFF");
                deviceLabel.setText(compactDeviceName(currentMicDeviceName));
                detailLabel.setText(current.detail == null || current.detail.isBlank() ? "-" : current.detail);
                onButton.setEnabled(!micOn || mutedNow);
                muteButton.setText(mutedNow ? "UNMUTE" : "Mic MUTE");
                muteButton.setEnabled(micOn || !mutedNow);
                offButton.setEnabled(micOn);
                ttsButton.setText(ttsEnabled.get() ? "TTS OFF" : "TTS ON");
                micCheckButton.setEnabled(!micOn);
            });
            timer.setRepeats(true);
            timer.start();

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.toFront();
        });
    }

    private JLabel metricValue(String text) {
        JLabel label = new JLabel(text, SwingConstants.LEFT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 20.0F));
        label.setForeground(TEXT);
        return label;
    }

    private void addMetricCard(JPanel parent, GridBagConstraints gc, int x, int y, String title, Component value) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(SURFACE_MUTED),
            new EmptyBorder(14, 14, 14, 14)
        ));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12.0F));
        label.setForeground(TEXT_MUTED);
        card.add(label);
        card.add(Box.createVerticalStrut(8));
        card.add(value);
        gc.gridx = x;
        gc.gridy = y;
        parent.add(card, gc);
    }

    private JPanel demoVoicePanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 10));
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(12, 2, 4, 2));

        JLabel title = new JLabel("Demo Voice");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14.0F));
        title.setForeground(TEXT);
        JLabel help = new JLabel("機械音声で代表発話を流します。TTS ON のときだけ再生し、再生中はマイク入力を抑制します。");
        help.setFont(help.getFont().deriveFont(12.0F));
        help.setForeground(TEXT_MUTED);
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(title);
        heading.add(Box.createVerticalStrut(3));
        heading.add(help);
        wrapper.add(heading, BorderLayout.NORTH);

        JPanel grid = new JPanel(new java.awt.GridLayout(0, 4, 8, 8));
        grid.setOpaque(false);
        for (DemoPhrase phrase : DEMO_PHRASES) {
            JButton button = neutralButton(phrase.label());
            button.setToolTipText(phrase.text());
            button.setPreferredSize(new Dimension(132, 36));
            button.addActionListener(event -> playDemoPhrase(phrase.text()));
            grid.add(button);
        }
        wrapper.add(grid, BorderLayout.CENTER);
        return wrapper;
    }

    private void playDemoPhrase(String text) {
        String spoken = sanitizeSpeechText(text);
        if (spoken.isBlank()) {
            return;
        }
        lastSpokenText = spoken;
        if (!ttsEnabled.get()) {
            status = Status.listening("TTS is off: " + spoken);
            return;
        }
        status = Status.listening("demo voice: " + spoken);
        speakAsync(spoken);
    }

    private JButton primaryButton(String text) {
        JButton button = new JButton(text);
        return styleButton(button, GREEN, Color.WHITE, new Color(0xD1FADF), new Color(0x065F46));
    }

    private JButton dangerButton(String text) {
        JButton button = new JButton(text);
        return styleButton(button, RED, Color.WHITE, new Color(0xFEE4E2), new Color(0x7A271A));
    }

    private JButton neutralButton(String text) {
        JButton button = new JButton(text);
        return styleButton(button, SURFACE, TEXT, new Color(0xF2F4F7), TEXT_MUTED);
    }

    private JButton styleButton(JButton button, Color background, Color foreground, Color disabledBackground, Color disabledForeground) {
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13.0F));
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            new EmptyBorder(8, 16, 8, 16)
        ));
        button.setDisabledIcon(null);
        button.putClientProperty("enabledBackground", background);
        button.putClientProperty("disabledBackground", disabledBackground);
        button.putClientProperty("enabledForeground", foreground);
        button.putClientProperty("disabledForeground", disabledForeground);
        button.addChangeListener(event -> {
            boolean enabledNow = button.isEnabled();
            button.setBackground((Color) button.getClientProperty(enabledNow ? "enabledBackground" : "disabledBackground"));
            button.setForeground((Color) button.getClientProperty(enabledNow ? "enabledForeground" : "disabledForeground"));
        });
        button.setFocusPainted(false);
        button.setPreferredSize(new Dimension(112, 40));
        return button;
    }

    private JPanel settingsPanel(SettingsFields fields, JFrame frame) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(APP_BG);
        panel.setBorder(new EmptyBorder(14, 4, 14, 4));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        int row = 0;
        row = addSection(panel, gc, row, "Voice");
        row = addVoicePresets(panel, gc, row, fields);
        row = addCombo(panel, gc, row, "Speech provider", fields.speechProvider, "Default is AmiVoice. Whisper uses OpenAI audio transcription after local silence detection.");
        row = addText(panel, gc, row, "AmiVoice transport", fields.voiceTransport, "websocket streams raw PCM. http uses the previous short-utterance HTTP path.");
        row = addCombo(panel, gc, row, "Input device", fields.micMixerName, "Choose a microphone. Default uses the operating system input device.");
        row = addSlider(panel, gc, row, "Speech threshold", fields.threshold, "Lower picks up softer voices. Higher ignores noise.");
        row = addCheck(panel, gc, row, fields.adaptiveNoiseEnabled, "Adaptive noise floor");
        row = addSlider(panel, gc, row, "Noise warmup ms", fields.adaptiveNoiseWarmup, "Short ambient-noise sampling window before each utterance.");
        row = addSlider(panel, gc, row, "Noise multiplier x10", fields.adaptiveNoiseMultiplier, "30 means noise floor x 3.0. Higher ignores more noise.");
        row = addSlider(panel, gc, row, "Noise min threshold x10000", fields.adaptiveNoiseMinThreshold, "Lower bound used with adaptive noise.");
        row = addCheck(panel, gc, row, fields.vadEnabled, "Silero VAD confidence gate");
        row = addCombo(panel, gc, row, "VAD provider", fields.vadProvider, "silero_onnx checks speech probability. rms uses only the old volume gate.");
        row = addText(panel, gc, row, "VAD model path", fields.vadModelPath, "Blank uses the bundled pinned Silero ONNX model.");
        row = addSlider(panel, gc, row, "VAD confidence x100", fields.vadConfidence, "50 means speech probability >= 0.50.");
        row = addSlider(panel, gc, row, "VAD min speech frames", fields.vadMinSpeechFrames, "2 keeps short commands fast while filtering one-frame noise.");
        row = addSlider(panel, gc, row, "VAD hangover frames", fields.vadHangoverFrames, "Keeps capture open briefly after speech probability drops.");
        row = addCheck(panel, gc, row, fields.pcmNormalizeEnabled, "PCM volume normalize");
        row = addSlider(panel, gc, row, "Target RMS x1000", fields.pcmNormalizeTarget, "35 means 0.035 target RMS for speech sent to AmiVoice.");
        row = addSlider(panel, gc, row, "Max gain x10", fields.pcmNormalizeMaxGain, "30 means up to 3.0x gain for quiet speech.");
        row = addCheck(panel, gc, row, fields.pcmSoftClipEnabled, "PCM soft clip");
        row = addSlider(panel, gc, row, "Silence ms", fields.silence, "How quickly speech is sent after silence.");
        row = addSlider(panel, gc, row, "Max recording ms", fields.maxRecording, "Upper bound for a single utterance.");
        row = addCheck(panel, gc, row, fields.keepListening, "Keep listening after recoverable errors");

        row = addSection(panel, gc, row, "AmiVoice / LLM");
        row = addPassword(panel, gc, row, "AmiVoice API key", fields.amivoiceApiKey);
        row = addText(panel, gc, row, "AmiVoice engine", fields.amivoiceEngine, "-a-general-input is the current default.");
        row = addPassword(panel, gc, row, "OpenAI API key", fields.openaiApiKey);
        row = addText(panel, gc, row, "OpenAI model", fields.openaiModel, "Example: gpt-4o-mini.");
        row = addText(panel, gc, row, "Whisper model", fields.openaiTranscriptionModel, "Default: whisper-1. Used only when Speech provider is Whisper.");
        row = addCheck(panel, gc, row, fields.llmFallbackEnabled, "Enable LLM fallback");
        row = addText(panel, gc, row, "Speech normalizer model", fields.openaiNormalizerModel, "Default: gpt-5-nano. Used only after ASR when rule routing fails.");
        row = addCheck(panel, gc, row, fields.openaiNormalizerEnabled, "Enable speech normalizer");

        row = addSection(panel, gc, row, "Minecraft / Bridge");
        row = addText(panel, gc, row, "MOD utterance URL", fields.modUtteranceUrl, "Usually http://127.0.0.1:8791/api/utterance");
        row = addText(panel, gc, row, "Bridge port", fields.bridgePort, "Changing this requires restarting the bridge app.");
        row = addCombo(panel, gc, row, "Assist mode", fields.assistMode, "World Assist is the default voice-first mode. Survival/off reduces convenience assists.");
        row = addCheck(panel, gc, row, fields.worldAssistEnabled, "World Assist enabled");
        row = addCheck(panel, gc, row, fields.worldAssistMaterialTopUp, "World Assist material top-up");
        row = addCheck(panel, gc, row, fields.worldAssistDirectCraft, "World Assist direct craft fallback");
        row = addCheck(panel, gc, row, fields.worldAssistRareItems, "Allow rare item assist");
        row = addSlider(panel, gc, row, "Explore distance", fields.programmaticExploreDistance, "Default 300 blocks when no loaded structure hint is found.");
        row = addSlider(panel, gc, row, "Boat travel distance", fields.programmaticBoatDistance, "Default bounded water-travel assist distance.");
        row = addCheck(panel, gc, row, fields.childModeEnabled, "Child mode");
        row = addCheck(panel, gc, row, fields.childModeMaterialAssist, "Child mode material assist");
        row = addSlider(panel, gc, row, "Child shelter material target", fields.childShelterTarget, "How many simple blocks to try to prepare.");

        row = addSection(panel, gc, row, "Text To Speech");
        row = addCheck(panel, gc, row, fields.ttsEnabled, "Enable TTS");
        row = addText(panel, gc, row, "TTS voice", fields.ttsVoice, "macOS example: Kyoko.");
        row = addSlider(panel, gc, row, "TTS rate", fields.ttsRate, "macOS say speech rate.");
        row = addSlider(panel, gc, row, "Mic suppress ms after TTS", fields.ttsSuppress, "Prevents read-aloud audio from looping back into recognition.");
        row = addCheck(panel, gc, row, fields.ttsInterruptOnSpeech, "Stop TTS when speech is detected");
        row = addSlider(panel, gc, row, "TTS interrupt RMS x10000", fields.ttsInterruptThreshold, "Higher avoids stopping TTS from speaker echo.");

        JButton save = primaryButton("Save settings");
        save.addActionListener(event -> {
            applySettings(fields);
            saveConfig(frame, true);
        });
        JButton reload = neutralButton("Reload from file");
        reload.addActionListener(event -> {
            try {
                config = Config.load();
                ttsEnabled.set(config.ttsEnabled);
                loadFields(fields, config);
            } catch (IOException error) {
                JOptionPane.showMessageDialog(frame, compactError(error), "Reload failed", JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(reload);
        buttons.add(save);
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        panel.add(buttons, gc);
        return panel;
    }

    private int addVoicePresets(JPanel panel, GridBagConstraints gc, int row, SettingsFields fields) {
        JPanel wrapper = new JPanel(new BorderLayout(8, 6));
        wrapper.setOpaque(false);
        JLabel help = new JLabel("Preset tweaks local mic gate/VAD behavior. Press Save settings to persist.");
        help.setForeground(TEXT_MUTED);
        help.setFont(help.getFont().deriveFont(12.0F));
        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 4, 8, 0));
        buttons.setOpaque(false);
        addPresetButton(buttons, "Quiet Room", () -> applyVoicePreset(fields, "quiet"));
        addPresetButton(buttons, "Minecraft BGM", () -> applyVoicePreset(fields, "bgm"));
        addPresetButton(buttons, "Kids", () -> applyVoicePreset(fields, "kids"));
        addPresetButton(buttons, "Demo Fast", () -> applyVoicePreset(fields, "demo"));
        wrapper.add(help, BorderLayout.NORTH);
        wrapper.add(buttons, BorderLayout.CENTER);
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        panel.add(wrapper, gc);
        gc.gridwidth = 1;
        return row + 1;
    }

    private void addPresetButton(JPanel parent, String label, Runnable action) {
        JButton button = neutralButton(label);
        button.setPreferredSize(new Dimension(136, 36));
        button.addActionListener(event -> action.run());
        parent.add(button);
    }

    private void applyVoicePreset(SettingsFields fields, String preset) {
        fields.voiceTransport.setText("websocket");
        fields.vadEnabled.setSelected(true);
        fields.vadProvider.setSelectedItem("silero_onnx");
        fields.pcmNormalizeEnabled.setSelected(true);
        fields.pcmSoftClipEnabled.setSelected(true);
        fields.keepListening.setSelected(true);
        fields.ttsInterruptOnSpeech.setSelected(true);
        switch (preset) {
            case "quiet" -> {
                fields.threshold.setValue(35);
                fields.adaptiveNoiseEnabled.setSelected(true);
                fields.adaptiveNoiseWarmup.setValue(450);
                fields.adaptiveNoiseMultiplier.setValue(25);
                fields.adaptiveNoiseMinThreshold.setValue(25);
                fields.vadConfidence.setValue(45);
                fields.vadMinSpeechFrames.setValue(2);
                fields.vadHangoverFrames.setValue(6);
                fields.pcmNormalizeTarget.setValue(35);
                fields.pcmNormalizeMaxGain.setValue(30);
                fields.silence.setValue(800);
                fields.maxRecording.setValue(6000);
                fields.ttsSuppress.setValue(1600);
                fields.ttsInterruptThreshold.setValue(150);
                status = Status.listening("voice preset: Quiet Room");
            }
            case "bgm" -> {
                fields.threshold.setValue(60);
                fields.adaptiveNoiseEnabled.setSelected(true);
                fields.adaptiveNoiseWarmup.setValue(700);
                fields.adaptiveNoiseMultiplier.setValue(42);
                fields.adaptiveNoiseMinThreshold.setValue(45);
                fields.vadConfidence.setValue(60);
                fields.vadMinSpeechFrames.setValue(3);
                fields.vadHangoverFrames.setValue(7);
                fields.pcmNormalizeTarget.setValue(32);
                fields.pcmNormalizeMaxGain.setValue(25);
                fields.silence.setValue(950);
                fields.maxRecording.setValue(7000);
                fields.ttsSuppress.setValue(2200);
                fields.ttsInterruptThreshold.setValue(200);
                status = Status.listening("voice preset: Minecraft BGM");
            }
            case "kids" -> {
                fields.threshold.setValue(25);
                fields.adaptiveNoiseEnabled.setSelected(true);
                fields.adaptiveNoiseWarmup.setValue(400);
                fields.adaptiveNoiseMultiplier.setValue(24);
                fields.adaptiveNoiseMinThreshold.setValue(20);
                fields.vadConfidence.setValue(42);
                fields.vadMinSpeechFrames.setValue(2);
                fields.vadHangoverFrames.setValue(8);
                fields.pcmNormalizeTarget.setValue(48);
                fields.pcmNormalizeMaxGain.setValue(45);
                fields.silence.setValue(1050);
                fields.maxRecording.setValue(8000);
                fields.childModeEnabled.setSelected(true);
                fields.childModeMaterialAssist.setSelected(true);
                fields.ttsSuppress.setValue(1900);
                fields.ttsInterruptThreshold.setValue(140);
                status = Status.listening("voice preset: Kids");
            }
            case "demo" -> {
                fields.threshold.setValue(40);
                fields.adaptiveNoiseEnabled.setSelected(true);
                fields.adaptiveNoiseWarmup.setValue(250);
                fields.adaptiveNoiseMultiplier.setValue(26);
                fields.adaptiveNoiseMinThreshold.setValue(30);
                fields.vadConfidence.setValue(50);
                fields.vadMinSpeechFrames.setValue(1);
                fields.vadHangoverFrames.setValue(4);
                fields.pcmNormalizeTarget.setValue(42);
                fields.pcmNormalizeMaxGain.setValue(35);
                fields.silence.setValue(550);
                fields.maxRecording.setValue(5000);
                fields.ttsSuppress.setValue(1100);
                fields.ttsInterruptThreshold.setValue(180);
                status = Status.listening("voice preset: Demo Fast");
            }
            default -> status = Status.listening("voice preset unchanged");
        }
    }

    private SettingsFields createSettingsFields() {
        SettingsFields fields = new SettingsFields();
        fields.threshold = slider(1, 80, (int) Math.round(config.speechRmsThreshold * 10000.0D), 20, 10);
        fields.speechProvider = new JComboBox<>(new String[] {"AmiVoice", "Whisper"});
        fields.voiceTransport = new JTextField(config.voiceTransport);
        fields.adaptiveNoiseEnabled = new JCheckBox();
        fields.adaptiveNoiseWarmup = slider(100, 2000, config.adaptiveNoiseWarmupMillis, 100, 500);
        fields.adaptiveNoiseMultiplier = slider(12, 80, (int) Math.round(config.adaptiveNoiseMultiplier * 10.0D), 2, 10);
        fields.adaptiveNoiseMinThreshold = slider(10, 80, (int) Math.round(config.adaptiveNoiseMinThreshold * 10000.0D), 5, 10);
        fields.vadEnabled = new JCheckBox();
        fields.vadProvider = new JComboBox<>(new String[] {"silero_onnx", "rms"});
        fields.vadModelPath = new JTextField(config.vadModelPath);
        fields.vadConfidence = slider(5, 95, (int) Math.round(config.vadConfidenceThreshold * 100.0D), 5, 10);
        fields.vadMinSpeechFrames = slider(1, 10, config.vadMinSpeechFrames, 1, 3);
        fields.vadHangoverFrames = slider(0, 30, config.vadHangoverFrames, 1, 5);
        fields.pcmNormalizeEnabled = new JCheckBox();
        fields.pcmNormalizeTarget = slider(5, 200, (int) Math.round(config.pcmNormalizeTargetRms * 1000.0D), 5, 25);
        fields.pcmNormalizeMaxGain = slider(10, 80, (int) Math.round(config.pcmNormalizeMaxGain * 10.0D), 5, 10);
        fields.pcmSoftClipEnabled = new JCheckBox();
        fields.silence = slider(250, 3000, config.silenceMillis, 250, 500);
        fields.maxRecording = slider(1000, 20000, config.maxRecordingMillis, 1000, 3000);
        fields.micMixerName = new JComboBox<>(inputDeviceOptions(config.micMixerName));
        fields.keepListening = new JCheckBox();
        fields.amivoiceApiKey = new JPasswordField(config.amivoiceApiKey);
        fields.amivoiceEngine = new JTextField(config.amivoiceEngine);
        fields.openaiApiKey = new JPasswordField(config.openaiApiKey);
        fields.openaiModel = new JTextField(config.openaiModel);
        fields.openaiTranscriptionModel = new JTextField(config.openaiTranscriptionModel);
        fields.llmFallbackEnabled = new JCheckBox();
        fields.openaiNormalizerModel = new JTextField(config.openaiNormalizerModel);
        fields.openaiNormalizerEnabled = new JCheckBox();
        fields.modUtteranceUrl = new JTextField(config.modUtteranceUrl);
        fields.bridgePort = new JTextField(Integer.toString(config.bridgePort));
        fields.assistMode = new JComboBox<>(new String[] {"World Assist", "Balanced", "Survival", "Off"});
        fields.worldAssistEnabled = new JCheckBox();
        fields.worldAssistMaterialTopUp = new JCheckBox();
        fields.worldAssistDirectCraft = new JCheckBox();
        fields.worldAssistRareItems = new JCheckBox();
        fields.programmaticExploreDistance = slider(64, 1024, config.programmaticExploreDistanceBlocks, 64, 256);
        fields.programmaticBoatDistance = slider(16, 1024, config.programmaticBoatTravelDistanceBlocks, 32, 256);
        fields.childModeEnabled = new JCheckBox();
        fields.childModeMaterialAssist = new JCheckBox();
        fields.childShelterTarget = slider(4, 24, config.childShelterMaterialTarget, 2, 4);
        fields.ttsEnabled = new JCheckBox();
        fields.ttsVoice = new JTextField(config.ttsVoice);
        fields.ttsRate = slider(120, 260, config.ttsRate, 10, 20);
        fields.ttsSuppress = slider(0, 4000, config.ttsMicSuppressMillis, 250, 1000);
        fields.ttsInterruptOnSpeech = new JCheckBox();
        fields.ttsInterruptThreshold = slider(10, 200, (int) Math.round(config.ttsInterruptRmsThreshold * 10000.0D), 10, 25);
        loadFields(fields, config);
        styleSettingsFields(fields);
        return fields;
    }

    private void styleSettingsFields(SettingsFields fields) {
        fields.speechProvider.setBackground(SURFACE);
        fields.speechProvider.setForeground(TEXT);
        fields.assistMode.setBackground(SURFACE);
        fields.assistMode.setForeground(TEXT);
        fields.vadProvider.setBackground(SURFACE);
        fields.vadProvider.setForeground(TEXT);
        fields.micMixerName.setBackground(SURFACE);
        fields.micMixerName.setForeground(TEXT);
        styleTextField(fields.voiceTransport);
        styleTextField(fields.vadModelPath);
        styleTextField(fields.amivoiceApiKey);
        styleTextField(fields.amivoiceEngine);
        styleTextField(fields.openaiApiKey);
        styleTextField(fields.openaiModel);
        styleTextField(fields.openaiTranscriptionModel);
        styleTextField(fields.openaiNormalizerModel);
        styleTextField(fields.modUtteranceUrl);
        styleTextField(fields.bridgePort);
        styleTextField(fields.ttsVoice);
        for (JCheckBox check : new JCheckBox[] { fields.adaptiveNoiseEnabled, fields.vadEnabled, fields.pcmNormalizeEnabled, fields.pcmSoftClipEnabled, fields.keepListening, fields.llmFallbackEnabled, fields.openaiNormalizerEnabled, fields.worldAssistEnabled, fields.worldAssistMaterialTopUp, fields.worldAssistDirectCraft, fields.worldAssistRareItems, fields.childModeEnabled, fields.childModeMaterialAssist, fields.ttsEnabled, fields.ttsInterruptOnSpeech }) {
            check.setForeground(TEXT);
        }
    }

    private void styleTextField(JTextField field) {
        field.setBackground(SURFACE);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            new EmptyBorder(6, 8, 6, 8)
        ));
    }

    private JSlider slider(int min, int max, int value, int minor, int major) {
        JSlider slider = new JSlider(min, max, Math.max(min, Math.min(value, max)));
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setMinorTickSpacing(minor);
        slider.setMajorTickSpacing(major);
        slider.setOpaque(false);
        return slider;
    }

    private int addSection(JPanel panel, GridBagConstraints gc, int row, String title) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15.0F));
        label.setForeground(TEXT);
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        gc.insets = new Insets(row == 0 ? 2 : 18, 6, 4, 6);
        panel.add(label, gc);
        gc.gridwidth = 1;
        gc.insets = new Insets(6, 6, 6, 6);
        return row + 1;
    }

    private int addText(JPanel panel, GridBagConstraints gc, int row, String label, JTextField field, String help) {
        return addField(panel, gc, row, label, field, help);
    }

    private int addCombo(JPanel panel, GridBagConstraints gc, int row, String label, JComboBox<String> field, String help) {
        return addField(panel, gc, row, label, field, help);
    }

    private int addPassword(JPanel panel, GridBagConstraints gc, int row, String label, JPasswordField field) {
        return addField(panel, gc, row, label, field, "Saved only in local Minecraft config.");
    }

    private int addSlider(JPanel panel, GridBagConstraints gc, int row, String label, JSlider slider, String help) {
        return addField(panel, gc, row, label + " (" + slider.getValue() + ")", sliderWithValue(slider), help);
    }

    private JPanel sliderWithValue(JSlider slider) {
        JPanel wrapper = new JPanel(new BorderLayout(8, 0));
        wrapper.setOpaque(false);
        JLabel value = new JLabel(Integer.toString(slider.getValue()), SwingConstants.RIGHT);
        value.setPreferredSize(new Dimension(54, 24));
        slider.addChangeListener(event -> value.setText(Integer.toString(slider.getValue())));
        wrapper.add(slider, BorderLayout.CENTER);
        wrapper.add(value, BorderLayout.EAST);
        return wrapper;
    }

    private int addCheck(JPanel panel, GridBagConstraints gc, int row, JCheckBox check, String label) {
        check.setText(label);
        check.setOpaque(false);
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        panel.add(check, gc);
        gc.gridwidth = 1;
        return row + 1;
    }

    private int addField(JPanel panel, GridBagConstraints gc, int row, String label, Component field, String help) {
        JLabel left = new JLabel("<html><b>" + htmlEscape(label) + "</b><br><span style='color:#667085'>" + htmlEscape(help) + "</span></html>");
        left.setForeground(TEXT);
        gc.gridx = 0;
        gc.gridy = row;
        gc.weightx = 0.32D;
        panel.add(left, gc);
        gc.gridx = 1;
        gc.weightx = 0.68D;
        panel.add(field, gc);
        return row + 1;
    }

    private void loadFields(SettingsFields fields, Config loaded) {
        fields.threshold.setValue((int) Math.round(loaded.speechRmsThreshold * 10000.0D));
        fields.speechProvider.setSelectedItem(loaded.useWhisperProvider() ? "Whisper" : "AmiVoice");
        fields.voiceTransport.setText(loaded.voiceTransport);
        fields.adaptiveNoiseEnabled.setSelected(loaded.adaptiveNoiseEnabled);
        fields.adaptiveNoiseWarmup.setValue(loaded.adaptiveNoiseWarmupMillis);
        fields.adaptiveNoiseMultiplier.setValue((int) Math.round(loaded.adaptiveNoiseMultiplier * 10.0D));
        fields.adaptiveNoiseMinThreshold.setValue((int) Math.round(loaded.adaptiveNoiseMinThreshold * 10000.0D));
        fields.vadEnabled.setSelected(loaded.vadEnabled);
        fields.vadProvider.setSelectedItem(loaded.vadProvider);
        fields.vadModelPath.setText(loaded.vadModelPath);
        fields.vadConfidence.setValue((int) Math.round(loaded.vadConfidenceThreshold * 100.0D));
        fields.vadMinSpeechFrames.setValue(loaded.vadMinSpeechFrames);
        fields.vadHangoverFrames.setValue(loaded.vadHangoverFrames);
        fields.pcmNormalizeEnabled.setSelected(loaded.pcmNormalizeEnabled);
        fields.pcmNormalizeTarget.setValue((int) Math.round(loaded.pcmNormalizeTargetRms * 1000.0D));
        fields.pcmNormalizeMaxGain.setValue((int) Math.round(loaded.pcmNormalizeMaxGain * 10.0D));
        fields.pcmSoftClipEnabled.setSelected(loaded.pcmSoftClipEnabled);
        fields.silence.setValue(loaded.silenceMillis);
        fields.maxRecording.setValue(loaded.maxRecordingMillis);
        reloadInputDeviceOptions(fields.micMixerName, loaded.micMixerName);
        fields.keepListening.setSelected(loaded.keepListeningOnError);
        fields.amivoiceApiKey.setText(loaded.amivoiceApiKey);
        fields.amivoiceEngine.setText(loaded.amivoiceEngine);
        fields.openaiApiKey.setText(loaded.openaiApiKey);
        fields.openaiModel.setText(loaded.openaiModel);
        fields.openaiTranscriptionModel.setText(loaded.openaiTranscriptionModel);
        fields.llmFallbackEnabled.setSelected(loaded.llmFallbackEnabled);
        fields.openaiNormalizerModel.setText(loaded.openaiNormalizerModel);
        fields.openaiNormalizerEnabled.setSelected(loaded.openaiNormalizerEnabled);
        fields.modUtteranceUrl.setText(loaded.modUtteranceUrl);
        fields.bridgePort.setText(Integer.toString(loaded.bridgePort));
        fields.assistMode.setSelectedItem(displayAssistMode(loaded.assistMode));
        fields.worldAssistEnabled.setSelected(loaded.worldAssistEnabled);
        fields.worldAssistMaterialTopUp.setSelected(loaded.worldAssistMaterialTopUp);
        fields.worldAssistDirectCraft.setSelected(loaded.worldAssistDirectCraft);
        fields.worldAssistRareItems.setSelected(loaded.worldAssistRareItems);
        fields.programmaticExploreDistance.setValue(loaded.programmaticExploreDistanceBlocks);
        fields.programmaticBoatDistance.setValue(loaded.programmaticBoatTravelDistanceBlocks);
        fields.childModeEnabled.setSelected(loaded.childModeEnabled);
        fields.childModeMaterialAssist.setSelected(loaded.childModeMaterialAssist);
        fields.childShelterTarget.setValue(loaded.childShelterMaterialTarget);
        fields.ttsEnabled.setSelected(loaded.ttsEnabled);
        fields.ttsVoice.setText(loaded.ttsVoice);
        fields.ttsRate.setValue(loaded.ttsRate);
        fields.ttsSuppress.setValue(loaded.ttsMicSuppressMillis);
        fields.ttsInterruptOnSpeech.setSelected(loaded.ttsInterruptOnSpeech);
        fields.ttsInterruptThreshold.setValue((int) Math.round(loaded.ttsInterruptRmsThreshold * 10000.0D));
    }

    private void applySettings(SettingsFields fields) {
        config = config.withSettings(
            stringValue(fields.bridgePort, Integer.toString(config.bridgePort)),
            fields.modUtteranceUrl.getText(),
            providerValue(fields.speechProvider),
            passwordValue(fields.amivoiceApiKey),
            fields.amivoiceEngine.getText(),
            fields.voiceTransport.getText(),
            fields.threshold.getValue() / 10000.0D,
            fields.adaptiveNoiseEnabled.isSelected(),
            fields.adaptiveNoiseWarmup.getValue(),
            fields.adaptiveNoiseMultiplier.getValue() / 10.0D,
            fields.adaptiveNoiseMinThreshold.getValue() / 10000.0D,
            fields.vadEnabled.isSelected(),
            providerValue(fields.vadProvider, "silero_onnx"),
            fields.vadModelPath.getText(),
            fields.vadConfidence.getValue() / 100.0D,
            fields.vadMinSpeechFrames.getValue(),
            fields.vadHangoverFrames.getValue(),
            fields.pcmNormalizeEnabled.isSelected(),
            fields.pcmNormalizeTarget.getValue() / 1000.0D,
            fields.pcmNormalizeMaxGain.getValue() / 10.0D,
            fields.pcmSoftClipEnabled.isSelected(),
            fields.silence.getValue(),
            fields.maxRecording.getValue(),
            micMixerConfigValue(fields.micMixerName),
            fields.keepListening.isSelected(),
            passwordValue(fields.openaiApiKey),
            fields.openaiModel.getText(),
            fields.openaiTranscriptionModel.getText(),
            fields.llmFallbackEnabled.isSelected(),
            fields.openaiNormalizerModel.getText(),
            fields.openaiNormalizerEnabled.isSelected(),
            assistModeValue(fields.assistMode),
            fields.worldAssistEnabled.isSelected(),
            fields.worldAssistMaterialTopUp.isSelected(),
            fields.worldAssistDirectCraft.isSelected(),
            fields.worldAssistRareItems.isSelected(),
            fields.programmaticExploreDistance.getValue(),
            fields.programmaticBoatDistance.getValue(),
            fields.childModeEnabled.isSelected(),
            fields.childModeMaterialAssist.isSelected(),
            fields.childShelterTarget.getValue(),
            fields.ttsEnabled.isSelected(),
            fields.ttsVoice.getText(),
            fields.ttsRate.getValue(),
            fields.ttsSuppress.getValue(),
            fields.ttsInterruptOnSpeech.isSelected(),
            fields.ttsInterruptThreshold.getValue() / 10000.0D
        );
        ttsEnabled.set(config.ttsEnabled);
    }

    private String stringValue(JTextField field, String fallback) {
        String value = field.getText();
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String micMixerConfigValue(JComboBox<String> field) {
        Object selected = field.getSelectedItem();
        String value = selected == null ? "" : selected.toString().trim();
        return DEFAULT_MIC_DEVICE_LABEL.equals(value) ? "" : value;
    }

    private String[] inputDeviceOptions(String configuredDevice) {
        List<String> devices = new ArrayList<>();
        devices.add(DEFAULT_MIC_DEVICE_LABEL);
        String configured = configuredDevice == null ? "" : configuredDevice.trim();
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (!mixer.isLineSupported(targetInfo)) {
                    continue;
                }
                String label = mixerDisplayName(mixerInfo);
                if (!devices.contains(label)) {
                    devices.add(label);
                }
            } catch (RuntimeException ignored) {
                // Some virtual devices throw while probing. Leave them out of the picker.
            }
        }
        if (!configured.isBlank() && devices.stream().noneMatch(device -> mixerMatches(configured, device))) {
            devices.add(configured);
        }
        return devices.toArray(String[]::new);
    }

    private void reloadInputDeviceOptions(JComboBox<String> field, String configuredDevice) {
        field.removeAllItems();
        for (String option : inputDeviceOptions(configuredDevice)) {
            field.addItem(option);
        }
        String configured = configuredDevice == null ? "" : configuredDevice.trim();
        field.setSelectedItem(configured.isBlank() ? DEFAULT_MIC_DEVICE_LABEL : configured);
    }

    private TargetDataLine openTargetDataLine() throws LineUnavailableException {
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        String wanted = config.micMixerName == null ? "" : config.micMixerName.trim();
        if (!wanted.isBlank()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (!mixer.isLineSupported(targetInfo) || !mixerMatches(wanted, mixerInfo)) {
                    continue;
                }
                TargetDataLine line = (TargetDataLine) mixer.getLine(targetInfo);
                line.open(FORMAT);
                currentMicDeviceName = mixerDisplayName(mixerInfo);
                return line;
            }
            throw new LineUnavailableException("Configured microphone not found: " + wanted);
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(targetInfo);
        line.open(FORMAT);
        currentMicDeviceName = DEFAULT_MIC_DEVICE_LABEL;
        return line;
    }

    private static boolean mixerMatches(String wanted, Mixer.Info mixerInfo) {
        return mixerMatches(wanted, mixerDisplayName(mixerInfo))
            || wanted.equals(mixerInfo.getName())
            || wanted.equals(mixerInfo.getDescription());
    }

    private static boolean mixerMatches(String wanted, String candidate) {
        return wanted != null && candidate != null && wanted.trim().equals(candidate.trim());
    }

    private static String mixerDisplayName(Mixer.Info mixerInfo) {
        String name = mixerInfo.getName() == null ? "" : mixerInfo.getName().trim();
        String description = mixerInfo.getDescription() == null ? "" : mixerInfo.getDescription().trim();
        if (description.isBlank() || description.equals(name)) {
            return name.isBlank() ? "Unnamed microphone" : name;
        }
        return name + " - " + description;
    }

    private static String compactDeviceName(String name) {
        String clean = name == null || name.isBlank() ? DEFAULT_MIC_DEVICE_LABEL : name.trim();
        return clean.length() <= 42 ? clean : clean.substring(0, 39) + "...";
    }

    private String passwordValue(JPasswordField field) {
        return new String(field.getPassword()).trim();
    }

    private String providerValue(JComboBox<String> field) {
        Object selected = field.getSelectedItem();
        String value = selected == null ? "" : selected.toString();
        return value.equalsIgnoreCase("whisper") ? "whisper" : "amivoice";
    }

    private String providerValue(JComboBox<String> field, String fallback) {
        Object selected = field.getSelectedItem();
        String value = selected == null ? "" : selected.toString().trim();
        return value.isBlank() ? fallback : value;
    }

    private String assistModeValue(JComboBox<String> field) {
        Object selected = field.getSelectedItem();
        String value = selected == null ? "" : selected.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "off" -> "off";
            case "survival", "strict" -> "survival";
            case "balanced" -> "balanced";
            case "programmatic", "world assist", "world_assist", "worldassist" -> "world_assist";
            default -> "world_assist";
        };
    }

    private String displayAssistMode(String mode) {
        return switch (mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> "Off";
            case "survival", "strict" -> "Survival";
            case "balanced" -> "Balanced";
            case "programmatic", "world_assist", "worldassist" -> "World Assist";
            default -> "World Assist";
        };
    }

    private void saveConfig(JFrame frame, boolean showDialog) {
        try {
            config.save();
            if (showDialog) {
                JOptionPane.showMessageDialog(frame, "Settings saved to Minecraft config.", "Saved", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException error) {
            JOptionPane.showMessageDialog(frame, compactError(error), "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Image appIcon() {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x111827));
        g.fillRoundRect(8, 8, 80, 80, 22, 22);
        g.setColor(new Color(0x22C55E));
        g.setStroke(new BasicStroke(7.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(27, 18, 42, 42, 200, 140);
        g.drawLine(48, 56, 48, 72);
        g.drawLine(36, 72, 60, 72);
        g.setColor(new Color(0xF8FAFC));
        g.setStroke(new BasicStroke(4.5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(30, 33, 22, 25);
        g.drawLine(66, 33, 74, 25);
        g.drawLine(30, 47, 20, 47);
        g.drawLine(66, 47, 76, 47);
        g.dispose();
        return image;
    }

    private void runMicCheck(JFrame frame) {
        if (enabled.get()) {
            JOptionPane.showMessageDialog(frame, "Mic Test needs Mic OFF. Turn the mic off first.", "Mic Test", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        status = Status.listening("mic test: quiet 2s, then speak 3s");
        recorderExecutor.submit(() -> {
            try {
                MicDiagnosticResult result = performMicCheck();
                status = Status.listening(result.shortSummary());
                EventQueue.invokeLater(() -> showMicTestDialog(frame, result));
            } catch (Exception error) {
                status = Status.error("mic test failed: " + compactError(error));
                EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(frame, compactError(error), "Mic Test failed", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private MicDiagnosticResult performMicCheck() throws LineUnavailableException {
        try (TargetDataLine line = openTargetDataLine()) {
            line.start();
            byte[] buffer = new byte[BUFFER_SIZE];
            long started = System.nanoTime();
            long quietNanos = Duration.ofMillis(2_000L).toNanos();
            long totalNanos = Duration.ofMillis(5_500L).toNanos();
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            List<Double> rmsTimeline = new ArrayList<>();
            List<Double> vadTimeline = new ArrayList<>();
            double quietMaxRms = 0.0D;
            double quietSumRms = 0.0D;
            double quietMaxVad = 0.0D;
            int quietSamples = 0;
            double speechMaxRms = 0.0D;
            double speechSumRms = 0.0D;
            double speechMaxVad = 0.0D;
            int speechSamples = 0;
            AdaptiveNoiseGate noiseGate = new AdaptiveNoiseGate(config);
            try (VoiceActivityGate voiceGate = VoiceActivityGate.create(config)) {
                while (System.nanoTime() - started < totalNanos) {
                    int read = line.read(buffer, 0, buffer.length);
                    if (read <= 0) {
                        continue;
                    }
                    capture.write(buffer, 0, read);
                    long elapsed = System.nanoTime() - started;
                    double rms = rms16le(buffer, read);
                    double threshold = noiseGate.updateAndThreshold(rms, Duration.ofNanos(elapsed), false);
                    VoiceActivityGate.Decision voice = voiceGate.evaluate(buffer, read, rms >= threshold, false);
                    rmsTimeline.add(rms);
                    vadTimeline.add(voice.confidence());
                    if (elapsed <= quietNanos) {
                        quietMaxRms = Math.max(quietMaxRms, rms);
                        quietSumRms += rms;
                        quietMaxVad = Math.max(quietMaxVad, voice.confidence());
                        quietSamples++;
                        status = new Status("mic-check", true, false, false, rms, quietMaxRms, voice.confidence(), voice.provider(), voice.fallbackReason(), "stay quiet...");
                    } else {
                        speechMaxRms = Math.max(speechMaxRms, rms);
                        speechSumRms += rms;
                        speechMaxVad = Math.max(speechMaxVad, voice.confidence());
                        speechSamples++;
                        status = new Status("mic-check", true, voice.confidence() >= config.vadConfidenceThreshold, true, rms, Math.max(quietMaxRms, speechMaxRms), voice.confidence(), voice.provider(), voice.fallbackReason(), "speak now...");
                    }
                }
            }
            line.stop();
            line.flush();
            double quietAvg = quietSamples == 0 ? 0.0D : quietSumRms / quietSamples;
            double speechAvg = speechSamples == 0 ? 0.0D : speechSumRms / speechSamples;
            double recommendedThreshold = Math.max(
                0.001D,
                Math.min(0.08D, Math.max(config.speechRmsThreshold, Math.max(quietAvg * 3.0D, quietMaxRms * 1.6D)))
            );
            return new MicDiagnosticResult(
                quietAvg,
                quietMaxRms,
                quietMaxVad,
                speechAvg,
                speechMaxRms,
                speechMaxVad,
                recommendedThreshold,
                config.vadConfidenceThreshold,
                currentMicDeviceName,
                capture.toByteArray(),
                toDoubleArray(rmsTimeline),
                toDoubleArray(vadTimeline),
                quietSamples
            );
        }
    }

    private void showMicTestDialog(JFrame frame, MicDiagnosticResult result) {
        JDialog dialog = new JDialog(frame, "KoeCraft Mic Test", true);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(APP_BG);
        root.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel summary = new JLabel("<html>" + htmlEscape(result.message()).replace("\n", "<br>") + "</html>");
        summary.setForeground(TEXT);
        root.add(summary, BorderLayout.NORTH);

        RmsVadGraphPanel graph = new RmsVadGraphPanel(result.rmsTimeline(), result.vadTimeline(), result.quietFrameCount());
        graph.setPreferredSize(new Dimension(620, 190));
        root.add(graph, BorderLayout.CENTER);

        JButton play = primaryButton("Play Recording");
        play.setPreferredSize(new Dimension(160, 40));
        play.addActionListener(event -> recorderExecutor.submit(() -> {
            try {
                playPcm(result.pcm());
            } catch (Exception error) {
                EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(dialog, compactError(error), "Playback failed", JOptionPane.ERROR_MESSAGE));
            }
        }));
        JButton close = neutralButton("Close");
        close.addActionListener(event -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(play);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void playPcm(byte[] pcm) throws LineUnavailableException {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            line.open(FORMAT);
            line.start();
            line.write(pcm, 0, pcm.length);
            line.drain();
            line.stop();
        }
    }

    private static double[] toDoubleArray(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private void recordingLoop() {
        while (enabled.get()) {
            try {
                if (!enabled.get()) {
                    return;
                }
                if (muted.get()) {
                    status = Status.muted();
                    Thread.sleep(120L);
                    continue;
                }
                String text;
                if (config.useWhisperProvider()) {
                    Recorded recorded = recordUntilSilence();
                    if (recorded.suppressed) {
                        status = Status.listening("ignored local TTS audio");
                        continue;
                    }
                    if (!recorded.detectedSpeech) {
                        status = Status.listening("no speech detected maxRms=" + round(recorded.maxRms));
                        continue;
                    }
                    status = Status.recognizing("Whisper recognizing...");
                    text = recognizeWhisper(recorded.wav);
                } else if (config.useWebSocketTransport()) {
                    StreamingRecognized streamed = recognizeStreamingFromMic();
                    if (streamed.suppressed) {
                        status = Status.listening("ignored local TTS audio");
                        continue;
                    }
                    if (!streamed.detectedSpeech || streamed.text.isBlank()) {
                        status = Status.listening("no speech detected maxRms=" + round(streamed.maxRms));
                        continue;
                    }
                    text = streamed.text;
                } else {
                    Recorded recorded = recordUntilSilence();
                    if (recorded.suppressed) {
                        status = Status.listening("ignored local TTS audio");
                        continue;
                    }
                    if (!recorded.detectedSpeech) {
                        status = Status.listening("no speech detected maxRms=" + round(recorded.maxRms));
                        continue;
                    }
                    status = Status.recognizing();
                    text = recognize(recorded.wav);
                }
                if (isLikelyLocalTtsEcho(text)) {
                    status = Status.listening("ignored local TTS echo");
                    continue;
                }
                status = Status.executing(text);
                postUtterance(text);
                consecutiveRecoverableErrors = 0;
                status = Status.listening();
            } catch (Exception error) {
                handleRecordingError(error);
            }
        }
    }

    private StreamingRecognized recognizeStreamingFromMic() throws Exception {
        if (config.amivoiceApiKey.isBlank()) {
            throw new IOException("AmiVoice API key is missing. Open Settings and save amivoice.apiKey.");
        }
        AmiVoiceStreamingListener listener = new AmiVoiceStreamingListener();
        WebSocket socket = httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .buildAsync(amivoiceWebSocketUri(), listener)
            .get(10, TimeUnit.SECONDS);
        socket.sendText(buildAmiVoiceWebSocketStartCommand(), true).join();
        listener.awaitStarted(10, TimeUnit.SECONDS);

        try (TargetDataLine line = openTargetDataLine()) {
            line.start();
            byte[] buffer = new byte[BUFFER_SIZE];
            long started = System.nanoTime();
            long lastSpeech = started;
            boolean detectedSpeech = false;
            boolean suppressed = false;
            double maxRms = 0.0D;
            AdaptiveNoiseGate noiseGate = new AdaptiveNoiseGate(config);
            Deque<byte[]> preRoll = new ArrayDeque<>();
            try (VoiceActivityGate voiceGate = VoiceActivityGate.create(config)) {
            while (enabled.get() && !muted.get()) {
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                double rms = rms16le(buffer, read);
                maxRms = Math.max(maxRms, rms);
                long now = System.nanoTime();
                maybeInterruptTtsFromMic(rms);
                if (isMicSuppressed()) {
                    suppressed = true;
                    detectedSpeech = false;
                    preRoll.clear();
                    lastSpeech = now;
                    VoiceActivityGate.GateStatus gateStatus = voiceGate.status();
                    status = new Status("listening", true, false, false, rms, maxRms, gateStatus.confidence(), gateStatus.provider(), gateStatus.fallbackReason(), "suppressing local TTS");
                    continue;
                }
                Duration elapsed = Duration.ofNanos(now - started);
                double threshold = noiseGate.updateAndThreshold(rms, elapsed, detectedSpeech);
                boolean rmsActive = rms >= threshold;
                VoiceActivityGate.Decision voice = voiceGate.evaluate(buffer, read, rmsActive, detectedSpeech);
                boolean speech = voice.speechActive();
                if (!detectedSpeech) {
                    preRoll.addLast(Arrays.copyOf(buffer, read));
                    while (preRoll.size() > PRE_ROLL_BUFFERS) {
                        preRoll.removeFirst();
                    }
                }
                if (speech) {
                    if (!detectedSpeech) {
                        for (byte[] chunk : preRoll) {
                            byte[] processed = processPcmForAsr(chunk, chunk.length, config);
                            sendAmiVoicePcmChunk(socket, processed, processed.length);
                        }
                        preRoll.clear();
                    }
                    detectedSpeech = true;
                    lastSpeech = now;
                }
                if (detectedSpeech) {
                    byte[] processed = processPcmForAsr(buffer, read, config);
                    sendAmiVoicePcmChunk(socket, processed, processed.length);
                }
                Duration silence = Duration.ofNanos(now - lastSpeech);
                status = new Status("listening", true, speech, detectedSpeech, rms, maxRms, voice.confidence(), voice.provider(), voice.fallbackReason(), "streaming pcm threshold=" + round(threshold) + " vad=" + voice.provider() + ":" + round(voice.confidence()));
                if (listener.shouldStopForEarlyShortCommand()) break;
                if (detectedSpeech && silence.toMillis() >= config.silenceMillis) break;
                if (elapsed.toMillis() >= config.maxRecordingMillis) break;
            }
            }
            line.stop();
            line.flush();
            status = Status.recognizing();
            socket.sendText("e", true).join();
            String text = listener.awaitFinalText(12, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            boolean recognized = detectedSpeech || !text.isBlank();
            return new StreamingRecognized(text, recognized, maxRms, suppressed);
        } catch (Exception error) {
            try {
                socket.abort();
            } catch (RuntimeException ignored) {
                // Ignore close errors while surfacing the recognition failure.
            }
            throw error;
        }
    }

    private void handleRecordingError(Exception error) {
        String message = compactError(error);
        System.err.println("[KoeCraft Mic Bridge] " + error);
        if (isFatalMicError(error)) {
            enabled.set(false);
            status = Status.error(message);
            return;
        }
        consecutiveRecoverableErrors++;
        if (!config.keepListeningOnError) {
            enabled.set(false);
            status = Status.error(message);
            return;
        }
        status = Status.listening("recoverable error " + consecutiveRecoverableErrors + ": " + message);
        try {
            Thread.sleep(Math.min(1200L, 250L * consecutiveRecoverableErrors));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isFatalMicError(Exception error) {
        return error instanceof LineUnavailableException || error instanceof SecurityException;
    }

    private String compactError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.toString();
        }
        return message.length() <= 96 ? message : message.substring(0, 93) + "...";
    }

    private void handleSpeak(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String text = sanitizeSpeechText(extractJsonString(body, "text"));
        if (text.isBlank()) {
            respond(exchange, 400, "{\"ok\":false,\"error\":\"missing_text\"}");
            return;
        }
        lastSpokenText = text;
        if (!ttsEnabled.get()) {
            respond(exchange, 200, "{\"ok\":true,\"spoken\":false,\"disabled\":true}");
            return;
        }
        speakAsync(text);
        respond(exchange, 202, "{\"ok\":true,\"spoken\":true}");
    }

    private void handleTtsToggle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        boolean enabledNow = !ttsEnabled.get();
        ttsEnabled.set(enabledNow);
        respond(exchange, 200, "{\"ok\":true,\"tts_enabled\":" + enabledNow + "}");
    }

    private void speakAsync(String text) {
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "KoeCraft Mic Bridge TTS");
            thread.setDaemon(true);
            return thread;
        }).submit(() -> {
            try {
                ttsSpeaking.set(true);
                suppressMicUntilMs = System.currentTimeMillis() + config.ttsMicSuppressMillis;
                runSystemTts(text);
            } catch (Exception error) {
                System.err.println("[KoeCraft Mic Bridge] TTS failed: " + error);
            } finally {
                ttsSpeaking.set(false);
                lastTtsEndedMs = System.currentTimeMillis();
                suppressMicUntilMs = lastTtsEndedMs + config.ttsMicSuppressMillis;
            }
        });
    }

    private void runSystemTts(String text) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        ProcessBuilder builder;
        if (os.contains("mac")) {
            builder = new ProcessBuilder("say", "-v", config.ttsVoice, "-r", Integer.toString(config.ttsRate), text);
        } else if (os.contains("win")) {
            String escaped = text.replace("'", "''");
            builder = new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-Command",
                "Add-Type -AssemblyName System.Speech; $s=New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Rate=0; $s.Speak('" + escaped + "');"
            );
        } else {
            builder = new ProcessBuilder("sh", "-lc", "command -v spd-say >/dev/null && spd-say \"$1\" || true", "koecraft-tts", text);
        }
        Process process = builder.redirectErrorStream(true).start();
        activeTtsProcess = process;
        try {
            process.waitFor();
        } finally {
            if (activeTtsProcess == process) {
                activeTtsProcess = null;
            }
        }
    }

    private Recorded recordUntilSilence() throws LineUnavailableException, IOException {
        try (TargetDataLine line = openTargetDataLine()) {
            line.start();
            byte[] buffer = new byte[BUFFER_SIZE];
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            long started = System.nanoTime();
            long lastSpeech = started;
            boolean detectedSpeech = false;
            boolean suppressed = false;
            double maxRms = 0.0D;
            AdaptiveNoiseGate noiseGate = new AdaptiveNoiseGate(config);
            Deque<byte[]> preRoll = new ArrayDeque<>();
            try (VoiceActivityGate voiceGate = VoiceActivityGate.create(config)) {
            while (enabled.get() && !muted.get()) {
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                double rms = rms16le(buffer, read);
                maxRms = Math.max(maxRms, rms);
                long now = System.nanoTime();
                maybeInterruptTtsFromMic(rms);
                if (isMicSuppressed()) {
                    suppressed = true;
                    detectedSpeech = false;
                    pcm.reset();
                    preRoll.clear();
                    lastSpeech = now;
                    VoiceActivityGate.GateStatus gateStatus = voiceGate.status();
                    status = new Status("listening", true, false, false, rms, maxRms, gateStatus.confidence(), gateStatus.provider(), gateStatus.fallbackReason(), "suppressing local TTS");
                    continue;
                }
                Duration elapsed = Duration.ofNanos(now - started);
                double threshold = noiseGate.updateAndThreshold(rms, elapsed, detectedSpeech);
                boolean rmsActive = rms >= threshold;
                VoiceActivityGate.Decision voice = voiceGate.evaluate(buffer, read, rmsActive, detectedSpeech);
                boolean speech = voice.speechActive();
                if (!detectedSpeech) {
                    preRoll.addLast(Arrays.copyOf(buffer, read));
                    while (preRoll.size() > PRE_ROLL_BUFFERS) {
                        preRoll.removeFirst();
                    }
                }
                if (speech) {
                    if (!detectedSpeech) {
                        for (byte[] chunk : preRoll) {
                            byte[] processed = processPcmForAsr(chunk, chunk.length, config);
                            pcm.write(processed, 0, processed.length);
                        }
                        preRoll.clear();
                    }
                    detectedSpeech = true;
                    lastSpeech = now;
                }
                if (detectedSpeech) {
                    byte[] processed = processPcmForAsr(buffer, read, config);
                    pcm.write(processed, 0, processed.length);
                }
                Duration silence = Duration.ofNanos(now - lastSpeech);
                status = new Status("listening", true, speech, detectedSpeech, rms, maxRms, voice.confidence(), voice.provider(), voice.fallbackReason(), "threshold=" + round(threshold) + " vad=" + voice.provider() + ":" + round(voice.confidence()));
                if (detectedSpeech && silence.toMillis() >= config.silenceMillis) break;
                if (elapsed.toMillis() >= config.maxRecordingMillis) break;
            }
            }
            line.stop();
            line.flush();
            if (!detectedSpeech || pcm.size() == 0) {
                return new Recorded(new byte[0], false, maxRms, suppressed);
            }
            return new Recorded(toWav(pcm.toByteArray()), true, maxRms, suppressed);
        }
    }

    private boolean isMicSuppressed() {
        return ttsSpeaking.get() || System.currentTimeMillis() < suppressMicUntilMs;
    }

    private void maybeInterruptTtsFromMic(double rms) {
        if (!config.ttsInterruptOnSpeech || !ttsSpeaking.get() || rms < config.ttsInterruptRmsThreshold) {
            return;
        }
        Process process = activeTtsProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            suppressMicUntilMs = System.currentTimeMillis() + Math.max(250, config.ttsMicSuppressMillis / 2);
            status = Status.listening("TTS ducked for user speech");
        }
    }

    private boolean isLikelyLocalTtsEcho(String recognizedText) {
        if (recognizedText == null || recognizedText.isBlank() || lastSpokenText == null || lastSpokenText.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastTtsEndedMs > Math.max(2500L, config.ttsMicSuppressMillis + 3500L)) {
            return false;
        }
        String recognized = normalizeForEchoCheck(recognizedText);
        String spoken = normalizeForEchoCheck(lastSpokenText);
        if (recognized.isBlank() || spoken.isBlank()) {
            return false;
        }
        if (recognized.equals(spoken) || recognized.contains(spoken) || spoken.contains(recognized)) {
            return true;
        }
        return echoSimilarity(recognized, spoken) >= 0.72D;
    }

    private String normalizeForEchoCheck(String text) {
        return text == null ? "" : text
            .replaceAll("[\\s\\p{Punct}、。！？・ー]+", "")
            .toLowerCase(java.util.Locale.ROOT);
    }

    private double echoSimilarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 0.0D;
        int distance = levenshteinDistance(a, b, Math.max(8, max / 2));
        return Math.max(0.0D, 1.0D - distance / (double) max);
    }

    private int levenshteinDistance(String a, String b, int limit) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private String recognize(byte[] wav) throws IOException, InterruptedException {
        if (config.amivoiceApiKey.isBlank()) {
            throw new IOException("AmiVoice API key is missing. Open Settings and save amivoice.apiKey.");
        }
        String boundary = "KoeCraftBridge" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, wav);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.amivoiceEndpoint))
            .header("content-type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AmiVoice HTTP " + response.statusCode());
        }
        String text = extractJsonString(response.body(), "text");
        if (text.isBlank()) {
            throw new IOException("AmiVoice returned empty text");
        }
        System.out.println("[KoeCraft Mic Bridge] recognized: " + text);
        return text;
    }

    private String recognizeWhisper(byte[] wav) throws IOException, InterruptedException {
        if (config.openaiApiKey.isBlank()) {
            throw new IOException("OpenAI API key is missing. Open Settings and save openai.apiKey.");
        }
        String boundary = "KoeCraftOpenAi" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = openAiTranscriptionMultipart(boundary, wav);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/audio/transcriptions"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + config.openaiApiKey)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = extractJsonString(response.body(), "message");
            throw new IOException("OpenAI transcription HTTP " + response.statusCode() + (message.isBlank() ? "" : ": " + message));
        }
        String text = extractJsonString(response.body(), "text");
        if (text.isBlank()) {
            throw new IOException("OpenAI transcription returned empty text");
        }
        System.out.println("[KoeCraft Mic Bridge] whisper recognized: " + text);
        return text;
    }

    private void postUtterance(String text) throws IOException, InterruptedException {
        String json = "{\"recognized_text\":\"" + jsonEscape(text) + "\",\"source\":\"koecraft_mic_bridge\",\"asr_provider\":\"" + jsonEscape(config.speechProvider) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.modUtteranceUrl))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MOD utterance HTTP " + response.statusCode());
        }
    }

    private URI amivoiceWebSocketUri() {
        if (!config.amivoiceWebSocketEndpoint.isBlank()) {
            return URI.create(config.amivoiceWebSocketEndpoint);
        }
        String endpoint = config.amivoiceEndpoint;
        if (endpoint.contains("/nolog/")) {
            return URI.create("wss://acp-api.amivoice.com/v1/nolog/");
        }
        return URI.create("wss://acp-api.amivoice.com/v1/");
    }

    private String buildAmiVoiceWebSocketStartCommand() {
        StringBuilder command = new StringBuilder("s LSB16K ")
            .append(config.amivoiceEngine)
            .append(" authorization=")
            .append(config.amivoiceApiKey);
        String profileWords = loadAmiVoiceProfileWords(config);
        if (!profileWords.isBlank()) {
            command.append(" profileWords=\"")
                .append(profileWords.replace("\"", "\"\""))
                .append("\"");
        }
        command.append(" resultUpdatedInterval=500");
        return command.toString();
    }

    private void sendAmiVoicePcmChunk(WebSocket socket, byte[] buffer, int length) {
        byte[] frame = new byte[length + 1];
        frame[0] = 'p';
        System.arraycopy(buffer, 0, frame, 1, length);
        socket.sendBinary(ByteBuffer.wrap(frame), true).join();
    }

    private byte[] multipart(String boundary, byte[] wav) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writePart(output, boundary, "u", config.amivoiceApiKey);
            writePart(output, boundary, "d", buildAmiVoiceDParameter(config));
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write("Content-Disposition: form-data; name=\"a\"; filename=\"koecraft-utterance.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
            output.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(wav);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        }
    }

    private byte[] openAiTranscriptionMultipart(String boundary, byte[] wav) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writePart(output, boundary, "model", config.openaiTranscriptionModel);
            writePart(output, boundary, "language", "ja");
            writePart(output, boundary, "response_format", "json");
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write("Content-Disposition: form-data; name=\"file\"; filename=\"koecraft-utterance.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
            output.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(wav);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        }
    }

    private String buildAmiVoiceDParameter(Config config) {
        StringBuilder builder = new StringBuilder("grammarFileNames=")
            .append(URLEncoder.encode(config.amivoiceEngine, StandardCharsets.UTF_8));
        String profileWords = loadAmiVoiceProfileWords(config);
        if (!profileWords.isBlank()) {
            builder.append(" profileWords=").append(URLEncoder.encode(profileWords, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private String loadAmiVoiceProfileWords(Config config) {
        int limit = Math.max(0, Math.min(config.amivoiceProfileWordsLimit, 1000));
        if (limit == 0 || config.amivoiceDictPath.isBlank()) {
            return "";
        }
        Path path = Path.of(config.amivoiceDictPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            path = Path.of(System.getProperty("user.dir"), config.amivoiceDictPath).toAbsolutePath().normalize();
        }
        if (!Files.exists(path)) {
            return "";
        }
        java.util.LinkedHashSet<String> words = new java.util.LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] columns = trimmed.split("\t");
                if (columns.length < 2 || columns[0].isBlank() || columns[1].isBlank()) {
                    continue;
                }
                words.add(columns[0].trim() + " " + columns[1].trim());
                if (words.size() >= limit) {
                    break;
                }
            }
        } catch (IOException ignored) {
            return "";
        }
        return String.join("|", words);
    }

    private void writePart(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String healthJson() {
        return "{\"ok\":true,\"mic_enabled\":" + enabled.get()
            + ",\"mic_muted\":" + muted.get()
            + ",\"tts_enabled\":" + ttsEnabled.get()
            + ",\"speech_provider\":\"" + jsonEscape(config.speechProvider) + "\""
            + ",\"voice_transport\":\"" + jsonEscape(config.voiceTransport) + "\""
            + ",\"mic_device\":\"" + jsonEscape(currentMicDeviceName) + "\""
            + ",\"configured_mic_device\":\"" + jsonEscape(config.micMixerName) + "\""
            + ",\"voice_phase\":\"" + status.phase + "\""
            + ",\"native_mic_recording\":" + status.recording
            + ",\"native_mic_status\":{\"speech_active\":" + status.speechActive
            + ",\"detected_speech\":" + status.detectedSpeech
            + ",\"last_rms\":" + round(status.lastRms)
            + ",\"max_rms\":" + round(status.maxRms)
            + ",\"vad_confidence\":" + round(status.vadConfidence)
            + ",\"vad_provider\":\"" + jsonEscape(status.vadProvider) + "\""
            + ",\"vad_fallback_reason\":\"" + jsonEscape(status.vadFallbackReason) + "\""
            + "},\"detail\":\"" + jsonEscape(status.detail) + "\""
            + ",\"last_spoken_text\":\"" + jsonEscape(lastSpokenText) + "\"}";
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String indexHtml() {
        String enabledLabel = enabled.get() ? "ON" : "OFF";
        String mutedLabel = muted.get() ? "ON" : "OFF";
        String vadLabel = htmlEscape(status.vadProvider) + " " + Math.round(status.vadConfidence * 100.0) + "%";
        String escapedDetail = htmlEscape(status.detail);
        return """
            <!doctype html>
            <html lang="ja">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>KoeCraft Mic Bridge</title>
              <style>
                :root { color-scheme: light dark; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
                body { margin: 0; padding: 32px; background: Canvas; color: CanvasText; }
                main { max-width: 520px; margin: 0 auto; }
                h1 { font-size: 24px; margin: 0 0 20px; }
                .status { border: 1px solid color-mix(in srgb, CanvasText 18%%, transparent); border-radius: 8px; padding: 16px; margin-bottom: 16px; }
                .row { display: flex; justify-content: space-between; gap: 16px; padding: 7px 0; border-bottom: 1px solid color-mix(in srgb, CanvasText 10%%, transparent); }
                .row:last-child { border-bottom: 0; }
                .label { color: color-mix(in srgb, CanvasText 62%%, transparent); }
                .value { font-weight: 700; text-align: right; }
                .actions { display: flex; gap: 12px; }
                button { flex: 1; min-height: 42px; border-radius: 8px; border: 1px solid color-mix(in srgb, CanvasText 18%%, transparent); font-weight: 700; cursor: pointer; }
                button.on { background: #1f9d5a; color: white; }
                button.off { background: #b42318; color: white; }
                small { display: block; margin-top: 18px; color: color-mix(in srgb, CanvasText 58%%, transparent); line-height: 1.5; }
              </style>
            </head>
            <body>
              <main>
                <h1>KoeCraft Mic Bridge</h1>
                <section class="status">
	                  <div class="row"><span class="label">Mic</span><span class="value">%s</span></div>
	                  <div class="row"><span class="label">Muted</span><span class="value">%s</span></div>
		                  <div class="row"><span class="label">ASR</span><span class="value">%s</span></div>
		                  <div class="row"><span class="label">Input</span><span class="value">%s</span></div>
		                  <div class="row"><span class="label">Phase</span><span class="value">%s</span></div>
	                  <div class="row"><span class="label">Speech</span><span class="value">%s</span></div>
	                  <div class="row"><span class="label">RMS</span><span class="value">%.4f</span></div>
	                  <div class="row"><span class="label">VAD</span><span class="value">%s</span></div>
	                  <div class="row"><span class="label">TTS</span><span class="value">%s</span></div>
	                  <div class="row"><span class="label">Detail</span><span class="value">%s</span></div>
	                </section>
	                <div class="actions">
	                  <button class="on" onclick="post('/api/mic/on')">ON</button>
	                  <button onclick="post('/api/mic/mute/toggle')">MUTE</button>
	                  <button class="off" onclick="post('/api/mic/off')">OFF</button>
	                  <button onclick="post('/api/tts/toggle')">TTS</button>
	                </div>
	                <small>Minecraft 側の V キーで ON/OFF、M キーで MUTE、N キーで完全停止できます。このページは状態確認と手動操作用です。</small>
              </main>
              <script>
                async function post(path) {
                  await fetch(path, { method: 'POST' });
                  location.reload();
                }
                setTimeout(() => location.reload(), 1500);
              </script>
            </body>
            </html>
	            """.formatted(
	                enabledLabel,
		                mutedLabel,
		                htmlEscape(config.displaySpeechProvider()),
		                htmlEscape(compactDeviceName(currentMicDeviceName)),
		                htmlEscape(status.phase),
	                status.speechActive ? "active" : "idle",
	                round(status.lastRms),
	                vadLabel,
	                ttsEnabled.get() ? "ON" : "OFF",
	                escapedDetail.isBlank() ? "-" : escapedDetail
	            );
    }

    private byte[] toWav(byte[] pcm) throws IOException {
        try (
            ByteArrayInputStream input = new ByteArrayInputStream(pcm);
            AudioInputStream audioInput = new AudioInputStream(input, FORMAT, pcm.length / FORMAT.getFrameSize());
            ByteArrayOutputStream wav = new ByteArrayOutputStream()
        ) {
            AudioSystem.write(audioInput, AudioFileFormat.Type.WAVE, wav);
            return wav.toByteArray();
        }
    }

    private double rms16le(byte[] bytes, int length) {
        long sumSquares = 0L;
        int samples = length / 2;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((bytes[i] & 0xFF) | (bytes[i + 1] << 8));
            sumSquares += (long) sample * sample;
        }
        return samples == 0 ? 0.0D : Math.sqrt(sumSquares / (double) samples) / 32768.0D;
    }

    private byte[] processPcmForAsr(byte[] input, int length, Config config) {
        byte[] output = Arrays.copyOf(input, length);
        if (!config.pcmNormalizeEnabled && !config.pcmSoftClipEnabled) {
            return output;
        }
        double rms = rms16le(output, output.length);
        double gain = 1.0D;
        if (config.pcmNormalizeEnabled && rms > 0.0001D && rms < config.pcmNormalizeTargetRms) {
            gain = Math.min(config.pcmNormalizeMaxGain, config.pcmNormalizeTargetRms / rms);
        }
        if (gain <= 1.0001D && !config.pcmSoftClipEnabled) {
            return output;
        }
        for (int index = 0; index + 1 < output.length; index += 2) {
            short sample = (short) ((output[index] & 0xFF) | (output[index + 1] << 8));
            double scaled = sample / 32768.0D * gain;
            if (config.pcmSoftClipEnabled && Math.abs(scaled) > 0.95D) {
                scaled = Math.tanh(scaled);
            }
            int clipped = (int) Math.round(Math.max(-1.0D, Math.min(1.0D, scaled)) * 32767.0D);
            output[index] = (byte) (clipped & 0xFF);
            output[index + 1] = (byte) ((clipped >> 8) & 0xFF);
        }
        return output;
    }

    private double round(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }

    private String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\"";
        int at = json.indexOf(marker);
        if (at < 0) return "";
        int colon = json.indexOf(':', at + marker.length());
        int start = json.indexOf('"', colon + 1);
        if (colon < 0 || start < 0) return "";
        StringBuilder out = new StringBuilder();
        boolean escaping = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                switch (c) {
                    case '"', '\\', '/' -> out.append(c);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                out.append('u');
                            }
                        } else {
                            out.append('u');
                        }
                    }
                    default -> out.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return decodeBareUnicodeEscapes(out.toString()).trim();
    }

    private String decodeBareUnicodeEscapes(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = BARE_UNICODE_ESCAPE.matcher(text);
        int matches = 0;
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            matches++;
            char value = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(decoded);
        return matches >= 2 ? decoded.toString() : text;
    }

    private String jsonEscape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String sanitizeSpeechText(String text) {
        String compact = text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return compact.length() > 80 ? compact.substring(0, 80) : compact;
    }

    private String htmlEscape(String text) {
        return text == null ? "" : text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private record Recorded(byte[] wav, boolean detectedSpeech, double maxRms, boolean suppressed) {
    }

    private record StreamingRecognized(String text, boolean detectedSpeech, double maxRms, boolean suppressed) {
    }

    private record DemoPhrase(String label, String text) {
    }

    private static final class AdaptiveNoiseGate {
        private final Config config;
        private double sumRms = 0.0D;
        private int samples = 0;

        AdaptiveNoiseGate(Config config) {
            this.config = config;
        }

        double updateAndThreshold(double rms, Duration elapsed, boolean detectedSpeech) {
            double current = threshold();
            if (
                config.adaptiveNoiseEnabled
                    && !detectedSpeech
                    && elapsed.toMillis() <= config.adaptiveNoiseWarmupMillis
                    && rms < Math.max(config.speechRmsThreshold * 1.8D, current * 1.2D)
            ) {
                sumRms += rms;
                samples++;
            }
            return threshold();
        }

        double threshold() {
            if (!config.adaptiveNoiseEnabled || samples == 0) {
                return config.speechRmsThreshold;
            }
            double noiseFloor = sumRms / samples;
            return Math.max(
                config.speechRmsThreshold,
                Math.max(config.adaptiveNoiseMinThreshold, noiseFloor * config.adaptiveNoiseMultiplier)
            );
        }
    }

    private interface VoiceActivityGate extends AutoCloseable {
        Decision evaluate(byte[] pcm16le, int length, boolean rmsActive, boolean detectedSpeech);
        GateStatus status();

        @Override
        default void close() {
        }

        static VoiceActivityGate create(Config config) {
            if (!config.vadEnabled || "rms".equalsIgnoreCase(config.vadProvider)) {
                return new RmsGate();
            }
            try {
                return new SileroOnnxGate(config);
            } catch (Throwable error) {
                return new RmsGate("silero_init_failed:" + compactStaticError(error));
            }
        }

        record Decision(boolean speechActive, double confidence, String provider, String fallbackReason) {
        }

        record GateStatus(String provider, double confidence, String fallbackReason) {
            static GateStatus rms() {
                return new GateStatus("rms", 0.0D, "");
            }
        }
    }

    private static final class RmsGate implements VoiceActivityGate {
        private final String fallbackReason;
        private double lastConfidence = 0.0D;

        RmsGate() {
            this("");
        }

        RmsGate(String fallbackReason) {
            this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
        }

        @Override
        public VoiceActivityGate.Decision evaluate(byte[] pcm16le, int length, boolean rmsActive, boolean detectedSpeech) {
            lastConfidence = rmsActive ? 1.0D : 0.0D;
            return new VoiceActivityGate.Decision(rmsActive, lastConfidence, "rms", fallbackReason);
        }

        @Override
        public VoiceActivityGate.GateStatus status() {
            return new VoiceActivityGate.GateStatus("rms", lastConfidence, fallbackReason);
        }
    }

    private static final class SileroOnnxGate implements VoiceActivityGate {
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

        SileroOnnxGate(Config config) throws IOException, OrtException {
            this.environment = OrtEnvironment.getEnvironment();
            Path modelPath = resolveModelPath(config);
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
                options.setIntraOpNumThreads(1);
                this.session = environment.createSession(modelPath.toString(), options);
            }
            this.threshold = (float) config.vadConfidenceThreshold;
            this.minSpeechFrames = Math.max(1, config.vadMinSpeechFrames);
            this.hangoverFrames = Math.max(0, config.vadHangoverFrames);
        }

        @Override
        public VoiceActivityGate.Decision evaluate(byte[] pcm16le, int length, boolean rmsActive, boolean detectedSpeech) {
            if (failed) {
                return new VoiceActivityGate.Decision(rmsActive, rmsActive ? 1.0D : 0.0D, "rms", fallbackReason);
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
                return new VoiceActivityGate.Decision(rmsActive && vadActive, lastConfidence, "silero_onnx", "");
            } catch (Throwable error) {
                failed = true;
                fallbackReason = "silero_runtime_failed:" + compactStaticError(error);
                return new VoiceActivityGate.Decision(rmsActive, rmsActive ? 1.0D : 0.0D, "rms", fallbackReason);
            }
        }

        @Override
        public VoiceActivityGate.GateStatus status() {
            return failed
                ? new VoiceActivityGate.GateStatus("rms", lastConfidence, fallbackReason)
                : new VoiceActivityGate.GateStatus("silero_onnx", lastConfidence, "");
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (OrtException ignored) {
                // Closing the gate should not stop the bridge.
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

        private static Path resolveModelPath(Config config) throws IOException {
            if (!config.vadModelPath.isBlank()) {
                Path configured = Path.of(config.vadModelPath).toAbsolutePath().normalize();
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
                try (InputStream input = KoeCraftMicBridge.class.getClassLoader().getResourceAsStream(MODEL_RESOURCE)) {
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

    private static String compactStaticError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.toString();
        }
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() <= 80 ? message : message.substring(0, 77) + "...";
    }

    private final class AmiVoiceStreamingListener implements WebSocket.Listener {
        private final CompletableFuture<Void> started = new CompletableFuture<>();
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private final AtomicReference<String> finalText = new AtomicReference<>("");
        private final AtomicBoolean earlyShortCommand = new AtomicBoolean(false);
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                handleMessage(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            started.completeExceptionally(error);
            ended.completeExceptionally(error);
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
                String text = extractJsonString(content, "text");
                if (!text.isBlank()) {
                    finalText.set(text);
                    if (canEarlyAcceptPartial(text)) {
                        earlyShortCommand.set(true);
                    }
                    System.out.println("[KoeCraft Mic Bridge] streaming recognized: " + text);
                }
                return;
            }
            if (event == 'A') {
                String text = extractJsonString(content, "text");
                if (!text.isBlank()) {
                    finalText.set(text);
                    System.out.println("[KoeCraft Mic Bridge] streaming final: " + text);
                }
                return;
            }
            if (event == 'e') {
                ended.complete(null);
            }
        }

        void awaitStarted(long timeout, TimeUnit unit) throws Exception {
            started.get(timeout, unit);
        }

        String awaitFinalText(long timeout, TimeUnit unit) throws Exception {
            try {
                ended.get(timeout, unit);
            } catch (Exception error) {
                if (finalText.get().isBlank()) {
                    throw error;
                }
            }
            return finalText.get().trim();
        }

        boolean shouldStopForEarlyShortCommand() {
            return earlyShortCommand.get();
        }
    }

    private record MicDiagnosticResult(
        double quietAvgRms,
        double quietMaxRms,
        double quietMaxVad,
        double speechAvgRms,
        double speechMaxRms,
        double speechMaxVad,
        double recommendedRmsThreshold,
        double currentVadThreshold,
        String deviceName,
        byte[] pcm,
        double[] rmsTimeline,
        double[] vadTimeline,
        int quietFrameCount
    ) {
        String shortSummary() {
            return "mic test: quiet=" + roundStatic(quietMaxRms) + " speech=" + roundStatic(speechMaxRms) + " vad=" + Math.round(speechMaxVad * 100.0D) + "%";
        }

        String message() {
            StringBuilder builder = new StringBuilder();
            builder.append("Mic Test result\n\n");
            builder.append("Input device: ").append(deviceName == null || deviceName.isBlank() ? DEFAULT_MIC_DEVICE_LABEL : deviceName).append('\n');
            builder.append("Quiet/BGM max RMS: ").append(roundStatic(quietMaxRms)).append('\n');
            builder.append("Quiet/BGM max VAD: ").append(Math.round(quietMaxVad * 100.0D)).append("%\n");
            builder.append("Speech max RMS: ").append(roundStatic(speechMaxRms)).append('\n');
            builder.append("Speech max VAD: ").append(Math.round(speechMaxVad * 100.0D)).append("%\n");
            builder.append("Suggested voice.speechRmsThreshold: ").append(roundStatic(recommendedRmsThreshold)).append("\n\n");
            if (quietMaxVad >= currentVadThreshold * 0.8D || quietMaxVad >= 0.35D) {
                builder.append("- Minecraft BGM/environment may sound speech-like. Try VAD confidence 0.60 or headphones.\n");
            }
            if (quietMaxRms >= speechMaxRms * 0.45D && speechMaxRms > 0.0D) {
                builder.append("- Background and speech levels are close. Lower Minecraft volume or move the mic closer.\n");
            }
            if (speechMaxRms < 0.010D || speechMaxVad < currentVadThreshold) {
                builder.append("- Speech looks weak. Increase microphone input gain or speak closer.\n");
            }
            if (builder.toString().endsWith("\n\n")) {
                builder.append("- Looks usable. Keep the current settings for now.\n");
            }
            builder.append("\nYou can apply the suggested threshold in Settings > Voice.");
            return builder.toString();
        }

        private static double roundStatic(double value) {
            return Math.round(value * 10000.0D) / 10000.0D;
        }
    }

    private static final class RmsVadGraphPanel extends JPanel {
        private final double[] rms;
        private final double[] vad;
        private final int quietFrameCount;

        RmsVadGraphPanel(double[] rms, double[] vad, int quietFrameCount) {
            this.rms = rms == null ? new double[0] : rms;
            this.vad = vad == null ? new double[0] : vad;
            this.quietFrameCount = quietFrameCount;
            setBackground(SURFACE);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(10, 10, 10, 10)
            ));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int left = 42;
                int right = getWidth() - 16;
                int top = 20;
                int bottom = getHeight() - 32;
                g.setColor(new Color(0xF2F4F7));
                g.fillRoundRect(left, top, right - left, bottom - top, 8, 8);
                g.setColor(BORDER);
                g.drawRoundRect(left, top, right - left, bottom - top, 8, 8);
                g.setFont(getFont().deriveFont(Font.BOLD, 11.0F));
                g.setColor(TEXT_MUTED);
                g.drawString("RMS", 12, top + 12);
                g.drawString("VAD", 12, top + 28);
                g.drawString("quiet", left, bottom + 18);
                g.drawString("speak", Math.min(right - 42, xFor(quietFrameCount, rms.length, left, right) + 5), bottom + 18);
                int splitX = xFor(quietFrameCount, Math.max(1, rms.length), left, right);
                g.setColor(new Color(0x99E7B84B, true));
                g.drawLine(splitX, top + 2, splitX, bottom - 2);
                drawSeries(g, rms, left, right, top, bottom, new Color(0x16A34A), Math.max(0.02D, max(rms)));
                drawSeries(g, vad, left, right, top, bottom, new Color(0x2563EB), 1.0D);
            } finally {
                g.dispose();
            }
        }

        private void drawSeries(Graphics2D g, double[] values, int left, int right, int top, int bottom, Color color, double scaleMax) {
            if (values.length < 2) {
                return;
            }
            g.setColor(color);
            g.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int previousX = left;
            int previousY = yFor(values[0], scaleMax, top, bottom);
            for (int i = 1; i < values.length; i++) {
                int x = xFor(i, values.length, left, right);
                int y = yFor(values[i], scaleMax, top, bottom);
                g.drawLine(previousX, previousY, x, y);
                previousX = x;
                previousY = y;
            }
        }

        private static int xFor(int index, int count, int left, int right) {
            if (count <= 1) {
                return left;
            }
            return left + (int) Math.round((right - left) * Math.max(0, Math.min(index, count - 1)) / (double) (count - 1));
        }

        private static int yFor(double value, double scaleMax, int top, int bottom) {
            double normalized = Math.max(0.0D, Math.min(1.0D, value / Math.max(0.0001D, scaleMax)));
            return bottom - (int) Math.round((bottom - top) * normalized);
        }

        private static double max(double[] values) {
            double max = 0.0D;
            for (double value : values) {
                max = Math.max(max, value);
            }
            return max;
        }
    }

    private record Status(String phase, boolean recording, boolean speechActive, boolean detectedSpeech, double lastRms, double maxRms, double vadConfidence, String vadProvider, String vadFallbackReason, String detail) {
        static Status off() { return new Status("off", false, false, false, 0, 0, 0, "rms", "", ""); }
        static Status muted() { return new Status("muted", false, false, false, 0, 0, 0, "rms", "", "mic muted"); }
        static Status listening() { return new Status("listening", true, false, false, 0, 0, 0, "rms", "", ""); }
        static Status listening(String detail) { return new Status("listening", true, false, false, 0, 0, 0, "rms", "", detail); }
        static Status recognizing() { return new Status("recognizing", false, false, true, 0, 0, 0, "rms", "", ""); }
        static Status recognizing(String detail) { return new Status("recognizing", false, false, true, 0, 0, 0, "rms", "", detail); }
        static Status executing(String text) { return new Status("executing", false, false, true, 0, 0, 0, "rms", "", text); }
        static Status error(String detail) { return new Status("error", false, false, false, 0, 0, 0, "rms", "", detail); }
    }

    private static boolean canEarlyAcceptPartial(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (Pattern.compile("止まって|止まれ|やめて|待って|ストップ|中止|キャンセル|stop|abort", Pattern.CASE_INSENSITIVE).matcher(normalized).find()) {
            return true;
        }
        if (Pattern.compile("作って|作りたい|クラフト|ネザー|家|村|探|松明|たいまつ|食料|倒して|狩って|攻撃|防御").matcher(normalized).find()
            && !Pattern.compile("(橋|はし|ハシ|ハッシュ|bridge).*(かけ|架け|掛け|作|つく|渡|伸ば)|(かけ|架け|掛け).*(橋|はし|ハシ|ハッシュ|bridge)", Pattern.CASE_INSENSITIVE).matcher(normalized).find()) {
            return false;
        }
        if (normalized.length() > 18 && !Pattern.compile("\\d{1,3}(ブロック|段|マス|歩)?|一|二|三|四|五|六|七|八|九|十|少し|ちょっと").matcher(normalized).find()) {
            return false;
        }
        return Pattern.compile("歩いて|歩け|あるいて|まっすぐ|真っ直ぐ|走って|走れ|はしって|ダッシュ|進んで|進め|前進|泳いで|およいで|泳げ|下がって|右|左|walk|run|sprint|swim|go", Pattern.CASE_INSENSITIVE).matcher(normalized).find()
            || Pattern.compile("拾って|拾え|拾う|取って|とって|回収して|アイテム取|pick.?up|pickup", Pattern.CASE_INSENSITIVE).matcher(normalized).find()
            || Pattern.compile("掘って|掘れ|階段掘り|トンネル|横掘り|dig|mine", Pattern.CASE_INSENSITIVE).matcher(normalized).find()
            || Pattern.compile("(橋|はし|ハシ|ハッシュ|bridge).*(かけ|架け|掛け|作|つく|渡|伸ば)|(かけ|架け|掛け).*(橋|はし|ハシ|ハッシュ|bridge)", Pattern.CASE_INSENSITIVE).matcher(normalized).find();
    }

    private static final class SettingsFields {
        JSlider threshold;
        JSlider silence;
        JSlider maxRecording;
        JComboBox<String> micMixerName;
        JComboBox<String> speechProvider;
        JTextField voiceTransport;
        JCheckBox adaptiveNoiseEnabled;
        JSlider adaptiveNoiseWarmup;
        JSlider adaptiveNoiseMultiplier;
        JSlider adaptiveNoiseMinThreshold;
        JCheckBox vadEnabled;
        JComboBox<String> vadProvider;
        JTextField vadModelPath;
        JSlider vadConfidence;
        JSlider vadMinSpeechFrames;
        JSlider vadHangoverFrames;
        JCheckBox pcmNormalizeEnabled;
        JSlider pcmNormalizeTarget;
        JSlider pcmNormalizeMaxGain;
        JCheckBox pcmSoftClipEnabled;
        JCheckBox keepListening;
        JPasswordField amivoiceApiKey;
        JTextField amivoiceEngine;
        JPasswordField openaiApiKey;
        JTextField openaiModel;
        JTextField openaiTranscriptionModel;
        JCheckBox llmFallbackEnabled;
        JTextField openaiNormalizerModel;
        JCheckBox openaiNormalizerEnabled;
        JTextField modUtteranceUrl;
        JTextField bridgePort;
        JComboBox<String> assistMode;
        JCheckBox worldAssistEnabled;
        JCheckBox worldAssistMaterialTopUp;
        JCheckBox worldAssistDirectCraft;
        JCheckBox worldAssistRareItems;
        JSlider programmaticExploreDistance;
        JSlider programmaticBoatDistance;
        JCheckBox childModeEnabled;
        JCheckBox childModeMaterialAssist;
        JSlider childShelterTarget;
        JCheckBox ttsEnabled;
        JTextField ttsVoice;
        JSlider ttsRate;
        JSlider ttsSuppress;
        JCheckBox ttsInterruptOnSpeech;
        JSlider ttsInterruptThreshold;
    }

    private record Config(
        Path path,
        Properties properties,
        int bridgePort,
        String modUtteranceUrl,
        String speechProvider,
        String amivoiceApiKey,
        String amivoiceEndpoint,
        String amivoiceWebSocketEndpoint,
        String amivoiceEngine,
        String amivoiceDictPath,
        int amivoiceProfileWordsLimit,
        String voiceTransport,
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
        String openaiApiKey,
        String openaiModel,
        String openaiTranscriptionModel,
        boolean llmFallbackEnabled,
        String openaiNormalizerModel,
        boolean openaiNormalizerEnabled,
        String assistMode,
        boolean worldAssistEnabled,
        boolean worldAssistMaterialTopUp,
        boolean worldAssistDirectCraft,
        boolean worldAssistRareItems,
        int programmaticExploreDistanceBlocks,
        int programmaticBoatTravelDistanceBlocks,
        boolean childModeEnabled,
        boolean childModeMaterialAssist,
        int childShelterMaterialTarget,
        boolean ttsEnabled,
        String ttsVoice,
        int ttsRate,
        int ttsMicSuppressMillis,
        boolean ttsInterruptOnSpeech,
        double ttsInterruptRmsThreshold
    ) {
        static Config load() throws IOException {
            Properties props = new Properties();
            Path config = Path.of(System.getProperty("user.home"), "Library/Application Support/minecraft/config/koecraft-agent.properties");
            Files.createDirectories(config.getParent());
            if (Files.exists(config)) {
                try (var input = Files.newInputStream(config)) {
                    props.load(input);
                }
            }
            Map<String, String> env = new LinkedHashMap<>(System.getenv());
            String key = firstNonBlank(props.getProperty("amivoice.apiKey"), env.get("AMIVOICE_API_KEY"));
            return new Config(
                config,
                props,
                intValue(props.getProperty("bridge.port"), 8790),
                props.getProperty("bridge.modUtteranceUrl", "http://127.0.0.1:8791/api/utterance"),
                normalizeSpeechProvider(firstNonBlank(props.getProperty("speech.provider"), env.get("KOECRAFT_SPEECH_PROVIDER"))),
                key,
                props.getProperty("amivoice.endpoint", "https://acp-api.amivoice.com/v1/nolog/recognize"),
                props.getProperty("amivoice.websocketEndpoint", ""),
                props.getProperty("amivoice.engine", "-a-general-input"),
                props.getProperty("amivoice.dictPath", "data/amivoice/dict.txt"),
                intValue(props.getProperty("amivoice.profileWordsLimit"), 200),
                props.getProperty("amivoice.transport", props.getProperty("voice.transport", "websocket")),
                props.getProperty("voice.micMixerName", ""),
                doubleValue(props.getProperty("voice.speechRmsThreshold"), 0.004D),
                booleanValue(props.getProperty("voice.adaptiveNoise.enabled"), true),
                intValue(props.getProperty("voice.adaptiveNoise.warmupMillis"), 450),
                doubleValue(props.getProperty("voice.adaptiveNoise.multiplier"), 3.0D),
                doubleValue(props.getProperty("voice.adaptiveNoise.minThreshold"), 0.0035D),
                booleanValue(props.getProperty("voice.vad.enabled"), true),
                normalizeVadProvider(props.getProperty("voice.vad.provider", "silero_onnx")),
                props.getProperty("voice.vad.modelPath", ""),
                doubleValue(props.getProperty("voice.vad.confidenceThreshold"), 0.50D),
                intValue(props.getProperty("voice.vad.minSpeechFrames"), 2),
                intValue(props.getProperty("voice.vad.hangoverFrames"), 6),
                booleanValue(props.getProperty("voice.pcmNormalize.enabled"), true),
                doubleValue(props.getProperty("voice.pcmNormalize.targetRms"), 0.035D),
                doubleValue(props.getProperty("voice.pcmNormalize.maxGain"), 3.0D),
                booleanValue(props.getProperty("voice.pcmSoftClip.enabled"), true),
                intValue(props.getProperty("voice.silenceMillis"), 900),
                intValue(props.getProperty("voice.maxRecordingMillis"), 6000),
                booleanValue(props.getProperty("voice.keepListeningOnError"), true),
                firstNonBlank(props.getProperty("openai.apiKey"), env.get("OPENAI_API_KEY")),
                props.getProperty("openai.model", "gpt-4o-mini"),
                firstNonBlank(props.getProperty("openai.transcription.model"), env.get("OPENAI_TRANSCRIPTION_MODEL"), "whisper-1"),
                booleanValue(props.getProperty("openai.fallbackEnabled"), true),
                props.getProperty("openai.normalizer.model", "gpt-5-nano"),
                booleanValue(props.getProperty("openai.normalizer.enabled"), true),
                normalizeAssistMode(props.getProperty("koecraft.executor.assistMode", "world_assist")),
                booleanValue(props.getProperty("koecraft.worldAssist.enabled"), true),
                booleanValue(props.getProperty("koecraft.worldAssist.allowCommonMaterialTopUp"), true),
                booleanValue(props.getProperty("koecraft.worldAssist.allowDirectCraft"), true),
                booleanValue(props.getProperty("koecraft.worldAssist.allowRareItems"), false),
                intValue(props.getProperty("koecraft.executor.programmaticExploreDistanceBlocks"), 300),
                intValue(props.getProperty("koecraft.executor.programmaticBoatTravelDistanceBlocks"), 180),
                booleanValue(props.getProperty("childMode.enabled"), false),
                booleanValue(props.getProperty("childMode.materialAssist"), true),
                intValue(props.getProperty("childMode.shelterMaterialTarget"), 8),
                booleanValue(props.getProperty("tts.enabled"), true),
                props.getProperty("tts.voice", "Kyoko"),
                intValue(props.getProperty("tts.rate"), 180),
                intValue(props.getProperty("tts.micSuppressMillis"), 1800),
                booleanValue(props.getProperty("tts.interruptOnSpeech"), true),
                doubleValue(props.getProperty("tts.interruptRmsThreshold"), 0.018D)
            );
        }

        Config withTtsEnabled(boolean enabled) {
            return withSettings(
                Integer.toString(bridgePort),
                modUtteranceUrl,
                speechProvider,
                amivoiceApiKey,
                amivoiceEngine,
                voiceTransport,
                speechRmsThreshold,
                adaptiveNoiseEnabled,
                adaptiveNoiseWarmupMillis,
                adaptiveNoiseMultiplier,
                adaptiveNoiseMinThreshold,
                vadEnabled,
                vadProvider,
                vadModelPath,
                vadConfidenceThreshold,
                vadMinSpeechFrames,
                vadHangoverFrames,
                pcmNormalizeEnabled,
                pcmNormalizeTargetRms,
                pcmNormalizeMaxGain,
                pcmSoftClipEnabled,
                silenceMillis,
                maxRecordingMillis,
                micMixerName,
                keepListeningOnError,
                openaiApiKey,
                openaiModel,
                openaiTranscriptionModel,
                llmFallbackEnabled,
                openaiNormalizerModel,
                openaiNormalizerEnabled,
                assistMode,
                worldAssistEnabled,
                worldAssistMaterialTopUp,
                worldAssistDirectCraft,
                worldAssistRareItems,
                programmaticExploreDistanceBlocks,
                programmaticBoatTravelDistanceBlocks,
                childModeEnabled,
                childModeMaterialAssist,
                childShelterMaterialTarget,
                enabled,
                ttsVoice,
                ttsRate,
                ttsMicSuppressMillis,
                ttsInterruptOnSpeech,
                ttsInterruptRmsThreshold
            );
        }

        Config withSettings(
            String bridgePort,
            String modUtteranceUrl,
            String speechProvider,
            String amivoiceApiKey,
            String amivoiceEngine,
            String voiceTransport,
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
            String micMixerName,
            boolean keepListeningOnError,
            String openaiApiKey,
            String openaiModel,
            String openaiTranscriptionModel,
            boolean llmFallbackEnabled,
            String openaiNormalizerModel,
            boolean openaiNormalizerEnabled,
            String assistMode,
            boolean worldAssistEnabled,
            boolean worldAssistMaterialTopUp,
            boolean worldAssistDirectCraft,
            boolean worldAssistRareItems,
            int programmaticExploreDistanceBlocks,
            int programmaticBoatTravelDistanceBlocks,
            boolean childModeEnabled,
            boolean childModeMaterialAssist,
            int childShelterMaterialTarget,
            boolean ttsEnabled,
            String ttsVoice,
            int ttsRate,
            int ttsMicSuppressMillis,
            boolean ttsInterruptOnSpeech,
            double ttsInterruptRmsThreshold
        ) {
            return new Config(
                path,
                properties,
                intValue(bridgePort, this.bridgePort),
                blankDefault(modUtteranceUrl, "http://127.0.0.1:8791/api/utterance"),
                normalizeSpeechProvider(speechProvider),
                amivoiceApiKey == null ? "" : amivoiceApiKey.trim(),
                amivoiceEndpoint,
                amivoiceWebSocketEndpoint,
                blankDefault(amivoiceEngine, "-a-general-input"),
                amivoiceDictPath,
                amivoiceProfileWordsLimit,
                normalizeVoiceTransport(voiceTransport),
                micMixerName == null ? "" : micMixerName.trim(),
                Math.max(0.001D, Math.min(speechRmsThreshold, 0.08D)),
                adaptiveNoiseEnabled,
                Math.max(100, Math.min(adaptiveNoiseWarmupMillis, 2000)),
                Math.max(1.2D, Math.min(adaptiveNoiseMultiplier, 8.0D)),
                Math.max(0.001D, Math.min(adaptiveNoiseMinThreshold, 0.08D)),
                vadEnabled,
                normalizeVadProvider(vadProvider),
                vadModelPath == null ? "" : vadModelPath.trim(),
                Math.max(0.05D, Math.min(vadConfidenceThreshold, 0.95D)),
                Math.max(1, Math.min(vadMinSpeechFrames, 10)),
                Math.max(0, Math.min(vadHangoverFrames, 30)),
                pcmNormalizeEnabled,
                Math.max(0.005D, Math.min(pcmNormalizeTargetRms, 0.2D)),
                Math.max(1.0D, Math.min(pcmNormalizeMaxGain, 8.0D)),
                pcmSoftClipEnabled,
                Math.max(250, Math.min(silenceMillis, 10000)),
                Math.max(1000, Math.min(maxRecordingMillis, 60000)),
                keepListeningOnError,
                openaiApiKey == null ? "" : openaiApiKey.trim(),
                blankDefault(openaiModel, "gpt-4o-mini"),
                blankDefault(openaiTranscriptionModel, "whisper-1"),
                llmFallbackEnabled,
                blankDefault(openaiNormalizerModel, "gpt-5-nano"),
                openaiNormalizerEnabled,
                normalizeAssistMode(assistMode),
                worldAssistEnabled,
                worldAssistMaterialTopUp,
                worldAssistDirectCraft,
                worldAssistRareItems,
                Math.max(64, Math.min(programmaticExploreDistanceBlocks, 1024)),
                Math.max(16, Math.min(programmaticBoatTravelDistanceBlocks, 1024)),
                childModeEnabled,
                childModeMaterialAssist,
                Math.max(4, Math.min(childShelterMaterialTarget, 24)),
                ttsEnabled,
                blankDefault(ttsVoice, "Kyoko"),
                Math.max(80, Math.min(ttsRate, 320)),
                Math.max(0, Math.min(ttsMicSuppressMillis, 10000)),
                ttsInterruptOnSpeech,
                Math.max(0.001D, Math.min(ttsInterruptRmsThreshold, 0.2D))
            );
        }

        void save() throws IOException {
            Properties copy = new Properties();
            copy.putAll(properties);
            copy.setProperty("bridge.port", Integer.toString(bridgePort));
            copy.setProperty("bridge.modUtteranceUrl", modUtteranceUrl);
            copy.setProperty("speech.provider", speechProvider);
            copy.setProperty("amivoice.apiKey", amivoiceApiKey);
            copy.setProperty("amivoice.endpoint", amivoiceEndpoint);
            copy.setProperty("amivoice.websocketEndpoint", amivoiceWebSocketEndpoint);
            copy.setProperty("amivoice.engine", amivoiceEngine);
            copy.setProperty("amivoice.dictPath", amivoiceDictPath);
            copy.setProperty("amivoice.profileWordsLimit", Integer.toString(amivoiceProfileWordsLimit));
            copy.setProperty("amivoice.transport", voiceTransport);
            copy.setProperty("voice.transport", voiceTransport);
            copy.setProperty("voice.micMixerName", micMixerName);
            copy.setProperty("voice.speechRmsThreshold", Double.toString(speechRmsThreshold));
            copy.setProperty("voice.adaptiveNoise.enabled", Boolean.toString(adaptiveNoiseEnabled));
            copy.setProperty("voice.adaptiveNoise.warmupMillis", Integer.toString(adaptiveNoiseWarmupMillis));
            copy.setProperty("voice.adaptiveNoise.multiplier", Double.toString(adaptiveNoiseMultiplier));
            copy.setProperty("voice.adaptiveNoise.minThreshold", Double.toString(adaptiveNoiseMinThreshold));
            copy.setProperty("voice.vad.enabled", Boolean.toString(vadEnabled));
            copy.setProperty("voice.vad.provider", vadProvider);
            copy.setProperty("voice.vad.modelPath", vadModelPath);
            copy.setProperty("voice.vad.confidenceThreshold", Double.toString(vadConfidenceThreshold));
            copy.setProperty("voice.vad.minSpeechFrames", Integer.toString(vadMinSpeechFrames));
            copy.setProperty("voice.vad.hangoverFrames", Integer.toString(vadHangoverFrames));
            copy.setProperty("voice.pcmNormalize.enabled", Boolean.toString(pcmNormalizeEnabled));
            copy.setProperty("voice.pcmNormalize.targetRms", Double.toString(pcmNormalizeTargetRms));
            copy.setProperty("voice.pcmNormalize.maxGain", Double.toString(pcmNormalizeMaxGain));
            copy.setProperty("voice.pcmSoftClip.enabled", Boolean.toString(pcmSoftClipEnabled));
            copy.setProperty("voice.silenceMillis", Integer.toString(silenceMillis));
            copy.setProperty("voice.maxRecordingMillis", Integer.toString(maxRecordingMillis));
            copy.setProperty("voice.keepListeningOnError", Boolean.toString(keepListeningOnError));
            copy.setProperty("openai.apiKey", openaiApiKey);
            copy.setProperty("openai.model", openaiModel);
            copy.setProperty("openai.transcription.model", openaiTranscriptionModel);
            copy.setProperty("openai.fallbackEnabled", Boolean.toString(llmFallbackEnabled));
            copy.setProperty("openai.normalizer.model", openaiNormalizerModel);
            copy.setProperty("openai.normalizer.enabled", Boolean.toString(openaiNormalizerEnabled));
            copy.setProperty("koecraft.executor.assistMode", assistMode);
            copy.setProperty("koecraft.worldAssist.enabled", Boolean.toString(worldAssistEnabled));
            copy.setProperty("koecraft.worldAssist.allowCommonMaterialTopUp", Boolean.toString(worldAssistMaterialTopUp));
            copy.setProperty("koecraft.worldAssist.allowDirectCraft", Boolean.toString(worldAssistDirectCraft));
            copy.setProperty("koecraft.worldAssist.allowRareItems", Boolean.toString(worldAssistRareItems));
            copy.setProperty("koecraft.executor.programmaticExploreDistanceBlocks", Integer.toString(programmaticExploreDistanceBlocks));
            copy.setProperty("koecraft.executor.programmaticBoatTravelDistanceBlocks", Integer.toString(programmaticBoatTravelDistanceBlocks));
            copy.setProperty("childMode.enabled", Boolean.toString(childModeEnabled));
            copy.setProperty("childMode.materialAssist", Boolean.toString(childModeMaterialAssist));
            copy.setProperty("childMode.shelterMaterialTarget", Integer.toString(childShelterMaterialTarget));
            copy.setProperty("tts.enabled", Boolean.toString(ttsEnabled));
            copy.setProperty("tts.voice", ttsVoice);
            copy.setProperty("tts.rate", Integer.toString(ttsRate));
            copy.setProperty("tts.micSuppressMillis", Integer.toString(ttsMicSuppressMillis));
            copy.setProperty("tts.interruptOnSpeech", Boolean.toString(ttsInterruptOnSpeech));
            copy.setProperty("tts.interruptRmsThreshold", Double.toString(ttsInterruptRmsThreshold));
            try (var output = Files.newOutputStream(path)) {
                copy.store(output, "KoeCraft Agent local settings. Do not commit this file.");
            }
            properties.clear();
            properties.putAll(copy);
        }

        private static String blankDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String normalizeVoiceTransport(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            return normalized.equals("http") || normalized.equals("http_wav") ? "http" : "websocket";
        }

        private static String normalizeSpeechProvider(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            return normalized.equals("whisper") || normalized.equals("openai") ? "whisper" : "amivoice";
        }

        private static String normalizeVadProvider(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals("off") || normalized.equals("none") || normalized.equals("rms")) {
                return "rms";
            }
            return "silero_onnx";
        }

        private static String normalizeAssistMode(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (normalized) {
                case "off" -> "off";
                case "survival", "strict" -> "survival";
                case "balanced" -> "balanced";
                case "programmatic", "world assist", "world_assist", "worldassist" -> "world_assist";
                default -> "world_assist";
            };
        }

        boolean useWebSocketTransport() {
            return !"http".equalsIgnoreCase(voiceTransport);
        }

        boolean useWhisperProvider() {
            return "whisper".equalsIgnoreCase(speechProvider);
        }

        String displaySpeechProvider() {
            return useWhisperProvider() ? "Whisper" : "AmiVoice";
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) return first.trim();
            return second == null ? "" : second.trim();
        }

        private static String firstNonBlank(String first, String second, String third) {
            String value = firstNonBlank(first, second);
            return value.isBlank() ? (third == null ? "" : third.trim()) : value;
        }

        private static int intValue(String raw, int fallback) {
            try { return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) { return fallback; }
        }

        private static double doubleValue(String raw, double fallback) {
            try { return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim()); } catch (NumberFormatException ignored) { return fallback; }
        }

        private static boolean booleanValue(String raw, boolean fallback) {
            return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw.trim());
        }
    }
}
