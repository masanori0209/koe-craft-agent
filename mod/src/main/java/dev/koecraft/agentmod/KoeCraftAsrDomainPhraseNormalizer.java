package dev.koecraft.agentmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class KoeCraftAsrDomainPhraseNormalizer {
    private static final String RESOURCE_PATH = "/koecraft/asr_domain_phrases.tsv";
    private static volatile List<Entry> entries;

    private KoeCraftAsrDomainPhraseNormalizer() {
    }

    static List<Candidate> candidates(String recognizedText, JsonObject raw) {
        Evidence evidence = evidence(recognizedText, raw);
        if (evidence.spokenCompact().isBlank()) {
            return List.of();
        }
        ArrayList<Candidate> candidates = new ArrayList<>();
        String baseText = compactSurface(KoeCraftAsrPostNormalizer.normalize(recognizedText));
        for (Entry entry : entries()) {
            double score = score(entry, evidence);
            if (score < entry.minScore()) {
                continue;
            }
            if (compactSurface(entry.canonicalText()).equals(baseText)) {
                continue;
            }
            candidates.add(new Candidate(
                entry.canonicalText(),
                entry.reading(),
                entry.intentHint(),
                score,
                evidence.spokenText()
            ));
        }
        candidates.sort(
            Comparator.comparingDouble(Candidate::score)
                .thenComparing(candidate -> candidate.reading().length())
                .reversed()
        );
        return candidates.size() <= 8 ? List.copyOf(candidates) : List.copyOf(candidates.subList(0, 8));
    }

    static List<Candidate> candidatesForTesting(String recognizedText, JsonObject raw) {
        return candidates(recognizedText, raw);
    }

    static String spokenTextForTesting(JsonObject raw) {
        return evidence("", raw).spokenText();
    }

    private static Evidence evidence(String recognizedText, JsonObject raw) {
        ArrayList<Token> tokens = new ArrayList<>();
        collectTokens(raw, tokens);
        if (tokens.isEmpty()) {
            return new Evidence("", "", 0.0D);
        }
        StringBuilder spoken = new StringBuilder();
        StringBuilder written = new StringBuilder();
        double confidenceTotal = 0.0D;
        int confidenceCount = 0;
        for (Token token : tokens) {
            if (!token.spoken().isBlank()) {
                spoken.append(token.spoken());
            }
            if (!token.written().isBlank()) {
                written.append(token.written());
            }
            if (token.confidence() != null) {
                confidenceTotal += token.confidence();
                confidenceCount++;
            }
        }
        String spokenText = spoken.isEmpty() ? written.toString() : spoken.toString();
        double confidence = confidenceCount == 0 ? 0.85D : confidenceTotal / confidenceCount;
        return new Evidence(spokenText, compactReading(spokenText), confidence);
    }

    private static void collectTokens(JsonObject raw, List<Token> output) {
        if (raw == null) {
            return;
        }
        if (raw.has("tokens") && raw.get("tokens").isJsonArray()) {
            collectTokenArray(raw.getAsJsonArray("tokens"), output);
        }
        if (!raw.has("results") || !raw.get("results").isJsonArray()) {
            return;
        }
        for (JsonElement element : raw.getAsJsonArray("results")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject result = element.getAsJsonObject();
            if (result.has("tokens") && result.get("tokens").isJsonArray()) {
                collectTokenArray(result.getAsJsonArray("tokens"), output);
            }
        }
    }

    private static void collectTokenArray(JsonArray rawTokens, List<Token> output) {
        for (JsonElement element : rawTokens) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject raw = element.getAsJsonObject();
            String written = string(raw, "written");
            if (written.isBlank()) {
                written = string(raw, "text");
            }
            String spoken = string(raw, "spoken");
            Double confidence = number(raw, "confidence");
            output.add(new Token(written, spoken, confidence));
        }
    }

    private static double score(Entry entry, Evidence evidence) {
        String spoken = evidence.spokenCompact();
        String reading = compactReading(entry.reading());
        if (spoken.isBlank() || reading.isBlank()) {
            return 0.0D;
        }
        double base;
        if (spoken.equals(reading)) {
            base = 1.0D;
        } else if (spoken.contains(reading) && reading.length() >= 4) {
            base = Math.min(0.96D, 0.72D + (double) reading.length() / Math.max(spoken.length(), 1) * 0.24D);
        } else if (reading.contains(spoken) && spoken.length() >= 5) {
            base = Math.min(0.9D, 0.68D + (double) spoken.length() / Math.max(reading.length(), 1) * 0.22D);
        } else {
            int max = Math.max(spoken.length(), reading.length());
            base = max == 0 ? 0.0D : 1.0D - ((double) levenshtein(spoken, reading) / max);
        }
        return clamp(base * 0.9D + clamp(evidence.confidence(), 0.0D, 1.0D) * 0.1D, 0.0D, 1.0D);
    }

    private static List<Entry> entries() {
        List<Entry> loaded = entries;
        if (loaded != null) {
            return loaded;
        }
        loaded = loadEntries();
        entries = loaded;
        return loaded;
    }

    private static List<Entry> loadEntries() {
        try (InputStream stream = KoeCraftAsrDomainPhraseNormalizer.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                return List.of();
            }
            ArrayList<Entry> parsed = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isBlank() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String[] parts = trimmed.split("\\t");
                    if (parts.length < 3) {
                        continue;
                    }
                    double minScore = parts.length >= 4 ? parseDouble(parts[3], 0.82D) : 0.82D;
                    parsed.add(new Entry(parts[0].trim(), parts[1].trim(), parts[2].trim(), minScore));
                }
            }
            return List.copyOf(parsed);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String compactReading(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || "、。,.!！?？「」『』（）()・ー~〜".indexOf(ch) >= 0) {
                continue;
            }
            if (ch >= 'ァ' && ch <= 'ヶ') {
                ch = (char) (ch - 0x60);
            }
            builder.append(ch);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static String compactSurface(String text) {
        return text == null ? "" : text.replaceAll("[\\s　、。,.!！?？]+", "").toLowerCase(Locale.ROOT);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static Double number(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    record Candidate(String text, String reading, String intentHint, double score, String spokenEvidence) {
    }

    private record Entry(String canonicalText, String reading, String intentHint, double minScore) {
    }

    private record Evidence(String spokenText, String spokenCompact, double confidence) {
    }

    private record Token(String written, String spoken, Double confidence) {
    }
}
