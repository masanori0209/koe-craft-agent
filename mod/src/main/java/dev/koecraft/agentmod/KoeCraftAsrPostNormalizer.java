package dev.koecraft.agentmod;

import java.util.Locale;
import java.util.regex.Pattern;

final class KoeCraftAsrPostNormalizer {
    private static final Pattern WOODEN_TOOL_ASR_CONFUSION = Pattern.compile("(昨日|きのう)(の)?(ツルハシ|つるはし|ピッケル|ぴっける|斧|おの|シャベル|しゃべる|スコップ|剣|けん|クワ|くわ)");
    private static final Pattern CREATE_WORDS = Pattern.compile("作|つく|クラフト|ほしい|欲しい|お願い|頼む");
    private static final Pattern BRIDGE_CONTEXT = Pattern.compile("橋|はし|ハシ|ハッシュ|架け|かけ|掛け|渡|伸ば|前|build|bridge", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICKAXE_CONTEXT = Pattern.compile("ツルハシ|つるはし|鶴橋|ツル橋|つる橋|ピッケル|ぴっける|pickaxe", Pattern.CASE_INSENSITIVE);
    private static final Pattern NETHER_CONTEXT = Pattern.compile("ネザー|レザー|ゲート|ポータル|行きたい|行く|作|つく|portal|nether", Pattern.CASE_INSENSITIVE);
    private static final Pattern WATASHI_PICKAXE_CONFUSION = Pattern.compile("^(私|わたし|ワタシ|わたくし)(を)?(作|つく|クラフト|ほしい|欲しい|お願い|頼む).*$");

    private KoeCraftAsrPostNormalizer() {
    }

    static String normalize(String text) {
        String normalized = text == null ? "" : text
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (normalized.isBlank()) {
            return "";
        }
        normalized = normalizeMinecraftHomophones(normalized);
        if (CREATE_WORDS.matcher(normalized).find()) {
            normalized = WOODEN_TOOL_ASR_CONFUSION.matcher(normalized).replaceAll("木の$3");
        }
        return normalized;
    }

    private static String normalizeMinecraftHomophones(String text) {
        String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String normalized = text;
        if (BRIDGE_CONTEXT.matcher(compact).find()) {
            normalized = normalized
                .replace("ハッシュ", "橋")
                .replace("端を掛け", "橋を掛け")
                .replace("端をかけ", "橋をかけ")
                .replace("端を架け", "橋を架け");
        }
        if (NETHER_CONTEXT.matcher(compact).find() && compact.contains("レザー")) {
            normalized = normalized.replace("レザー", "ネザー");
        }
        if (WATASHI_PICKAXE_CONFUSION.matcher(compact).matches()) {
            normalized = "ツルハシ作って";
            compact = normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        }
        if (compact.matches("^(鶴橋|つる橋|ツル橋)へ$")) {
            normalized = "ツルハシ作って";
            compact = normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        }
        if (PICKAXE_CONTEXT.matcher(compact).find()) {
            normalized = normalized
                .replace("石野", "石の")
                .replace("鶴橋", "ツルハシ")
                .replace("つる橋", "ツルハシ")
                .replace("ツル橋", "ツルハシ")
                .replace("つる端", "ツルハシ")
                .replace("つる箸", "ツルハシ")
                .replace("ツル端", "ツルハシ")
                .replace("ツル箸", "ツルハシ");
        }
        String normalizedCompact = normalized.replaceAll("\\s+", "");
        if ("撮って".equals(normalizedCompact)) {
            normalized = "取って";
        } else if ("百歩吠えて".equals(normalizedCompact)) {
            normalized = "100歩歩いて";
        } else if ("落ち着くって".equals(normalizedCompact)) {
            normalized = "おうち作って";
        } else if (normalizedCompact.contains("がかりつけるやつ")) {
            normalized = normalized.replace("がかりつけるやつ", "あかりつけるやつ");
        }
        return normalized;
    }
}
