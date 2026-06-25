package dev.koecraft.agentmod;

import java.util.Locale;
import java.util.regex.Pattern;

final class KoeCraftShortCommandMode {
    private static final Pattern STOP = Pattern.compile("止まって|止まれ|やめて|待って|ストップ|中止|キャンセル|stop|abort", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVE = Pattern.compile("歩いて|歩け|まっすぐ|真っ直ぐ|走って|走れ|ダッシュ|進んで|進め|前進|泳いで|泳げ|下がって|右|左|walk|run|sprint|swim|go", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICKUP = Pattern.compile("拾って|拾え|拾う|回収して|アイテム取|pick.?up|pickup", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIG = Pattern.compile("掘って|掘れ|階段掘り|トンネル|横掘り|dig|mine", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRIDGE = Pattern.compile("(橋|はし|ハシ|ハッシュ|bridge).*(かけ|架け|掛け|作|つく|渡|伸ば|build)|(かけ|架け|掛け).*(橋|はし|ハシ|ハッシュ|bridge)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_LONG_PLAN = Pattern.compile("作って|作りたい|クラフト|ネザー|家|村|探|松明|たいまつ|食料|倒して|狩って|攻撃|防御");

    private KoeCraftShortCommandMode() {
    }

    static boolean canEarlyAcceptPartial(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        if (STOP.matcher(normalized).find()) {
            return true;
        }
        if (UNSAFE_LONG_PLAN.matcher(normalized).find() && !BRIDGE.matcher(normalized).find()) {
            return false;
        }
        if (normalized.length() > 18 && !containsDistance(normalized)) {
            return false;
        }
        return MOVE.matcher(normalized).find()
            || PICKUP.matcher(normalized).find()
            || DIG.matcher(normalized).find()
            || BRIDGE.matcher(normalized).find();
    }

    static String normalize(String text) {
        return KoeCraftAsrPostNormalizer.normalize(text)
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT);
    }

    private static boolean containsDistance(String text) {
        return Pattern.compile("\\d{1,3}(ブロック|段|マス|歩)?").matcher(text).find()
            || Pattern.compile("一|二|三|四|五|六|七|八|九|十|少し|ちょっと").matcher(text).find();
    }
}
