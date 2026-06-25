package dev.koecraft.agentmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class MinecraftVanillaTerms {
    private final Class<?> resourceOwner;
    private List<Term> terms;

    MinecraftVanillaTerms(Class<?> resourceOwner) {
        this.resourceOwner = resourceOwner;
    }

    Optional<TermMatch> findCraftTarget(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        Optional<TermMatch> alias = findAlias(normalized);
        if (alias.isPresent()) {
            return alias;
        }
        return terms().stream()
            .filter(term -> term.kind().equals("item") || term.kind().equals("block"))
            .flatMap(term -> term.surfaces().stream()
                .filter(surface -> surface.length() >= 2 && normalized.contains(normalize(surface)))
                .map(surface -> new TermMatch(term.id(), term.kind(), surface)))
            .filter(match -> match.id().startsWith("minecraft:"))
            .max(Comparator.comparingInt(match -> match.surface().length()));
    }

    private Optional<TermMatch> findAlias(String normalized) {
        ArrayList<TermMatch> matches = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : aliases().entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalized.contains(normalize(alias))) {
                    matches.add(new TermMatch(entry.getKey(), "item", alias));
                }
            }
        }
        return matches.stream().max(Comparator.comparingInt(match -> match.surface().length()));
    }

    private List<Term> terms() {
        if (terms != null) {
            return terms;
        }
        try (InputStream stream = resourceOwner.getResourceAsStream("/koecraft/vanilla_terms.json")) {
            if (stream == null) {
                terms = List.of();
                return terms;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            ArrayList<Term> parsed = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("terms")) {
                JsonObject raw = element.getAsJsonObject();
                parsed.add(new Term(
                    string(raw, "id"),
                    string(raw, "kind"),
                    string(raw, "ja_jp"),
                    string(raw, "reading"),
                    string(raw, "en_us")
                ));
            }
            terms = List.copyOf(parsed);
            return terms;
        } catch (Exception ignored) {
            terms = List.of();
            return terms;
        }
    }

    private Map<String, List<String>> aliases() {
        LinkedHashMap<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("minecraft:torch", List.of("松明", "たいまつ", "トーチ", "明かり", "灯り", "照明", "光源", "あかりつけるやつ", "暗いところに置くやつ"));
        aliases.put("minecraft:stone_pickaxe", List.of("石のピッケル", "石ピッケル", "ストーンピッケル", "石のツルハシ", "石つるはし"));
        aliases.put("minecraft:wooden_pickaxe", List.of("木のピッケル", "木ピッケル", "木のツルハシ"));
        aliases.put("minecraft:crafting_table", List.of("作業台", "クラフト台"));
        aliases.put("minecraft:furnace", List.of("かまど", "竈", "炉"));
        aliases.put("minecraft:shield", List.of("盾", "たて", "シールド"));
        aliases.put("minecraft:chest", List.of("チェスト", "箱", "収納箱"));
        aliases.put("minecraft:boat", List.of("ボート", "船", "ふね"));
        aliases.put("minecraft:chest_boat", List.of("チェスト付きボート", "チェストボート", "荷物を載せるボート"));
        aliases.put("minecraft:door", List.of("ドア", "扉", "とびら"));
        aliases.put("minecraft:trapdoor", List.of("トラップドア", "床扉"));
        aliases.put("minecraft:fence", List.of("フェンス", "柵", "さく"));
        aliases.put("minecraft:fence_gate", List.of("フェンスゲート", "柵のゲート", "門"));
        aliases.put("minecraft:sign", List.of("看板", "かんばん"));
        aliases.put("minecraft:hanging_sign", List.of("吊り看板", "つり看板"));
        aliases.put("minecraft:slab", List.of("ハーフブロック", "半ブロック", "スラブ"));
        aliases.put("minecraft:stairs", List.of("階段", "かいだん"));
        aliases.put("minecraft:button", List.of("ボタン", "木のボタン"));
        aliases.put("minecraft:pressure_plate", List.of("感圧板", "プレッシャープレート"));
        aliases.put("minecraft:wall", List.of("壁", "石の壁", "石垣"));
        aliases.put("minecraft:ladder", List.of("はしご", "梯子", "ラダー"));
        aliases.put("minecraft:bowl", List.of("ボウル", "お椀"));
        aliases.put("minecraft:bow", List.of("弓", "ゆみ"));
        aliases.put("minecraft:arrow", List.of("矢", "や", "アロー"));
        aliases.put("minecraft:fishing_rod", List.of("釣竿", "釣り竿", "つりざお"));
        aliases.put("minecraft:bucket", List.of("バケツ"));
        aliases.put("minecraft:shears", List.of("ハサミ", "はさみ"));
        aliases.put("minecraft:flint_and_steel", List.of("火打石と打ち金", "火打ち石", "火をつける道具"));
        aliases.put("minecraft:bed", List.of("ベッド", "寝床"));
        aliases.put("minecraft:carpet", List.of("カーペット", "絨毯", "じゅうたん"));
        aliases.put("minecraft:banner", List.of("旗", "バナー"));
        aliases.put("minecraft:wool", List.of("羊毛", "ウール"));
        aliases.put("minecraft:coal", List.of("石炭"));
        aliases.put("minecraft:charcoal", List.of("木炭"));
        aliases.put("minecraft:stick", List.of("棒", "ぼう", "スティック"));
        return aliases;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    private String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    record Term(String id, String kind, String jaJp, String reading, String enUs) {
        List<String> surfaces() {
            ArrayList<String> surfaces = new ArrayList<>();
            if (!jaJp.isBlank()) surfaces.add(jaJp);
            if (!reading.isBlank() && !reading.equals(jaJp)) surfaces.add(reading);
            if (!enUs.isBlank()) surfaces.add(enUs);
            return surfaces;
        }
    }

    record TermMatch(String id, String kind, String surface) {
    }
}
