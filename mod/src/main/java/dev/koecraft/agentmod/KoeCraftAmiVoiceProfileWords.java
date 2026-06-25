package dev.koecraft.agentmod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

final class KoeCraftAmiVoiceProfileWords {
    private KoeCraftAmiVoiceProfileWords() {
    }

    static String load(KoeCraftVoiceConfig config) {
        int limit = Math.max(0, Math.min(config.amivoiceProfileWordsLimit(), 1000));
        if (limit == 0 || config.amivoiceDictPath().isBlank()) {
            return "";
        }
        Path path = Path.of(config.amivoiceDictPath()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return "";
        }

        Set<String> words = new LinkedHashSet<>();
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
}
