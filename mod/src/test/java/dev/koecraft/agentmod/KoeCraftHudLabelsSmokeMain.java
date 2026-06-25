package dev.koecraft.agentmod;

import com.google.gson.JsonObject;

public final class KoeCraftHudLabelsSmokeMain {
    private KoeCraftHudLabelsSmokeMain() {
    }

    public static void main(String[] args) {
        assertEquals("できなかった", ExecutorStatusLabels.statusLabel("blocked"), "blocked status label");
        assertEquals("途中までできた", ExecutorStatusLabels.statusLabel("partial"), "partial status label");
        assertEquals("石のツルハシ", ExecutorStatusLabels.friendlyMinecraftName("minecraft:stone_pickaxe"), "friendly item name");
        assertContains(
            ExecutorStatusLabels.friendlyMessageText("grant minecraft:oak_log after blocked"),
            "オークの原木",
            "friendly message item"
        );
        assertContains(
            ExecutorStatusLabels.friendlyMessageText("grant minecraft:oak_log after blocked"),
            "できない",
            "friendly message status"
        );

        JsonObject craft = new JsonObject();
        craft.addProperty("recipe", "minecraft:stone_pickaxe");
        assertEquals(
            "作る 石のツルハシ",
            ExecutorStatusLabels.actionLabel(new ExecutorProtocol.Action("craft", craft)),
            "craft action label"
        );

        String detail = KoeCraftAgentClient.taskDetailForTesting("復旧中 1/4 (1/5): minecraft:crafting_table blocked");
        assertContains(detail, "作業台", "hud task detail item");
        assertContains(detail, "できない", "hud task detail status");

        System.out.println("[hud-labels-smoke] passed");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected `" + expected + "` but got `" + actual + "`");
        }
    }

    private static void assertContains(String actual, String expectedFragment, String label) {
        if (actual == null || !actual.contains(expectedFragment)) {
            throw new AssertionError(label + ": expected `" + actual + "` to contain `" + expectedFragment + "`");
        }
    }
}
