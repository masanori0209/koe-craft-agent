package dev.koecraft.agentmod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record KoeCraftModConfig(
    int port,
    int maxScanRadius,
    double maxReach,
    int defaultBreakTimeoutTicks,
    int defaultCollectTimeoutTicks,
    int maxApproachExposureAttempts,
    int maxExposureAttempts,
    int maxWatchdogRecoveryAttempts,
    double occupiedWorkstationRadius,
    boolean itemMagnetEnabled,
    int itemMagnetRadius,
    double itemMagnetStrength,
    boolean voiceAssistEnabled,
    int voiceAssistSupportTopUpCount,
    String assistMode,
    boolean worldAssistEnabled,
    boolean worldAssistConsumeItemsWhenPossible,
    boolean worldAssistAllowCommonMaterialTopUp,
    boolean worldAssistAllowRareItems,
    boolean worldAssistAllowWorkstationPlacement,
    boolean worldAssistAllowMagnetPickup,
    boolean worldAssistAllowDirectCraft,
    boolean worldAssistAllowSmallBuilds,
    int programmaticExploreDistanceBlocks,
    int programmaticBoatTravelDistanceBlocks
) {
    public static KoeCraftModConfig load() {
        Properties fileProps = loadLocalProperties();
        String assistMode = normalizeAssistMode(stringProp(fileProps, "koecraft.executor.assistMode", "world_assist"));
        boolean defaultWorldAssistEnabled = "world_assist".equals(assistMode) || "balanced".equals(assistMode);
        boolean worldAssistEnabled = booleanProp(fileProps, "koecraft.worldAssist.enabled", defaultWorldAssistEnabled) && !"off".equals(assistMode);
        boolean allowCommonTopUp = booleanProp(fileProps, "koecraft.worldAssist.allowCommonMaterialTopUp", true);
        boolean allowMagnetPickup = booleanProp(fileProps, "koecraft.worldAssist.allowMagnetPickup", true);
        return new KoeCraftModConfig(
            intProp(fileProps, "koecraft.executor.port", 8787, 1024, 65535),
            intProp(fileProps, "koecraft.executor.maxScanRadius", 32, 1, 32),
            doubleProp(fileProps, "koecraft.executor.maxReach", 5.0D, 1.0D, 6.0D),
            intProp(fileProps, "koecraft.executor.defaultBreakTimeoutTicks", 120, 20, 400),
            intProp(fileProps, "koecraft.executor.defaultCollectTimeoutTicks", 160, 20, 400),
            intProp(fileProps, "koecraft.executor.maxApproachExposureAttempts", 2, 0, 10),
            intProp(fileProps, "koecraft.executor.maxExposureAttempts", 5, 1, 10),
            intProp(fileProps, "koecraft.executor.maxWatchdogRecoveryAttempts", 3, 1, 5),
            doubleProp(fileProps, "koecraft.executor.occupiedWorkstationRadius", 2.0D, 0.0D, 8.0D),
            worldAssistEnabled && allowMagnetPickup && booleanProp(fileProps, "koecraft.executor.itemMagnetEnabled", true),
            intProp(fileProps, "koecraft.executor.itemMagnetRadius", 8, 1, 16),
            doubleProp(fileProps, "koecraft.executor.itemMagnetStrength", 0.65D, 0.05D, 2.0D),
            worldAssistEnabled && allowCommonTopUp && booleanProp(fileProps, "koecraft.executor.voiceAssistEnabled", true),
            intProp(fileProps, "koecraft.executor.voiceAssistSupportTopUpCount", 32, 1, 64),
            assistMode,
            worldAssistEnabled,
            booleanProp(fileProps, "koecraft.worldAssist.consumeItemsWhenPossible", true),
            allowCommonTopUp,
            booleanProp(fileProps, "koecraft.worldAssist.allowRareItems", false),
            booleanProp(fileProps, "koecraft.worldAssist.allowWorkstationPlacement", true),
            allowMagnetPickup,
            booleanProp(fileProps, "koecraft.worldAssist.allowDirectCraft", true),
            booleanProp(fileProps, "koecraft.worldAssist.allowSmallBuilds", true),
            intProp(fileProps, "koecraft.executor.programmaticExploreDistanceBlocks", 300, 64, 1024),
            intProp(fileProps, "koecraft.executor.programmaticBoatTravelDistanceBlocks", 180, 16, 1024)
        );
    }

    public boolean worldAssistEnabled() {
        return worldAssistEnabled && ("world_assist".equals(assistMode) || "balanced".equals(assistMode));
    }

    public boolean programmaticAssistEnabled() {
        return worldAssistEnabled();
    }

    public boolean worldAssistCommonMaterialTopUpEnabled() {
        return worldAssistEnabled() && worldAssistAllowCommonMaterialTopUp && voiceAssistEnabled;
    }

    public boolean worldAssistRareItemsEnabled() {
        return worldAssistEnabled() && worldAssistAllowRareItems;
    }

    public boolean worldAssistWorkstationPlacementEnabled() {
        return worldAssistEnabled() && worldAssistAllowWorkstationPlacement;
    }

    public boolean worldAssistMagnetPickupEnabled() {
        return worldAssistEnabled() && worldAssistAllowMagnetPickup && itemMagnetEnabled;
    }

    public boolean worldAssistDirectCraftEnabled() {
        return worldAssistEnabled() && worldAssistAllowDirectCraft;
    }

    public boolean worldAssistSmallBuildsEnabled() {
        return worldAssistEnabled() && worldAssistAllowSmallBuilds;
    }

    public int effectiveExploreDistanceBlocks() {
        return programmaticAssistEnabled() ? programmaticExploreDistanceBlocks : 128;
    }

    private static String normalizeAssistMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "programmatic";
        }
        return switch (raw.trim().toLowerCase()) {
            case "off" -> "off";
            case "survival", "strict" -> "survival";
            case "balanced" -> "balanced";
            case "programmatic", "worldassist", "world_assist" -> "world_assist";
            default -> "world_assist";
        };
    }

    private static Properties loadLocalProperties() {
        Properties props = new Properties();
        Path path = localPropertiesPath();
        if (!Files.exists(path)) {
            return props;
        }
        try (var input = Files.newInputStream(path)) {
            props.load(input);
        } catch (IOException ignored) {
            return new Properties();
        }
        return props;
    }

    private static Path localPropertiesPath() {
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft", "config", "koecraft-agent.properties");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, ".minecraft", "config", "koecraft-agent.properties");
            }
        }
        return Path.of(home, ".minecraft", "config", "koecraft-agent.properties");
    }

    private static String stringProp(Properties props, String key, String fallback) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String file = props.getProperty(key);
        return file == null || file.isBlank() ? fallback : file;
    }

    private static int intProp(Properties props, String key, int fallback, int min, int max) {
        String raw = stringProp(props, key, Integer.toString(fallback));
        try {
            return Math.max(min, Math.min(Integer.parseInt(raw.trim()), max));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanProp(Properties props, String key, boolean fallback) {
        String raw = stringProp(props, key, Boolean.toString(fallback));
        return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw);
    }

    private static double doubleProp(Properties props, String key, double fallback, double min, double max) {
        String raw = stringProp(props, key, Double.toString(fallback));
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(Double.parseDouble(raw), max));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
