package dev.koecraft.agentmod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.SmithingScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.option.Perspective;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SurvivalActionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(KoeCraftAgentClient.MOD_ID);
    private static final double HARD_MAX_BREAK_DISTANCE = 6.0D;
    private static final double EFFECTIVE_SURVIVAL_BREAK_REACH = 4.35D;
    private static final int MAX_BLOCK_SAMPLES = 512;
    private static final int MAX_ENTITY_SAMPLES = 64;
    private static final long CONTAINER_SUPPLY_CACHE_TTL_MS = 30_000L;
    private static final long BLOCK_SEARCH_CACHE_TTL_MS = 1_200L;
    private static final long UNREACHABLE_TARGET_TTL_MS = 20_000L;
    private static final int BLOCK_SEARCH_CACHE_BUCKET_SIZE = 4;
    private static final int MAX_BLOCK_SEARCH_CACHE_ENTRIES = 96;
    private static final int MAX_UNREACHABLE_TARGET_ENTRIES = 64;
    private static final long SCAN_CONTEXT_CACHE_TTL_MS = 800L;
    private static final int SCAN_CONTEXT_CACHE_BUCKET_SIZE = 2;
    private static final int MAX_SCAN_CONTEXT_CACHE_ENTRIES = 32;
    private static final int BLOCKED_REACTION_TICKS = 72;
    private static final String BLOCKED_REACTION_MESSAGE = "すみませんが、できないです。";
    private final MinecraftClient client;
    private final KoeCraftModConfig config;
    private final Consumer<String> statusSink;
    private volatile boolean aborted;
    private volatile boolean executing;
    private String lastFailureSignature = "";
    private int repeatedFailureCount = 0;
    private final Set<String> exploredSectors = new HashSet<>();
    private final Map<String, ContainerSupplyCacheEntry> containerSupplyHits = new HashMap<>();
    private final Map<String, Long> containerSupplyMisses = new HashMap<>();
    private final Map<String, BlockSearchCacheEntry> blockSearchCache = new HashMap<>();
    private final Map<String, Long> unreachableBlockTargets = new HashMap<>();
    private final Map<String, BlockScanCacheEntry> blockScanCache = new HashMap<>();
    private final Map<String, JsonArrayCacheEntry> entityScanCache = new HashMap<>();
    private final Map<String, JsonArrayCacheEntry> itemScanCache = new HashMap<>();
    private final VanillaRecipeCatalog vanillaRecipeCatalog = new VanillaRecipeCatalog(SurvivalActionExecutor.class);

    private record BlockScanResult(JsonArray details, JsonObject summary, int scannedBlocks, boolean detailTruncated) {}
    private record ExecutionSnapshot(double x, double y, double z, int blockX, int blockY, int blockZ, int inventoryItems, String screen, String feetBlock, String headBlock) {}
    private record ContainerSupplyCacheEntry(BlockPos pos, long expiresAtMs) {}
    private record BlockSearchCacheEntry(BlockPos pos, long expiresAtMs, boolean found) {}
    private record BlockScanCacheEntry(BlockScanResult result, long expiresAtMs) {}
    private record JsonArrayCacheEntry(JsonArray value, long expiresAtMs) {}
    private record DirectContainerContext(MinecraftServer server, RegistryKey<World> dimensionKey, UUID playerUuid) {}
    private record DirectContainerTakeResult(JsonObject data, BlockPos hit, int scannedCount, int unknownCount, int matchingCount, int movedCount) {}
    private record ProgrammaticBoatResult(boolean spawned, boolean riding, String mode, String reason) {}
    private record BlueprintSite(BlockPos base, Direction right, Direction forward, int width, int depth, double score) {}
    private record BlueprintBlock(BlockPos pos, String blockId, String role) {}
    private record ProgrammaticBlockPlacementResult(boolean placed, String reason) {}
    private record ProgrammaticBlockBreakResult(
        boolean broken,
        String blockId,
        String dropItem,
        int dropCount,
        int insertedCount,
        int spawnedCount,
        String reason
    ) {}
    private record DirectCraftIngredientUse(int slot, String itemId, boolean generated) {}

    public SurvivalActionExecutor(MinecraftClient client) {
        this(client, KoeCraftModConfig.load());
    }

    public SurvivalActionExecutor(MinecraftClient client, KoeCraftModConfig config) {
        this(client, config, message -> {});
    }

    public SurvivalActionExecutor(MinecraftClient client, KoeCraftModConfig config, Consumer<String> statusSink) {
        this.client = client;
        this.config = config;
        this.statusSink = statusSink;
    }

    public synchronized List<ExecutorProtocol.StepResult> execute(List<ExecutorProtocol.Action> actions) {
        return execute("", actions);
    }

    public synchronized List<ExecutorProtocol.StepResult> execute(String goal, List<ExecutorProtocol.Action> actions) {
        aborted = false;
        executing = true;
        lastFailureSignature = "";
        repeatedFailureCount = 0;
        List<ExecutorProtocol.StepResult> results = new ArrayList<>();
        publishStatus("計画開始 0/" + actions.size() + ": " + (goal == null || goal.isBlank() ? "agent task" : goal));
        boolean observerCamera = shouldUseObserverCamera(actions);
        boolean restoreCamera = observerCamera;
        if (observerCamera) {
            switchToAgentObserverCamera();
        }
        try {
            for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                ExecutorProtocol.Action action = actions.get(actionIndex);
                int index = actionIndex + 1;
                long stepStartedNanos = System.nanoTime();
                if (aborted) {
                    results.add(new ExecutorProtocol.StepResult(action.type(), "aborted", "Execution was interrupted."));
                    publishStatus("中断: " + ExecutorStatusLabels.actionLabel(action));
                    break;
                }
                publishStatus("計画 " + index + "/" + actions.size() + ": " + ExecutorStatusLabels.actionLabel(action));
                ExecutionSnapshot before = captureExecutionSnapshot();
                long actionStartedNanos = System.nanoTime();
                ExecutorProtocol.StepResult result = executeOne(action);
                result = attachElapsedMillis(result, "action_elapsed_ms", actionStartedNanos);
                ExecutionSnapshot after = captureExecutionSnapshot();
                result = applyExecutionWatchdog(action, result, before, after, index);
                int recoveryAttempts = 0;
                while (shouldAttemptRecovery(action, result) && recoveryAttempts < config.maxWatchdogRecoveryAttempts()) {
                    recoveryAttempts++;
                    publishStatus("復旧中 " + index + "/" + actions.size() + " (" + recoveryAttempts + "/" + config.maxWatchdogRecoveryAttempts() + "): " + recoveryHint(action, result));
                    long recoveryStartedNanos = System.nanoTime();
                    ExecutorProtocol.StepResult recovery = recoverFromActionStall(action, result);
                    recovery = attachElapsedMillis(recovery, "recovery_elapsed_ms", recoveryStartedNanos);
                    attachRecoveryResult(result, recovery, recoveryAttempts);
                    if (!"accepted".equals(recovery.status()) || aborted) {
                        break;
                    }
                    ExecutionSnapshot retryBefore = captureExecutionSnapshot();
                    long retryStartedNanos = System.nanoTime();
                    ExecutorProtocol.StepResult retry = executeOne(action);
                    retry = attachElapsedMillis(retry, "retry_action_elapsed_ms", retryStartedNanos);
                    ExecutionSnapshot retryAfter = captureExecutionSnapshot();
                    retry = applyExecutionWatchdog(action, retry, retryBefore, retryAfter, index);
                    attachRecoveryResult(retry, recovery, recoveryAttempts);
                    result = retry;
                }
                result = attachElapsedMillis(result, "step_total_elapsed_ms", stepStartedNanos);
                if (!aborted && shouldPlayBlockedReaction(result)) {
                    restoreCamera = true;
                    result = playBlockedReaction(action, result);
                }
                results.add(result);
                logStepResult(index, actions.size(), action, result);
                publishStatus(ExecutorStatusLabels.statusLabel(result.status()) + ": " + ExecutorStatusLabels.actionLabel(action));
                if (shouldContinueAfterPartial(action, result, actions, actionIndex)) {
                    publishStatus("継続: " + ExecutorStatusLabels.actionLabel(action) + " -> " + ExecutorStatusLabels.actionLabel(actions.get(actionIndex + 1)));
                    continue;
                }
                if (ExecutorStatusLabels.isTerminalStepStatus(result.status())) {
                    break;
                }
            }
        } finally {
            releaseKeys();
            if (restoreCamera) {
                restoreFirstPersonCamera();
            }
            executing = false;
        }
        ExecutorProtocol.StepResult last = results.isEmpty() ? null : results.get(results.size() - 1);
        if (last == null || !ExecutorStatusLabels.isTerminalStepStatus(last.status())) {
            publishStatus("完了 " + actions.size() + "/" + actions.size() + ": agent task");
        }
        return results;
    }

    private boolean shouldContinueAfterPartial(
        ExecutorProtocol.Action action,
        ExecutorProtocol.StepResult result,
        List<ExecutorProtocol.Action> actions,
        int actionIndex
    ) {
        if (!"partial".equals(result.status()) || actionIndex + 1 >= actions.size() || result.data() == null) {
            return false;
        }
        return shouldContinueAfterPartialStep(action.type(), actions.get(actionIndex + 1).type(), result.data());
    }

    static boolean shouldContinueAfterPartialStep(String actionType, String nextActionType, JsonObject data) {
        if (data == null) {
            return false;
        }
        if ("collect_block".equals(actionType) && "collect_drops".equals(nextActionType)) {
            int brokenCount = intData(data, "broken_count", 0);
            return brokenCount > 0;
        }
        if ("collect_drops".equals(actionType)) {
            int collectedCount = intData(data, "collected_count", 0);
            return collectedCount > 0;
        }
        if ("craft_planks_from_log".equals(actionType)) {
            int craftedCount = intData(data, "crafted_count", 0);
            int endingPlanks = intData(data, "ending_planks", 0);
            return craftedCount > 0 || endingPlanks > 0;
        }
        if ("craft".equals(actionType)) {
            int craftedCount = intData(data, "crafted_count", 0);
            return craftedCount > 0;
        }
        return false;
    }

    static boolean shouldPlayBlockedReaction(ExecutorProtocol.StepResult result) {
        return result != null && "blocked".equals(result.status());
    }

    private static int intData(JsonObject data, String key, int fallback) {
        if (data == null || !data.has(key) || !data.get(key).isJsonPrimitive() || !data.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return data.get(key).getAsInt();
    }

    public void abort(String reason) {
        aborted = true;
        releaseKeys();
        restoreFirstPersonCamera();
        publishStatus("中断: " + reason);
    }

    public boolean isExecuting() {
        return executing;
    }

    private boolean shouldUseObserverCamera(List<ExecutorProtocol.Action> actions) {
        return actions.stream().anyMatch(action -> !action.type().equals("scan_state") && !action.type().equals("abort"));
    }

    private void switchToAgentObserverCamera() {
        callOnClientThread(() -> {
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            return null;
        });
    }

    private void restoreFirstPersonCamera() {
        callOnClientThread(() -> {
            client.options.setPerspective(Perspective.FIRST_PERSON);
            return null;
        });
    }

    private void publishStatus(String message) {
        statusSink.accept("[KoeCraft] " + message);
    }

    private String recoveryHint(ExecutorProtocol.Action action, ExecutorProtocol.StepResult result) {
        String label = ExecutorStatusLabels.actionLabel(action);
        String reason = result == null || result.message() == null ? "" : result.message();
        String strategy = switch (action.type()) {
            case "move", "explore", "find_structure" -> "安全な横避け/近づき直し";
            case "collect_block", "dig_pattern" -> "手前ブロック露出/接近";
            case "collect_drops", "pickup_items" -> "拾得位置へ近づき直し";
            case "place_block", "place_support", "build_bridge" -> "足場位置を再確認";
            case "craft", "open_workstation" -> "UI安定待ち/作業台再接近";
            default -> "再スキャン";
        };
        if (!reason.isBlank()) {
            reason = " / " + reason;
        }
        return strategy + ": " + label + reason;
    }

    private String shortMinecraftId(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return ExecutorStatusLabels.friendlyMinecraftName(value);
    }

    private ExecutorProtocol.StepResult executeOne(ExecutorProtocol.Action action) {
        if (action.type().equals("planner_blocked_reason")) {
            return plannerBlockedReason(action);
        }
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
        }

        return switch (action.type()) {
            case "abort" -> {
                abort("agent_requested_abort");
                yield new ExecutorProtocol.StepResult(action.type(), "aborted", "Abort accepted.");
            }
            case "scan_state" -> scanState(action);
            case "scan_build_area" -> scanBuildArea(action);
            case "scan_explore_area" -> scanExploreArea(action);
            case "look_at" -> lookAt(action);
            case "break_block" -> executeBreakBlock(action);
            case "place_block" -> placeBlock(action);
            case "build_blueprint" -> buildBlueprint(action);
            case "take_from_container" -> takeFromContainer(action);
            case "grant_item" -> grantItem(action);
            case "collect_block" -> collectBlock(action);
            case "collect_honeycomb" -> collectHoneycomb(action);
            case "collect_honey_bottle" -> collectHoneyBottle(action);
            case "dig_pattern" -> digPattern(action);
            case "drop_inventory" -> dropInventory(action);
            case "collect_drops" -> collectDrops(action);
            case "replant_crop" -> replantCrop(action);
            case "attack_entity" -> attackEntity(action);
            case "collect_milk" -> collectMilk(action);
            case "defensive_move" -> defensiveMove(action);
            case "open_passage" -> openPassage(action);
            case "celebrate" -> celebrate(action);
            case "ambient_chat" -> ambientChat(action);
            case "check_tool_durability" -> checkToolDurability(action);
            case "emergency_shelter" -> emergencyShelter(action);
            case "escape_fluid" -> escapeFluid(action);
            case "use_boat_if_water" -> useBoatIfWater(action);
            case "move" -> move(action);
            case "build_bridge" -> buildBridge(action);
            case "explore" -> explore(action);
            case "equip_gear" -> equipGear(action);
            case "ensure_hotbar" -> ensureHotbar(action);
            case "open_workstation" -> openWorkstation(action);
            case "close_screen" -> closeScreen(action);
            case "craft_planks_from_log" -> craftPlanksFromLog(action);
            case "craft" -> craft(action);
            case "smithing_trim" -> smithingTrim(action);
            case "component_craft" -> componentCraft(action);
            case "smelt" -> smelt(action);
            case "smelt_food" -> smeltFood(action);
            case "eat_food" -> eatFood(action);
            case "collect_fluid" -> collectFluid(action);
            case "build_nether_portal" -> buildNetherPortal(action);
            case "ignite_nether_portal" -> igniteNetherPortal(action);
            default -> new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported action type.");
        };
    }

    private ExecutorProtocol.StepResult plannerBlockedReason(ExecutorProtocol.Action action) {
        JsonObject data = new JsonObject();
        data.addProperty("reason_code", action.stringField("reason_code"));
        data.addProperty("item", action.stringField("item"));
        data.addProperty("max_depth", action.intField("max_depth", 0));
        String message = action.stringField("message");
        if (message.isBlank()) {
            message = "Planner stopped before executing unsafe or cyclic recipe expansion.";
        }
        return new ExecutorProtocol.StepResult(action.type(), "blocked", message, data);
    }

    private ExecutionSnapshot captureExecutionSnapshot() {
        return callOnClientThread(() -> {
            if (client.player == null || client.world == null) {
                return new ExecutionSnapshot(0.0D, 0.0D, 0.0D, 0, 0, 0, 0, "not_ready", "", "");
            }
            Vec3d pos = client.player.getPos();
            BlockPos blockPos = client.player.getBlockPos();
            String screen = client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName();
            return new ExecutionSnapshot(
                pos.x,
                pos.y,
                pos.z,
                blockPos.getX(),
                blockPos.getY(),
                blockPos.getZ(),
                totalInventoryItems(),
                screen,
                blockIdAt(blockPos),
                blockIdAt(blockPos.up())
            );
        });
    }

    private ExecutorProtocol.StepResult applyExecutionWatchdog(
        ExecutorProtocol.Action action,
        ExecutorProtocol.StepResult result,
        ExecutionSnapshot before,
        ExecutionSnapshot after,
        int index
    ) {
        JsonObject data = result.data() == null ? new JsonObject() : result.data();
        JsonObject guard = new JsonObject();
        double moved = snapshotDistance(before, after);
        int inventoryDelta = after.inventoryItems() - before.inventoryItems();
        boolean sameBlock = before.blockX() == after.blockX() && before.blockY() == after.blockY() && before.blockZ() == after.blockZ();
        boolean noProgress = moved < 0.08D && inventoryDelta == 0 && sameBlock && before.screen().equals(after.screen());
        guard.addProperty("step_index", index);
        guard.addProperty("moved_blocks", moved);
        guard.addProperty("inventory_delta", inventoryDelta);
        guard.addProperty("same_block_position", sameBlock);
        guard.addProperty("before_screen", before.screen());
        guard.addProperty("after_screen", after.screen());
        guard.addProperty("feet_block", after.feetBlock());
        guard.addProperty("head_block", after.headBlock());
        guard.addProperty("no_progress", noProgress);

        String status = result.status();
        String message = result.message();
        if ("accepted".equals(status) && shouldRequireActionProgress(action, data) && noProgress) {
            status = "blocked";
            message = "Action reported success but made no measurable progress.";
            guard.addProperty("converted_status", true);
        }

        if (ExecutorStatusLabels.isTerminalStepStatus(status)) {
            String signature = action.type() + "|" + status + "|" + message + "|" + after.blockX() + "," + after.blockY() + "," + after.blockZ();
            if (signature.equals(lastFailureSignature)) {
                repeatedFailureCount++;
            } else {
                lastFailureSignature = signature;
                repeatedFailureCount = 1;
            }
            guard.addProperty("failure_signature", signature);
            guard.addProperty("repeated_failure_count", repeatedFailureCount);
            if (repeatedFailureCount >= 2) {
                guard.addProperty("repeated_failure", true);
            }
        } else if (!"accepted".equals(status)) {
            lastFailureSignature = "";
            repeatedFailureCount = 0;
        }

        data.add("watchdog", guard);
        return new ExecutorProtocol.StepResult(result.type(), status, message, data);
    }

    private boolean shouldRequireActionProgress(ExecutorProtocol.Action action, JsonObject data) {
        return switch (action.type()) {
            case "move", "explore" -> true;
            case "build_bridge" -> data.has("placed_count") && data.get("placed_count").isJsonPrimitive() && data.get("placed_count").getAsInt() <= 0;
            default -> false;
        };
    }

    private double snapshotDistance(ExecutionSnapshot before, ExecutionSnapshot after) {
        double dx = after.x() - before.x();
        double dy = after.y() - before.y();
        double dz = after.z() - before.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean shouldAttemptRecovery(ExecutorProtocol.Action action, ExecutorProtocol.StepResult result) {
        if (aborted || !"blocked".equals(result.status()) || result.data() == null) {
            return false;
        }
        JsonObject watchdog = objectField(result.data(), "watchdog");
        boolean watchdogNoProgress = boolField(watchdog, "no_progress");
        boolean breakNoProgress = result.data().has("no_block_progress_ticks")
            && result.data().get("no_block_progress_ticks").isJsonPrimitive()
            && result.data().get("no_block_progress_ticks").getAsInt() > 0;
        boolean dangerStop = boolField(result.data(), "danger_stop");
        boolean assistBlocked = "blocked".equals(stringField(result.data(), "assist_status", ""))
            || result.data().has("assist")
            || result.data().has("movement_assist")
            || result.data().has("navigation");
        boolean movementDangerRecovery = dangerStop && assistBlocked && switch (action.type()) {
            case "move", "explore", "build_bridge" -> true;
            default -> false;
        };
        if (!watchdogNoProgress && !breakNoProgress && !movementDangerRecovery) {
            return false;
        }
        return switch (action.type()) {
            case "move", "explore", "build_bridge", "break_block", "collect_block", "collect_drops", "dig_pattern" -> true;
            default -> false;
        };
    }

    private ExecutorProtocol.StepResult recoverFromActionStall(ExecutorProtocol.Action action, ExecutorProtocol.StepResult failed) {
        JsonObject data = new JsonObject();
        data.addProperty("failed_action", action.type());
        data.addProperty("failed_message", failed.message());
        if (failed.data() != null) {
            data.add("failed_data", failed.data());
        }
        releaseKeys();
        return switch (action.type()) {
            case "move", "build_bridge", "dig_pattern" -> recoverMovementAction(action, data);
            case "explore" -> recoverExploreAction(action, data);
            case "break_block", "collect_block" -> recoverBlockAction(action, failed, data);
            case "collect_drops" -> recoverDropAction(failed, data);
            default -> new ExecutorProtocol.StepResult(action.type(), "blocked", "No recovery policy is available for this action.", data);
        };
    }

    private ExecutorProtocol.StepResult recoverMovementAction(ExecutorProtocol.Action action, JsonObject data) {
        String direction = action.stringField("direction");
        if (!direction.equals("back") && !direction.equals("left") && !direction.equals("right")) {
            direction = "forward";
        }
        data.addProperty("direction", direction);
        ExecutorProtocol.StepResult lane = trySafeLaneRecovery(action, data);
        if ("accepted".equals(lane.status())) {
            return lane;
        }
        ExecutorProtocol.StepResult assist = tryMovementAssist(
            direction,
            action.booleanField("allow_place", true),
            action.booleanField("allow_dig", true),
            action.booleanField("allow_sprint_jump", true),
            action.type()
        );
        if (assist.data() != null) {
            data.add("movement_assist", assist.data());
        }
        if ("accepted".equals(assist.status())) {
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Recovered stalled movement with local movement assist.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "blocked", "Movement recovery could not find a safe local assist.", data);
    }

    private ExecutorProtocol.StepResult trySafeLaneRecovery(ExecutorProtocol.Action action, JsonObject data) {
        Boolean steered = callOnClientThread(() -> {
            if (client.player == null || client.world == null) {
                return false;
            }
            int distanceBlocks = Math.max(2, Math.min(action.intField("distance_blocks", 4), 8));
            MoveLane lane = chooseSafeMoveLane(movementVector("forward"), distanceBlocks);
            if (lane == null || lane.safeSteps() < 2) {
                return false;
            }
            faceHorizontalDirection(lane.direction(), 24.0F);
            releaseMovementKeys();
            client.options.forwardKey.setPressed(true);
            client.options.sprintKey.setPressed(action.booleanField("sprint", false) && lane.safeSteps() >= 4);
            client.options.jumpKey.setPressed(action.booleanField("jump_while_sprinting", false) && lane.safeSteps() >= 4);
            data.addProperty("recovery_strategy", "safe_lane_scan");
            data.addProperty("lane_adjusted", lane.adjusted());
            data.addProperty("lane_safe_steps", lane.safeSteps());
            data.addProperty("lane_score", lane.score());
            return true;
        });
        if (!Boolean.TRUE.equals(steered)) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No safe adjacent scan lane was available.", data);
        }
        sleepTicks(8);
        callOnClientThread(() -> {
            releaseMovementKeys();
            return null;
        });
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Recovered by moving into a safer scanned local lane.", data);
    }

    private ExecutorProtocol.StepResult recoverExploreAction(ExecutorProtocol.Action action, JsonObject data) {
        ExecutorProtocol.StepResult lane = trySafeLaneRecovery(action, data);
        if ("accepted".equals(lane.status())) {
            return lane;
        }
        Vec3d target = callOnClientThread(() -> client.player == null ? Vec3d.ZERO : client.player.getPos().add(movementVector("forward").multiply(4.0D)));
        ExecutorProtocol.StepResult assist = tryMovementAssistToward(target, action.type());
        if (assist.data() != null) {
            data.add("explore_assist", assist.data());
        }
        if ("accepted".equals(assist.status())) {
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Recovered stalled exploration with local path assist.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "blocked", "Exploration recovery could not find a safe local path.", data);
    }

    private ExecutorProtocol.StepResult recoverBlockAction(ExecutorProtocol.Action action, ExecutorProtocol.StepResult failed, JsonObject data) {
        BlockPos target = blockPosFromData(failed.data(), "target");
        if (target == null) {
            target = blockPosFromData(failed.data(), "");
        }
        if (target == null) {
            data.addProperty("recovery_strategy", "none");
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Block recovery did not have a target position.", data);
        }
        addBlockPos(data, "target", target);
        double reach = effectiveBreakReach(config.maxReach());
        ExecutorProtocol.StepResult exposed = exposeTargetBlock(target, reach, action.type());
        if (exposed.data() != null) {
            data.add("expose_attempt", exposed.data());
        }
        if ("accepted".equals(exposed.status())) {
            data.addProperty("recovery_strategy", "expose_target_block");
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Recovered by exposing the target block.", data);
        }
        ExecutorProtocol.StepResult approach = moveWithinReachOfBlock(target, reach, 80, action.type());
        if (approach.data() != null) {
            data.add("approach_attempt", approach.data());
        }
        if ("accepted".equals(approach.status())) {
            data.addProperty("recovery_strategy", "approach_target_block");
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Recovered by moving closer to the target block.", data);
        }
        ExecutorProtocol.StepResult assist = tryMovementAssistToward(Vec3d.ofCenter(target), action.type());
        if (assist.data() != null) {
            data.add("movement_assist", assist.data());
        }
        if ("accepted".equals(assist.status())) {
            data.addProperty("recovery_strategy", "movement_assist_toward_block");
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Recovered with local movement assist toward the block.", data);
        }
        rememberUnreachableBlockTarget(target);
        data.addProperty("unreachable_target_ttl_ms", UNREACHABLE_TARGET_TTL_MS);
        return new ExecutorProtocol.StepResult(action.type(), "blocked", "Block recovery could not expose or approach the target.", data);
    }

    private ExecutorProtocol.StepResult recoverDropAction(ExecutorProtocol.StepResult failed, JsonObject data) {
        Vec3d target = vectorFromData(failed.data(), "target");
        if (target == null) {
            target = callOnClientThread(() -> client.player == null ? null : client.player.getPos().add(movementVector("forward").multiply(2.0D)));
        }
        if (target == null) {
            return new ExecutorProtocol.StepResult("collect_drops", "blocked", "Drop recovery did not have a target position.", data);
        }
        ExecutorProtocol.StepResult assist = tryMovementAssistToward(target, "collect_drops");
        if (assist.data() != null) {
            data.add("drop_assist", assist.data());
        }
        if ("accepted".equals(assist.status())) {
            return new ExecutorProtocol.StepResult("collect_drops", "accepted", "Recovered stalled drop collection with local movement assist.", data);
        }
        return new ExecutorProtocol.StepResult("collect_drops", "blocked", "Drop recovery could not find a safe local path.", data);
    }

    private void attachRecoveryResult(ExecutorProtocol.StepResult result, ExecutorProtocol.StepResult recovery, int attempt) {
        if (result.data() == null || recovery == null) {
            return;
        }
        JsonObject recoveryData = new JsonObject();
        recoveryData.addProperty("attempt", attempt);
        recoveryData.addProperty("status", recovery.status());
        recoveryData.addProperty("message", recovery.message());
        if (recovery.data() != null) {
            recoveryData.add("data", recovery.data());
        }
        JsonArray history = result.data().has("recovery_history") && result.data().get("recovery_history").isJsonArray()
            ? result.data().getAsJsonArray("recovery_history")
            : new JsonArray();
        history.add(recoveryData);
        result.data().add("recovery_history", history);
        result.data().add("recovery", recoveryData);
    }

    private ExecutorProtocol.StepResult attachElapsedMillis(ExecutorProtocol.StepResult result, String key, long startedNanos) {
        JsonObject data = result.data() == null ? new JsonObject() : result.data();
        data.addProperty(key, elapsedMillis(startedNanos));
        return new ExecutorProtocol.StepResult(result.type(), result.status(), result.message(), data);
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private void logStepResult(int index, int total, ExecutorProtocol.Action action, ExecutorProtocol.StepResult result) {
        JsonObject data = result.data();
        long actionElapsed = longField(data, "action_elapsed_ms");
        long stepElapsed = longField(data, "step_total_elapsed_ms");
        LOGGER.info(
            "[KoeCraft] step_result {}/{} action={} status={} action_elapsed_ms={} step_total_elapsed_ms={} message={}",
            index,
            total,
            action.type(),
            result.status(),
            actionElapsed,
            stepElapsed,
            result.message()
        );
        if (data != null && ("partial".equals(result.status()) || "blocked".equals(result.status()) || "rejected".equals(result.status()))) {
            String detail = data.toString();
            if (detail.length() > 2_000) {
                detail = detail.substring(0, 2_000) + "...";
            }
            LOGGER.info("[KoeCraft] step_result_data {}/{} action={} data={}", index, total, action.type(), detail);
        }
    }

    private long longField(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isNumber()) {
            return -1L;
        }
        return object.get(key).getAsLong();
    }

    private JsonObject objectField(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
    }

    private boolean boolField(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private String stringField(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
            ? object.get(key).getAsString()
            : fallback;
    }

    private BlockPos blockPosFromData(JsonObject data, String prefix) {
        if (data == null) {
            return null;
        }
        String keyPrefix = prefix == null || prefix.isBlank() ? "" : prefix + "_";
        String xKey = keyPrefix + "x";
        String yKey = keyPrefix + "y";
        String zKey = keyPrefix + "z";
        if (!data.has(xKey) || !data.has(yKey) || !data.has(zKey)) {
            return null;
        }
        return new BlockPos(data.get(xKey).getAsInt(), data.get(yKey).getAsInt(), data.get(zKey).getAsInt());
    }

    private Vec3d vectorFromData(JsonObject data, String prefix) {
        if (data == null) {
            return null;
        }
        String keyPrefix = prefix == null || prefix.isBlank() ? "" : prefix + "_";
        String xKey = keyPrefix + "x";
        String yKey = keyPrefix + "y";
        String zKey = keyPrefix + "z";
        if (!data.has(xKey) || !data.has(yKey) || !data.has(zKey)) {
            return null;
        }
        return new Vec3d(data.get(xKey).getAsDouble(), data.get(yKey).getAsDouble(), data.get(zKey).getAsDouble());
    }

    private ExecutorProtocol.StepResult collectBlock(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }

        int radius = Math.max(1, Math.min(action.intField("search_radius", 12), config.maxScanRadius()));
        int requestedCount = Math.max(1, Math.min(action.intField("count", 1), 64));
        int timeoutTicks = Math.max(20, Math.min(action.intField("timeout_ticks", config.defaultCollectTimeoutTicks()), 400));
        boolean optional = action.booleanField("optional", false);
        String exactBlock = action.stringField("block");
        String group = action.stringField("block_group");
        if (exactBlock.isBlank() && group.isBlank()) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "collect_block requires block or block_group.");
        }
        if (!group.isBlank() && !BlockGroups.hasGroup(group)) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unknown block group: " + group);
        }

        JsonArray broken = new JsonArray();
        int brokenCount = 0;
        int approachedCount = 0;
        for (int i = 0; i < requestedCount && !aborted; i++) {
            BlockPos target = callOnClientThread(() -> findNearestMatchingBlock(radius, exactBlock, group, Double.POSITIVE_INFINITY));
            if (target == null) {
                break;
            }
            double breakReach = effectiveBreakReach(config.maxReach());
            ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(target, breakReach, Math.min(timeoutTicks, 120), action.type());
            if (!"accepted".equals(approached.status())) {
                rememberUnreachableBlockTarget(target);
                if (brokenCount > 0) {
                    JsonObject data = new JsonObject();
                    data.addProperty("requested_count", requestedCount);
                    data.addProperty("broken_count", brokenCount);
                    data.addProperty("approached_count", approachedCount);
                    data.addProperty("optional", optional);
                    data.add("broken_blocks", broken);
                    data.addProperty("last_status", approached.status());
                    data.addProperty("last_message", approached.message());
                    if (optional) {
                        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional block collection stopped after collecting some matching blocks.", data);
                    }
                    return new ExecutorProtocol.StepResult(action.type(), "partial", "Collected some matching blocks, then could not path within reach of the next target.", data);
                }
                if (optional) {
                    JsonObject data = new JsonObject();
                    data.addProperty("requested_count", requestedCount);
                    data.addProperty("broken_count", brokenCount);
                    data.addProperty("approached_count", approachedCount);
                    data.addProperty("optional", true);
                    data.addProperty("last_status", approached.status());
                    data.addProperty("last_message", approached.message());
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional block collection skipped because no safe reachable target was found.", data);
                }
                return approached;
            }
            approachedCount++;

            ExecutorProtocol.StepResult result = breakBlockAtOrExpose(target, breakReach, timeoutTicks, action.type());
            if (!"accepted".equals(result.status())) {
                rememberUnreachableBlockTarget(target);
                if (brokenCount > 0) {
                    JsonObject data = new JsonObject();
                    data.addProperty("requested_count", requestedCount);
                    data.addProperty("broken_count", brokenCount);
                    data.addProperty("approached_count", approachedCount);
                    data.addProperty("optional", optional);
                    data.add("broken_blocks", broken);
                    data.addProperty("last_status", result.status());
                    data.addProperty("last_message", result.message());
                    if (optional) {
                        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional block collection stopped after collecting some matching blocks.", data);
                    }
                    return new ExecutorProtocol.StepResult(action.type(), "partial", "Collected some matching blocks, then stopped.", data);
                }
                if (optional) {
                    JsonObject data = new JsonObject();
                    data.addProperty("requested_count", requestedCount);
                    data.addProperty("broken_count", brokenCount);
                    data.addProperty("approached_count", approachedCount);
                    data.addProperty("optional", true);
                    data.addProperty("last_status", result.status());
                    data.addProperty("last_message", result.message());
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional block collection skipped because breaking the target was not safe.", data);
                }
                return result;
            }
            brokenCount++;
            if (result.data() != null) {
                broken.add(result.data());
            }
            sleepTicks(4);
        }

        releaseKeys();
        JsonObject data = new JsonObject();
        data.addProperty("requested_count", requestedCount);
        data.addProperty("broken_count", brokenCount);
        data.addProperty("approached_count", approachedCount);
        data.addProperty("path_aware_approach", true);
        data.addProperty("optional", optional);
        if (!exactBlock.isBlank()) {
            data.addProperty("block", exactBlock);
        }
        if (!group.isBlank()) {
            data.addProperty("block_group", group);
        }
        data.add("broken_blocks", broken);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Collect block was interrupted.", data);
        }
        if (brokenCount == 0) {
            if (optional) {
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional block collection skipped because no reachable matching block was found.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No reachable matching block found within scan radius.", data);
        }
        if (brokenCount < requestedCount) {
            if (optional) {
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional block collection collected fewer blocks than requested.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "partial", "No more reachable matching blocks found before requested count.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Collected requested matching blocks with survival block breaking.", data);
    }

    private ExecutorProtocol.StepResult collectHoneycomb(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }
        int radius = Math.max(1, Math.min(action.intField("search_radius", 12), config.maxScanRadius()));
        int requestedCount = Math.max(1, Math.min(action.intField("count", 1), 16));
        int timeoutTicks = Math.max(40, Math.min(action.intField("timeout_ticks", 240), 800));
        boolean requireSmoke = action.booleanField("require_smoke", false);
        JsonObject data = new JsonObject();
        data.addProperty("search_radius", radius);
        data.addProperty("requested_count", requestedCount);
        data.addProperty("require_smoke", requireSmoke);
        JsonArray harvested = new JsonArray();
        int harvestedCount = 0;
        int ticks = 0;

        if (!selectHotbarItem("minecraft:shears")) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Shears are required in the hotbar to harvest honeycomb.", data);
        }

        while (harvestedCount < requestedCount && ticks < timeoutTicks && !aborted) {
            BlockPos target = findNearestHarvestableBeeNest(radius, requireSmoke);
            if (target == null) {
                data.addProperty("harvested_count", harvestedCount);
                data.add("harvested_blocks", harvested);
                return new ExecutorProtocol.StepResult(action.type(), harvestedCount > 0 ? "accepted" : "blocked", requireSmoke
                    ? "No reachable mature bee nest or beehive with campfire smoke was found."
                    : "No reachable mature bee nest or beehive was found.", data);
            }
            ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(target, config.maxReach(), 120, action.type());
            if (!"accepted".equals(approached.status())) {
                data.addProperty("harvested_count", harvestedCount);
                data.add("harvested_blocks", harvested);
                if (approached.data() != null) data.add("approach", approached.data());
                return new ExecutorProtocol.StepResult(action.type(), harvestedCount > 0 ? "accepted" : approached.status(), "Could not approach the mature bee nest or beehive.", data);
            }
            String beforeState = blockProgressKey(target);
            Vec3d hitPos = Vec3d.ofCenter(target);
            lookAtPosition(hitPos);
            BlockHitResult hit = new BlockHitResult(hitPos, breakSide(target), target, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            client.player.swingHand(Hand.MAIN_HAND);
            sleepTicks(8);
            String afterState = blockProgressKey(target);
            JsonObject item = new JsonObject();
            addBlockPos(item, "block", target);
            item.addProperty("block_id", blockIdAt(target));
            item.addProperty("smoke_protected", hasCampfireSmokeBelow(target));
            item.addProperty("state_before", beforeState);
            item.addProperty("state_after", afterState);
            harvested.add(item);
            if (!beforeState.equals(afterState)) {
                harvestedCount++;
            } else {
                break;
            }
            ticks += 8;
        }

        releaseKeys();
        data.addProperty("harvested_count", harvestedCount);
        data.add("harvested_blocks", harvested);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Honeycomb collection was interrupted.", data);
        }
        if (harvestedCount == 0) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not harvest honeycomb from any mature bee nest or beehive.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), harvestedCount < requestedCount ? "partial" : "accepted", "Harvested honeycomb with shears from mature bee nest or beehive blocks.", data);
    }

    private ExecutorProtocol.StepResult collectHoneyBottle(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }
        int radius = Math.max(1, Math.min(action.intField("search_radius", 12), config.maxScanRadius()));
        int requestedCount = Math.max(1, Math.min(action.intField("count", 1), 16));
        int timeoutTicks = Math.max(40, Math.min(action.intField("timeout_ticks", 240), 800));
        boolean requireSmoke = action.booleanField("require_smoke", false);
        JsonObject data = new JsonObject();
        data.addProperty("search_radius", radius);
        data.addProperty("requested_count", requestedCount);
        data.addProperty("require_smoke", requireSmoke);
        int beforeHoney = countInventoryItem("minecraft:honey_bottle");
        int beforeBottle = countInventoryItem("minecraft:glass_bottle");
        data.addProperty("honey_bottle_before", beforeHoney);
        data.addProperty("glass_bottle_before", beforeBottle);
        if (beforeBottle <= 0) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "A glass bottle is required to collect honey.", data);
        }
        if (!selectHotbarItem("minecraft:glass_bottle")) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not move a glass bottle to the hotbar.", data);
        }

        JsonArray collected = new JsonArray();
        int collectedCount = 0;
        int ticks = 0;
        while (collectedCount < requestedCount && ticks < timeoutTicks && !aborted) {
            BlockPos target = findNearestHarvestableBeeNest(radius, requireSmoke);
            if (target == null) {
                data.addProperty("collected_count", collectedCount);
                data.add("collected_blocks", collected);
                return new ExecutorProtocol.StepResult(action.type(), collectedCount > 0 ? "accepted" : "blocked", requireSmoke
                    ? "No reachable mature bee nest or beehive with campfire smoke was found."
                    : "No reachable mature bee nest or beehive was found.", data);
            }
            ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(target, config.maxReach(), 120, action.type());
            if (!"accepted".equals(approached.status())) {
                data.addProperty("collected_count", collectedCount);
                data.add("collected_blocks", collected);
                if (approached.data() != null) data.add("approach", approached.data());
                return new ExecutorProtocol.StepResult(action.type(), collectedCount > 0 ? "accepted" : approached.status(), "Could not approach the mature bee nest or beehive.", data);
            }
            if (countInventoryItem("minecraft:glass_bottle") <= 0) {
                data.addProperty("collected_count", collectedCount);
                data.add("collected_blocks", collected);
                return new ExecutorProtocol.StepResult(action.type(), collectedCount > 0 ? "partial" : "blocked", "Ran out of glass bottles while collecting honey.", data);
            }
            selectHotbarItem("minecraft:glass_bottle");
            int honeyBeforeUse = countInventoryItem("minecraft:honey_bottle");
            int bottleBeforeUse = countInventoryItem("minecraft:glass_bottle");
            String beforeState = blockProgressKey(target);
            Vec3d hitPos = Vec3d.ofCenter(target);
            lookAtPosition(hitPos);
            BlockHitResult hit = new BlockHitResult(hitPos, breakSide(target), target, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            client.player.swingHand(Hand.MAIN_HAND);
            sleepTicks(8);
            int honeyAfterUse = countInventoryItem("minecraft:honey_bottle");
            int bottleAfterUse = countInventoryItem("minecraft:glass_bottle");
            String afterState = blockProgressKey(target);
            JsonObject item = new JsonObject();
            addBlockPos(item, "block", target);
            item.addProperty("block_id", blockIdAt(target));
            item.addProperty("smoke_protected", hasCampfireSmokeBelow(target));
            item.addProperty("state_before", beforeState);
            item.addProperty("state_after", afterState);
            item.addProperty("honey_delta", honeyAfterUse - honeyBeforeUse);
            item.addProperty("glass_bottle_delta", bottleAfterUse - bottleBeforeUse);
            collected.add(item);
            if (honeyAfterUse > honeyBeforeUse) {
                collectedCount += honeyAfterUse - honeyBeforeUse;
            } else if (!beforeState.equals(afterState)) {
                collectedCount++;
            } else {
                break;
            }
            ticks += 8;
        }

        releaseKeys();
        int actualDelta = countInventoryItem("minecraft:honey_bottle") - beforeHoney;
        data.addProperty("collected_count", Math.max(collectedCount, actualDelta));
        data.addProperty("honey_bottle_after", countInventoryItem("minecraft:honey_bottle"));
        data.addProperty("glass_bottle_after", countInventoryItem("minecraft:glass_bottle"));
        data.add("collected_blocks", collected);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Honey bottle collection was interrupted.", data);
        }
        if (actualDelta <= 0) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not collect honey from any mature bee nest or beehive.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), actualDelta < requestedCount ? "partial" : "accepted", "Collected honey bottles from mature bee nest or beehive blocks.", data);
    }

    private ExecutorProtocol.StepResult digPattern(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }
        String pattern = action.stringField("pattern");
        if (!pattern.equals("stair_down") && !pattern.equals("tunnel_forward") && !pattern.equals("shaft_down_safe")) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported dig pattern: " + pattern);
        }
        String directionName = action.stringField("direction");
        if (directionName.isBlank()) {
            directionName = "forward";
        }
        if (!directionName.equals("forward") && !directionName.equals("back") && !directionName.equals("left") && !directionName.equals("right")) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported dig direction: " + directionName);
        }
        int steps = Math.max(1, Math.min(action.intField("steps", 4), 12));
        int timeoutTicks = Math.max(20, Math.min(action.intField("timeout_ticks", steps * 140), 1200));
        JsonObject data = new JsonObject();
        data.addProperty("pattern", pattern);
        data.addProperty("direction", directionName);
        data.addProperty("requested_steps", steps);
        data.addProperty("timeout_ticks", timeoutTicks);

        int completed = 0;
        int ticksUsed = 0;
        JsonArray stepResults = new JsonArray();
        for (int step = 0; step < steps && !aborted && ticksUsed < timeoutTicks; step++) {
            if (callOnClientThread(this::hasImmediateAbortDanger)) {
                data.addProperty("danger_stop", true);
                break;
            }
            ExecutorProtocol.StepResult result = switch (pattern) {
                case "tunnel_forward" -> digTunnelForwardStep(directionName, action.type());
                case "shaft_down_safe" -> digSafeShaftStep(directionName, action.type());
                default -> digStairDownStep(directionName, action.type());
            };
            JsonObject stepData = new JsonObject();
            stepData.addProperty("index", step);
            stepData.addProperty("status", result.status());
            stepData.addProperty("message", result.message());
            if (result.data() != null) stepData.add("data", result.data());
            stepResults.add(stepData);
            if (!"accepted".equals(result.status())) {
                data.addProperty("last_status", result.status());
                data.addProperty("last_message", result.message());
                break;
            }
            completed++;
            ticksUsed += 20;
        }
        releaseKeys();
        data.addProperty("completed_steps", completed);
        data.add("steps", stepResults);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Dig pattern was interrupted.", data);
        }
        if (completed == 0) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not complete the first bounded dig pattern step.", data);
        }
        if (completed < steps) {
            return new ExecutorProtocol.StepResult(action.type(), "partial", "Completed part of the bounded dig pattern before stopping.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Completed bounded dig pattern with survival block breaking.", data);
    }

    private ExecutorProtocol.StepResult collectDrops(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
        }
        String item = action.stringField("item");
        String group = action.stringField("item_group");
        if (item.isBlank() && group.isBlank()) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "collect_drops requires item or item_group.");
        }
        if (!group.isBlank()
            && !group.equals("food_drop")
            && !group.equals("any_drop")
            && !MinecraftItemGroups.hasGroup(group)) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported item group: " + group);
        }
        int radius = Math.max(1, Math.min(action.intField("search_radius", 8), config.maxScanRadius()));
        int requestedCount = Math.max(1, Math.min(action.intField("count", 1), 16));
        int timeoutTicks = Math.max(20, Math.min(action.intField("timeout_ticks", 120), 400));
        boolean optional = action.booleanField("optional", false);
        boolean acceptIfAlreadyPresent = action.booleanField("accept_if_already_present", false);
        boolean magnetFallback = action.booleanField("magnet_fallback", acceptIfAlreadyPresent);
        boolean magnetOnly = action.booleanField("magnet_only", magnetFallback);
        JsonObject data = new JsonObject();
        data.addProperty("item", item);
        data.addProperty("item_group", group);
        data.addProperty("requested_count", requestedCount);
        data.addProperty("optional", optional);
        data.addProperty("accept_if_already_present", acceptIfAlreadyPresent);
        data.addProperty("magnet_fallback", magnetFallback);
        data.addProperty("magnet_only", magnetOnly);
        JsonArray collected = new JsonArray();
        int collectedCount = 0;
        int requestedItemStartCount = callOnClientThread(() -> dropTargetInventoryCount(item, group));
        int alreadyPresentCount = callOnClientThread(() -> item.isBlank() ? countInventoryGroup(group) : countInventoryItem(item));
        data.addProperty("already_present_count", alreadyPresentCount);
        if (acceptIfAlreadyPresent && alreadyPresentCount >= requestedCount) {
            data.addProperty("accepted_already_present", true);
            data.addProperty("collected_count", 0);
            data.add("collected_items", collected);
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Matching drops are already present in inventory.", data);
        }
        if (magnetFallback && config.worldAssistMagnetPickupEnabled()) {
            JsonObject magnet = pullDropsWithItemMagnet(item, group, requestedItemStartCount, requestedCount, Math.min(timeoutTicks, 16));
            data.add("item_magnet_prepass", magnet);
            collectedCount = Math.max(collectedCount, callOnClientThread(() -> dropTargetInventoryCount(item, group)) - requestedItemStartCount);
            if (collectedCount >= requestedCount) {
                data.addProperty("collected_count", collectedCount);
                data.add("collected_items", collected);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Collected matching item drops with item magnet.", data);
            }
        }
        if (magnetOnly) {
            if (magnetFallback && config.worldAssistMagnetPickupEnabled() && collectedCount < requestedCount && !aborted) {
                JsonObject magnet = pullDropsWithItemMagnet(item, group, requestedItemStartCount, requestedCount, Math.min(timeoutTicks, 120));
                data.add("item_magnet", magnet);
                collectedCount = Math.max(collectedCount, callOnClientThread(() -> dropTargetInventoryCount(item, group)) - requestedItemStartCount);
            }
            releaseKeys();
            data.addProperty("movement_skipped", true);
            data.addProperty("collected_count", collectedCount);
            data.add("collected_items", collected);
            if (aborted) {
                return new ExecutorProtocol.StepResult(action.type(), "aborted", "Drop collection was interrupted.", data);
            }
            if (collectedCount == 0) {
                if (optional) {
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional magnet-only drop collection skipped because no matching drop was pulled.", data);
                }
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No matching drop was pulled by item magnet.", data);
            }
            if (optional && collectedCount < requestedCount) {
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional magnet-only drop collection pulled fewer items than requested.", data);
            }
            if (group.equals("any_drop") && collectedCount > 0 && collectedCount < requestedCount) {
                data.addProperty("accepted_partial_any_drop", true);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Pulled some nearby drops with item magnet; continuing so later material checks can decide.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), collectedCount < requestedCount ? "partial" : "accepted", "Collected matching item drops with magnet-only pickup.", data);
        }

        for (int i = 0; i < requestedCount && !aborted; i++) {
            if (!item.isBlank()) {
                collectedCount = Math.max(collectedCount, callOnClientThread(() -> countInventoryItem(item)) - requestedItemStartCount);
                if (collectedCount >= requestedCount) {
                    break;
                }
            }
            ItemEntity target = callOnClientThread(() -> findNearestMatchingItemEntity(item, group, radius));
            if (target == null) {
                break;
            }
            String itemId = callOnClientThread(() -> itemId(target.getStack()));
            int before = callOnClientThread(() -> countInventoryItem(itemId));
            ExecutorProtocol.StepResult result = moveTowardItemEntity(target, itemId, before, timeoutTicks, action.type());
            if (result.data() != null) {
                collected.add(result.data());
            }
            if (!"accepted".equals(result.status())) {
                data.addProperty("collected_count", collectedCount);
                data.add("collected_items", collected);
                data.addProperty("last_status", result.status());
                data.addProperty("last_message", result.message());
                if (optional) {
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional drop collection stopped before all requested items were collected.", data);
                }
                if (group.equals("any_drop") && collectedCount > 0) {
                    data.addProperty("accepted_partial_any_drop", true);
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Collected some nearby drops; continuing so later material checks can decide.", data);
                }
                return new ExecutorProtocol.StepResult(action.type(), collectedCount > 0 ? "partial" : result.status(), "Drop collection stopped before all requested items were collected.", data);
            }
            collectedCount = item.isBlank()
                ? collectedCount + 1
                : Math.max(collectedCount, callOnClientThread(() -> countInventoryItem(item)) - requestedItemStartCount);
        }

        releaseKeys();
        collectedCount = Math.max(collectedCount, callOnClientThread(() -> dropTargetInventoryCount(item, group)) - requestedItemStartCount);
        if (magnetFallback && collectedCount < requestedCount && !aborted) {
            if (config.worldAssistMagnetPickupEnabled()) {
                JsonObject magnet = pullDropsWithItemMagnet(item, group, requestedItemStartCount, requestedCount, Math.min(timeoutTicks, 80));
                data.add("item_magnet", magnet);
                collectedCount = Math.max(collectedCount, callOnClientThread(() -> dropTargetInventoryCount(item, group)) - requestedItemStartCount);
            }
        }
        if (magnetFallback && collectedCount < requestedCount && !aborted) {
            JsonObject sweep = pickupSweepForDrops(item, group, requestedItemStartCount, requestedCount, Math.min(timeoutTicks, 80), action.type());
            data.add("magnet_sweep", sweep);
            collectedCount = Math.max(collectedCount, callOnClientThread(() -> dropTargetInventoryCount(item, group)) - requestedItemStartCount);
        }
        data.addProperty("collected_count", collectedCount);
        data.add("collected_items", collected);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Drop collection was interrupted.", data);
        }
        if (collectedCount == 0) {
            if (optional) {
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional drop collection skipped because no reachable matching drop was found.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No reachable matching drop found within scan radius.", data);
        }
        if (optional && collectedCount < requestedCount) {
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional drop collection collected fewer items than requested.", data);
        }
        if (group.equals("any_drop") && collectedCount > 0 && collectedCount < requestedCount) {
            data.addProperty("accepted_partial_any_drop", true);
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Collected some nearby drops; continuing so later material checks can decide.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), collectedCount < requestedCount ? "partial" : "accepted", "Collected matching item drops by walking to them.", data);
    }

    private JsonObject pullDropsWithItemMagnet(String item, String group, int startCount, int requestedCount, int timeoutTicks) {
        JsonObject data = new JsonObject();
        data.addProperty("enabled", true);
        data.addProperty("mode", "item_entity_snap_and_velocity_pull");
        data.addProperty("radius", config.itemMagnetRadius());
        data.addProperty("strength", config.itemMagnetStrength());
        int ticks = 0;
        int pulses = 0;
        int affectedTotal = 0;
        while (ticks < timeoutTicks && !aborted) {
            int current = callOnClientThread(() -> dropTargetInventoryCount(item, group));
            if (current - startCount >= requestedCount) {
                break;
            }
            int affected = callOnClientThread(() -> applyItemMagnetPulse(item, group));
            affectedTotal += affected;
            pulses++;
            sleepTicks(2);
            ticks += 2;
            if (affected <= 0 && pulses >= 2) {
                break;
            }
        }
        int endCount = callOnClientThread(() -> dropTargetInventoryCount(item, group));
        data.addProperty("ticks", ticks);
        data.addProperty("pulses", pulses);
        data.addProperty("affected_total", affectedTotal);
        data.addProperty("inventory_before", startCount);
        data.addProperty("inventory_after", endCount);
        data.addProperty("inventory_delta", endCount - startCount);
        data.addProperty("requested_count", requestedCount);
        data.addProperty("satisfied", endCount - startCount >= requestedCount);
        return data;
    }

    private int applyItemMagnetPulse(String item, String group) {
        if (client.player == null || client.world == null) {
            return 0;
        }
        int radius = config.itemMagnetRadius();
        Vec3d target = client.player.getPos().add(0.0D, 0.65D, 0.0D);
        Box box = new Box(client.player.getBlockPos()).expand(radius);
        List<ItemMagnetTarget> targets = new ArrayList<>();
        for (Entity entity : client.world.getOtherEntities(client.player, box)) {
            if (!(entity instanceof ItemEntity itemEntity) || itemEntity.isRemoved() || itemEntity.getStack().isEmpty()) {
                continue;
            }
            String itemId = itemId(itemEntity.getStack());
            if (!matchesDropTarget(item, group, itemId)) {
                continue;
            }
            Vec3d pull = target.subtract(itemEntity.getPos());
            if (pull.lengthSquared() <= radius * radius) {
                itemEntity.setPosition(target.x, target.y, target.z);
                itemEntity.setVelocity(Vec3d.ZERO);
            } else if (pull.lengthSquared() >= 0.05D) {
                Vec3d velocity = itemEntity.getVelocity().multiply(0.2D).add(pull.normalize().multiply(config.itemMagnetStrength()));
                itemEntity.setVelocity(velocity);
            }
            targets.add(new ItemMagnetTarget(itemEntity.getUuid()));
        }
        applyIntegratedServerItemMagnet(targets, target);
        return targets.size();
    }

    private void applyIntegratedServerItemMagnet(List<ItemMagnetTarget> targets, Vec3d target) {
        if (targets.isEmpty() || client.world == null) {
            return;
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        var dimensionKey = client.world.getRegistryKey();
        double strength = config.itemMagnetStrength();
        server.execute(() -> {
            ServerWorld serverWorld = server.getWorld(dimensionKey);
            if (serverWorld == null) {
                return;
            }
            for (ItemMagnetTarget magnetTarget : targets) {
                Entity entity = serverWorld.getEntity(magnetTarget.uuid());
                if (!(entity instanceof ItemEntity itemEntity) || itemEntity.isRemoved()) {
                    continue;
                }
                Vec3d pull = target.subtract(itemEntity.getPos());
                if (pull.lengthSquared() <= config.itemMagnetRadius() * config.itemMagnetRadius()) {
                    itemEntity.setPosition(target.x, target.y, target.z);
                    itemEntity.setVelocity(Vec3d.ZERO);
                } else if (pull.lengthSquared() >= 0.05D) {
                    itemEntity.setVelocity(itemEntity.getVelocity().multiply(0.2D).add(pull.normalize().multiply(strength)));
                }
            }
        });
    }

    private boolean matchesDropTarget(String exactItem, String group, String itemId) {
        return (!exactItem.isBlank() && exactItem.equals(itemId))
            || group.equals("any_drop")
            || (group.equals("food_drop") && isFoodDrop(itemId))
            || (!group.isBlank() && MinecraftItemGroups.matches(group, itemId));
    }

    private JsonObject pickupSweepForDrops(String item, String group, int startCount, int requestedCount, int timeoutTicks, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("enabled", true);
        data.addProperty("command_free", true);
        data.addProperty("mode", "short_safe_sweep");
        int ticks = 0;
        int safeMoves = 0;
        int skippedUnsafe = 0;
        String[] directions = {"forward", "right", "back", "left"};
        while (ticks < timeoutTicks && !aborted) {
            int current = callOnClientThread(() -> dropTargetInventoryCount(item, group));
            if (current - startCount >= requestedCount) {
                break;
            }
            String direction = directions[(ticks / 6) % directions.length];
            boolean safe = Boolean.TRUE.equals(callOnClientThread(() -> isMoveStepSafe(direction)));
            if (!safe) {
                skippedUnsafe++;
                callOnClientThread(() -> {
                    releaseMovementKeys();
                    return null;
                });
                sleepTicks(2);
                ticks += 2;
                continue;
            }
            safeMoves++;
            callOnClientThread(() -> pressMoveKeys(direction, false));
            sleepTicks(3);
            ticks += 3;
            callOnClientThread(() -> {
                releaseMovementKeys();
                return null;
            });
            sleepTicks(1);
            ticks++;
        }
        callOnClientThread(() -> {
            releaseMovementKeys();
            return null;
        });
        int endCount = callOnClientThread(() -> dropTargetInventoryCount(item, group));
        data.addProperty("ticks", ticks);
        data.addProperty("safe_moves", safeMoves);
        data.addProperty("skipped_unsafe_moves", skippedUnsafe);
        data.addProperty("inventory_before", startCount);
        data.addProperty("inventory_after", endCount);
        data.addProperty("inventory_delta", endCount - startCount);
        data.addProperty("requested_count", requestedCount);
        data.addProperty("satisfied", endCount - startCount >= requestedCount);
        data.addProperty("action_type", actionType);
        return data;
    }

    private int dropTargetInventoryCount(String item, String group) {
        if (!item.isBlank()) {
            return countInventoryItem(item);
        }
        if (group.equals("any_drop")) {
            return totalInventoryItems();
        }
        if (group.equals("food_drop")) {
            int total = 0;
            for (String food : rawFoodPriority()) {
                total += countInventoryItem(food);
            }
            return total;
        }
        return countInventoryGroup(group);
    }

    private ExecutorProtocol.StepResult takeFromContainer(ExecutorProtocol.Action action) {
        if (!Boolean.TRUE.equals(callOnClientThread(() -> client.player != null && client.world != null && client.interactionManager != null))) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }
        String item = action.stringField("item");
        String group = action.stringField("item_group");
        boolean optional = action.booleanField("optional", true);
        if (item.isBlank() && group.isBlank()) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "take_from_container requires item or item_group.");
        }
        int count = Math.max(1, Math.min(action.intField("count", 1), 64));
        int radius = Math.max(1, Math.min(action.intField("search_radius", 6), Math.min(config.maxScanRadius(), 12)));
        String supplyKey = containerSupplyKey(item, group);
        pruneContainerSupplyCache();
        JsonObject data = new JsonObject();
        data.addProperty("item", item);
        data.addProperty("item_group", group);
        data.addProperty("requested_count", count);
        data.addProperty("optional", optional);

        int before = callOnClientThread(() -> item.isBlank() ? countInventoryGroup(group) : countInventoryItem(item));
        List<BlockPos> candidates = callOnClientThread(() -> findNearestContainers(radius, supplyKey));
        data.addProperty("containers_found", candidates.size());
        if (candidates.isEmpty()) {
            return optional
                ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply skipped because no unchecked nearby chest or barrel was found.", data)
                : new ExecutorProtocol.StepResult(action.type(), "blocked", "No unchecked nearby chest or barrel was found.", data);
        }

        DirectContainerTakeResult directTake = tryDirectTakeFromContainers(candidates, item, group, count);
        data.add("direct_container_scan", directTake.data());
        if (directTake.hit() != null) {
            rememberContainerHit(supplyKey, directTake.hit());
        }
        int afterDirect = before + directTake.movedCount();
        if (directTake.movedCount() > 0) {
            data.addProperty("inventory_before", before);
            data.addProperty("inventory_after", afterDirect);
            data.addProperty("inventory_delta", directTake.movedCount());
            data.addProperty("moved_stacks", intData(directTake.data(), "moved_stacks", 0));
            if (directTake.movedCount() < count) {
                return optional
                    ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply directly moved fewer items than requested.", data)
                    : new ExecutorProtocol.StepResult(action.type(), "partial", "Nearby container directly supplied fewer items than requested.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Took requested materials from a nearby container by direct inventory transfer.", data);
        }
        if (directTake.scannedCount() > 0 && directTake.unknownCount() == 0) {
            if (directTake.matchingCount() > 0) {
                return optional
                    ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply found matching items but inventory could not accept them.", data)
                    : new ExecutorProtocol.StepResult(action.type(), "blocked", "Matching items were found in a nearby container but inventory could not accept them.", data);
            }
            candidates.forEach(pos -> rememberContainerMiss(supplyKey, pos));
            return optional
                ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply skipped because scanned nearby containers had no matching items.", data)
                : new ExecutorProtocol.StepResult(action.type(), "blocked", "Scanned nearby containers had no matching items.", data);
        }
        BlockPos container = candidates.get(0);
        addBlockPos(data, "container", container);
        data.addProperty("cache_preferred", isPositiveCachedContainer(supplyKey, container));
        ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(container, config.maxReach(), 100, action.type());
        if (!"accepted".equals(approached.status())) {
            if (approached.data() != null) data.add("approach", approached.data());
            rememberContainerMiss(supplyKey, container);
            return optional
                ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply skipped because the container was not reachable.", data)
                : approached;
        }
        ExecutorProtocol.StepResult opened = openContainerAt(container, action.type());
        if (!"accepted".equals(opened.status())) {
            if (opened.data() != null) data.add("open", opened.data());
            rememberContainerMiss(supplyKey, container);
            return optional
                ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply skipped because the container screen did not open.", data)
                : opened;
        }

        int movedStacks = quickMoveMatchingContainerStacks(item, group, count, before);
        sleepTicks(2);
        int after = callOnClientThread(() -> item.isBlank() ? countInventoryGroup(group) : countInventoryItem(item));
        callOnClientThread(() -> {
            if (client.player != null) {
                client.player.closeHandledScreen();
            }
            return null;
        });
        data.addProperty("inventory_before", before);
        data.addProperty("inventory_after", after);
        data.addProperty("inventory_delta", after - before);
        data.addProperty("moved_stacks", movedStacks);
        if (after <= before) {
            rememberContainerMiss(supplyKey, container);
            return optional
                ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply checked nearby container but found no matching items.", data)
                : new ExecutorProtocol.StepResult(action.type(), "blocked", "No matching item was found in the nearby container.", data);
        }
        rememberContainerHit(supplyKey, container);
        if (after - before < count) {
            return optional
                ? new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional container supply moved fewer items than requested.", data)
                : new ExecutorProtocol.StepResult(action.type(), "partial", "Nearby container supplied fewer items than requested.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Took requested materials from a nearby container.", data);
    }

    private ExecutorProtocol.StepResult grantItem(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            String item = action.stringField("item");
            int count = Math.max(1, Math.min(action.intField("count", 1), 256));
            int minCount = Math.max(0, Math.min(action.intField("min_count", 0), 256));
            boolean optional = action.booleanField("optional", false);
            JsonObject data = new JsonObject();
            data.addProperty("item", item);
            data.addProperty("count", count);
            data.addProperty("min_count", minCount);
            data.addProperty("voice_assist", true);
            data.addProperty("world_assist", true);
            data.addProperty("optional", optional);

            if (!config.worldAssistCommonMaterialTopUpEnabled()) {
                data.addProperty("enabled", false);
                return new ExecutorProtocol.StepResult(
                    action.type(),
                    optional ? "accepted" : "blocked",
                    "World assist item grant is disabled.",
                    data
                );
            }
            if (!isVoiceAssistGrantAllowedItem(item)) {
                data.addProperty("enabled", false);
                data.addProperty("reason", "invalid_item_for_voice_assist_grant");
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "Voice assist item grant requires a valid minecraft item.", data);
            }

            int before = countInventoryItem(item);
            int requested = minCount > 0 ? Math.max(0, minCount - before) : count;
            requested = Math.min(requested, Math.max(count, config.voiceAssistSupportTopUpCount()));
            data.addProperty("inventory_before", before);
            data.addProperty("requested_grant_count", requested);
            if (requested <= 0) {
                data.addProperty("inventory_after", before);
                data.addProperty("inserted_count", 0);
                data.addProperty("already_satisfied", true);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Voice assist item grant was already satisfied.", data);
            }

            VoiceAssistGrantResult grant = grantVoiceAssistItemToInventory(item, requested);
            int after = countInventoryItem(item);
            data.addProperty("grant_mode", grant.mode());
            data.addProperty("grant_reason", grant.reason());
            data.addProperty("inserted_count", grant.insertedCount());
            data.addProperty("inventory_after", after);
            if (grant.insertedCount() <= 0) {
                return new ExecutorProtocol.StepResult(
                    action.type(),
                    optional ? "accepted" : "blocked",
                    optional ? "Optional world assist item grant was skipped." : "World assist could not grant the requested item.",
                    data
                );
            }
            publishStatus("[World Assist] 素材補充: " + shortMinecraftId(item) + " x" + grant.insertedCount());
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "World assist granted the requested item.", data);
        });
    }

    private DirectContainerTakeResult tryDirectTakeFromContainers(List<BlockPos> candidates, String item, String group, int requestedCount) {
        DirectContainerContext context = callOnClientThread(() -> {
            if (client.player == null || client.world == null) {
                return null;
            }
            MinecraftServer server = client.getServer();
            if (server == null) {
                return null;
            }
            return new DirectContainerContext(server, client.world.getRegistryKey(), client.player.getUuid());
        });
        JsonObject fallback = new JsonObject();
        fallback.addProperty("enabled", false);
        fallback.addProperty("reason", "integrated_server_not_available");
        if (context == null) {
            return new DirectContainerTakeResult(fallback, null, 0, candidates.size(), 0, 0);
        }
        CompletableFuture<DirectContainerTakeResult> future = new CompletableFuture<>();
        context.server().execute(() -> {
            try {
                ServerWorld serverWorld = context.server().getWorld(context.dimensionKey());
                ServerPlayerEntity serverPlayer = context.server().getPlayerManager().getPlayer(context.playerUuid());
                future.complete(directTakeFromContainersOnServer(serverWorld, serverPlayer, candidates, item, group, requestedCount));
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get(800, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            JsonObject data = new JsonObject();
            data.addProperty("enabled", false);
            data.addProperty("reason", "server_direct_container_take_failed");
            data.addProperty("error", error.toString());
            return new DirectContainerTakeResult(data, null, 0, candidates.size(), 0, 0);
        }
    }

    private DirectContainerTakeResult directTakeFromContainersOnServer(
        ServerWorld serverWorld,
        ServerPlayerEntity serverPlayer,
        List<BlockPos> candidates,
        String item,
        String group,
        int requestedCount
    ) {
        JsonObject data = new JsonObject();
        JsonArray scanned = new JsonArray();
        JsonArray moved = new JsonArray();
        data.addProperty("enabled", true);
        data.addProperty("mode", "integrated_server_inventory_transfer");
        data.addProperty("requested_count", requestedCount);
        if (serverWorld == null || serverPlayer == null) {
            data.addProperty("error", "server_world_or_player_not_ready");
            data.add("scanned_containers", scanned);
            data.add("moved_items", moved);
            return new DirectContainerTakeResult(data, null, 0, candidates.size(), 0, 0);
        }
        int scannedCount = 0;
        int unknownCount = 0;
        int matchingCount = 0;
        int movedCount = 0;
        int movedStacks = 0;
        BlockPos hit = null;
        for (BlockPos pos : candidates) {
            JsonObject one = new JsonObject();
            addBlockPos(one, "pos", pos);
            var blockEntity = serverWorld.getBlockEntity(pos);
            if (!(blockEntity instanceof Inventory inventory)) {
                unknownCount++;
                one.addProperty("readable", false);
                scanned.add(one);
                continue;
            }
            scannedCount++;
            int matching = countMatchingInventory(inventory, item, group);
            matchingCount += matching;
            one.addProperty("readable", true);
            one.addProperty("matching_count", matching);
            scanned.add(one);
            if (matching <= 0 || movedCount >= requestedCount) {
                continue;
            }
            if (hit == null) {
                hit = pos.toImmutable();
            }
            int movedFromThis = moveMatchingInventoryToPlayer(inventory, serverPlayer, item, group, requestedCount - movedCount, moved, pos);
            if (movedFromThis > 0) {
                movedStacks++;
                movedCount += movedFromThis;
                inventory.markDirty();
                serverPlayer.getInventory().markDirty();
            }
            if (movedCount >= requestedCount) {
                break;
            }
        }
        data.addProperty("scanned_count", scannedCount);
        data.addProperty("unknown_count", unknownCount);
        data.addProperty("matching_count", matchingCount);
        data.addProperty("moved_count", movedCount);
        data.addProperty("moved_stacks", movedStacks);
        data.add("scanned_containers", scanned);
        data.add("moved_items", moved);
        return new DirectContainerTakeResult(data, hit, scannedCount, unknownCount, matchingCount, movedCount);
    }

    private int countMatchingInventory(Inventory inventory, String item, String group) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (matchesDropTarget(item, group, itemId(stack))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int moveMatchingInventoryToPlayer(
        Inventory source,
        ServerPlayerEntity player,
        String item,
        String group,
        int requestedCount,
        JsonArray moved,
        BlockPos sourcePos
    ) {
        int movedCount = 0;
        for (int slot = 0; slot < source.size() && movedCount < requestedCount; slot++) {
            ItemStack stack = source.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = itemId(stack);
            if (!matchesDropTarget(item, group, itemId)) {
                continue;
            }
            int moveCount = Math.min(requestedCount - movedCount, stack.getCount());
            ItemStack transfer = stack.copy();
            transfer.setCount(moveCount);
            player.getInventory().insertStack(transfer);
            int insertedCount = moveCount - transfer.getCount();
            if (insertedCount <= 0) {
                continue;
            }
            stack.decrement(insertedCount);
            movedCount += insertedCount;
            JsonObject one = new JsonObject();
            one.addProperty("item", itemId);
            one.addProperty("slot", slot);
            one.addProperty("count", insertedCount);
            addBlockPos(one, "container", sourcePos);
            moved.add(one);
        }
        return movedCount;
    }

    private boolean isVoiceAssistGrantAllowedItem(String itemId) {
        return itemId != null
            && itemId.startsWith("minecraft:")
            && itemById(itemId) != null
            && !isForbiddenVoiceAssistGrantItem(itemId)
            && (config.worldAssistRareItemsEnabled() || !isRareVoiceAssistGrantItem(itemId));
    }

    private boolean isRareVoiceAssistGrantItem(String itemId) {
        if (itemId == null || !itemId.startsWith("minecraft:")) {
            return true;
        }
        String name = itemId.substring("minecraft:".length());
        return name.endsWith("_ore")
            || name.contains("diamond")
            || name.contains("emerald")
            || name.contains("netherite")
            || name.contains("debris")
            || name.contains("obsidian")
            || name.contains("elytra")
            || name.contains("dragon")
            || name.contains("wither")
            || name.contains("shulker")
            || name.contains("blaze")
            || name.contains("ender")
            || name.contains("ghast")
            || name.contains("totem")
            || name.contains("skull")
            || name.endsWith("_head")
            || name.endsWith("_armor_trim_smithing_template")
            || name.equals("netherite_upgrade_smithing_template")
            || name.equals("nether_star")
            || name.equals("heart_of_the_sea")
            || name.equals("heavy_core")
            || name.equals("echo_shard")
            || name.equals("disc_fragment_5")
            || name.startsWith("music_disc")
            || name.equals("trial_key")
            || name.equals("ominous_trial_key")
            || name.equals("breeze_rod")
            || name.equals("enchanted_golden_apple");
    }

    private boolean isForbiddenVoiceAssistGrantItem(String itemId) {
        if (itemId == null || !itemId.startsWith("minecraft:")) {
            return true;
        }
        String name = itemId.substring("minecraft:".length());
        return name.equals("bedrock")
            || name.equals("barrier")
            || name.equals("command_block")
            || name.equals("chain_command_block")
            || name.equals("repeating_command_block")
            || name.equals("structure_block")
            || name.equals("structure_void")
            || name.equals("jigsaw")
            || name.equals("debug_stick")
            || name.equals("knowledge_book")
            || name.equals("spawner")
            || name.equals("reinforced_deepslate");
    }

    private VoiceAssistGrantResult grantVoiceAssistItemToInventory(String itemId, int count) {
        if (client.player == null || client.world == null) {
            return new VoiceAssistGrantResult(0, "unavailable", "player_or_world_not_ready");
        }
        Item item = itemById(itemId);
        if (item == null) {
            return new VoiceAssistGrantResult(0, "unavailable", "invalid_item");
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            return new VoiceAssistGrantResult(0, "unavailable", "integrated_server_not_available");
        }
        UUID playerUuid = client.player.getUuid();
        CompletableFuture<VoiceAssistGrantResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);
                future.complete(grantVoiceAssistItemOnServer(serverPlayer, item, count));
            } catch (RuntimeException error) {
                future.complete(new VoiceAssistGrantResult(0, "integrated_server_grant", error.toString()));
            }
        });
        try {
            return future.get(800, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            return new VoiceAssistGrantResult(0, "integrated_server_grant", error.toString());
        }
    }

    private VoiceAssistGrantResult grantVoiceAssistItemOnServer(ServerPlayerEntity player, Item item, int count) {
        if (player == null) {
            return new VoiceAssistGrantResult(0, "integrated_server_grant", "server_player_not_ready");
        }
        int remaining = Math.max(0, count);
        int inserted = 0;
        int maxStack = Math.max(1, item.getMaxCount());
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            ItemStack stack = new ItemStack(item, batch);
            player.getInventory().insertStack(stack);
            int insertedThisBatch = batch - stack.getCount();
            if (insertedThisBatch <= 0) {
                break;
            }
            inserted += insertedThisBatch;
            remaining -= insertedThisBatch;
        }
        if (inserted > 0) {
            player.getInventory().markDirty();
            player.playerScreenHandler.sendContentUpdates();
            player.currentScreenHandler.sendContentUpdates();
        }
        return new VoiceAssistGrantResult(inserted, "integrated_server_grant", inserted > 0 ? "ok" : "inventory_full");
    }

    private Item itemById(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            Item item = Registries.ITEM.get(Identifier.of(itemId));
            return item == Items.AIR ? null : item;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ExecutorProtocol.StepResult dropInventory(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or interaction manager is not ready.");
            }
            String group = action.stringField("item_group");
            if (!group.equals("low_value")) {
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported drop item group: " + group);
            }
            int requestedCount = Math.max(1, Math.min(action.intField("count", 1), 16));
            ScreenHandler handler = client.player.currentScreenHandler;
            if (!handler.getCursorStack().isEmpty()) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Cursor stack is not empty; refusing to drop inventory items.");
            }

            JsonObject data = new JsonObject();
            JsonArray dropped = new JsonArray();
            int droppedCount = 0;
            for (int i = 0; i < requestedCount; i++) {
                int slot = findHandlerSlotForLowValueDrop(handler);
                if (slot < 0) {
                    break;
                }
                ItemStack stack = handler.getSlot(slot).getStack();
                String itemId = itemId(stack);
                client.interactionManager.clickSlot(handler.syncId, slot, 1, SlotActionType.THROW, client.player);
                JsonObject one = new JsonObject();
                one.addProperty("item", itemId);
                one.addProperty("slot", slot);
                dropped.add(one);
                droppedCount++;
                sleepTicks(1);
            }

            data.addProperty("requested_count", requestedCount);
            data.addProperty("dropped_count", droppedCount);
            data.add("dropped", dropped);
            if (droppedCount == 0) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No low-value inventory item was available to drop.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), droppedCount < requestedCount ? "partial" : "accepted", "Dropped low-value inventory items to free space.", data);
        });
    }

    private ExecutorProtocol.StepResult replantCrop(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }

            int radius = Math.max(1, Math.min(action.intField("search_radius", 8), config.maxScanRadius()));
            int requestedCount = Math.max(1, Math.min(action.intField("count", 1), 16));
            String seedItem = action.stringField("seed_item");
            if (seedItem.isBlank()) {
                seedItem = findAvailableSeedItem();
            }
            JsonObject data = new JsonObject();
            data.addProperty("seed_item", seedItem);
            data.addProperty("requested_count", requestedCount);
            if (seedItem.isBlank() || !isKnownSeedItem(seedItem)) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No supported seed item is available for replanting.", data);
            }

            ExecutorProtocol.StepResult ensured = ensureHotbarItem(seedItem, 1, action.type());
            if (!"accepted".equals(ensured.status())) {
                if (ensured.data() != null) {
                    data.add("ensure_hotbar", ensured.data());
                }
                return new ExecutorProtocol.StepResult(action.type(), ensured.status(), "Seed item could not be moved to the hotbar.", data);
            }

            JsonArray planted = new JsonArray();
            int plantedCount = 0;
            for (int i = 0; i < requestedCount && !aborted; i++) {
                BlockHitResult target = findNearestEmptyFarmland(radius);
                if (target == null) {
                    break;
                }
                lookAtPosition(target.getPos());
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, target);
                client.player.swingHand(Hand.MAIN_HAND);
                sleepTicks(3);

                BlockPos cropPos = target.getBlockPos().up();
                if (client.world.getBlockState(cropPos).isAir()) {
                    break;
                }
                JsonObject item = new JsonObject();
                item.addProperty("x", cropPos.getX());
                item.addProperty("y", cropPos.getY());
                item.addProperty("z", cropPos.getZ());
                item.addProperty("block", blockIdAt(cropPos));
                planted.add(item);
                plantedCount++;
                if (countInventoryItem(seedItem) <= 0) {
                    break;
                }
            }

            data.addProperty("planted_count", plantedCount);
            data.add("planted", planted);
            if (aborted) {
                return new ExecutorProtocol.StepResult(action.type(), "aborted", "Replanting was interrupted.", data);
            }
            if (plantedCount == 0) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No reachable empty farmland was found for replanting.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), plantedCount < requestedCount ? "partial" : "accepted", "Replanted crop seeds on reachable farmland.", data);
        });
    }

    private ExecutorProtocol.StepResult attackEntity(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }

        String group = action.stringField("entity_group");
        if (!isSupportedEntityGroup(group)) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported entity group: " + group);
        }
        int radius = Math.max(1, Math.min(action.intField("search_radius", 8), config.maxScanRadius()));
        int requestedCount = Math.max(1, Math.min(action.intField("count", 1), 8));
        double maxDistance = Math.max(1.0D, Math.min(action.doubleField("max_distance", config.maxReach()), HARD_MAX_BREAK_DISTANCE));
        int timeoutTicks = Math.max(20, Math.min(action.intField("timeout_ticks", 120), 400));
        boolean optional = action.booleanField("optional", false);
        JsonObject data = new JsonObject();
        data.addProperty("entity_group", group);
        data.addProperty("requested_count", requestedCount);
        data.addProperty("optional", optional);
        JsonArray attacked = new JsonArray();
        int defeated = 0;

        for (int i = 0; i < requestedCount && !aborted; i++) {
            Entity target = callOnClientThread(() -> findNearestMatchingEntity(group, radius, maxDistance));
            if (target == null) {
                break;
            }
            ExecutorProtocol.StepResult result = attackEntityUntilDone(target, group, maxDistance, timeoutTicks, action.type());
            if (result.data() != null) {
                attacked.add(result.data());
            }
            if (!"accepted".equals(result.status())) {
                data.addProperty("defeated_count", defeated);
                data.add("attacked_entities", attacked);
                data.addProperty("last_status", result.status());
                data.addProperty("last_message", result.message());
                if (optional && defeated == 0) {
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional entity attack skipped before any target was defeated.", data);
                }
                return new ExecutorProtocol.StepResult(action.type(), defeated > 0 ? "partial" : result.status(), "Entity attack stopped before all requested targets were defeated.", data);
            }
            defeated++;
            sleepTicks(4);
        }

        releaseKeys();
        data.addProperty("defeated_count", defeated);
        data.add("attacked_entities", attacked);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Entity attack was interrupted.", data);
        }
        if (defeated == 0) {
            if (optional) {
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional entity attack skipped because no reachable matching entity was found.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No reachable matching entity found within scan radius.", data);
        }
        if (optional && defeated < requestedCount) {
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional entity attack defeated fewer targets than requested.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), defeated < requestedCount ? "partial" : "accepted", "Attacked matching entities with survival melee interaction.", data);
    }

    private ExecutorProtocol.StepResult collectMilk(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }
        int radius = Math.max(1, Math.min(action.intField("search_radius", 12), config.maxScanRadius()));
        int timeoutTicks = Math.max(40, Math.min(action.intField("timeout_ticks", 220), 600));
        JsonObject data = new JsonObject();
        data.addProperty("search_radius", radius);
        data.addProperty("timeout_ticks", timeoutTicks);
        int beforeMilk = countInventoryItem("minecraft:milk_bucket");
        int beforeBucket = countInventoryItem("minecraft:bucket");
        data.addProperty("milk_bucket_before", beforeMilk);
        data.addProperty("bucket_before", beforeBucket);
        if (beforeBucket <= 0) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "An empty bucket is required to collect milk.", data);
        }
        if (!selectHotbarItem("minecraft:bucket")) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not move an empty bucket to the hotbar.", data);
        }

        int ticks = 0;
        while (ticks < timeoutTicks && !aborted) {
            Entity target = findNearestMatchingEntity("cow", radius, radius);
            if (target == null) {
                data.addProperty("ticks", ticks);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No cow or mooshroom was found within scan radius.", data);
            }
            double distance = client.player.getEyePos().distanceTo(target.getPos());
            if (distance > config.maxReach()) {
                DropMoveStatus status = steerToward(target.getPos(), distance);
                if (status == DropMoveStatus.BLOCKED) {
                    releaseKeys();
                    data.addProperty("ticks", ticks);
                    data.addProperty("target_distance", distance);
                    return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not safely approach the cow or mooshroom.", data);
                }
                sleepTicks(2);
                ticks += 2;
                continue;
            }
            lookAtPosition(target.getEyePos());
            client.interactionManager.interactEntity(client.player, target, Hand.MAIN_HAND);
            client.player.swingHand(Hand.MAIN_HAND);
            sleepTicks(4);
            int afterMilk = countInventoryItem("minecraft:milk_bucket");
            int afterBucket = countInventoryItem("minecraft:bucket");
            data.addProperty("ticks", ticks);
            data.addProperty("milk_bucket_after", afterMilk);
            data.addProperty("bucket_after", afterBucket);
            data.addProperty("target_entity", Registries.ENTITY_TYPE.getId(target.getType()).toString());
            if (afterMilk > beforeMilk || afterBucket < beforeBucket) {
                releaseKeys();
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Collected milk into a bucket from a cow or mooshroom.", data);
            }
            ticks += 4;
        }
        releaseKeys();
        data.addProperty("ticks", ticks);
        return new ExecutorProtocol.StepResult(action.type(), aborted ? "aborted" : "blocked", aborted ? "Milk collection was interrupted." : "Timed out before collecting milk.", data);
    }

    private ExecutorProtocol.StepResult defensiveMove(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
        }
        int durationTicks = Math.max(5, Math.min(action.intField("duration_ticks", 30), 100));
        double minDistance = Math.max(2.0D, Math.min(action.doubleField("min_distance", 5.0D), 10.0D));
        JsonObject data = new JsonObject();
        data.addProperty("strategy", action.stringField("strategy"));
        data.addProperty("duration_ticks", durationTicks);
        data.addProperty("min_distance", minDistance);

        ExecutorProtocol.StepResult shield = callOnClientThread(() -> ensureHotbarItem("minecraft:shield", 1, action.type()));
        boolean hasShield = "accepted".equals(shield.status());
        data.addProperty("shield_available", hasShield);

        int ticks = 0;
        while (ticks < durationTicks && !aborted) {
            Boolean safeEnough = callOnClientThread(() -> {
                if (client.player == null || client.world == null) {
                    return true;
                }
                Entity closest = findNearestMatchingEntity("hostile", 8, 8.0D);
                if (closest == null) {
                    return true;
                }
                lookAtPosition(closest.getEyePos());
                double distance = client.player.getPos().distanceTo(closest.getPos());
                if (hasShield) {
                    client.options.useKey.setPressed(true);
                } else {
                    Vec3d retreatTarget = safeRetreatTargetAwayFrom(closest.getPos(), minDistance);
                    if (retreatTarget == null) {
                        releaseMovementKeys();
                        return false;
                    }
                    steerToward(retreatTarget, client.player.getPos().distanceTo(retreatTarget));
                    client.options.sprintKey.setPressed(true);
                }
                return distance >= minDistance;
            });
            if (safeEnough) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Created enough distance or no hostile target remains nearby.", data);
            }
            sleepTicks(1);
            ticks++;
        }

        releaseKeys();
        data.addProperty("ticks", ticks);
        return new ExecutorProtocol.StepResult(action.type(), aborted ? "aborted" : "partial", aborted ? "Defensive move was interrupted." : "Defensive move completed for the bounded duration.", data);
    }

    private ExecutorProtocol.StepResult openPassage(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }
            int radius = Math.max(1, Math.min(action.intField("search_radius", 4), 8));
            BlockPos target = findNearestClosedPassage(radius);
            JsonObject data = new JsonObject();
            if (target == null) {
                data.addProperty("search_radius", radius);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No nearby closed door or fence gate was found.", data);
            }
            ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(target, config.maxReach(), Math.max(10, Math.min(action.intField("timeout_ticks", 80), 160)), action.type());
            if (!"accepted".equals(approached.status())) {
                return approached;
            }
            boolean blockedBefore = hasBlockingCollision(target);
            interactWithBlock(target);
            sleepTicks(4);
            boolean blockedAfter = hasBlockingCollision(target);
            addBlockPos(data, "target", target);
            data.addProperty("blocking_before", blockedBefore);
            data.addProperty("blocking_after", blockedAfter);
            if (blockedBefore && !blockedAfter) {
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Opened a nearby door or fence gate blocking local movement.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "partial", "Interacted with a nearby passage block, but open state could not be fully confirmed.", data);
        });
    }

    private ExecutorProtocol.StepResult celebrate(ExecutorProtocol.Action action) {
        String style = action.stringField("style");
        if (!style.equals("youtuber_pose") && !style.equals("dance") && !style.equals("cheer")) {
            style = "cheer";
        }
        int durationTicks = Math.max(20, Math.min(action.intField("duration_ticks", 70), 120));
        boolean thirdPerson = action.booleanField("third_person", true);
        JsonObject data = new JsonObject();
        data.addProperty("style", style);
        data.addProperty("duration_ticks", durationTicks);
        data.addProperty("third_person", thirdPerson);

        callOnClientThread(() -> {
            if (client.player == null) {
                return null;
            }
            if (thirdPerson) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            if (client.currentScreen != null) {
                client.setScreen(null);
            }
            releaseKeys();
            return null;
        });

        for (int tick = 0; tick < durationTicks && !aborted; tick++) {
            final int currentTick = tick;
            final String currentStyle = style;
            callOnClientThread(() -> {
                if (client.player == null) {
                    return null;
                }
                releaseMovementKeys();
                client.options.sneakKey.setPressed(false);
                float baseYaw = client.player.getYaw();

                if (currentStyle.equals("dance")) {
                    int phase = (currentTick / 8) % 4;
                    client.options.leftKey.setPressed(phase == 0);
                    client.options.rightKey.setPressed(phase == 2);
                    client.options.sneakKey.setPressed(phase == 1);
                    client.options.jumpKey.setPressed(currentTick % 18 < 3);
                    client.player.setYaw(baseYaw + (phase == 0 ? -12.0F : phase == 2 ? 12.0F : 0.0F));
                    client.player.setPitch(-6.0F);
                    if (currentTick % 12 == 0) {
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                } else if (currentStyle.equals("youtuber_pose")) {
                    client.options.sneakKey.setPressed(currentTick % 20 < 6);
                    client.options.jumpKey.setPressed(currentTick == 8 || currentTick == 28);
                    client.player.setYaw(baseYaw + (float) Math.sin(currentTick / 5.0D) * 10.0F);
                    client.player.setPitch(-10.0F + (float) Math.sin(currentTick / 8.0D) * 4.0F);
                    if (currentTick % 10 == 0) {
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                } else {
                    client.options.jumpKey.setPressed(currentTick % 16 < 4);
                    client.options.sneakKey.setPressed(currentTick % 24 >= 12 && currentTick % 24 < 16);
                    client.player.setYaw(baseYaw + (float) Math.sin(currentTick / 6.0D) * 8.0F);
                    client.player.setPitch(-5.0F);
                    if (currentTick % 14 == 0) {
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                return null;
            });
            sleepTicks(1);
        }
        releaseKeys();

        return new ExecutorProtocol.StepResult(
            action.type(),
            aborted ? "aborted" : "accepted",
            aborted ? "Celebration was interrupted." : "Played a short third-person celebration reaction.",
            data
        );
    }

    private ExecutorProtocol.StepResult ambientChat(ExecutorProtocol.Action action) {
        String message = sanitizeBubbleText(action.stringField("message"));
        if (message.isBlank()) {
            message = "うん、この世界で様子を見てるよ。";
        }
        String style = action.stringField("style");
        if (!style.equals("dance") && !style.equals("cheer") && !style.equals("shrug") && !style.equals("nod")) {
            style = "nod";
        }
        int durationTicks = Math.max(30, Math.min(action.intField("duration_ticks", 80), 140));
        boolean thirdPerson = action.booleanField("third_person", true);
        JsonObject data = new JsonObject();
        data.addProperty("message", message);
        data.addProperty("style", style);
        data.addProperty("duration_ticks", durationTicks);
        data.addProperty("third_person", thirdPerson);
        publishStatus("[KoeCraft Bubble] " + message);

        callOnClientThread(() -> {
            if (client.player == null) {
                return null;
            }
            if (thirdPerson) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            if (client.currentScreen != null) {
                client.setScreen(null);
            }
            releaseKeys();
            return null;
        });

        for (int tick = 0; tick < durationTicks && !aborted; tick++) {
            final int currentTick = tick;
            final String currentStyle = style;
            callOnClientThread(() -> {
                if (client.player == null) {
                    return null;
                }
                releaseMovementKeys();
                client.options.sneakKey.setPressed(false);
                float baseYaw = client.player.getYaw();
                if (currentStyle.equals("dance")) {
                    int phase = (currentTick / 10) % 4;
                    client.options.leftKey.setPressed(phase == 0);
                    client.options.rightKey.setPressed(phase == 2);
                    client.options.jumpKey.setPressed(currentTick % 24 < 3);
                    client.player.setYaw(baseYaw + (phase == 0 ? -8.0F : phase == 2 ? 8.0F : 0.0F));
                    client.player.setPitch(-6.0F);
                } else if (currentStyle.equals("cheer")) {
                    client.options.jumpKey.setPressed(currentTick % 20 < 3);
                    client.player.setYaw(baseYaw + (float) Math.sin(currentTick / 7.0D) * 7.0F);
                    client.player.setPitch(-8.0F);
                    if (currentTick % 18 == 0) {
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                } else if (currentStyle.equals("shrug")) {
                    client.options.sneakKey.setPressed(currentTick % 28 >= 8 && currentTick % 28 < 14);
                    client.player.setYaw(baseYaw + (float) Math.sin(currentTick / 8.0D) * 5.0F);
                    client.player.setPitch(-2.0F + (float) Math.sin(currentTick / 6.0D) * 5.0F);
                    if (currentTick % 22 == 0) {
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                } else {
                    client.player.setYaw(baseYaw + (float) Math.sin(currentTick / 9.0D) * 4.0F);
                    client.player.setPitch(-4.0F + (float) Math.sin(currentTick / 5.0D) * 4.0F);
                    if (currentTick % 24 == 0) {
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                return null;
            });
            sleepTicks(1);
        }
        releaseKeys();
        return new ExecutorProtocol.StepResult(
            action.type(),
            aborted ? "aborted" : "accepted",
            aborted ? "Ambient chat reaction was interrupted." : "Displayed an in-world chat bubble and reaction.",
            data
        );
    }

    private ExecutorProtocol.StepResult playBlockedReaction(ExecutorProtocol.Action action, ExecutorProtocol.StepResult result) {
        JsonObject data = result.data() == null ? new JsonObject() : result.data();
        JsonObject reaction = new JsonObject();
        reaction.addProperty("style", "sad_sneak_shuffle");
        reaction.addProperty("message", BLOCKED_REACTION_MESSAGE);
        reaction.addProperty("duration_ticks", BLOCKED_REACTION_TICKS);
        reaction.addProperty("third_person", true);
        reaction.addProperty("action", action.type());
        data.add("blocked_reaction", reaction);
        JsonArray choices = new JsonArray();
        String[] utterances = blockedInterventionUtterances(action, result);
        for (int i = 0; i < utterances.length; i++) {
            addInterventionChoice(choices, utterances[i], blockedInterventionHint(i, utterances[i]));
        }
        data.add("intervention_choices", choices);

        publishStatus("[KoeCraft Choices] " + blockedInterventionChoicePayload(action, result));
        Float baseYaw = callOnClientThread(() -> {
            if (client.player == null) {
                return Float.NaN;
            }
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            if (client.currentScreen != null) {
                client.setScreen(null);
            }
            releaseKeys();
            client.options.sneakKey.setPressed(true);
            return client.player.getYaw();
        });

        if (baseYaw == null || baseYaw.isNaN()) {
            reaction.addProperty("played", false);
            reaction.addProperty("reason", "player_not_ready");
            return new ExecutorProtocol.StepResult(result.type(), result.status(), result.message(), data);
        }

        int sideStepTicks = 0;
        for (int tick = 0; tick < BLOCKED_REACTION_TICKS && !aborted; tick++) {
            final int currentTick = tick;
            final float initialYaw = baseYaw;
            Boolean movedSideways = callOnClientThread(() -> {
                if (client.player == null || client.world == null) {
                    return false;
                }
                releaseMovementKeys();
                client.options.sneakKey.setPressed(true);
                client.options.jumpKey.setPressed(false);
                client.options.sprintKey.setPressed(false);

                int phase = (currentTick / 12) % 4;
                boolean moved = false;
                if (phase == 0 && isMoveStepSafe("left")) {
                    client.options.leftKey.setPressed(true);
                    moved = true;
                } else if (phase == 2 && isMoveStepSafe("right")) {
                    client.options.rightKey.setPressed(true);
                    moved = true;
                }

                client.player.setYaw(initialYaw + (float) Math.sin(currentTick / 5.0D) * 7.0F);
                client.player.setPitch(12.0F + (float) Math.sin(currentTick / 8.0D) * 3.0F);
                if (currentTick % 24 == 0) {
                    client.player.swingHand(Hand.MAIN_HAND);
                }
                return moved;
            });
            if (Boolean.TRUE.equals(movedSideways)) {
                sideStepTicks++;
            }
            sleepTicks(1);
        }

        callOnClientThread(() -> {
            releaseKeys();
            return null;
        });
        reaction.addProperty("played", true);
        reaction.addProperty("interrupted", aborted);
        reaction.addProperty("side_step_ticks", sideStepTicks);
        return new ExecutorProtocol.StepResult(result.type(), result.status(), result.message(), data);
    }

    private void addInterventionChoice(JsonArray choices, String utterance, String hint) {
        JsonObject choice = new JsonObject();
        choice.addProperty("utterance", utterance);
        choice.addProperty("hint", hint);
        choices.add(choice);
    }

    private String blockedInterventionChoicePayload(ExecutorProtocol.Action action, ExecutorProtocol.StepResult result) {
        return blockedInterventionTitle(action, result) + "|" + String.join("|", blockedInterventionUtterances(action, result));
    }

    private String blockedInterventionTitle(ExecutorProtocol.Action action, ExecutorProtocol.StepResult result) {
        String message = result == null || result.message() == null ? "" : result.message();
        if ("craft".equals(action.type()) || "open_workstation".equals(action.type())) {
            return "作るところでつまったよ";
        }
        if ("dig_pattern".equals(action.type()) || "break_block".equals(action.type()) || "collect_block".equals(action.type()) || message.contains("crosshair") || message.contains("掘")) {
            return "掘るところでつまったよ";
        }
        if ("move".equals(action.type()) || "explore".equals(action.type()) || "build_bridge".equals(action.type())) {
            return "進む道でつまったよ";
        }
        if ("collect_drops".equals(action.type()) || "pickup_items".equals(action.type())) {
            return "拾うところでつまったよ";
        }
        return "ちょっとつまったよ";
    }

    private String[] blockedInterventionUtterances(ExecutorProtocol.Action action, ExecutorProtocol.StepResult result) {
        String message = result == null || result.message() == null ? "" : result.message();
        if ("craft".equals(action.type()) || "open_workstation".equals(action.type())) {
            return new String[] {"作業台を近くに置いて", "右にずれて", "一回やめて"};
        }
        if ("dig_pattern".equals(action.type()) || "break_block".equals(action.type()) || "collect_block".equals(action.type()) || message.contains("crosshair") || message.contains("掘")) {
            return new String[] {"手前を掘って", "階段掘りして", "一回やめて"};
        }
        if ("move".equals(action.type()) || "explore".equals(action.type()) || "build_bridge".equals(action.type())) {
            return new String[] {"右によけて", "橋をかけて", "一回やめて"};
        }
        if ("collect_drops".equals(action.type()) || "pickup_items".equals(action.type())) {
            return new String[] {"アイテム拾って", "近くを探して", "一回やめて"};
        }
        return new String[] {"右にずれて", "手前を掘って", "一回やめて"};
    }

    private String blockedInterventionHint(int index, String utterance) {
        if (utterance.contains("作業台")) {
            return "place_workstation_nearby";
        }
        if (utterance.contains("右")) {
            return "move_right";
        }
        if (utterance.contains("橋")) {
            return "build_bridge";
        }
        if (utterance.contains("アイテム") || utterance.contains("拾")) {
            return "pickup_items";
        }
        if (utterance.contains("近く")) {
            return "search_nearby";
        }
        if (utterance.contains("掘")) {
            return "dig_front";
        }
        if (utterance.contains("やめ")) {
            return "abort";
        }
        return "choice_" + (index + 1);
    }

    private String sanitizeBubbleText(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return compact.length() > 48 ? compact.substring(0, 48) : compact;
    }

    private ExecutorProtocol.StepResult checkToolDurability(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player is not ready.");
            }
            String group = action.stringField("tool_group");
            int minRemaining = Math.max(1, Math.min(action.intField("min_remaining", 12), 512));
            boolean optional = action.booleanField("optional", false);
            JsonObject data = new JsonObject();
            data.addProperty("tool_group", group);
            data.addProperty("min_remaining", minRemaining);
            ItemStack best = null;
            int bestRemaining = -1;
            int checkedSlots = Math.min(36, client.player.getInventory().size());
            for (int slot = 0; slot < checkedSlots; slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                String itemId = itemId(stack);
                if (!matchesToolGroup(itemId, group)) {
                    continue;
                }
                int remaining = remainingDurability(stack);
                if (remaining > bestRemaining) {
                    bestRemaining = remaining;
                    best = stack;
                }
            }
            if (best == null) {
                data.addProperty("best_remaining", 0);
                if (optional) {
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "No matching tool was found, but durability check is optional.", data);
                }
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No matching tool is available before a risky batch action.", data);
            }
            data.addProperty("tool", itemId(best));
            data.addProperty("best_remaining", bestRemaining);
            if (bestRemaining < minRemaining) {
                return new ExecutorProtocol.StepResult(
                    action.type(),
                    optional ? "accepted" : "blocked",
                    optional ? "Best matching tool durability is low, but durability check is optional." : "Best matching tool durability is below the requested safety threshold.",
                    data
                );
            }
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Tool durability is sufficient for the next bounded action.", data);
        });
    }

    private ExecutorProtocol.StepResult emergencyShelter(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }
            String material = firstPlaceableSupportItem();
            JsonObject data = new JsonObject();
            data.addProperty("material", material);
            if (material.isBlank()) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No solid block is available for emergency shelter construction.", data);
            }
            int before = countInventoryItem(material);
            BlockPos base = client.player.getBlockPos();
            List<BlockPos> targets = emergencyShelterTargets(base, Math.max(1, Math.min(action.intField("radius", 2), 3)));
            data.addProperty("pattern", "threat_weighted_shell");
            JsonArray placedBlocks = new JsonArray();
            int placed = 0;
            for (BlockPos target : targets) {
                if (aborted) {
                    break;
                }
                if (countInventoryItem(material) <= 0) {
                    break;
                }
                if (!blockIdAt(target).equals("minecraft:air")) {
                    continue;
                }
                if (placeItemAt(material, target)) {
                    JsonObject one = new JsonObject();
                    one.addProperty("x", target.getX());
                    one.addProperty("y", target.getY());
                    one.addProperty("z", target.getZ());
                    placedBlocks.add(one);
                    placed++;
                    sleepTicks(1);
                }
            }
            data.addProperty("requested_positions", targets.size());
            data.addProperty("placed_count", placed);
            data.addProperty("before_material_count", before);
            data.addProperty("after_material_count", countInventoryItem(material));
            data.add("placed", placedBlocks);
            if (aborted) {
                return new ExecutorProtocol.StepResult(action.type(), "aborted", "Emergency shelter construction was interrupted.", data);
            }
            if (placed == 0) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No emergency shelter blocks could be placed safely.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), placed >= 8 ? "accepted" : "partial", "Built a bounded emergency shelter shell from available solid blocks.", data);
        });
    }

    private ExecutorProtocol.StepResult escapeFluid(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
        }
        int radius = Math.max(2, Math.min(action.intField("radius", 8), 16));
        int timeoutTicks = Math.max(20, Math.min(action.intField("timeout_ticks", 160), 400));
        boolean allowPlace = action.booleanField("allow_place", true);
        JsonObject data = new JsonObject();
        data.addProperty("radius", radius);
        data.addProperty("allow_place", allowPlace);

        EscapePlan plan = callOnClientThread(() -> findBestEscapePlan(radius));
        if (plan == null && allowPlace) {
            ExecutorProtocol.StepResult support = callOnClientThread(() -> placeEmergencyEscapeSupport(action.type()));
            if ("accepted".equals(support.status())) {
                if (support.data() != null) data.add("support", support.data());
                plan = callOnClientThread(() -> findBestEscapePlan(radius));
            } else if (support.data() != null) {
                data.add("support_failed", support.data());
            }
        }
        if (plan == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No nearby safe ground was found for fluid escape.", data);
        }
        BlockPos target = plan.target();
        addBlockPos(data, "target", target);
        data.addProperty("escape_score", plan.score());
        data.addProperty("escape_path_nodes", plan.path().size());
        BlockPos escapeTarget = target;

        int ticks = 0;
        int blockedTicks = 0;
        while (ticks < timeoutTicks && !aborted) {
            Boolean safe = callOnClientThread(() -> client.player != null && canStandAt(client.player.getPos()) && !isDangerNear(client.player.getBlockPos(), 1));
            if (safe) {
                callOnClientThread(() -> {
                    releaseMovementKeys();
                    return null;
                });
                data.addProperty("ticks", ticks);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Reached nearby safe ground away from water, lava, or fire.", data);
            }
            DropMoveStatus status = callOnClientThread(() -> {
                if (client.player == null) return DropMoveStatus.BLOCKED;
                Vec3d next = nextEscapeWaypoint(client.player.getPos(), escapeTarget, radius);
                return steerToward(next, client.player.getPos().distanceTo(next));
            });
            if (status == DropMoveStatus.BLOCKED) {
                blockedTicks++;
                if (allowPlace && blockedTicks == 4) {
                    ExecutorProtocol.StepResult support = callOnClientThread(() -> placeEmergencyEscapeSupport(action.type()));
                    if (support.data() != null) data.add("late_support", support.data());
                }
            } else {
                blockedTicks = 0;
            }
            if (blockedTicks >= 12) {
                break;
            }
            sleepTicks(1);
            ticks++;
        }
        callOnClientThread(() -> {
            releaseMovementKeys();
            return null;
        });
        data.addProperty("ticks", ticks);
        data.addProperty("blocked_ticks", blockedTicks);
        return new ExecutorProtocol.StepResult(action.type(), aborted ? "aborted" : "blocked", aborted ? "Fluid escape was interrupted." : "Could not reach safe ground within the bounded escape window.", data);
    }

    private ExecutorProtocol.StepResult useBoatIfWater(ExecutorProtocol.Action action) {
        boolean optional = action.booleanField("optional", true);
        JsonObject data = new JsonObject();
        data.addProperty("optional", optional);
        data.addProperty("programmatic_assist", true);
        data.addProperty("world_assist", config.worldAssistEnabled());
        data.addProperty("command_free", true);
        data.addProperty("assist_mode", config.assistMode());
        if (!config.programmaticAssistEnabled()) {
            data.addProperty("enabled", false);
            return new ExecutorProtocol.StepResult(
                action.type(),
                optional ? "accepted" : "blocked",
                optional ? "World assist boat action skipped because world assist is disabled." : "World assist boat action is disabled.",
                data
            );
        }
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), optional ? "accepted" : "blocked", "Player or world is not ready for boat assist.", data);
        }

        int searchRadius = Math.max(2, Math.min(action.intField("search_radius", 12), 24));
        int travelDistance = Math.max(4, Math.min(action.intField("distance_blocks", config.programmaticBoatTravelDistanceBlocks()), config.programmaticBoatTravelDistanceBlocks()));
        data.addProperty("search_radius", searchRadius);
        data.addProperty("distance_blocks", travelDistance);

        BlockPos water = callOnClientThread(() -> findNearestBoatWaterSurface(searchRadius));
        if (water == null) {
            data.addProperty("water_found", false);
            return new ExecutorProtocol.StepResult(
                action.type(),
                optional ? "accepted" : "blocked",
                optional ? "No boat-friendly water was nearby; continuing with normal movement." : "No boat-friendly water was nearby.",
                data
            );
        }
        data.addProperty("water_found", true);
        addBlockPos(data, "water", water);

        int boatBefore = callOnClientThread(() -> countInventoryItem("minecraft:oak_boat"));
        data.addProperty("oak_boat_before", boatBefore);
        if (boatBefore <= 0 && config.worldAssistCommonMaterialTopUpEnabled()) {
            VoiceAssistGrantResult grant = grantVoiceAssistItemToInventory("minecraft:oak_boat", 1);
            data.addProperty("boat_grant_mode", grant.mode());
            data.addProperty("boat_grant_reason", grant.reason());
            data.addProperty("boat_grant_inserted", grant.insertedCount());
        } else if (boatBefore <= 0) {
            data.addProperty("boat_grant_skipped", "common_material_top_up_disabled");
        }

        ProgrammaticBoatResult spawned = spawnProgrammaticBoatAndRide(water);
        data.addProperty("boat_spawned", spawned.spawned());
        data.addProperty("riding_boat", spawned.riding());
        data.addProperty("boat_mode", spawned.mode());
        data.addProperty("boat_reason", spawned.reason());
        if (!spawned.riding()) {
            return new ExecutorProtocol.StepResult(
                action.type(),
                optional ? "accepted" : "blocked",
                optional ? "Programmatic boat assist could not mount a boat; continuing with normal movement." : "Programmatic boat assist could not mount a boat.",
                data
            );
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Programmatic boat assist mounted a boat for water travel.", data);
    }

    private BlockPos findNearestBoatWaterSurface(int radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int y = -2; y <= 2; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!blockIdAt(pos).equals("minecraft:water")) {
                        continue;
                    }
                    String above = blockIdAt(pos.up());
                    if (!above.equals("minecraft:air") && !above.equals("minecraft:cave_air") && !above.equals("minecraft:void_air")) {
                        continue;
                    }
                    double distance = origin.getSquaredDistance(pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private ProgrammaticBoatResult spawnProgrammaticBoatAndRide(BlockPos water) {
        if (client.player == null || client.world == null) {
            return new ProgrammaticBoatResult(false, false, "integrated_server_boat", "player_or_world_not_ready");
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            return new ProgrammaticBoatResult(false, false, "integrated_server_boat", "integrated_server_not_available");
        }
        UUID playerUuid = client.player.getUuid();
        RegistryKey<World> dimensionKey = client.world.getRegistryKey();
        CompletableFuture<ProgrammaticBoatResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld serverWorld = server.getWorld(dimensionKey);
                ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);
                if (serverWorld == null || serverPlayer == null) {
                    future.complete(new ProgrammaticBoatResult(false, false, "integrated_server_boat", "server_player_or_world_not_ready"));
                    return;
                }
                BoatEntity boat = new BoatEntity(EntityType.OAK_BOAT, serverWorld, () -> Items.OAK_BOAT);
                boat.refreshPositionAndAngles(water.getX() + 0.5D, water.getY() + 1.0D, water.getZ() + 0.5D, serverPlayer.getYaw(), 0.0F);
                boolean spawned = serverWorld.spawnEntity(boat);
                boolean riding = spawned && serverPlayer.startRiding(boat, true);
                future.complete(new ProgrammaticBoatResult(spawned, riding, "integrated_server_boat", riding ? "ok" : "spawn_or_mount_failed"));
            } catch (RuntimeException error) {
                future.complete(new ProgrammaticBoatResult(false, false, "integrated_server_boat", error.toString()));
            }
        });
        try {
            return future.get(800, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            return new ProgrammaticBoatResult(false, false, "integrated_server_boat", error.toString());
        }
    }

    private ExecutorProtocol.StepResult move(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
        }
        String direction = action.stringField("direction");
        if (!direction.equals("forward") && !direction.equals("back") && !direction.equals("left") && !direction.equals("right")) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported move direction: " + direction);
        }
        int durationTicks = Math.max(5, Math.min(action.intField("duration_ticks", 30), 700));
        int distanceBlocks = Math.max(1, Math.min(action.intField("distance_blocks", action.booleanField("sprint", false) ? 7 : 4), 96));
        boolean sprint = action.booleanField("sprint", false);
        boolean allowAssist = action.booleanField("allow_assist", true);
        boolean allowPlace = action.booleanField("allow_place", true);
        boolean allowDig = action.booleanField("allow_dig", true);
        boolean allowSprintJump = action.booleanField("allow_sprint_jump", true);
        boolean swim = action.booleanField("swim", false);
        boolean scanAware = action.booleanField("scan_aware", true);
        boolean jumpWhileSprinting = action.booleanField("jump_while_sprinting", false);
        boolean adaptiveDistance = action.booleanField("adaptive_distance", scanAware);
        boolean extendIfClear = action.booleanField("extend_if_clear", false);
        boolean faceMoveDirection = action.booleanField("face_move_direction", true);
        boolean autoJump = action.booleanField("auto_jump", true);
        int maxAdaptiveDistance = Math.max(1, Math.min(action.intField("max_adaptive_distance_blocks", sprint ? 24 : 16), 32));
        JsonObject data = new JsonObject();
        data.addProperty("direction", direction);
        data.addProperty("duration_ticks", durationTicks);
        data.addProperty("distance_blocks", distanceBlocks);
        data.addProperty("sprint", sprint);
        data.addProperty("swim", swim);
        data.addProperty("jump_while_sprinting", jumpWhileSprinting);
        data.addProperty("allow_assist", allowAssist);
        data.addProperty("path_aware_target", true);
        data.addProperty("scan_aware", scanAware);
        data.addProperty("adaptive_distance", adaptiveDistance);
        data.addProperty("extend_if_clear", extendIfClear);
        data.addProperty("face_move_direction", faceMoveDirection);
        data.addProperty("auto_jump", autoJump);
        data.addProperty("max_adaptive_distance_blocks", maxAdaptiveDistance);

        callOnClientThread(() -> {
            if (client.player != null && client.currentScreen != null) {
                client.player.closeHandledScreen();
                data.addProperty("closed_screen_before_move", true);
            }
            return null;
        });

        MoveExecutionPlan movePlan = callOnClientThread(() -> prepareMoveExecutionPlan(
            direction,
            distanceBlocks,
            swim,
            scanAware,
            adaptiveDistance,
            extendIfClear,
            faceMoveDirection,
            maxAdaptiveDistance
        ));
        Vec3d targetPos = movePlan.targetPos();
        String keyDirection = movePlan.keyDirection();
        int effectiveDistance = movePlan.effectiveDistanceBlocks();
        data.addProperty("key_direction", keyDirection);
        data.addProperty("effective_distance_blocks", effectiveDistance);
        data.addProperty("face_direction_applied", movePlan.facedMoveDirection());
        data.addProperty("scan_lane_available", movePlan.laneAvailable());
        data.addProperty("scan_lane_adjusted", movePlan.laneAdjusted());
        data.addProperty("scan_lane_safe_steps", movePlan.laneSafeSteps());
        data.addProperty("scan_lane_score", movePlan.laneScore());

        Boolean initialSafe = callOnClientThread(() -> isPlayerRidingBoat() || swim && isPlayerInWater() || isMoveStepSafe(keyDirection) || autoJump && canStepUp(keyDirection));
        if (!initialSafe) {
            ExecutorProtocol.StepResult assist = allowAssist ? tryMovementAssist(keyDirection, allowPlace, allowDig, allowSprintJump, action.type()) : null;
            if (assist == null || !"accepted".equals(assist.status())) {
                data.addProperty("danger_stop", true);
                if (assist != null && assist.data() != null) {
                    data.add("assist", assist.data());
                    data.addProperty("assist_status", assist.status());
                    data.addProperty("assist_message", assist.message());
                }
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Refused to move because the next step is not safe and assist failed.", data);
            }
            data.add("initial_assist", assist.data());
        }

        callOnClientThread(() -> pressMoveKeys(keyDirection, shouldSprintForMove(keyDirection, sprint, swim), swim, shouldJumpWhileMoving(keyDirection, sprint, swim, jumpWhileSprinting, autoJump)));
        int ticks = 0;
        int assistCount = 0;
        int pathCorrections = 0;
        int laneReroutes = 0;
        int stuckTicks = 0;
        double lastDistance = callOnClientThread(() -> client.player == null ? 0.0D : client.player.getPos().distanceTo(targetPos));
        while (ticks < durationTicks && !aborted) {
            double distanceNow = callOnClientThread(() -> client.player == null ? 0.0D : client.player.getPos().distanceTo(targetPos));
            if (distanceNow <= 0.75D) {
                data.addProperty("reached_distance_target", true);
                break;
            }
            if (ticks % 3 == 0 && !callOnClientThread(() -> isPlayerRidingBoat() || swim && isPlayerInWater() || isMoveStepSafe(keyDirection) || autoJump && canStepUp(keyDirection))) {
                boolean rerouted = Boolean.TRUE.equals(callOnClientThread(() ->
                    scanAware
                        && !swim
                        && steerToSafeMoveLane(effectiveDistance, sprint, jumpWhileSprinting, autoJump)
                ));
                if (rerouted) {
                    laneReroutes++;
                    sleepTicks(1);
                    ticks++;
                    continue;
                }
                ExecutorProtocol.StepResult assist = allowAssist ? tryMovementAssist(keyDirection, allowPlace, allowDig, allowSprintJump, action.type()) : null;
                if (assist == null || !"accepted".equals(assist.status())) {
                    data.addProperty("danger_stop", true);
                    if (assist != null && assist.data() != null) {
                        data.add("assist", assist.data());
                        data.addProperty("assist_status", assist.status());
                        data.addProperty("assist_message", assist.message());
                    }
                    break;
                }
                assistCount++;
                callOnClientThread(() -> pressMoveKeys(keyDirection, shouldSprintForMove(keyDirection, sprint, swim), swim, shouldJumpWhileMoving(keyDirection, sprint, swim, jumpWhileSprinting, autoJump)));
            } else if (ticks > 0 && ticks % 10 == 0) {
                callOnClientThread(() -> {
                    client.options.sprintKey.setPressed(shouldSprintForMove(keyDirection, sprint, swim));
                    client.options.jumpKey.setPressed(shouldJumpWhileMoving(keyDirection, sprint, swim, jumpWhileSprinting, autoJump));
                    return null;
                });
                if (distanceNow + 0.05D >= lastDistance) {
                    stuckTicks += 10;
                } else {
                    stuckTicks = 0;
                }
                if (stuckTicks >= 20 && pathCorrections < 3) {
                    DropMoveStatus correction = callOnClientThread(() -> steerToward(targetPos, distanceNow));
                    if (correction == DropMoveStatus.BLOCKED) {
                        ExecutorProtocol.StepResult assist = allowAssist ? tryMovementAssist(keyDirection, allowPlace, allowDig, allowSprintJump, action.type()) : null;
                        if (assist == null || !"accepted".equals(assist.status())) {
                            data.addProperty("danger_stop", true);
                            if (assist != null && assist.data() != null) {
                                data.add("assist", assist.data());
                                data.addProperty("assist_status", assist.status());
                                data.addProperty("assist_message", assist.message());
                            }
                            break;
                        }
                        assistCount++;
                    } else {
                        pathCorrections++;
                    }
                    stuckTicks = 0;
                }
                lastDistance = distanceNow;
            }
            sleepTicks(1);
            ticks++;
        }
        callOnClientThread(() -> {
            releaseMovementKeys();
            return null;
        });
        data.addProperty("ticks", ticks);
        data.addProperty("assist_count", assistCount);
        data.addProperty("path_corrections", pathCorrections);
        data.addProperty("lane_reroutes", laneReroutes);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Move was interrupted.", data);
        }
        if (data.has("danger_stop")) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Stopped moving before an unsafe step.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Moved for the bounded duration.", data);
    }

    private MoveExecutionPlan prepareMoveExecutionPlan(
        String requestedDirection,
        int requestedDistance,
        boolean swim,
        boolean scanAware,
        boolean adaptiveDistance,
        boolean extendIfClear,
        boolean faceMoveDirection,
        int maxAdaptiveDistance
    ) {
        if (client.player == null) {
            return new MoveExecutionPlan("forward", Vec3d.ZERO, Vec3d.ZERO, requestedDistance, 0, 0.0D, false, false, false);
        }
        Vec3d movement = movementVector(requestedDirection);
        String keyDirection = requestedDirection;
        boolean faced = false;
        if (faceMoveDirection && !requestedDirection.equals("forward") && movement.lengthSquared() > 0.01D) {
            faceHorizontalDirection(movement, 180.0F);
            keyDirection = "forward";
            movement = movementVector("forward");
            faced = true;
        }

        int effectiveDistance = requestedDistance;
        int scanDistance = requestedDistance;
        if (adaptiveDistance && extendIfClear) {
            scanDistance = Math.max(requestedDistance, maxAdaptiveDistance);
        }
        MoveLane lane = null;
        if (scanAware && !swim && movement.lengthSquared() > 0.01D) {
            lane = chooseSafeMoveLane(movement, scanDistance, adaptiveDistance ? 1 : Math.min(3, Math.max(1, scanDistance)));
            if (lane != null) {
                faceHorizontalDirection(lane.direction(), faced ? 180.0F : 45.0F);
                keyDirection = "forward";
                movement = lane.direction();
                if (adaptiveDistance) {
                    if (lane.safeSteps() < requestedDistance) {
                        effectiveDistance = Math.max(1, lane.safeSteps());
                    } else if (extendIfClear) {
                        effectiveDistance = Math.max(requestedDistance, Math.min(maxAdaptiveDistance, lane.safeSteps()));
                    }
                }
            }
        }

        Vec3d target = client.player.getPos().add(movement.multiply(Math.max(1, effectiveDistance)));
        return new MoveExecutionPlan(
            keyDirection,
            movement,
            target,
            Math.max(1, effectiveDistance),
            lane == null ? 0 : lane.safeSteps(),
            lane == null ? 0.0D : lane.score(),
            lane != null && lane.adjusted(),
            faced || lane != null && lane.adjusted(),
            lane != null
        );
    }

    private ExecutorProtocol.StepResult explore(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
        }
        String targetGroup = action.stringField("target_group");
        if (!isSupportedExplorationTargetGroup(targetGroup)) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported exploration target group: " + targetGroup);
        }
        int distanceBlocks = Math.max(8, Math.min(action.intField("distance_blocks", 32), config.effectiveExploreDistanceBlocks()));
        int searchRadius = Math.max(8, Math.min(action.intField("search_radius", 32), 128));
        int maxTimeoutTicks = config.programmaticAssistEnabled() ? 6000 : 2000;
        int timeoutTicks = Math.max(120, Math.min(action.intField("timeout_ticks", 700), maxTimeoutTicks));
        boolean avoidVisited = action.booleanField("avoid_visited", true);
        JsonObject data = new JsonObject();
        data.addProperty("target_group", targetGroup);
        data.addProperty("distance_blocks", distanceBlocks);
        data.addProperty("search_radius", searchRadius);
        data.addProperty("avoid_visited", avoidVisited);
        data.addProperty("programmatic_assist", config.programmaticAssistEnabled());
        data.addProperty("world_assist", config.worldAssistEnabled());
        data.addProperty("assist_mode", config.assistMode());

        BlockPos foundBeforeMove = callOnClientThread(() -> findNearestMatchingBlock(searchRadius, "", targetGroup, searchRadius));
        if (foundBeforeMove != null) {
            addBlockPos(data, "found", foundBeforeMove);
            ExecutorProtocol.StepResult navigation = navigateNearExplorationTarget(foundBeforeMove, 6.0D, timeoutTicks, action.type());
            if (navigation.data() != null) {
                data.add("navigation", navigation.data());
            }
            data.addProperty("navigation_status", navigation.status());
            data.addProperty("navigation_message", navigation.message());
            String message = "accepted".equals(navigation.status())
                ? "Found a nearby exploration hint and moved toward it."
                : "Found a nearby exploration hint but could not reach it.";
            return new ExecutorProtocol.StepResult(action.type(), navigation.status(), message, data);
        }

        ExploreDirection direction = callOnClientThread(() -> chooseExploreDirection(distanceBlocks, avoidVisited));
        if (direction == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not choose an exploration direction.");
        }
        data.addProperty("direction", direction.name());
        data.addProperty("sector", direction.currentSector());
        data.addProperty("target_sector", direction.targetSector());
        data.addProperty("stable_direction", true);
        Vec3d targetPos = callOnClientThread(() -> {
            if (client.player == null) return Vec3d.ZERO;
            return client.player.getPos().add(direction.vector().multiply(distanceBlocks));
        });
        Vec3d startPos = callOnClientThread(() -> client.player == null ? Vec3d.ZERO : client.player.getPos());

        int ticks = 0;
        int stuckTicks = 0;
        int assistCount = 0;
        int steeringTicks = 0;
        double lastProgress = 0.0D;
        callOnClientThread(() -> {
            faceExploreDirection(direction.vector(), 24.0F);
            return null;
        });
        while (ticks < timeoutTicks && !aborted) {
            if (ticks % 10 == 0 && callOnClientThread(this::hasImmediateAbortDanger)) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("danger_stop", true);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Stopped exploration because an immediate survival danger appeared.", data);
            }
            if (ticks % 40 == 0) {
                BlockPos found = callOnClientThread(() -> findNearestMatchingBlock(searchRadius, "", targetGroup, searchRadius));
                if (found != null) {
                    releaseKeys();
                    addBlockPos(data, "found", found);
                    data.addProperty("ticks", ticks);
                    ExecutorProtocol.StepResult navigation = navigateNearExplorationTarget(found, 6.0D, Math.max(160, timeoutTicks - ticks), action.type());
                    if (navigation.data() != null) {
                        data.add("navigation", navigation.data());
                    }
                    data.addProperty("navigation_status", navigation.status());
                    data.addProperty("navigation_message", navigation.message());
                    String message = "accepted".equals(navigation.status())
                        ? "Found an exploration hint while exploring and moved toward it."
                        : "Found an exploration hint while exploring but could not reach it.";
                    return new ExecutorProtocol.StepResult(action.type(), navigation.status(), message, data);
                }
            }
            DropMoveStatus status = callOnClientThread(() -> {
                if (client.player == null) return DropMoveStatus.BLOCKED;
                double progress = exploreProgress(startPos, client.player.getPos(), direction.vector());
                if (progress >= distanceBlocks - 1.0D || client.player.getPos().distanceTo(targetPos) <= 1.5D) {
                    releaseMovementKeys();
                    return DropMoveStatus.COLLECTED;
                }
                return steerExploreAlong(direction.vector(), targetPos);
            });
            if (status == DropMoveStatus.COLLECTED) {
                releaseKeys();
                exploredSectors.add(direction.targetSector());
                data.addProperty("ticks", ticks);
                data.addProperty("reached_range_edge", true);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Reached the edge of the bounded exploration range without revisiting a known sector.", data);
            }
            if (status == DropMoveStatus.BLOCKED) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
            double progressNow = callOnClientThread(() -> client.player == null ? 0.0D : exploreProgress(startPos, client.player.getPos(), direction.vector()));
            if (ticks > 0 && ticks % 10 == 0) {
                if (progressNow <= lastProgress + 0.08D) {
                    stuckTicks += 10;
                } else {
                    stuckTicks = 0;
                }
                lastProgress = progressNow;
            }
            if (stuckTicks >= 12 && assistCount < 3) {
                ExecutorProtocol.StepResult assist = tryExploreAssist(direction.vector(), action.type());
                if ("accepted".equals(assist.status())) {
                    assistCount++;
                    stuckTicks = 0;
                    data.add("assist_" + assistCount, assist.data());
                    sleepTicks(2);
                    ticks += 2;
                    continue;
                }
                if (assist.data() != null) {
                    data.add("assist_failed", assist.data());
                    data.addProperty("assist_message", assist.message());
                }
            }
            if (stuckTicks >= 24) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("stuck_ticks", stuckTicks);
                data.addProperty("progress_blocks", progressNow);
                data.addProperty("assist_count", assistCount);
                data.addProperty("steering_ticks", steeringTicks);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No safe local exploration path was found in the chosen direction.", data);
            }
            if (status == DropMoveStatus.MOVING) {
                steeringTicks++;
            }
            sleepTicks(1);
            ticks++;
        }
        releaseKeys();
        data.addProperty("ticks", ticks);
        data.addProperty("progress_blocks", callOnClientThread(() -> client.player == null ? 0.0D : exploreProgress(startPos, client.player.getPos(), direction.vector())));
        data.addProperty("assist_count", assistCount);
        data.addProperty("steering_ticks", steeringTicks);
        return new ExecutorProtocol.StepResult(action.type(), aborted ? "aborted" : "partial", aborted ? "Exploration was interrupted." : "Exploration stopped at the bounded timeout.", data);
    }

    private boolean isSupportedExplorationTargetGroup(String targetGroup) {
        return targetGroup.equals("village_hint") || targetGroup.equals("structure_hint");
    }

    private ExecutorProtocol.StepResult navigateNearExplorationTarget(BlockPos target, double stopDistance, int timeoutTicks, String actionType) {
        JsonObject data = new JsonObject();
        addBlockPos(data, "target", target);
        data.addProperty("stop_distance", stopDistance);
        data.addProperty("timeout_ticks", timeoutTicks);
        data.addProperty("path_aware", true);
        if (isRecentUnreachableBlockTarget(target)) {
            data.addProperty("unreachable_blacklist_hit", true);
            data.addProperty("unreachable_target_ttl_ms", UNREACHABLE_TARGET_TTL_MS);
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Target was recently marked unreachable by local navigation.", data);
        }
        int ticks = 0;
        int stuckTicks = 0;
        int assistCount = 0;
        double lastDistance = Double.MAX_VALUE;
        while (ticks < timeoutTicks && !aborted) {
            if (ticks % 10 == 0 && callOnClientThread(this::hasImmediateAbortDanger)) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("danger_stop", true);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Stopped target navigation because an immediate survival danger appeared.", data);
            }

            final LocalNavigationPlan[] navigationPlan = new LocalNavigationPlan[1];
            DropMoveStatus status = callOnClientThread(() -> {
                if (client.player == null) {
                    return DropMoveStatus.BLOCKED;
                }
                Vec3d targetPos = Vec3d.ofCenter(target);
                double distance = client.player.getPos().distanceTo(targetPos);
                if (distance <= stopDistance) {
                    releaseMovementKeys();
                    return DropMoveStatus.COLLECTED;
                }
                LocalNavigationPlan plan = planLocalNavigation(
                    client.player.getPos(),
                    target,
                    Math.max(1, (int) Math.ceil(stopDistance)),
                    Math.max(8, Math.min(18, (int) Math.ceil(distance) + 4)),
                    4,
                    1024
                );
                navigationPlan[0] = plan;
                if (!plan.reached() || plan.path().size() < 2) {
                    return DropMoveStatus.BLOCKED;
                }
                return steerToward(plan.waypoint(), distance);
            });
            if (navigationPlan[0] != null && ticks % 10 == 0) {
                addLocalNavigationData(data, "target_path", navigationPlan[0]);
            }
            if (status == DropMoveStatus.COLLECTED) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("assist_count", assistCount);
                data.addProperty("distance_to_target", callOnClientThread(() -> client.player == null ? -1.0D : client.player.getPos().distanceTo(Vec3d.ofCenter(target))));
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Reached navigation range around the exploration target.", data);
            }
            if (status == DropMoveStatus.BLOCKED) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }

            double distanceNow = callOnClientThread(() -> client.player == null ? Double.MAX_VALUE : client.player.getPos().distanceTo(Vec3d.ofCenter(target)));
            if (ticks > 0 && ticks % 10 == 0) {
                if (distanceNow + 0.08D >= lastDistance) {
                    stuckTicks += 10;
                } else {
                    stuckTicks = 0;
                }
                lastDistance = distanceNow;
            }

            if (stuckTicks >= 12 && assistCount < 6) {
                Vec3d desired = callOnClientThread(() -> {
                    if (client.player == null) {
                        return Vec3d.ZERO;
                    }
                    Vec3d delta = Vec3d.ofCenter(target).subtract(client.player.getPos()).multiply(1.0D, 0.0D, 1.0D);
                    return delta.lengthSquared() < 0.01D ? Vec3d.ZERO : delta.normalize();
                });
                ExecutorProtocol.StepResult assist = tryExploreAssist(desired, actionType);
                if ("accepted".equals(assist.status())) {
                    assistCount++;
                    stuckTicks = 0;
                    if (assist.data() != null) {
                        data.add("assist_" + assistCount, assist.data());
                    }
                    sleepTicks(2);
                    ticks += 2;
                    continue;
                }
                if (assist.data() != null) {
                    data.add("assist_failed", assist.data());
                    data.addProperty("assist_message", assist.message());
                }
            }
            if (stuckTicks >= 30) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("stuck_ticks", stuckTicks);
                data.addProperty("assist_count", assistCount);
                data.addProperty("distance_to_target", distanceNow);
                rememberUnreachableBlockTarget(target);
                data.addProperty("unreachable_target_ttl_ms", UNREACHABLE_TARGET_TTL_MS);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe assisted path toward the exploration target was found.", data);
            }
            sleepTicks(1);
            ticks++;
        }
        releaseKeys();
        data.addProperty("ticks", ticks);
        data.addProperty("assist_count", assistCount);
        data.addProperty("distance_to_target", callOnClientThread(() -> client.player == null ? -1.0D : client.player.getPos().distanceTo(Vec3d.ofCenter(target))));
        if (!aborted) {
            rememberUnreachableBlockTarget(target);
            data.addProperty("unreachable_target_ttl_ms", UNREACHABLE_TARGET_TTL_MS);
        }
        return new ExecutorProtocol.StepResult(actionType, aborted ? "aborted" : "partial", aborted ? "Target navigation was interrupted." : "Timed out before reaching the exploration target.", data);
    }

    private boolean isMoveStepSafe(String direction) {
        if (client.player == null || client.world == null) {
            return false;
        }
        Vec3d step = movementVector(direction).multiply(0.9D);
        return stepSafetyAllowingOneBlockDown(client.player.getPos().add(step)).safe();
    }

    private boolean steerToSafeMoveLane(int distanceBlocks, boolean requestedSprint, boolean requestedJump, boolean autoJump) {
        if (client.player == null || client.world == null) {
            return false;
        }
        MoveLane lane = chooseSafeMoveLane(movementVector("forward"), Math.min(distanceBlocks, 8), 1);
        if (lane == null || lane.safeSteps() < 2) {
            return false;
        }
        faceHorizontalDirection(lane.direction(), 30.0F);
        releaseMovementKeys();
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(requestedSprint && lane.safeSteps() >= 4);
        client.options.jumpKey.setPressed(shouldJumpWhileMoving("forward", requestedSprint, false, requestedJump, autoJump));
        return true;
    }

    private ExecutorProtocol.StepResult tryMovementAssist(String direction, boolean allowPlace, boolean allowDig, boolean allowSprintJump, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("direction", direction);
        data.addProperty("allow_place", allowPlace);
        data.addProperty("allow_dig", allowDig);
        data.addProperty("allow_sprint_jump", allowSprintJump);

        boolean canStepUp = Boolean.TRUE.equals(callOnClientThread(() -> canStepUp(direction)));
        if (canStepUp) {
            data.addProperty("strategy", "step_up");
            JsonObject stepData = performStepUp(direction);
            data.add("step_up", stepData);
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Used bounded jump step-up movement assist.", data);
        }

        boolean canSprintJump = Boolean.TRUE.equals(callOnClientThread(() -> canSprintJump(direction)));
        if (allowSprintJump && canSprintJump) {
            data.addProperty("strategy", "sprint_jump");
            performSprintJump(direction);
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Used bounded sprint-jump movement assist.", data);
        }

        ExecutorProtocol.StepResult planned = tryGoalPlannedMovementAssist(direction, allowPlace, allowDig, actionType);
        if (planned.data() != null) {
            data.add("goal_planned_assist", planned.data());
        }
        if ("accepted".equals(planned.status())) {
            data.addProperty("strategy", "goal_planned_" + stringField(planned.data(), "selected_primitive", "assist"));
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Used goal-planned movement assist.", data);
        }

        return new ExecutorProtocol.StepResult(actionType, "blocked", "No movement assist strategy was available.", data);
    }

    private ExecutorProtocol.StepResult tryGoalPlannedMovementAssist(String direction, boolean allowPlace, boolean allowDig, String actionType) {
        LocalNavigationPlan plan = callOnClientThread(() -> planMovementAssist(direction, allowDig, allowPlace, true));
        JsonObject data = new JsonObject();
        data.addProperty("direction", direction);
        addLocalNavigationData(data, "assist_path", plan);
        if (plan == null || plan.firstAssistStep() == null || !plan.firstAssistStep().actionable()) {
            if (plan != null && plan.reached() && plan.path().size() >= 2) {
                data.addProperty("selected_primitive", "walk");
                Vec3d waypoint = plan.waypoint();
                callOnClientThread(() -> {
                    faceHorizontalTarget(waypoint);
                    releaseMovementKeys();
                    client.options.forwardKey.setPressed(true);
                    client.options.jumpKey.setPressed(false);
                    return null;
                });
                sleepTicks(6);
                callOnClientThread(() -> {
                    releaseMovementKeys();
                    return null;
                });
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Goal planner chose a short safe walking waypoint.", data);
            }
            data.addProperty("selected_primitive", "none");
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Goal planner did not choose an actionable movement assist.", data);
        }

        KoeCraftLocalGoalPathfinder.AssistStep assist = plan.firstAssistStep();
        data.addProperty("selected_primitive", assist.primitive().name().toLowerCase());
        addBlockPos(data, "selected_pos", assist.pos());
        callOnClientThread(() -> {
            faceHorizontalTarget(Vec3d.ofBottomCenter(assist.pos()));
            return null;
        });
        ExecutorProtocol.StepResult result = switch (assist.primitive()) {
            case OPEN_PASSAGE -> openForwardPassage("forward", actionType);
            case PLACE_SUPPORT -> placeBridgeBlock("forward", actionType);
            case DIG -> digBlockingStep("forward", actionType);
            case WALK -> new ExecutorProtocol.StepResult(actionType, "blocked", "Goal planner selected walking-only assist.", data);
        };
        data.addProperty("primitive_status", result.status());
        data.addProperty("primitive_message", result.message());
        if (result.data() != null) {
            data.add("primitive_result", result.data());
        }
        if ("accepted".equals(result.status())) {
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Executed goal-planned movement primitive.", data);
        }
        return new ExecutorProtocol.StepResult(actionType, "blocked", "Goal-planned primitive did not complete.", data);
    }

    private LocalNavigationPlan planMovementAssist(String direction, boolean allowDig, boolean allowPlace, boolean allowOpenPassage) {
        if (client.player == null || client.world == null) {
            return LocalNavigationPlan.blocked(null, "player_or_world_not_ready");
        }
        Vec3d dir = movementVector(direction).multiply(1.0D, 0.0D, 1.0D);
        if (dir.lengthSquared() < 0.01D) {
            return LocalNavigationPlan.blocked(null, "invalid_direction");
        }
        Vec3d origin = client.player.getPos();
        BlockPos target = BlockPos.ofFloored(origin.add(dir.normalize().multiply(4.0D)));
        return planLocalNavigation(
            origin,
            target,
            1,
            6,
            2,
            384,
            KoeCraftLocalGoalPathfinder.Options.localAssist(6, 2, 384, allowDig, allowPlace, allowOpenPassage)
        );
    }

    private ExecutorProtocol.StepResult openForwardPassage(String direction, String actionType) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Player, world, or interaction manager is not ready.");
            }
            Vec3d dir = movementVector(direction);
            BlockPos origin = client.player.getBlockPos();
            JsonObject data = new JsonObject();
            for (double distance : new double[] {0.8D, 1.2D, 1.8D}) {
                BlockPos pos = BlockPos.ofFloored(client.player.getPos().add(dir.multiply(distance)));
                for (BlockPos target : List.of(pos, pos.up())) {
                    String id = blockIdAt(target);
                    if (!isPassageBlock(id) || !hasBlockingCollision(target)) {
                        continue;
                    }
                    interactWithBlock(target);
                    sleepTicks(4);
                    addBlockPos(data, "target", target);
                    data.addProperty("distance", distance);
                    data.addProperty("blocking_after", hasBlockingCollision(target));
                    if (!hasBlockingCollision(target)) {
                        return new ExecutorProtocol.StepResult(actionType, "accepted", "Opened a forward passage block.", data);
                    }
                    return new ExecutorProtocol.StepResult(actionType, "partial", "Interacted with a forward passage block.", data);
                }
            }
            data.addProperty("origin_x", origin.getX());
            data.addProperty("origin_y", origin.getY());
            data.addProperty("origin_z", origin.getZ());
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No forward door or gate was available to open.", data);
        });
    }

    private ExecutorProtocol.StepResult digStairDownStep(String direction, String actionType) {
        DigStepPlan plan = callOnClientThread(() -> planStairDownStep(direction));
        if (plan == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe stair-down dig step was available.");
        }
        return executeDigStepPlan(plan, true, actionType);
    }

    private ExecutorProtocol.StepResult digSafeShaftStep(String direction, String actionType) {
        DigStepPlan plan = callOnClientThread(() -> planStairDownStep(direction));
        if (plan == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe shaft-down step was available.");
        }
        return executeDigStepPlan(plan, true, actionType);
    }

    private ExecutorProtocol.StepResult digTunnelForwardStep(String direction, String actionType) {
        DigStepPlan plan = callOnClientThread(() -> planTunnelForwardStep(direction));
        if (plan == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe tunnel-forward dig step was available.");
        }
        return executeDigStepPlan(plan, true, actionType);
    }

    private ExecutorProtocol.StepResult executeDigStepPlan(DigStepPlan plan, boolean moveAfterDig, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("strategy", plan.strategy());
        data.addProperty("direction", plan.direction());
        addBlockPos(data, "landing", plan.landing());
        JsonArray digTargets = new JsonArray();
        for (BlockPos target : plan.digTargets()) {
            JsonObject entry = new JsonObject();
            addBlockPos(entry, "pos", target);
            digTargets.add(entry);
        }
        data.add("dig_targets", digTargets);
        int dug = 0;
        for (BlockPos target : plan.digTargets()) {
            if (aborted) {
                data.addProperty("dug_count", dug);
                return new ExecutorProtocol.StepResult(actionType, "aborted", "Dig step was interrupted.", data);
            }
            ExecutorProtocol.StepResult result = breakBlockAt(target, config.maxReach(), 120, actionType);
            if (!"accepted".equals(result.status())) {
                data.addProperty("dug_count", dug);
                data.addProperty("last_status", result.status());
                data.addProperty("last_message", result.message());
                if (result.data() != null) data.add("last_result", result.data());
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Could not dig a required block for the pattern step.", data);
            }
            dug++;
            sleepTicks(2);
        }
        Boolean landingReady = callOnClientThread(() -> isStandableBlockPos(plan.landing()) && !isDangerNear(plan.landing(), 1));
        if (!Boolean.TRUE.equals(landingReady) && clearDigLandingWithWorldAssist(plan.landing(), actionType, data)) {
            landingReady = callOnClientThread(() -> isStandableBlockPos(plan.landing()) && !isDangerNear(plan.landing(), 1));
        }
        data.addProperty("dug_count", dug);
        data.addProperty("landing_ready", landingReady);
        if (!Boolean.TRUE.equals(landingReady)) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Dig pattern landing was not safe after digging.", data);
        }
        if (moveAfterDig) {
            moveToLanding(plan.landing());
            Boolean reachedLanding = callOnClientThread(() -> client.player != null && client.player.getBlockPos().getSquaredDistance(plan.landing()) <= 2.0D);
            data.addProperty("reached_landing", reachedLanding);
        }
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Completed one bounded dig pattern step.", data);
    }

    private boolean clearDigLandingWithWorldAssist(BlockPos landing, String actionType, JsonObject data) {
        JsonArray attempts = new JsonArray();
        boolean changed = false;
        for (int pass = 0; pass < 3; pass++) {
            boolean passChanged = false;
            for (BlockPos target : List.of(landing, landing.up())) {
                Boolean blocked = callOnClientThread(() -> client.world != null && hasBlockingCollision(target));
                if (!Boolean.TRUE.equals(blocked)) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                addBlockPos(entry, "target", target);
                ExecutorProtocol.StepResult original = new ExecutorProtocol.StepResult(
                    actionType,
                    "blocked",
                    "Dig landing remained blocked after survival digging.",
                    entry
                );
                ExecutorProtocol.StepResult assisted = tryWorldAssistBreakFallback(target, effectiveBreakReach(config.maxReach()), actionType, original, "no_progress");
                entry.addProperty("status", assisted == null ? "blocked" : assisted.status());
                entry.addProperty("message", assisted == null ? "No world assist break fallback was available." : assisted.message());
                attempts.add(entry);
                if (assisted != null && "accepted".equals(assisted.status())) {
                    passChanged = true;
                    changed = true;
                    sleepTicks(1);
                }
            }
            Boolean ready = callOnClientThread(() -> isStandableBlockPos(landing) && !isDangerNear(landing, 1));
            if (Boolean.TRUE.equals(ready) || !passChanged) {
                break;
            }
        }
        data.add("landing_clear_assist", attempts);
        return changed;
    }

    private DigStepPlan planStairDownStep(String direction) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Vec3d dir = movementVector(direction);
        BlockPos landing = BlockPos.ofFloored(client.player.getPos().add(dir.multiply(1.0D))).down();
        if (isUnsafeFluid(landing) || isUnsafeFluid(landing.up()) || isUnsafeFluid(landing.down())) {
            return null;
        }
        if (!hasBlockingCollision(landing.down())) {
            return null;
        }
        ArrayList<BlockPos> digTargets = new ArrayList<>();
        for (BlockPos target : List.of(landing, landing.up())) {
            if (hasBlockingCollision(target)) {
                digTargets.add(target);
            }
        }
        if (digTargets.isEmpty() && !isStandableBlockPos(landing)) {
            return null;
        }
        return new DigStepPlan("stair_down", direction, landing, digTargets);
    }

    private DigStepPlan planTunnelForwardStep(String direction) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Vec3d dir = movementVector(direction);
        BlockPos landing = BlockPos.ofFloored(client.player.getPos().add(dir.multiply(1.0D)));
        if (isUnsafeFluid(landing) || isUnsafeFluid(landing.up()) || isUnsafeFluid(landing.down())) {
            return null;
        }
        if (!hasBlockingCollision(landing.down())) {
            return null;
        }
        ArrayList<BlockPos> digTargets = new ArrayList<>();
        for (BlockPos target : List.of(landing, landing.up())) {
            if (hasBlockingCollision(target)) {
                digTargets.add(target);
            }
        }
        if (digTargets.isEmpty() && !isStandableBlockPos(landing)) {
            return null;
        }
        return new DigStepPlan("tunnel_forward", direction, landing, digTargets);
    }

    private void moveToLanding(BlockPos landing) {
        callOnClientThread(() -> {
            faceHorizontalTarget(Vec3d.ofBottomCenter(landing));
            releaseMovementKeys();
            client.options.forwardKey.setPressed(true);
            client.options.sneakKey.setPressed(true);
            return null;
        });
        sleepTicks(10);
        callOnClientThread(() -> {
            releaseMovementKeys();
            client.options.sneakKey.setPressed(false);
            return null;
        });
    }

    private ExecutorProtocol.StepResult tryMovementAssistToward(Vec3d targetPos, String actionType) {
        callOnClientThread(() -> {
            faceHorizontalTarget(targetPos);
            releaseMovementKeys();
            return null;
        });
        ExecutorProtocol.StepResult assist = tryMovementAssist("forward", true, true, true, actionType);
        if (assist.data() != null) {
            assist.data().addProperty("toward_x", targetPos.x);
            assist.data().addProperty("toward_y", targetPos.y);
            assist.data().addProperty("toward_z", targetPos.z);
        }
        return assist;
    }

    private ExecutorProtocol.StepResult tryDownwardBlockApproachAssist(BlockPos target, String actionType) {
        DownwardApproachPlan plan = callOnClientThread(() -> planDownwardBlockApproach(target));
        JsonObject data = new JsonObject();
        data.addProperty("strategy", "downward_stair_dig");
        addBlockPos(data, "target", target);
        if (plan == null) {
            data.addProperty("planned", false);
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No downward stair-dig assist plan was available.", data);
        }
        data.addProperty("planned", true);
        data.addProperty("direction", plan.direction().asString());
        addBlockPos(data, "landing", plan.landing());
        JsonArray digTargets = new JsonArray();
        for (BlockPos digTarget : plan.digTargets()) {
            JsonObject entry = new JsonObject();
            addBlockPos(entry, "pos", digTarget);
            digTargets.add(entry);
        }
        data.add("dig_targets", digTargets);

        int dug = 0;
        for (BlockPos digTarget : plan.digTargets()) {
            if (aborted) {
                data.addProperty("dug_count", dug);
                return new ExecutorProtocol.StepResult(actionType, "aborted", "Downward stair-dig assist was interrupted.", data);
            }
            ExecutorProtocol.StepResult result = breakBlockAt(digTarget, config.maxReach(), 120, actionType);
            if (!"accepted".equals(result.status())) {
                data.addProperty("dug_count", dug);
                data.addProperty("failed_target_x", digTarget.getX());
                data.addProperty("failed_target_y", digTarget.getY());
                data.addProperty("failed_target_z", digTarget.getZ());
                data.addProperty("last_status", result.status());
                data.addProperty("last_message", result.message());
                if (result.data() != null) data.add("last_result", result.data());
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Could not dig a downward stair step toward the target block.", data);
            }
            dug++;
            sleepTicks(2);
        }

        Boolean landingReady = callOnClientThread(() -> isStandableBlockPos(plan.landing()) && !isDangerNear(plan.landing(), 1));
        data.addProperty("dug_count", dug);
        data.addProperty("landing_ready", landingReady);
        if (!Boolean.TRUE.equals(landingReady)) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Downward stair landing was not safe after digging.", data);
        }

        callOnClientThread(() -> {
            faceHorizontalTarget(Vec3d.ofBottomCenter(plan.landing()));
            releaseMovementKeys();
            client.options.forwardKey.setPressed(true);
            client.options.sneakKey.setPressed(true);
            return null;
        });
        sleepTicks(10);
        callOnClientThread(() -> {
            releaseMovementKeys();
            client.options.sneakKey.setPressed(false);
            return null;
        });
        Boolean reachedLanding = callOnClientThread(() -> client.player != null && client.player.getBlockPos().getSquaredDistance(plan.landing()) <= 2.0D);
        data.addProperty("reached_landing", reachedLanding);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Dug and stepped down one bounded stair toward the target block.", data);
    }

    private DownwardApproachPlan planDownwardBlockApproach(BlockPos target) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        if (target.getY() >= origin.getY() - 1) {
            return null;
        }
        Direction direction = horizontalDirectionToward(origin, target);
        BlockPos landing = origin.offset(direction).down();
        if (landing.getY() < target.getY() - 1) {
            return null;
        }
        if (isUnsafeFluid(landing) || isUnsafeFluid(landing.up()) || isUnsafeFluid(landing.down())) {
            return null;
        }
        if (!hasBlockingCollision(landing.down())) {
            return null;
        }
        ArrayList<BlockPos> digTargets = new ArrayList<>();
        for (BlockPos clearTarget : List.of(landing, landing.up())) {
            if (clearTarget.equals(target)) {
                return null;
            }
            if (hasBlockingCollision(clearTarget)) {
                digTargets.add(clearTarget);
            }
        }
        if (digTargets.isEmpty() && !isStandableBlockPos(landing)) {
            return null;
        }
        return new DownwardApproachPlan(direction, landing, digTargets);
    }

    private Direction horizontalDirectionToward(BlockPos origin, BlockPos target) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return client.player == null ? Direction.NORTH : client.player.getHorizontalFacing();
    }

    private void faceHorizontalTarget(Vec3d targetPos) {
        if (client.player == null) {
            return;
        }
        Vec3d playerPos = client.player.getPos();
        double dx = targetPos.x - playerPos.x;
        double dz = targetPos.z - playerPos.z;
        if (dx * dx + dz * dz < 0.001D) {
            return;
        }
        float yaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        client.player.setYaw(yaw);
        client.player.setPitch(MathHelper.clamp(client.player.getPitch(), -20.0F, 20.0F));
    }

    private boolean canSprintJump(String direction) {
        if (client.player == null || client.world == null || !direction.equals("forward")) {
            return false;
        }
        Vec3d playerPos = client.player.getPos();
        Vec3d forward = movementVector(direction);
        return !stepSafety(playerPos.add(forward.multiply(0.9D))).safe()
            && stepSafety(playerPos.add(forward.multiply(2.2D))).safe()
            && stepSafety(playerPos.add(forward.multiply(2.8D))).safe();
    }

    private boolean canStepUp(String direction) {
        if (client.player == null || client.world == null || !client.player.isOnGround()) {
            return false;
        }
        Vec3d playerPos = client.player.getPos();
        Vec3d dir = movementVector(direction);
        Vec3d ahead = playerPos.add(dir.multiply(0.9D));
        if (stepSafety(ahead).safe()) {
            return false;
        }
        Vec3d raised = ahead.add(0.0D, 1.0D, 0.0D);
        BlockPos raisedFeet = BlockPos.ofFloored(raised.x, raised.y, raised.z);
        return stepSafety(raised).safe() && hasBlockingCollision(raisedFeet.down());
    }

    private JsonObject performStepUp(String direction) {
        JsonObject data = new JsonObject();
        double beforeY = callOnClientThread(() -> client.player == null ? 0.0D : client.player.getY());
        callOnClientThread(() -> {
            releaseMovementKeys();
            pressMoveKeys(direction, false);
            client.options.jumpKey.setPressed(true);
            return null;
        });
        sleepTicks(8);
        callOnClientThread(() -> {
            releaseMovementKeys();
            return null;
        });
        double afterY = callOnClientThread(() -> client.player == null ? beforeY : client.player.getY());
        data.addProperty("direction", direction);
        data.addProperty("before_y", beforeY);
        data.addProperty("after_y", afterY);
        data.addProperty("moved_up", afterY > beforeY + 0.35D);
        return data;
    }

    private void performSprintJump(String direction) {
        callOnClientThread(() -> {
            releaseMovementKeys();
            client.options.sprintKey.setPressed(true);
            client.options.forwardKey.setPressed(direction.equals("forward"));
            client.options.jumpKey.setPressed(true);
            return null;
        });
        sleepTicks(9);
        callOnClientThread(() -> {
            releaseMovementKeys();
            return null;
        });
    }

    private ExecutorProtocol.StepResult placeBridgeBlock(String direction, String actionType) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Player, world, or interaction manager is not ready.");
            }
            Vec3d dir = movementVector(direction);
            BlockPos feet = client.player.getBlockPos();
            BlockPos targetSupport = BlockPos.ofFloored(client.player.getPos().add(dir.multiply(1.0D))).down();
            JsonObject data = new JsonObject();
            data.addProperty("target_x", targetSupport.getX());
            data.addProperty("target_y", targetSupport.getY());
            data.addProperty("target_z", targetSupport.getZ());
            if (!client.world.getBlockState(targetSupport).isAir()) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Movement support target is not empty.", data);
            }
            if (!client.world.getBlockState(targetSupport.up()).isAir() || !client.world.getBlockState(targetSupport.up(2)).isAir()) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Movement support target has no headroom.", data);
            }
            String blockItem = firstPlaceableSupportItem();
            if (blockItem.isBlank() && config.worldAssistCommonMaterialTopUpEnabled()) {
                VoiceAssistGrantResult grant = grantVoiceAssistItemToInventory("minecraft:dirt", config.voiceAssistSupportTopUpCount());
                JsonObject grantData = new JsonObject();
                grantData.addProperty("item", "minecraft:dirt");
                grantData.addProperty("requested_count", config.voiceAssistSupportTopUpCount());
                grantData.addProperty("inserted_count", grant.insertedCount());
                grantData.addProperty("mode", grant.mode());
                grantData.addProperty("reason", grant.reason());
                grantData.addProperty("voice_assist", true);
                grantData.addProperty("world_assist", true);
                data.add("world_assist_material_top_up", grantData);
                blockItem = firstPlaceableSupportItem();
            }
            data.addProperty("item", blockItem);
            if (blockItem.isBlank()) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "No placeable support block is available.", data);
            }
            if (!selectHotbarItem(blockItem)) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Support block could not be selected.", data);
            }
            Direction side = Direction.getFacing(dir.x, 0.0D, dir.z);
            BlockPos currentSupport = feet.down();
            Vec3d hitPos = Vec3d.ofCenter(currentSupport).add(side.getOffsetX() * 0.5D, 0.0D, side.getOffsetZ() * 0.5D);
            BlockHitResult hit = new BlockHitResult(hitPos, side, currentSupport, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            client.player.swingHand(Hand.MAIN_HAND);
            sleepTicks(2);
            data.addProperty("placed", !client.world.getBlockState(targetSupport).isAir());
            if (client.world.getBlockState(targetSupport).isAir()) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Support block placement was not confirmed.", data);
            }
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Placed movement support block.", data);
        });
    }

    private ExecutorProtocol.StepResult buildBridge(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
        }
        String direction = action.stringField("direction");
        if (!direction.equals("forward") && !direction.equals("back") && !direction.equals("left") && !direction.equals("right")) {
            direction = "forward";
        }
        int distance = Math.max(1, Math.min(action.intField("distance_blocks", 4), 12));
        int timeoutTicks = Math.max(40, Math.min(action.intField("timeout_ticks", distance * 45), 800));
        boolean moveAfterPlace = action.booleanField("move_after_place", true);
        JsonObject data = new JsonObject();
        data.addProperty("direction", direction);
        data.addProperty("requested_distance", distance);
        data.addProperty("timeout_ticks", timeoutTicks);
        JsonArray steps = new JsonArray();
        int placed = 0;
        int moved = 0;
        int ticks = 0;
        for (int i = 0; i < distance && !aborted && ticks < timeoutTicks; i++) {
            ExecutorProtocol.StepResult placedStep = placeBridgeBlock(direction, action.type());
            JsonObject step = new JsonObject();
            step.addProperty("index", i);
            step.addProperty("place_status", placedStep.status());
            step.addProperty("place_message", placedStep.message());
            if (placedStep.data() != null) {
                step.add("place_data", placedStep.data());
            }
            if (!"accepted".equals(placedStep.status())) {
                steps.add(step);
                break;
            }
            placed++;
            ticks += 8;
            if (moveAfterPlace) {
                ExecutorProtocol.StepResult moveStep = shortBridgeMove(direction, action.type());
                step.addProperty("move_status", moveStep.status());
                step.addProperty("move_message", moveStep.message());
                if (moveStep.data() != null) {
                    step.add("move_data", moveStep.data());
                }
                if ("accepted".equals(moveStep.status())) {
                    moved++;
                } else {
                    steps.add(step);
                    break;
                }
                ticks += 10;
            }
            steps.add(step);
        }
        releaseKeys();
        data.addProperty("placed_count", placed);
        data.addProperty("moved_steps", moved);
        data.add("steps", steps);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Bridge building was interrupted.", data);
        }
        if (placed == 0) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Could not place the first bridge block.", data);
        }
        if (placed < distance) {
            return new ExecutorProtocol.StepResult(action.type(), "partial", "Built part of the requested bridge before stopping.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Built a bounded survival bridge from available blocks.", data);
    }

    private ExecutorProtocol.StepResult shortBridgeMove(String direction, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("direction", direction);
        Boolean ready = callOnClientThread(() -> client.player != null && client.world != null);
        if (!Boolean.TRUE.equals(ready)) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player or world is not ready.", data);
        }
        callOnClientThread(() -> {
            releaseMovementKeys();
            pressMoveKeys(direction, false);
            return null;
        });
        sleepTicks(7);
        callOnClientThread(() -> {
            releaseMovementKeys();
            return null;
        });
        data.addProperty("ticks", 7);
        boolean safe = Boolean.TRUE.equals(callOnClientThread(() -> isMoveStepSafe(direction)));
        data.addProperty("next_step_safe", safe);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Moved onto the placed bridge block.", data);
    }

    private ExecutorProtocol.StepResult digBlockingStep(String direction, String actionType) {
        BlockPos target = callOnClientThread(() -> {
            if (client.player == null || client.world == null) return null;
            Vec3d dir = movementVector(direction);
            BlockPos ahead = BlockPos.ofFloored(client.player.getPos().add(dir.multiply(1.0D)));
            if (!client.world.getBlockState(ahead).isAir()) return ahead;
            if (!client.world.getBlockState(ahead.up()).isAir()) return ahead.up();
            return null;
        });
        if (target == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No blocking step block was found.");
        }
        if (!callOnClientThread(() -> isMovementAssistDiggableBlock(target))) {
            JsonObject data = new JsonObject();
            addBlockPos(data, "target", target);
            data.addProperty("block", callOnClientThread(() -> blockIdAt(target)));
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Blocking step block is not safe for automatic movement digging.", data);
        }
        return breakBlockAt(target, config.maxReach(), 80, actionType);
    }

    private Vec3d movementVector(String direction) {
        if (client.player == null) {
            return Vec3d.ZERO;
        }
        double yaw = Math.toRadians(client.player.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0.0D, Math.cos(yaw)).normalize();
        Vec3d right = new Vec3d(Math.cos(yaw), 0.0D, Math.sin(yaw)).normalize();
        return switch (direction) {
            case "back" -> forward.multiply(-1.0D);
            case "left" -> right.multiply(-1.0D);
            case "right" -> right;
            default -> forward;
        };
    }

    private ExploreDirection chooseExploreDirection(int sectorSize, boolean avoidVisited) {
        if (client.player == null) {
            return null;
        }
        Vec3d pos = client.player.getPos();
        int sectorX = (int) Math.floor(pos.x / sectorSize);
        int sectorZ = (int) Math.floor(pos.z / sectorSize);
        String currentSector = sectorKey(sectorX, sectorZ);
        exploredSectors.add(currentSector);
        List<ExploreDirection> candidates = new ArrayList<>();
        candidates.add(new ExploreDirection("north", new Vec3d(0.0D, 0.0D, -1.0D), currentSector, sectorKey(sectorX, sectorZ - 1)));
        candidates.add(new ExploreDirection("east", new Vec3d(1.0D, 0.0D, 0.0D), currentSector, sectorKey(sectorX + 1, sectorZ)));
        candidates.add(new ExploreDirection("south", new Vec3d(0.0D, 0.0D, 1.0D), currentSector, sectorKey(sectorX, sectorZ + 1)));
        candidates.add(new ExploreDirection("west", new Vec3d(-1.0D, 0.0D, 0.0D), currentSector, sectorKey(sectorX - 1, sectorZ)));
        Collections.shuffle(candidates);
        if (!avoidVisited) {
            return candidates.get(0);
        }
        for (ExploreDirection candidate : candidates) {
            if (!exploredSectors.contains(candidate.targetSector())) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private String sectorKey(int x, int z) {
        return x + "," + z;
    }

    private ExecutorProtocol.StepResult equipGear(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or interaction manager is not ready.");
            }
            if (!action.stringField("gear_group").equals("combat")) {
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported gear group: " + action.stringField("gear_group"));
            }

            JsonObject data = new JsonObject();
            JsonArray prepared = new JsonArray();
            String weapon = bestAvailableCombatWeapon();
            if (!weapon.isBlank()) {
                ExecutorProtocol.StepResult ensured = ensureHotbarItem(weapon, 1, action.type());
                if ("accepted".equals(ensured.status())) {
                    JsonObject item = new JsonObject();
                    item.addProperty("item", weapon);
                    item.addProperty("role", "weapon");
                    prepared.add(item);
                }
            }

            if (countInventoryItem("minecraft:shield") > 0) {
                ExecutorProtocol.StepResult ensured = ensureHotbarItem("minecraft:shield", 1, action.type());
                if ("accepted".equals(ensured.status())) {
                    JsonObject item = new JsonObject();
                    item.addProperty("item", "minecraft:shield");
                    item.addProperty("role", "shield_hotbar");
                    prepared.add(item);
                }
            }

            for (String armor : bestArmorPieces()) {
                if (countInventoryItem(armor) <= 0) {
                    continue;
                }
                ExecutorProtocol.StepResult ensured = ensureHotbarItem(armor, 1, action.type());
                if (!"accepted".equals(ensured.status())) {
                    continue;
                }
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                client.player.swingHand(Hand.MAIN_HAND);
                sleepTicks(2);
                JsonObject item = new JsonObject();
                item.addProperty("item", armor);
                item.addProperty("role", "armor_use");
                prepared.add(item);
            }

            data.add("prepared", prepared);
            data.addProperty("prepared_count", prepared.size());
            data.addProperty("optional", action.booleanField("optional", true));
            return new ExecutorProtocol.StepResult(action.type(), "accepted", prepared.size() == 0 ? "No combat gear was available; optional equip skipped." : "Prepared available combat gear.", data);
        });
    }

    private ExecutorProtocol.StepResult ensureHotbar(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> ensureHotbarItem(action.stringField("item"), Math.max(1, action.intField("count", 1)), action.type()));
    }

    private ExecutorProtocol.StepResult openWorkstation(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }

            String station = action.stringField("station");
            int radius = Math.max(1, Math.min(action.intField("search_radius", 8), config.maxScanRadius()));
            boolean allowPlace = action.booleanField("allow_place", true);
            boolean avoidOccupied = action.booleanField("avoid_occupied", true);
            if (!station.equals("minecraft:crafting_table") && !station.equals("minecraft:furnace") && !station.equals("minecraft:stonecutter") && !station.equals("minecraft:smithing_table")) {
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported workstation: " + station);
            }

            if (isExpectedScreenOpen(station)) {
                JsonObject data = new JsonObject();
                data.addProperty("station", station);
                data.addProperty("already_open", true);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Requested workstation screen is already open.", data);
            }
            closeCurrentScreenForWorldInteraction();

            BlockPos existing = findNearestWorkstation(station, radius, avoidOccupied);
            if (existing != null) {
                ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(existing, config.maxReach(), 120, action.type());
                if (!"accepted".equals(approached.status())) {
                    return approached;
                }
                ExecutorProtocol.StepResult opened = interactWithWorkstation(existing, station, action.type(), "existing");
                if ("accepted".equals(opened.status())) {
                    return opened;
                }
            }

            if (!allowPlace) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No reachable available workstation found and placement is disabled.");
            }

            BlockHitResult placement = station.equals("minecraft:crafting_table") ? findNearbyUsableWorkstationPlacement(4) : findNearbySolidFloorPlacement(4);
            if (placement == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No reachable placement spot found for " + station + ".");
            }

            BlockPos placed = placement.getBlockPos().offset(placement.getSide());
            JsonObject placeData = new JsonObject();
            placeData.addProperty("station", station);
            addBlockPos(placeData, "target", placed);
            addBlockPos(placeData, "support", placement.getBlockPos());
            placeData.addProperty("preferred_side", placement.getSide().asString());
            boolean placedByInteraction = false;
            if (config.worldAssistWorkstationPlacementEnabled()) {
                ProgrammaticBlockPlacementResult primary = placeProgrammaticWorkstationBlock(station, placed);
                JsonObject primaryData = new JsonObject();
                primaryData.addProperty("placed", primary.placed());
                primaryData.addProperty("reason", primary.reason());
                primaryData.addProperty("world_assist", true);
                primaryData.addProperty("mode", "primary_near_player");
                primaryData.addProperty("consume_items_when_possible", config.worldAssistConsumeItemsWhenPossible());
                placeData.add("world_assist_workstation_placement", primaryData);
                if (primary.placed()) {
                    publishStatus("[World Assist] 作業台配置: " + shortMinecraftId(station));
                }
            }
            if (!blockIdAt(placed).equals(station)) {
                if (!selectHotbarItem(stationItem(station))) {
                    placeData.addProperty("place_error", "station_item_not_available_in_hotbar");
                    return new ExecutorProtocol.StepResult(action.type(), "blocked", station + " is not available in the hotbar for placement.", placeData);
                }
                placedByInteraction = placeWorkstationBlock(station, placed, placement, placeData);
            }
            if (!blockIdAt(placed).equals(station)) {
                boolean placedByGenericItemUse = placeItemAt(stationItem(station), placed);
                placeData.addProperty("placed_by_generic_item_use", placedByGenericItemUse);
                placeData.addProperty("target_block_after_generic_item_use", blockIdAt(placed));
            }
            if (!blockIdAt(placed).equals(station) && config.worldAssistWorkstationPlacementEnabled()) {
                ProgrammaticBlockPlacementResult programmatic = placeProgrammaticWorkstationBlock(station, placed);
                JsonObject fallback = new JsonObject();
                fallback.addProperty("placed", programmatic.placed());
                fallback.addProperty("reason", programmatic.reason());
                fallback.addProperty("world_assist", true);
                fallback.addProperty("consume_items_when_possible", config.worldAssistConsumeItemsWhenPossible());
                placeData.add("programmatic_workstation_fallback", fallback);
                if (programmatic.placed()) {
                    publishStatus("[World Assist] 作業台配置: " + shortMinecraftId(station));
                }
            }
            if (!blockIdAt(placed).equals(station)) {
                BlockPos found = findNearestWorkstation(station, 3, false);
                if (found == null) {
                    placeData.addProperty("placed_by_interaction", placedByInteraction);
                    return new ExecutorProtocol.StepResult(action.type(), "blocked", "Placed workstation could not be confirmed.", placeData);
                }
                placed = found;
                addBlockPos(placeData, "fallback_found", placed);
            }
            int blockSettleTicks = waitForBlockIdAt(placed, station, 8);
            placeData.addProperty("block_settle_ticks", blockSettleTicks);
            int postPlacementSettleTicks = station.equals("minecraft:crafting_table") ? 10 : 6;
            sleepTicks(postPlacementSettleTicks);
            placeData.addProperty("post_placement_settle_ticks", postPlacementSettleTicks);
            ExecutorProtocol.StepResult approachedPlaced = moveWithinReachOfBlock(placed, config.maxReach(), 80, action.type());
            if (!"accepted".equals(approachedPlaced.status())) {
                if (approachedPlaced.data() != null) {
                    approachedPlaced.data().add("placement", placeData);
                }
                return approachedPlaced;
            }
            ExecutorProtocol.StepResult opened = interactWithWorkstation(placed, station, action.type(), "placed");
            if (opened.data() != null) {
                opened.data().add("placement", placeData);
            }
            return opened;
        });
    }

    private void closeCurrentScreenForWorldInteraction() {
        if (client.player == null || client.currentScreen == null) {
            return;
        }
        client.player.closeHandledScreen();
        waitForHandledScreenClosed(8);
    }

    private boolean placeWorkstationBlock(String station, BlockPos target, BlockHitResult preferredHit, JsonObject data) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            data.addProperty("place_error", "player_world_or_interaction_manager_not_ready");
            return false;
        }
        if (!selectHotbarItem(stationItem(station))) {
            data.addProperty("place_error", "station_item_not_selectable");
            return false;
        }
        data.addProperty("station_item_count", countInventoryItem(stationItem(station)));

        List<BlockHitResult> hits = new ArrayList<>();
        addWorkstationPlacementHits(hits, target, preferredHit);

        JsonArray attempts = new JsonArray();
        int attemptNumber = 1;
        boolean[] sneakModes = {false, true};
        for (int i = 0; i < hits.size(); i++) {
            BlockHitResult hit = hits.get(i);
            for (boolean sneak : sneakModes) {
                JsonObject attempt = new JsonObject();
                attempt.addProperty("attempt", attemptNumber++);
                attempt.addProperty("hit_index", i + 1);
                attempt.addProperty("sneak", sneak);
                addBlockPos(attempt, "hit_block", hit.getBlockPos());
                attempt.addProperty("side", hit.getSide().asString());
                attempt.add("hit_pos", vec(hit.getPos().x, hit.getPos().y, hit.getPos().z));
                lookAtPosition(hit.getPos());
                client.options.sneakKey.setPressed(sneak);
                if (sneak) {
                    sleepTicks(1);
                }
                try {
                    var interaction = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                    attempt.addProperty("interaction_result", String.valueOf(interaction));
                    client.player.swingHand(Hand.MAIN_HAND);
                    sleepTicks(5);
                } finally {
                    client.options.sneakKey.setPressed(false);
                }
                attempt.addProperty("target_block_after", blockIdAt(target));
                attempt.addProperty("placed", blockIdAt(target).equals(station));
                attempts.add(attempt);
                if (blockIdAt(target).equals(station)) {
                    data.add("placement_attempts", attempts);
                    return true;
                }
            }
        }
        data.add("placement_attempts", attempts);
        return false;
    }

    private void addWorkstationPlacementHits(List<BlockHitResult> hits, BlockPos target, BlockHitResult preferredHit) {
        addUniquePlacementHit(hits, preferredHit);
        addUniquePlacementHit(hits, placementHitFor(target));
        if (client.world == null) {
            return;
        }
        BlockPos support = target.down();
        if (!client.world.getBlockState(support).isAir()) {
            double[][] offsets = {
                {0.0D, 0.0D},
                {0.24D, 0.0D},
                {-0.24D, 0.0D},
                {0.0D, 0.24D},
                {0.0D, -0.24D}
            };
            for (double[] offset : offsets) {
                addUniquePlacementHit(hits, new BlockHitResult(Vec3d.ofCenter(support).add(offset[0], 0.5D, offset[1]), Direction.UP, support, false));
            }
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = target.offset(direction);
            if (client.world.getBlockState(neighbor).isAir()) {
                continue;
            }
            Direction side = direction.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(neighbor).add(side.getOffsetX() * 0.5D, side.getOffsetY() * 0.5D, side.getOffsetZ() * 0.5D);
            addUniquePlacementHit(hits, new BlockHitResult(hitPos, side, neighbor, false));
        }
    }

    private void addUniquePlacementHit(List<BlockHitResult> hits, BlockHitResult hit) {
        if (hit == null) {
            return;
        }
        for (BlockHitResult existing : hits) {
            if (existing.getBlockPos().equals(hit.getBlockPos())
                && existing.getSide() == hit.getSide()
                && existing.getPos().squaredDistanceTo(hit.getPos()) < 0.0001D) {
                return;
            }
        }
        hits.add(hit);
    }

    private ExecutorProtocol.StepResult closeScreen(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player is not ready.");
            }
            client.player.closeHandledScreen();
            JsonObject data = new JsonObject();
            data.addProperty("closed", true);
            data.addProperty("close_wait_ticks", waitForHandledScreenClosed(8));
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Closed current handled screen.", data);
        });
    }

    private ExecutorProtocol.StepResult craftPlanksFromLog(ExecutorProtocol.Action action) {
        int minPlanks = Math.max(1, Math.min(action.intField("min_planks", 4), 64));
        int maxCrafts = Math.max(1, Math.min(action.intField("max_crafts", 4), 16));
        JsonObject data = new JsonObject();
        data.addProperty("min_planks", minPlanks);
        data.addProperty("starting_planks", callOnClientThread(() -> countInventoryGroup("planks")));
        int crafted = 0;

        while (callOnClientThread(() -> countInventoryGroup("planks")) < minPlanks && crafted < maxCrafts && !aborted) {
            String recipe = callOnClientThread(this::bestAvailablePlanksRecipeFromLog);
            if (recipe.isBlank()) {
                data.addProperty("crafted_count", crafted);
                data.addProperty("ending_planks", callOnClientThread(() -> countInventoryGroup("planks")));
                return new ExecutorProtocol.StepResult(action.type(), crafted > 0 ? "partial" : "blocked", "No supported log item is available for plank crafting.", data);
            }
            ExecutorProtocol.StepResult one = callOnClientThread(() -> craftOnce(action.type(), recipe));
            if (!"accepted".equals(one.status())) {
                data.addProperty("recipe", recipe);
                data.addProperty("crafted_count", crafted);
                data.addProperty("ending_planks", callOnClientThread(() -> countInventoryGroup("planks")));
                data.addProperty("last_status", one.status());
                data.addProperty("last_message", one.message());
                if (one.data() != null) {
                    data.add("last_data", one.data());
                }
                return new ExecutorProtocol.StepResult(action.type(), crafted > 0 ? "partial" : one.status(), one.message(), data);
            }
            crafted += stepOutputCount(one, recipe);
            sleepTicks(2);
        }

        data.addProperty("crafted_count", crafted);
        data.addProperty("ending_planks", callOnClientThread(() -> countInventoryGroup("planks")));
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Dynamic plank crafting was interrupted.", data);
        }
        return new ExecutorProtocol.StepResult(
            action.type(),
            callOnClientThread(() -> countInventoryGroup("planks")) >= minPlanks ? "accepted" : "partial",
            "Crafted planks from the available log type.",
            data
        );
    }

    private ExecutorProtocol.StepResult craft(ExecutorProtocol.Action action) {
        String recipe = action.stringField("recipe");
        int count = Math.max(1, Math.min(action.intField("count", 1), 64));
        int crafted = 0;
        JsonObject data = new JsonObject();
        data.addProperty("recipe", recipe);
        data.addProperty("requested_count", count);

        if (!isKnownCraftRecipe(recipe)) {
            data.addProperty("vanilla_scope", "dictionary_target_requires_bundled_shaped_shapeless_or_stonecutting_recipe");
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unknown or unsupported recipe for executor: " + recipe, data);
        }

        while (crafted < count && !aborted) {
            int beforeAttempt = callOnClientThread(() -> countInventoryItem(recipe));
            String expectedStation = action.stringField("expected_station");
            ExecutorProtocol.StepResult one = callOnClientThread(() -> craftOnce(action.type(), recipe, expectedStation));
            if (!"accepted".equals(one.status())) {
                ExecutorProtocol.StepResult fallback = tryWorldAssistCraftFallbackOnce(action.type(), recipe, beforeAttempt, one);
                if ("accepted".equals(fallback.status())) {
                    one = fallback;
                }
            }
            if (!"accepted".equals(one.status())) {
                data.addProperty("crafted_count", crafted);
                data.addProperty("last_status", one.status());
                data.addProperty("last_message", one.message());
                if (one.data() != null) {
                    data.add("last_data", one.data());
                }
                return new ExecutorProtocol.StepResult(action.type(), crafted > 0 ? "partial" : one.status(), one.message(), data);
            }
            crafted += stepOutputCount(one, recipe);
            sleepTicks(2);
        }

        data.addProperty("crafted_count", crafted);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Crafting was interrupted.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Crafted recipe using survival UI slot operations.", data);
    }

    private ExecutorProtocol.StepResult smelt(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }

        String input = action.stringField("input");
        String fuel = action.stringField("fuel");
        String output = action.stringField("output");
        int count = Math.max(1, Math.min(action.intField("count", 1), 16));
        int timeoutTicks = Math.max(40, Math.min(action.intField("timeout_ticks", 240), 1200));
        JsonObject data = new JsonObject();
        data.addProperty("input", input);
        data.addProperty("fuel", fuel);
        data.addProperty("output", output);
        data.addProperty("requested_count", count);

        if (!isKnownSmeltOutput(input, output)) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported smelting pair for executor: " + input + " -> " + output, data);
        }

        int produced = 0;
        for (int i = 0; i < count && !aborted; i++) {
            ExecutorProtocol.StepResult prepared = callOnClientThread(() -> prepareFurnaceOnce(input, fuel, output));
            if (!"accepted".equals(prepared.status())) {
                data.addProperty("produced_count", produced);
                data.addProperty("last_status", prepared.status());
                data.addProperty("last_message", prepared.message());
                if (prepared.data() != null) {
                    data.add("last_data", prepared.data());
                }
                return new ExecutorProtocol.StepResult(action.type(), produced > 0 ? "partial" : prepared.status(), prepared.message(), data);
            }

            ExecutorProtocol.StepResult taken = waitForFurnaceOutput(output, timeoutTicks);
            if (!"accepted".equals(taken.status())) {
                data.addProperty("produced_count", produced);
                data.addProperty("last_status", taken.status());
                data.addProperty("last_message", taken.message());
                return new ExecutorProtocol.StepResult(action.type(), produced > 0 ? "partial" : taken.status(), taken.message(), data);
            }
            produced++;
        }

        data.addProperty("produced_count", produced);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Smelting was interrupted.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Smelted requested output using furnace UI slot operations.", data);
    }

    private ExecutorProtocol.StepResult smeltFood(ExecutorProtocol.Action action) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }
        int count = Math.max(1, Math.min(action.intField("count", 1), 8));
        int timeoutTicks = Math.max(40, Math.min(action.intField("timeout_ticks", 240), 1200));
        boolean optional = action.booleanField("optional", false);
        JsonObject data = new JsonObject();
        data.addProperty("requested_count", count);
        data.addProperty("optional", optional);

        int produced = 0;
        JsonArray cooked = new JsonArray();
        for (int i = 0; i < count && !aborted; i++) {
            String input = callOnClientThread(this::findAvailableRawFoodItem);
            if (input.isBlank()) {
                break;
            }
            String output = cookedFoodOutput(input);
            ExecutorProtocol.StepResult prepared = callOnClientThread(() -> prepareFurnaceOnce(input, "", output));
            if (!"accepted".equals(prepared.status())) {
                data.addProperty("produced_count", produced);
                data.addProperty("last_status", prepared.status());
                data.addProperty("last_message", prepared.message());
                if (optional && produced == 0) {
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional food smelting skipped because a furnace route was not ready.", data);
                }
                return new ExecutorProtocol.StepResult(action.type(), produced > 0 ? "partial" : prepared.status(), "Could not prepare furnace for food smelting.", data);
            }
            ExecutorProtocol.StepResult taken = waitForFurnaceOutput(output, timeoutTicks);
            if (!"accepted".equals(taken.status())) {
                data.addProperty("produced_count", produced);
                data.addProperty("last_status", taken.status());
                data.addProperty("last_message", taken.message());
                if (optional && produced == 0) {
                    return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional food smelting skipped because cooked output was not ready.", data);
                }
                return new ExecutorProtocol.StepResult(action.type(), produced > 0 ? "partial" : taken.status(), "Could not collect cooked food output.", data);
            }
            JsonObject one = new JsonObject();
            one.addProperty("input", input);
            one.addProperty("output", output);
            cooked.add(one);
            produced++;
        }

        data.addProperty("produced_count", produced);
        data.add("cooked", cooked);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Food smelting was interrupted.", data);
        }
        if (produced == 0) {
            if (optional) {
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Optional food smelting skipped because no supported raw food is available.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No supported raw food item is available for smelting.", data);
        }
        return new ExecutorProtocol.StepResult(action.type(), produced < count ? "partial" : "accepted", "Cooked available raw food using furnace UI slot operations.", data);
    }

    private ExecutorProtocol.StepResult eatFood(ExecutorProtocol.Action action) {
        if (client.player == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or interaction manager is not ready.");
        }
        String requestedItem = action.stringField("item");
        int minHunger = Math.max(1, Math.min(action.intField("min_hunger", 14), 20));
        int timeoutTicks = Math.max(20, Math.min(action.intField("timeout_ticks", 160), 400));
        JsonObject data = new JsonObject();
        data.addProperty("requested_item", requestedItem);
        data.addProperty("min_hunger", minHunger);
        data.addProperty("starting_hunger", client.player.getHungerManager().getFoodLevel());

        if (client.player.getHungerManager().getFoodLevel() >= minHunger) {
            data.addProperty("ending_hunger", client.player.getHungerManager().getFoodLevel());
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Hunger is already above the requested threshold.", data);
        }

        String foodItem = requestedItem.isBlank() ? findAvailableFoodItem() : requestedItem;
        if (foodItem.isBlank() || !isKnownFood(foodItem)) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No supported food item is available in inventory.", data);
        }

        ExecutorProtocol.StepResult ensured = callOnClientThread(() -> ensureHotbarItem(foodItem, 1, action.type()));
        if (!"accepted".equals(ensured.status())) {
            if (ensured.data() != null) {
                data.add("ensure_hotbar", ensured.data());
            }
            return new ExecutorProtocol.StepResult(action.type(), ensured.status(), "Food could not be moved to the hotbar.", data);
        }

        callOnClientThread(() -> {
            if (client.player != null && client.interactionManager != null) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                client.options.useKey.setPressed(true);
            }
            return true;
        });

        int ticks = 0;
        while (ticks < timeoutTicks && !aborted) {
            if (ticks % 10 == 0 && callOnClientThread(this::hasImmediateAbortDanger)) {
                releaseKeys();
                data.addProperty("food_item", foodItem);
                data.addProperty("ticks", ticks);
                data.addProperty("ending_hunger", client.player == null ? 0 : client.player.getHungerManager().getFoodLevel());
                data.addProperty("danger_stop", true);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Stopped eating because an immediate survival danger appeared.", data);
            }
            int hunger = callOnClientThread(() -> client.player == null ? 0 : client.player.getHungerManager().getFoodLevel());
            if (hunger >= minHunger) {
                releaseKeys();
                data.addProperty("food_item", foodItem);
                data.addProperty("ticks", ticks);
                data.addProperty("ending_hunger", hunger);
                return new ExecutorProtocol.StepResult(action.type(), "accepted", "Ate food until hunger reached the requested threshold.", data);
            }
            callOnClientThread(() -> {
                if (client.options != null) {
                    client.options.useKey.setPressed(true);
                }
                return true;
            });
            sleepTicks(1);
            ticks++;
        }

        releaseKeys();
        data.addProperty("food_item", foodItem);
        data.addProperty("ticks", ticks);
        data.addProperty("ending_hunger", client.player == null ? 0 : client.player.getHungerManager().getFoodLevel());
        return new ExecutorProtocol.StepResult(action.type(), aborted ? "aborted" : "blocked", aborted ? "Eating was interrupted." : "Timed out before hunger improved enough.", data);
    }

    private ExecutorProtocol.StepResult lookAt(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player is not ready.");
            }

            JsonObject target = action.objectField("target");
            double x = numberField(target, "x", Double.NaN);
            double y = numberField(target, "y", Double.NaN);
            double z = numberField(target, "z", Double.NaN);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "look_at target coordinates are required.");
            }

            Vec3d eye = client.player.getEyePos();
            double dx = x - eye.x;
            double dy = y - eye.y;
            double dz = z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0F / Math.PI) - 90.0F;
            float pitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0F / Math.PI);
            client.player.setYaw(yaw);
            client.player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));

            JsonObject data = new JsonObject();
            data.addProperty("yaw", yaw);
            data.addProperty("pitch", pitch);
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Adjusted player view toward target.", data);
        });
    }

    private ExecutorProtocol.StepResult breakBlock(ExecutorProtocol.Action action) {
        return callOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }

            double maxDistance = Math.max(1.0D, Math.min(action.doubleField("max_distance", config.maxReach()), HARD_MAX_BREAK_DISTANCE));
            BlockHitResult target = resolveBreakTarget(action);
            if (target == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No breakable block target is available.");
            }

            JsonObject data = new JsonObject();
            data.addProperty("x", target.getBlockPos().getX());
            data.addProperty("y", target.getBlockPos().getY());
            data.addProperty("z", target.getBlockPos().getZ());
            data.addProperty("max_distance", maxDistance);
            return new ExecutorProtocol.StepResult(action.type(), "ready", "Break target resolved.", data);
        });
    }

    private ExecutorProtocol.StepResult executeBreakBlock(ExecutorProtocol.Action action) {
        ExecutorProtocol.StepResult resolved = breakBlock(action);
        if (!"ready".equals(resolved.status()) || resolved.data() == null) {
            return resolved;
        }
        JsonObject data = resolved.data();
        BlockPos pos = new BlockPos(data.get("x").getAsInt(), data.get("y").getAsInt(), data.get("z").getAsInt());
        double maxDistance = data.get("max_distance").getAsDouble();
        return breakBlockAt(pos, maxDistance, action.intField("timeout_ticks", config.defaultBreakTimeoutTicks()), action.type());
    }

    private ExecutorProtocol.StepResult breakBlockAt(BlockPos pos, double maxDistance, int timeoutTicks, String actionType) {
        double breakReach = effectiveBreakReach(maxDistance);
        ExecutorProtocol.StepResult start = callOnClientThread(() -> startBreaking(pos, breakReach, actionType));
        if (!"accepted".equals(start.status())) {
            ExecutorProtocol.StepResult assisted = tryWorldAssistBreakFallback(pos, breakReach, actionType, start, "start_break_failed");
            if (assisted != null) {
                return assisted;
            }
            return start;
        }

        int boundedTimeout = Math.max(20, Math.min(timeoutTicks, 400));
        int ticks = 0;
        int aimMissTicks = 0;
        int noBlockProgressTicks = 0;
        JsonObject lastAimMiss = null;
        String lastBlockStateKey = callOnClientThread(() -> blockProgressKey(pos));
        while (ticks < boundedTimeout && !aborted) {
            if (ticks % 10 == 0 && callOnClientThread(this::hasImmediateAbortDanger)) {
                JsonObject data = start.data() == null ? new JsonObject() : start.data();
                data.addProperty("ticks", ticks);
                data.addProperty("danger_stop", true);
                releaseKeys();
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Stopped block breaking because an immediate survival danger appeared.", data);
            }
            if (ticks % 5 == 0) {
                ExecutorProtocol.StepResult reach = callOnClientThread(() -> validateBreakReach(pos, breakReach, actionType));
                if (!"accepted".equals(reach.status())) {
                    JsonObject data = start.data() == null ? new JsonObject() : start.data();
                    data.addProperty("ticks", ticks);
                    if (reach.data() != null) {
                        data.add("reach_check", reach.data());
                    }
                    releaseKeys();
                    return new ExecutorProtocol.StepResult(actionType, "blocked", "Stopped block breaking because the target left survival reach.", data);
                }
            }
            boolean done = callOnClientThread(() -> client.world == null || client.world.getBlockState(pos).isAir());
            if (done) {
                JsonObject data = start.data() == null ? new JsonObject() : start.data();
                data.addProperty("ticks", ticks);
                data.addProperty("completed", true);
                releaseKeys();
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Broke target block with survival interaction.", data);
            }

            ExecutorProtocol.StepResult aim = callOnClientThread(() -> continueBreakingIfAimed(pos, breakReach, actionType));
            if (!"accepted".equals(aim.status())) {
                aimMissTicks++;
                if (aim.data() != null) {
                    lastAimMiss = aim.data();
                }
                if (aimMissTicks >= 8) {
                    JsonObject data = start.data() == null ? new JsonObject() : start.data();
                    data.addProperty("ticks", ticks);
                    data.addProperty("completed", false);
                    data.addProperty("aim_miss_ticks", aimMissTicks);
                    if (lastAimMiss != null) {
                        data.add("last_aim_check", lastAimMiss);
                    }
                    releaseKeys();
                    ExecutorProtocol.StepResult blocked = new ExecutorProtocol.StepResult(actionType, "blocked", "Stopped block breaking because the target block was not under the crosshair.", data);
                    ExecutorProtocol.StepResult assisted = tryWorldAssistBreakFallback(pos, breakReach, actionType, blocked, "aim_miss");
                    return assisted == null ? blocked : assisted;
                }
            } else {
                aimMissTicks = 0;
            }
            if (ticks > 0 && ticks % 10 == 0) {
                String currentBlockStateKey = callOnClientThread(() -> blockProgressKey(pos));
                double currentDistance = callOnClientThread(() -> client.player == null ? 0.0D : client.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)));
                if (currentBlockStateKey.equals(lastBlockStateKey)) {
                    noBlockProgressTicks += 10;
                } else {
                    noBlockProgressTicks = 0;
                    lastBlockStateKey = currentBlockStateKey;
                }
                if (noBlockProgressTicks >= 50 && currentDistance > EFFECTIVE_SURVIVAL_BREAK_REACH - 0.15D) {
                    JsonObject data = start.data() == null ? new JsonObject() : start.data();
                    data.addProperty("ticks", ticks);
                    data.addProperty("completed", false);
                    data.addProperty("aim_miss_ticks", aimMissTicks);
                    data.addProperty("no_block_progress_ticks", noBlockProgressTicks);
                    data.addProperty("distance", currentDistance);
                    data.addProperty("block_state_key", currentBlockStateKey);
                    if (lastAimMiss != null) {
                        data.add("last_aim_check", lastAimMiss);
                    }
                    releaseKeys();
                    ExecutorProtocol.StepResult blocked = new ExecutorProtocol.StepResult(actionType, "blocked", "Stopped block breaking because no progress was detected near the edge of reach.", data);
                    ExecutorProtocol.StepResult assisted = tryWorldAssistBreakFallback(pos, breakReach, actionType, blocked, "no_progress");
                    return assisted == null ? blocked : assisted;
                }
            }
            sleepTicks(1);
            ticks++;
        }

        releaseKeys();
        if (aborted) {
            return new ExecutorProtocol.StepResult(actionType, "aborted", "Block breaking was interrupted.", start.data());
        }
        JsonObject data = start.data() == null ? new JsonObject() : start.data();
        data.addProperty("ticks", ticks);
        data.addProperty("completed", false);
        data.addProperty("aim_miss_ticks", aimMissTicks);
        data.addProperty("no_block_progress_ticks", noBlockProgressTicks);
        if (lastAimMiss != null) {
            data.add("last_aim_check", lastAimMiss);
        }
        ExecutorProtocol.StepResult timedOut = new ExecutorProtocol.StepResult(actionType, "blocked", "Timed out before the target block was fully broken.", data);
        ExecutorProtocol.StepResult assisted = tryWorldAssistBreakFallback(pos, breakReach, actionType, timedOut, "timeout");
        return assisted == null ? timedOut : assisted;
    }

    private ExecutorProtocol.StepResult tryWorldAssistBreakFallback(
        BlockPos pos,
        double maxDistance,
        String actionType,
        ExecutorProtocol.StepResult original,
        String trigger
    ) {
        if (!shouldUseWorldAssistBreakFallback(pos, maxDistance, actionType, original, trigger)) {
            return null;
        }
        releaseKeys();
        JsonObject data = original.data() == null ? new JsonObject() : original.data();
        JsonObject assist = new JsonObject();
        assist.addProperty("trigger", trigger);
        assist.addProperty("world_assist", true);
        assist.addProperty("command_free", true);
        addBlockPos(assist, "target", pos);
        ProgrammaticBlockBreakResult result = breakProgrammaticCommonBlock(pos);
        assist.addProperty("broken", result.broken());
        assist.addProperty("block", result.blockId());
        assist.addProperty("drop_item", result.dropItem());
        assist.addProperty("drop_count", result.dropCount());
        assist.addProperty("inserted_count", result.insertedCount());
        assist.addProperty("spawned_count", result.spawnedCount());
        assist.addProperty("reason", result.reason());
        data.add("world_assist_direct_break", assist);
        if (!result.broken()) {
            return null;
        }
        publishStatus("[World Assist] 採掘補助: " + shortMinecraftId(result.blockId()));
        return new ExecutorProtocol.StepResult(
            actionType,
            "accepted",
            "Broke a common block with bounded world assist after survival aiming stalled.",
            data
        );
    }

    private boolean shouldUseWorldAssistBreakFallback(
        BlockPos pos,
        double maxDistance,
        String actionType,
        ExecutorProtocol.StepResult original,
        String trigger
    ) {
        if (!config.worldAssistEnabled() || aborted || client.player == null || client.world == null) {
            return false;
        }
        if (!Set.of("break_block", "collect_block", "dig_pattern", "move", "explore", "build_bridge", "open_passage").contains(actionType)) {
            return false;
        }
        if (pos.equals(client.player.getBlockPos()) || pos.equals(client.player.getBlockPos().down())) {
            return false;
        }
        double distance = client.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        if (distance > maxDistance + 0.25D) {
            return false;
        }
        String message = original == null || original.message() == null ? "" : original.message();
        boolean aimOrProgressFailure = trigger.equals("aim_miss")
            || trigger.equals("no_progress")
            || trigger.equals("timeout")
            || message.contains("crosshair")
            || message.contains("under the crosshair");
        if (!aimOrProgressFailure) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        return isWorldAssistDirectBreakAllowed(blockId, state, pos);
    }

    private ExecutorProtocol.StepResult startBreaking(BlockPos pos, double maxDistance, String actionType) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player, world, or interaction manager is not ready.");
        }
        if (client.world.getBlockState(pos).isAir()) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Target block is air.");
        }
        String blockId = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
        ExecutorProtocol.StepResult tool = selectRequiredToolForBlock(blockId, actionType);
        if (!"accepted".equals(tool.status())) {
            return tool;
        }

        ExecutorProtocol.StepResult reach = validateBreakReach(pos, maxDistance, actionType);
        if (!"accepted".equals(reach.status())) {
            return reach;
        }

        orientPlayerTowardBlock(pos);
        BlockHitResult aimed = raycastBreakTarget(pos, maxDistance);
        if (aimed == null) {
            JsonObject data = new JsonObject();
            data.addProperty("x", pos.getX());
            data.addProperty("y", pos.getY());
            data.addProperty("z", pos.getZ());
            data.addProperty("block", blockId);
            data.addProperty("distance", client.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)));
            data.addProperty("effective_max_distance", maxDistance);
            ExecutorProtocol.StepResult blocked = new ExecutorProtocol.StepResult(actionType, "blocked", "Target block is within reach but is not under the crosshair.", data);
            ExecutorProtocol.StepResult assisted = tryWorldAssistBreakFallback(pos, maxDistance, actionType, blocked, "aim_miss");
            return assisted == null ? blocked : assisted;
        }
        Direction side = aimed.getSide();
        client.interactionManager.attackBlock(pos, side);
        client.interactionManager.updateBlockBreakingProgress(pos, side);
        client.player.swingHand(Hand.MAIN_HAND);
        client.options.attackKey.setPressed(true);

        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("block", blockId);
        data.addProperty("distance", client.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)));
        data.addProperty("effective_max_distance", maxDistance);
        if (tool.data() != null && tool.data().has("selected_tool")) {
            data.addProperty("selected_tool", tool.data().get("selected_tool").getAsString());
        }
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Started breaking target block with survival interaction.", data);
    }

    private ExecutorProtocol.StepResult continueBreakingIfAimed(BlockPos pos, double maxDistance, String actionType) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            releaseMovementKeys();
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player, world, or interaction manager is not ready.");
        }
        orientPlayerTowardBlock(pos);
        BlockHitResult aimed = raycastBreakTarget(pos, maxDistance);
        if (aimed == null) {
            client.options.attackKey.setPressed(false);
            JsonObject data = new JsonObject();
            data.addProperty("target_x", pos.getX());
            data.addProperty("target_y", pos.getY());
            data.addProperty("target_z", pos.getZ());
            data.addProperty("distance", client.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)));
            HitResult hit = client.player.raycast(maxDistance, 0.0F, false);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                addBlockPos(data, "crosshair_block", blockHit.getBlockPos());
            } else {
                data.addProperty("crosshair_block", "none");
            }
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Crosshair is not on the target block.", data);
        }
        client.options.attackKey.setPressed(true);
        client.interactionManager.updateBlockBreakingProgress(pos, aimed.getSide());
        client.player.swingHand(Hand.MAIN_HAND);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Block breaking crosshair is aligned.");
    }

    private BlockHitResult raycastBreakTarget(BlockPos pos, double maxDistance) {
        if (client.player == null) {
            return null;
        }
        HitResult hit = client.player.raycast(maxDistance, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK && blockHit.getBlockPos().equals(pos)) {
            return blockHit;
        }
        return null;
    }

    private double effectiveBreakReach(double requestedReach) {
        return Math.max(1.0D, Math.min(Math.min(requestedReach, config.maxReach()), EFFECTIVE_SURVIVAL_BREAK_REACH));
    }

    private ExecutorProtocol.StepResult validateBreakReach(BlockPos pos, double maxDistance, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("max_distance", maxDistance);
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player or world is not ready.", data);
        }
        if (client.world.getBlockState(pos).isAir()) {
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Target block is already gone.", data);
        }
        double distance = client.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        data.addProperty("distance", distance);
        if (distance > maxDistance) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Target block is outside effective survival break reach.", data);
        }
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Target block is within effective survival break reach.", data);
    }

    private void orientPlayerTowardBlock(BlockPos pos) {
        if (client.player == null) {
            return;
        }
        Vec3d eye = client.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0F / Math.PI) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0F / Math.PI);
        client.player.setYaw(yaw);
        client.player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
    }

    private Direction breakSide(BlockPos pos) {
        if (client.player == null) {
            return Direction.UP;
        }
        Vec3d eye = client.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d delta = center.subtract(eye);
        return Direction.getFacing(delta.x, delta.y, delta.z).getOpposite();
    }

    private ExecutorProtocol.StepResult moveWithinReachOfBlock(BlockPos target, double maxDistance, int timeoutTicks, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("target_x", target.getX());
        data.addProperty("target_y", target.getY());
        data.addProperty("target_z", target.getZ());
        data.addProperty("max_distance", maxDistance);
        data.addProperty("path_aware", true);

        int ticks = 0;
        int stuckTicks = 0;
        int assistCount = 0;
        int exposureDigCount = 0;
        double lastDistance = Double.MAX_VALUE;
        while (ticks < timeoutTicks && !aborted) {
            if (ticks % 10 == 0 && callOnClientThread(this::hasImmediateAbortDanger)) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("danger_stop", true);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Stopped block approach because an immediate survival danger appeared.", data);
            }
            final Vec3d[] approachTarget = new Vec3d[1];
            final LocalNavigationPlan[] approachPlan = new LocalNavigationPlan[1];
            BlockMoveStatus status = callOnClientThread(() -> {
                if (client.player == null || client.world == null) {
                    return BlockMoveStatus.BLOCKED;
                }
                if (client.world.getBlockState(target).isAir()) {
                    return BlockMoveStatus.REACHED;
                }
                double distance = client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target));
                if (distance <= maxDistance * maxDistance && hasBreakLineOfSightFromCurrentPosition(target, maxDistance)) {
                    releaseMovementKeys();
                    return BlockMoveStatus.REACHED;
                }
                BlockApproachPlan approach = planBlockApproach(target, maxDistance);
                if (approach == null) {
                    return BlockMoveStatus.BLOCKED;
                }
                approachTarget[0] = approach.waypoint();
                approachPlan[0] = approach.navigation();
                return steerToward(approach.waypoint(), Math.sqrt(distance)) == DropMoveStatus.BLOCKED ? BlockMoveStatus.BLOCKED : BlockMoveStatus.MOVING;
            });
            if (approachPlan[0] != null && ticks % 10 == 0) {
                addLocalNavigationData(data, "approach_path", approachPlan[0]);
            }
            if (status == BlockMoveStatus.REACHED) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Moved within survival reach of target block.", data);
            }
            if (status == BlockMoveStatus.BLOCKED) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
            double distanceNow = callOnClientThread(() -> client.player == null ? 0.0D : client.player.getEyePos().distanceTo(Vec3d.ofCenter(target)));
            if (distanceNow + 0.05D >= lastDistance) {
                stuckTicks++;
            }
            lastDistance = distanceNow;
            if (stuckTicks >= 12 && assistCount < 6) {
                Vec3d assistTarget = approachTarget[0] == null ? Vec3d.ofCenter(target) : approachTarget[0];
                int maxApproachExposureAttempts = config.maxApproachExposureAttempts();
                ExecutorProtocol.StepResult assist = exposureDigCount < maxApproachExposureAttempts
                    ? exposeTargetBlock(target, maxDistance, actionType)
                    : new ExecutorProtocol.StepResult(actionType, "blocked", "Approach exposure dig budget is exhausted.");
                if ("accepted".equals(assist.status())) {
                    exposureDigCount++;
                }
                if (!"accepted".equals(assist.status())) {
                    assist = tryDownwardBlockApproachAssist(target, actionType);
                }
                if (!"accepted".equals(assist.status())) {
                    assist = tryMovementAssistToward(assistTarget, actionType);
                }
                if ("accepted".equals(assist.status())) {
                    assistCount++;
                    stuckTicks = 0;
                    if (assist.data() != null) {
                        data.add("assist_" + assistCount, assist.data());
                    }
                    sleepTicks(2);
                    ticks += 2;
                    continue;
                }
                if (assist.data() != null) {
                    data.add("assist_failed", assist.data());
                    data.addProperty("assist_message", assist.message());
                }
            }
            if (stuckTicks >= 24) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("stuck_ticks", stuckTicks);
                data.addProperty("assist_count", assistCount);
                data.addProperty("exposure_dig_count", exposureDigCount);
                data.addProperty("max_approach_exposure_attempts", config.maxApproachExposureAttempts());
                rememberUnreachableBlockTarget(target);
                data.addProperty("unreachable_target_ttl_ms", UNREACHABLE_TARGET_TTL_MS);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe local path within reach of target block was found.", data);
            }
            sleepTicks(1);
            ticks++;
        }
        releaseKeys();
        data.addProperty("ticks", ticks);
        data.addProperty("assist_count", assistCount);
        data.addProperty("exposure_dig_count", exposureDigCount);
        data.addProperty("max_approach_exposure_attempts", config.maxApproachExposureAttempts());
        if (!aborted) {
            rememberUnreachableBlockTarget(target);
            data.addProperty("unreachable_target_ttl_ms", UNREACHABLE_TARGET_TTL_MS);
        }
        return new ExecutorProtocol.StepResult(actionType, aborted ? "aborted" : "blocked", aborted ? "Block approach was interrupted." : "Timed out before moving within reach of target block.", data);
    }

    private ExecutorProtocol.StepResult exposeTargetBlock(BlockPos target, double maxDistance, String actionType) {
        BlockPos obstruction = callOnClientThread(() -> firstBreakLineObstruction(target, maxDistance));
        JsonObject data = new JsonObject();
        addBlockPos(data, "target", target);
        if (obstruction == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No exposure obstruction was found.", data);
        }
        addBlockPos(data, "obstruction", obstruction);
        String blockId = callOnClientThread(() -> blockIdAt(obstruction));
        data.addProperty("obstruction_block", blockId);
        if (!callOnClientThread(() -> isExposureDiggableBlock(obstruction, target))) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Exposure obstruction is not safe to dig automatically.", data);
        }
        ExecutorProtocol.StepResult result = breakBlockAt(obstruction, maxDistance, 80, actionType);
        data.addProperty("dig_status", result.status());
        data.addProperty("dig_message", result.message());
        if (result.data() != null) {
            data.add("dig_result", result.data());
        }
        if (!"accepted".equals(result.status())) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Could not dig the exposure obstruction.", data);
        }
        boolean visible = callOnClientThread(() -> hasBreakLineOfSightFromCurrentPosition(target, maxDistance));
        data.addProperty("target_visible_after", visible);
        return new ExecutorProtocol.StepResult(actionType, "accepted", visible ? "Dug a foreground block and exposed the target block." : "Dug a foreground block while approaching the target block.", data);
    }

    private Vec3d nearestApproachPointForBlock(BlockPos target) {
        if (client.player == null) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -4; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos candidate = target.add(x, y, z);
                    if (!isStandableBlockPos(candidate)) {
                        continue;
                    }
                    double reachDistance = Vec3d.ofBottomCenter(candidate).add(0.0D, client.player.getEyeHeight(client.player.getPose()), 0.0D).squaredDistanceTo(Vec3d.ofCenter(target));
                    if (reachDistance > config.maxReach() * config.maxReach()) {
                        continue;
                    }
                    if (!hasBreakLineOfSightFrom(candidate, target, config.maxReach())) {
                        continue;
                    }
                    double playerDistance = client.player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(candidate));
                    if (playerDistance < bestDistance) {
                        bestDistance = playerDistance;
                        best = candidate;
                    }
                }
            }
        }
        return best == null ? null : Vec3d.ofBottomCenter(best);
    }

    private BlockApproachPlan planBlockApproach(BlockPos target, double maxDistance) {
        if (client.player == null || client.world == null) {
            return null;
        }
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (int y = -4; y <= 2; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos candidate = target.add(x, y, z);
                    if (!isStandableBlockPos(candidate) || isDangerNear(candidate, 1)) {
                        continue;
                    }
                    double reachDistance = Vec3d.ofBottomCenter(candidate)
                        .add(0.0D, client.player.getEyeHeight(client.player.getPose()), 0.0D)
                        .squaredDistanceTo(Vec3d.ofCenter(target));
                    if (reachDistance > maxDistance * maxDistance) {
                        continue;
                    }
                    if (!hasBreakLineOfSightFrom(candidate, target, maxDistance)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(
            client.player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(a)),
            client.player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(b))
        ));

        BlockApproachPlan best = null;
        int checked = 0;
        for (BlockPos candidate : candidates) {
            if (checked++ >= 10) {
                break;
            }
            double distance = client.player.getPos().distanceTo(Vec3d.ofBottomCenter(candidate));
            LocalNavigationPlan navigation = planLocalNavigation(
                client.player.getPos(),
                candidate,
                0,
                Math.max(6, Math.min(16, (int) Math.ceil(distance) + 4)),
                4,
                768
            );
            if (!navigation.reached() || navigation.path().isEmpty()) {
                continue;
            }
            double score = navigation.cost()
                + client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) * 0.01D
                + Math.abs(candidate.getY() - client.player.getBlockY()) * 0.4D;
            BlockApproachPlan plan = new BlockApproachPlan(candidate, navigation, score);
            if (best == null || plan.score() < best.score()) {
                best = plan;
            }
        }
        return best;
    }

    private boolean hasBreakLineOfSightFromCurrentPosition(BlockPos target, double maxDistance) {
        if (client.player == null) {
            return false;
        }
        return hasBreakLineOfSight(client.player.getEyePos(), target, maxDistance);
    }

    private boolean hasBreakLineOfSightFrom(BlockPos standPos, BlockPos target, double maxDistance) {
        if (client.player == null) {
            return false;
        }
        Vec3d eye = Vec3d.ofBottomCenter(standPos).add(0.0D, client.player.getEyeHeight(client.player.getPose()), 0.0D);
        return hasBreakLineOfSight(eye, target, maxDistance);
    }

    private boolean hasBreakLineOfSight(Vec3d eye, BlockPos target, double maxDistance) {
        if (client.world == null || eye.distanceTo(Vec3d.ofCenter(target)) > maxDistance) {
            return false;
        }
        BlockHitResult hit = client.world.raycast(new RaycastContext(
            eye,
            Vec3d.ofCenter(target),
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private BlockPos firstBreakLineObstruction(BlockPos target, double maxDistance) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Vec3d eye = client.player.getEyePos();
        if (eye.distanceTo(Vec3d.ofCenter(target)) > maxDistance) {
            return null;
        }
        BlockHitResult hit = client.world.raycast(new RaycastContext(
            eye,
            Vec3d.ofCenter(target),
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) {
            return null;
        }
        return hit.getBlockPos();
    }

    private boolean isExposureDiggableBlock(BlockPos obstruction, BlockPos target) {
        if (client.player == null || client.world == null) {
            return false;
        }
        if (obstruction.equals(target) || obstruction.equals(client.player.getBlockPos()) || obstruction.equals(client.player.getBlockPos().down())) {
            return false;
        }
        BlockState state = client.world.getBlockState(obstruction);
        if (state.isAir() || isUnsafeFluid(obstruction) || state.getHardness(client.world, obstruction) < 0.0F) {
            return false;
        }
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        return blockId.equals("minecraft:dirt")
            || blockId.equals("minecraft:grass_block")
            || blockId.equals("minecraft:coarse_dirt")
            || blockId.equals("minecraft:rooted_dirt")
            || blockId.equals("minecraft:podzol")
            || blockId.equals("minecraft:mycelium")
            || blockId.equals("minecraft:gravel")
            || blockId.equals("minecraft:sand")
            || blockId.equals("minecraft:red_sand")
            || blockId.equals("minecraft:clay")
            || blockId.equals("minecraft:short_grass")
            || blockId.equals("minecraft:tall_grass")
            || blockId.equals("minecraft:fern")
            || blockId.equals("minecraft:large_fern");
    }

    private boolean isMovementAssistDiggableBlock(BlockPos target) {
        if (client.player == null || client.world == null) {
            return false;
        }
        if (target.equals(client.player.getBlockPos()) || target.equals(client.player.getBlockPos().down())) {
            return false;
        }
        BlockState state = client.world.getBlockState(target);
        if (state.isAir() || isUnsafeFluid(target) || state.getHardness(client.world, target) < 0.0F) {
            return false;
        }
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        return blockId.equals("minecraft:dirt")
            || blockId.equals("minecraft:grass_block")
            || blockId.equals("minecraft:coarse_dirt")
            || blockId.equals("minecraft:rooted_dirt")
            || blockId.equals("minecraft:podzol")
            || blockId.equals("minecraft:mycelium")
            || blockId.equals("minecraft:gravel")
            || blockId.equals("minecraft:sand")
            || blockId.equals("minecraft:red_sand")
            || blockId.equals("minecraft:clay")
            || blockId.equals("minecraft:short_grass")
            || blockId.equals("minecraft:tall_grass")
            || blockId.equals("minecraft:fern")
            || blockId.equals("minecraft:large_fern")
            || blockId.equals("minecraft:snow")
            || blockId.equals("minecraft:snow_block")
            || blockId.endsWith("_leaves");
    }

    private BlockPos findNearestMatchingBlock(int radius, String exactBlock, String group, double maxDistance) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        pruneBlockSearchCache();
        pruneUnreachableBlockTargets();
        String cacheKey = blockSearchCacheKey(origin, radius, exactBlock, group, maxDistance);
        BlockSearchCacheEntry cached = blockSearchCache.get(cacheKey);
        if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
            if (!cached.found()) {
                return null;
            }
            if (!isRecentUnreachableBlockTarget(cached.pos()) && isCachedBlockSearchResultUsable(cached.pos(), exactBlock, group, maxDistance)) {
                return cached.pos();
            }
            blockSearchCache.remove(cacheKey);
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -Math.min(radius, 8); y <= Math.min(radius, 8); y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    var state = client.world.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (isRecentUnreachableBlockTarget(pos)) {
                        continue;
                    }
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    boolean matchesExact = !exactBlock.isBlank() && exactBlock.equals(blockId);
                    boolean matchesGroup = !group.isBlank() && BlockGroups.matches(group, blockId);
                    if (!matchesExact && !matchesGroup) {
                        continue;
                    }
                    if (group.equals("crop") && !isMatureFoodCrop(state, blockId)) {
                        continue;
                    }
                    double distance = client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distance > maxDistance * maxDistance) {
                        continue;
                    }
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        rememberBlockSearch(cacheKey, best);
        return best;
    }

    private String blockSearchCacheKey(BlockPos origin, int radius, String exactBlock, String group, double maxDistance) {
        int bucket = BLOCK_SEARCH_CACHE_BUCKET_SIZE;
        int bx = Math.floorDiv(origin.getX(), bucket);
        int by = Math.floorDiv(origin.getY(), bucket);
        int bz = Math.floorDiv(origin.getZ(), bucket);
        String distanceKey = Double.isInfinite(maxDistance) ? "inf" : Integer.toString((int) Math.ceil(maxDistance));
        return bx + "," + by + "," + bz + "|r=" + radius + "|d=" + distanceKey + "|b=" + exactBlock + "|g=" + group;
    }

    private boolean isCachedBlockSearchResultUsable(BlockPos pos, String exactBlock, String group, double maxDistance) {
        if (client.player == null || client.world == null || pos == null) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        boolean matchesExact = !exactBlock.isBlank() && exactBlock.equals(blockId);
        boolean matchesGroup = !group.isBlank() && BlockGroups.matches(group, blockId);
        if (!matchesExact && !matchesGroup) {
            return false;
        }
        if (group.equals("crop") && !isMatureFoodCrop(state, blockId)) {
            return false;
        }
        return client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= maxDistance * maxDistance;
    }

    private void rememberBlockSearch(String cacheKey, BlockPos pos) {
        if (blockSearchCache.size() >= MAX_BLOCK_SEARCH_CACHE_ENTRIES) {
            pruneBlockSearchCache();
            if (blockSearchCache.size() >= MAX_BLOCK_SEARCH_CACHE_ENTRIES) {
                blockSearchCache.clear();
            }
        }
        blockSearchCache.put(cacheKey, new BlockSearchCacheEntry(pos, System.currentTimeMillis() + BLOCK_SEARCH_CACHE_TTL_MS, pos != null));
    }

    private void pruneBlockSearchCache() {
        long now = System.currentTimeMillis();
        blockSearchCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
    }

    private void rememberUnreachableBlockTarget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        pruneUnreachableBlockTargets();
        if (unreachableBlockTargets.size() >= MAX_UNREACHABLE_TARGET_ENTRIES) {
            unreachableBlockTargets.clear();
        }
        unreachableBlockTargets.put(blockTargetKey(pos), System.currentTimeMillis() + UNREACHABLE_TARGET_TTL_MS);
        blockSearchCache.clear();
    }

    private boolean isRecentUnreachableBlockTarget(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        pruneUnreachableBlockTargets();
        Long expiresAt = unreachableBlockTargets.get(blockTargetKey(pos));
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private void pruneUnreachableBlockTargets() {
        long now = System.currentTimeMillis();
        unreachableBlockTargets.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private String blockTargetKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private BlockHitResult findNearestEmptyFarmland(int radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -Math.min(radius, 3); y <= Math.min(radius, 3); y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos farmland = origin.add(x, y, z);
                    if (!client.world.getBlockState(farmland).isOf(Blocks.FARMLAND)) {
                        continue;
                    }
                    BlockPos cropPos = farmland.up();
                    if (!client.world.getBlockState(cropPos).isAir()) {
                        continue;
                    }
                    Vec3d hitPos = Vec3d.ofCenter(farmland).add(0.0D, 0.5D, 0.0D);
                    double distance = client.player.getEyePos().squaredDistanceTo(hitPos);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    bestDistance = distance;
                    best = new BlockHitResult(hitPos, Direction.UP, farmland, false);
                }
            }
        }
        return best;
    }

    private Entity findNearestMatchingEntity(String group, int radius, double maxDistance) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Box box = new Box(client.player.getBlockPos()).expand(radius);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.world.getOtherEntities(client.player, box)) {
            String entityId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            boolean matches = matchesEntityGroup(group, entityId);
            if (!matches || !(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            double distance = client.player.getEyePos().squaredDistanceTo(entity.getPos());
            if (distance > maxDistance * maxDistance || distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            best = entity;
        }
        return best;
    }

    private ItemEntity findNearestMatchingItemEntity(String exactItem, String group, int radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Box box = new Box(client.player.getBlockPos()).expand(radius);
        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.world.getOtherEntities(client.player, box)) {
            if (!(entity instanceof ItemEntity itemEntity) || itemEntity.isRemoved() || itemEntity.getStack().isEmpty()) {
                continue;
            }
            String itemId = itemId(itemEntity.getStack());
            if (!matchesDropTarget(exactItem, group, itemId)) {
                continue;
            }
            double distance = client.player.getPos().squaredDistanceTo(itemEntity.getPos());
            if (distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            best = itemEntity;
        }
        return best;
    }

    private ExecutorProtocol.StepResult moveTowardItemEntity(ItemEntity target, String itemId, int beforeCount, int timeoutTicks, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("item", itemId);
        data.addProperty("starting_count", beforeCount);
        data.addProperty("path_aware", true);
        int ticks = 0;
        int stuckTicks = 0;
        int assistCount = 0;
        double lastDistance = Double.MAX_VALUE;
        while (ticks < timeoutTicks && !aborted) {
            if (ticks % 10 == 0 && callOnClientThread(this::hasImmediateAbortDanger)) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("ending_count", callOnClientThread(() -> countInventoryItem(itemId)));
                data.addProperty("danger_stop", true);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Stopped drop collection because an immediate survival danger appeared.", data);
            }
            DropMoveStatus status = callOnClientThread(() -> {
                if (client.player == null || target.isRemoved()) {
                    return DropMoveStatus.COLLECTED;
                }
                int current = countInventoryItem(itemId);
                if (current > beforeCount) {
                    return DropMoveStatus.COLLECTED;
                }
                Vec3d targetPos = target.getPos();
                double distance = client.player.getPos().distanceTo(targetPos);
                if (distance <= 1.25D) {
                    releaseMovementKeys();
                    return DropMoveStatus.MOVING;
                }
                return steerToward(targetPos, distance);
            });
            if (status == DropMoveStatus.COLLECTED) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("ending_count", callOnClientThread(() -> countInventoryItem(itemId)));
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Collected or consumed the target item entity.", data);
            }
            if (status == DropMoveStatus.BLOCKED) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
            double distanceNow = callOnClientThread(() -> client.player == null || target.isRemoved() ? 0.0D : client.player.getPos().distanceTo(target.getPos()));
            if (distanceNow + 0.05D >= lastDistance) {
                stuckTicks++;
            }
            lastDistance = distanceNow;
            if (stuckTicks >= 10 && assistCount < 3) {
                Vec3d assistTarget = callOnClientThread(() -> target.isRemoved() ? null : target.getPos());
                if (assistTarget != null) {
                    ExecutorProtocol.StepResult assist = tryMovementAssistToward(assistTarget, actionType);
                    if ("accepted".equals(assist.status())) {
                        assistCount++;
                        stuckTicks = 0;
                        if (assist.data() != null) {
                            data.add("assist_" + assistCount, assist.data());
                        }
                        sleepTicks(2);
                        ticks += 2;
                        continue;
                    }
                    if (assist.data() != null) {
                        data.add("assist_failed", assist.data());
                        data.addProperty("assist_message", assist.message());
                    }
                }
            }
            if (stuckTicks >= 20) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("ending_count", callOnClientThread(() -> countInventoryItem(itemId)));
                data.addProperty("stuck_ticks", stuckTicks);
                data.addProperty("assist_count", assistCount);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe local path toward the target drop was found.", data);
            }
            sleepTicks(1);
            ticks++;
        }
        releaseKeys();
        data.addProperty("ticks", ticks);
        data.addProperty("ending_count", callOnClientThread(() -> countInventoryItem(itemId)));
        data.addProperty("assist_count", assistCount);
        return new ExecutorProtocol.StepResult(actionType, aborted ? "aborted" : "blocked", aborted ? "Drop collection was interrupted." : "Timed out before collecting target item.", data);
    }

    private DropMoveStatus steerToward(Vec3d targetPos, double distance) {
        if (client.player == null || client.world == null) {
            return DropMoveStatus.BLOCKED;
        }
        Vec3d playerPos = client.player.getPos();
        Vec3d navigationTarget = nextWaypointToward(playerPos, targetPos);
        double baseAngle = Math.atan2(navigationTarget.z - playerPos.z, navigationTarget.x - playerPos.x);
        double[] offsets = {0.0D, 25.0D, -25.0D, 50.0D, -50.0D, 80.0D, -80.0D, 120.0D, -120.0D, 180.0D};
        SteeringDecision best = null;
        for (double offset : offsets) {
            double angle = baseAngle + Math.toRadians(offset);
            Vec3d direction = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle)).normalize();
            SteeringDecision decision = evaluateStep(playerPos, direction, navigationTarget);
            if (!decision.safe()) {
                continue;
            }
            if (best == null || decision.score() < best.score()) {
                best = decision;
            }
        }
        if (best == null) {
            releaseMovementKeys();
            return DropMoveStatus.BLOCKED;
        }

        float yaw = (float) (Math.atan2(best.direction().z, best.direction().x) * 180.0D / Math.PI) - 90.0F;
        client.player.setYaw(rotateYawToward(client.player.getYaw(), yaw, 24.0F));
        client.player.setPitch(MathHelper.clamp(client.player.getPitch(), -18.0F, 18.0F));
        releaseMovementKeys();
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(best.jump() || distance < 2.0D && navigationTarget.y > playerPos.y + 0.4D);
        return DropMoveStatus.MOVING;
    }

    private DropMoveStatus steerExploreAlong(Vec3d desiredDirection, Vec3d targetPos) {
        if (client.player == null || client.world == null) {
            return DropMoveStatus.BLOCKED;
        }
        Vec3d playerPos = client.player.getPos();
        Vec3d baseDirection = desiredDirection.multiply(1.0D, 0.0D, 1.0D);
        if (baseDirection.lengthSquared() < 0.01D) {
            return DropMoveStatus.BLOCKED;
        }
        baseDirection = baseDirection.normalize();

        double baseAngle = Math.atan2(baseDirection.z, baseDirection.x);
        double[] offsets = {0.0D, 18.0D, -18.0D, 35.0D, -35.0D};
        SteeringDecision best = null;
        for (double offset : offsets) {
            double angle = baseAngle + Math.toRadians(offset);
            Vec3d candidateDirection = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle)).normalize();
            if (candidateDirection.dotProduct(baseDirection) < 0.7D) {
                continue;
            }
            SteeringDecision decision = evaluateStep(playerPos, candidateDirection, targetPos);
            if (!decision.safe()) {
                continue;
            }
            double headingPenalty = (1.0D - candidateDirection.dotProduct(baseDirection)) * 6.0D;
            double score = decision.score() + headingPenalty;
            SteeringDecision adjusted = new SteeringDecision(decision.direction(), decision.jump(), score);
            if (best == null || adjusted.score() < best.score()) {
                best = adjusted;
            }
        }
        if (best == null) {
            releaseMovementKeys();
            return DropMoveStatus.BLOCKED;
        }

        float yaw = yawForVector(best.direction());
        client.player.setYaw(rotateYawToward(client.player.getYaw(), yaw, 18.0F));
        client.player.setPitch(MathHelper.clamp(client.player.getPitch(), -14.0F, 14.0F));
        releaseMovementKeys();
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(safeLaneSteps(playerPos, best.direction(), 4, false) >= 4);
        client.options.jumpKey.setPressed(best.jump());
        return DropMoveStatus.MOVING;
    }

    private ExecutorProtocol.StepResult tryExploreAssist(Vec3d desiredDirection, String actionType) {
        callOnClientThread(() -> {
            faceExploreDirection(desiredDirection, 30.0F);
            return null;
        });
        return tryMovementAssist("forward", true, true, true, actionType);
    }

    private void faceExploreDirection(Vec3d desiredDirection, float maxDelta) {
        if (client.player == null) {
            return;
        }
        Vec3d flat = desiredDirection.multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSquared() < 0.01D) {
            return;
        }
        client.player.setYaw(rotateYawToward(client.player.getYaw(), yawForVector(flat.normalize()), maxDelta));
        client.player.setPitch(MathHelper.clamp(client.player.getPitch(), -14.0F, 14.0F));
    }

    private void faceHorizontalDirection(Vec3d direction, float maxDelta) {
        if (client.player == null) {
            return;
        }
        Vec3d flat = direction.multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSquared() < 0.01D) {
            return;
        }
        client.player.setYaw(rotateYawToward(client.player.getYaw(), yawForVector(flat.normalize()), maxDelta));
        client.player.setPitch(MathHelper.clamp(client.player.getPitch(), -14.0F, 14.0F));
    }

    private double exploreProgress(Vec3d startPos, Vec3d currentPos, Vec3d desiredDirection) {
        Vec3d flat = desiredDirection.multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSquared() < 0.01D) {
            return 0.0D;
        }
        Vec3d delta = currentPos.subtract(startPos).multiply(1.0D, 0.0D, 1.0D);
        return delta.dotProduct(flat.normalize());
    }

    private float yawForVector(Vec3d direction) {
        return (float) (Math.atan2(direction.z, direction.x) * 180.0D / Math.PI) - 90.0F;
    }

    private float rotateYawToward(float currentYaw, float targetYaw, float maxDelta) {
        float delta = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float clamped = MathHelper.clamp(delta, -maxDelta, maxDelta);
        return currentYaw + clamped;
    }

    private Vec3d nextWaypointToward(Vec3d origin, Vec3d targetPos) {
        LocalNavigationPlan plan = planLocalNavigation(
            origin,
            BlockPos.ofFloored(targetPos.x, targetPos.y, targetPos.z),
            1,
            Math.max(4, Math.min(12, (int) Math.ceil(origin.distanceTo(targetPos)) + 4)),
            3,
            768
        );
        List<BlockPos> path = plan.path();
        if (path.size() < 2) {
            return targetPos;
        }
        BlockPos waypoint = path.get(Math.min(2, path.size() - 1));
        return Vec3d.ofBottomCenter(waypoint);
    }

    private Vec3d safeRetreatTargetAwayFrom(Vec3d threatPos, double minDistance) {
        if (client.player == null) {
            return null;
        }
        Vec3d playerPos = client.player.getPos();
        Vec3d away = playerPos.subtract(threatPos).multiply(1.0D, 0.0D, 1.0D);
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(-Math.sin(Math.toRadians(client.player.getYaw())), 0.0D, Math.cos(Math.toRadians(client.player.getYaw())));
        }
        away = away.normalize();
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        double baseAngle = Math.atan2(away.z, away.x);
        for (double offset : new double[] {0.0D, 25.0D, -25.0D, 50.0D, -50.0D, 80.0D, -80.0D}) {
            double angle = baseAngle + Math.toRadians(offset);
            Vec3d direction = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle)).normalize();
            for (double distance : new double[] {minDistance, minDistance + 1.5D, minDistance + 3.0D}) {
                Vec3d candidate = playerPos.add(direction.multiply(distance));
                BlockPos standable = nearestStandable(BlockPos.ofFloored(candidate.x, candidate.y, candidate.z), 1);
                if (standable == null) {
                    continue;
                }
                Vec3d point = Vec3d.ofBottomCenter(standable);
                if (point.distanceTo(threatPos) <= playerPos.distanceTo(threatPos)) {
                    continue;
                }
                List<BlockPos> path = planLocalPath(playerPos, point);
                if (path.size() < 2) {
                    continue;
                }
                double score = path.size() + 1.0D / Math.max(0.5D, point.distanceTo(threatPos));
                if (score < bestScore) {
                    bestScore = score;
                    best = point;
                }
            }
        }
        return best;
    }

    private List<BlockPos> planLocalPath(Vec3d origin, Vec3d targetPos) {
        LocalNavigationPlan plan = planLocalNavigation(
            origin,
            BlockPos.ofFloored(targetPos.x, targetPos.y, targetPos.z),
            1,
            Math.max(4, Math.min(12, (int) Math.ceil(origin.distanceTo(targetPos)) + 4)),
            3,
            768
        );
        return plan.path();
    }

    private LocalNavigationPlan planLocalNavigation(Vec3d origin, BlockPos target, int rangeBlocks, int horizontalLimit, int verticalLimit, int maxNodes) {
        if (client.world == null) {
            return LocalNavigationPlan.blocked(target, "world_not_ready");
        }
        BlockPos start = nearestStandable(BlockPos.ofFloored(origin.x, origin.y, origin.z), 1);
        if (start == null || target == null) {
            return LocalNavigationPlan.blocked(target, "no_standable_start_or_goal");
        }
        KoeCraftLocalGoalPathfinder.Plan plan = KoeCraftLocalGoalPathfinder.plan(
            start,
            KoeCraftLocalGoalPathfinder.Goal.near(target, Math.max(0, rangeBlocks)),
            localGoalTerrain(),
            KoeCraftLocalGoalPathfinder.Options.localWalk(horizontalLimit, verticalLimit, maxNodes)
        );
        return new LocalNavigationPlan(
            target,
            plan.path(),
            plan.cost(),
            plan.reached(),
            plan.blockedReason(),
            plan.visitedNodes(),
            plan.digSteps(),
            plan.placeSteps(),
            plan.openSteps(),
            plan.firstAssistStep()
        );
    }

    private LocalNavigationPlan planLocalNavigation(Vec3d origin, BlockPos target, int rangeBlocks, int horizontalLimit, int verticalLimit, int maxNodes, KoeCraftLocalGoalPathfinder.Options options) {
        if (client.world == null) {
            return LocalNavigationPlan.blocked(target, "world_not_ready");
        }
        BlockPos start = nearestStandable(BlockPos.ofFloored(origin.x, origin.y, origin.z), 1);
        if (start == null || target == null) {
            return LocalNavigationPlan.blocked(target, "no_standable_start_or_goal");
        }
        KoeCraftLocalGoalPathfinder.Plan plan = KoeCraftLocalGoalPathfinder.plan(
            start,
            KoeCraftLocalGoalPathfinder.Goal.near(target, Math.max(0, rangeBlocks)),
            localGoalTerrain(),
            options
        );
        return new LocalNavigationPlan(
            target,
            plan.path(),
            plan.cost(),
            plan.reached(),
            plan.blockedReason(),
            plan.visitedNodes(),
            plan.digSteps(),
            plan.placeSteps(),
            plan.openSteps(),
            plan.firstAssistStep()
        );
    }

    private KoeCraftLocalGoalPathfinder.Terrain localGoalTerrain() {
        return new KoeCraftLocalGoalPathfinder.Terrain() {
            @Override
            public boolean isStandable(BlockPos pos) {
                return isStandableBlockPos(pos);
            }

            @Override
            public boolean canDigToStandable(BlockPos pos) {
                return canDigMovementLanding(pos);
            }

            @Override
            public boolean canPlaceSupport(BlockPos pos) {
                return canPlaceSupportLanding(pos);
            }

            @Override
            public boolean canOpenPassage(BlockPos pos) {
                return canOpenPassageLanding(pos);
            }

            @Override
            public boolean hasNearbyHazard(BlockPos pos) {
                return isDangerNear(pos, 1);
            }
        };
    }

    private boolean canDigMovementLanding(BlockPos feet) {
        if (client.world == null || isUnsafeFluid(feet) || isUnsafeFluid(feet.up()) || isUnsafeFluid(feet.down())) {
            return false;
        }
        if (!hasBlockingCollision(feet.down())) {
            return false;
        }
        boolean needsDig = false;
        for (BlockPos target : List.of(feet, feet.up())) {
            if (!hasBlockingCollision(target)) {
                continue;
            }
            needsDig = true;
            if (!isMovementAssistDiggableBlock(target)) {
                return false;
            }
        }
        return needsDig;
    }

    private boolean canPlaceSupportLanding(BlockPos feet) {
        if (client.world == null || isUnsafeFluid(feet) || isUnsafeFluid(feet.up()) || isUnsafeFluid(feet.down())) {
            return false;
        }
        return !hasBlockingCollision(feet)
            && !hasBlockingCollision(feet.up())
            && client.world.getBlockState(feet.down()).isAir();
    }

    private boolean canOpenPassageLanding(BlockPos feet) {
        if (client.world == null || isUnsafeFluid(feet) || isUnsafeFluid(feet.up()) || isUnsafeFluid(feet.down())) {
            return false;
        }
        if (!hasBlockingCollision(feet.down())) {
            return false;
        }
        return isPassageBlock(blockIdAt(feet)) && hasBlockingCollision(feet)
            || isPassageBlock(blockIdAt(feet.up())) && hasBlockingCollision(feet.up());
    }

    private void addLocalNavigationData(JsonObject data, String prefix, LocalNavigationPlan plan) {
        if (plan == null) {
            return;
        }
        data.addProperty(prefix + "_reached", plan.reached());
        data.addProperty(prefix + "_blocked_reason", plan.blockedReason());
        data.addProperty(prefix + "_size", plan.path().size());
        data.addProperty(prefix + "_cost", Math.round(plan.cost() * 100.0D) / 100.0D);
        data.addProperty(prefix + "_visited_nodes", plan.visitedNodes());
        data.addProperty(prefix + "_dig_steps", plan.digSteps());
        data.addProperty(prefix + "_place_steps", plan.placeSteps());
        data.addProperty(prefix + "_open_steps", plan.openSteps());
        if (plan.firstAssistStep() != null && plan.firstAssistStep().actionable()) {
            data.addProperty(prefix + "_first_assist", plan.firstAssistStep().primitive().name().toLowerCase());
            addBlockPos(data, prefix + "_first_assist_pos", plan.firstAssistStep().pos());
        }
        if (plan.target() != null) {
            addBlockPos(data, prefix + "_target", plan.target());
        }
        if (!plan.path().isEmpty()) {
            Vec3d waypoint = plan.waypoint();
            addBlockPos(data, prefix + "_waypoint", BlockPos.ofFloored(waypoint.x, waypoint.y, waypoint.z));
        }
    }

    private List<BlockPos> reconstructPath(BlockPos start, BlockPos end, Map<BlockPos, BlockPos> cameFrom) {
        ArrayList<BlockPos> reversed = new ArrayList<>();
        BlockPos current = end;
        reversed.add(current);
        while (!current.equals(start) && cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            reversed.add(current);
        }
        ArrayList<BlockPos> path = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    private BlockPos nearestStandable(BlockPos center, int radius) {
        if (isStandableBlockPos(center)) {
            return center;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = center.add(x, y, z);
                    if (!isStandableBlockPos(candidate)) {
                        continue;
                    }
                    double distance = center.getSquaredDistance(candidate);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private boolean isStandableBlockPos(BlockPos feet) {
        if (client.world == null) {
            return false;
        }
        return !hasBlockingCollision(feet)
            && !hasBlockingCollision(feet.up())
            && !isUnsafeFluid(feet)
            && !isUnsafeFluid(feet.up())
            && !isUnsafeFluid(feet.down())
            && hasBlockingCollision(feet.down());
    }

    private SteeringDecision evaluateStep(Vec3d origin, Vec3d direction, Vec3d targetPos) {
        Vec3d next = origin.add(direction.multiply(0.8D));
        StepSafety flat = stepSafetyAllowingOneBlockDown(next);
        if (flat.safe()) {
            return new SteeringDecision(direction, false, flat.score() + next.squaredDistanceTo(targetPos));
        }
        Vec3d stepUp = next.add(0.0D, 1.0D, 0.0D);
        StepSafety raised = stepSafety(stepUp);
        if (raised.safe() && canStandAt(origin.add(0.0D, 1.0D, 0.0D))) {
            return new SteeringDecision(direction, true, raised.score() + stepUp.squaredDistanceTo(targetPos) + 0.5D);
        }
        return new SteeringDecision(direction, false, Double.MAX_VALUE);
    }

    private StepSafety stepSafetyAllowingOneBlockDown(Vec3d pos) {
        StepSafety flat = stepSafety(pos);
        if (flat.safe() || client.world == null) {
            return flat;
        }
        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        if (hasBlockingCollision(feet) || hasBlockingCollision(feet.up()) || isUnsafeFluid(feet) || isUnsafeFluid(feet.up())) {
            return flat;
        }
        StepSafety oneDown = stepSafety(pos.add(0.0D, -1.0D, 0.0D));
        return oneDown.safe() ? new StepSafety(true, oneDown.score() + 0.25D) : flat;
    }

    private MoveLane chooseSafeMoveLane(Vec3d baseDirection, int distanceBlocks) {
        return chooseSafeMoveLane(baseDirection, distanceBlocks, Math.min(3, Math.max(1, distanceBlocks)));
    }

    private MoveLane chooseSafeMoveLane(Vec3d baseDirection, int distanceBlocks, int minSafeSteps) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Vec3d flat = baseDirection.multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSquared() < 0.01D) {
            return null;
        }
        flat = flat.normalize();
        Vec3d origin = client.player.getPos();
        double baseAngle = Math.atan2(flat.z, flat.x);
        double[] offsets = {0.0D, 18.0D, -18.0D, 32.0D, -32.0D, 45.0D, -45.0D};
        MoveLane best = null;
        int targetSteps = Math.max(1, Math.min(distanceBlocks, 24));
        int requiredSteps = Math.max(1, Math.min(minSafeSteps, targetSteps));
        for (double offset : offsets) {
            double angle = baseAngle + Math.toRadians(offset);
            Vec3d direction = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle)).normalize();
            int safeSteps = safeLaneSteps(origin, direction, targetSteps, false);
            if (safeSteps < requiredSteps) {
                continue;
            }
            double score = Math.abs(offset) * 0.08D + (targetSteps - safeSteps) * 4.0D;
            MoveLane lane = new MoveLane(direction, safeSteps, score, Math.abs(offset) > 0.01D);
            if (best == null || lane.score() < best.score()) {
                best = lane;
            }
        }
        return best;
    }

    private int safeLaneSteps(Vec3d origin, Vec3d direction, int steps, boolean allowWater) {
        if (client.world == null) {
            return 0;
        }
        Vec3d cursor = origin;
        int safe = 0;
        for (int i = 0; i < steps; i++) {
            Vec3d next = cursor.add(direction.multiply(0.85D));
            StepSafety flat = allowWater ? stepSafetyForSwimming(next) : stepSafety(next);
            if (flat.safe()) {
                cursor = next;
                safe++;
                continue;
            }
            StepSafety oneDown = allowWater ? stepSafetyForSwimming(next.add(0.0D, -1.0D, 0.0D)) : stepSafety(next.add(0.0D, -1.0D, 0.0D));
            if (oneDown.safe()) {
                cursor = next.add(0.0D, -1.0D, 0.0D);
                safe++;
                continue;
            }
            StepSafety raised = allowWater ? stepSafetyForSwimming(next.add(0.0D, 1.0D, 0.0D)) : stepSafety(next.add(0.0D, 1.0D, 0.0D));
            if (raised.safe()) {
                cursor = next.add(0.0D, 1.0D, 0.0D);
                safe++;
                continue;
            }
            break;
        }
        return safe;
    }

    private boolean shouldSprintForMove(String direction, boolean requestedSprint, boolean swim) {
        if (client.player == null || !requestedSprint) {
            return false;
        }
        if (swim) {
            return isPlayerInWater();
        }
        return direction.equals("forward") && safeLaneSteps(client.player.getPos(), movementVector(direction), 4, false) >= 4;
    }

    private boolean shouldJumpWhileMoving(String direction, boolean requestedSprint, boolean swim, boolean requestedJump) {
        return shouldJumpWhileMoving(direction, requestedSprint, swim, requestedJump, false);
    }

    private boolean shouldJumpWhileMoving(String direction, boolean requestedSprint, boolean swim, boolean requestedJump, boolean autoJump) {
        if (client.player == null) {
            return false;
        }
        if (swim) {
            return isPlayerInWater();
        }
        boolean sprintJump = requestedJump
            && requestedSprint
            && direction.equals("forward")
            && client.player.isOnGround()
            && safeLaneSteps(client.player.getPos(), movementVector(direction), 4, false) >= 4;
        return sprintJump || autoJump && client.player.isOnGround() && canStepUp(direction);
    }

    private boolean isPlayerInWater() {
        if (client.player == null || client.world == null) {
            return false;
        }
        BlockPos feet = client.player.getBlockPos();
        return blockIdAt(feet).equals("minecraft:water") || blockIdAt(feet.up()).equals("minecraft:water");
    }

    private boolean isPlayerRidingBoat() {
        return client.player != null && client.player.getVehicle() instanceof BoatEntity;
    }

    private StepSafety stepSafetyForSwimming(Vec3d pos) {
        if (client.world == null) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        BlockPos head = feet.up();
        if (isUnsafeFluid(feet) && !blockIdAt(feet).equals("minecraft:water")) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        if (isUnsafeFluid(head) && !blockIdAt(head).equals("minecraft:water")) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        if (hasBlockingCollision(feet) || hasBlockingCollision(head)) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        return new StepSafety(blockIdAt(feet).equals("minecraft:water") || blockIdAt(head).equals("minecraft:water"), 0.0D);
    }

    private StepSafety stepSafety(Vec3d pos) {
        if (client.world == null) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        BlockPos head = feet.up();
        BlockPos support = feet.down();
        if (hasBlockingCollision(feet) || hasBlockingCollision(head)) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        if (isUnsafeFluid(feet) || isUnsafeFluid(head) || isUnsafeFluid(support)) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        if (!hasBlockingCollision(support)) {
            return new StepSafety(false, Double.MAX_VALUE);
        }
        double score = client.world.getBlockState(support).isOf(Blocks.FARMLAND) ? 0.4D : 0.0D;
        return new StepSafety(true, score);
    }

    private Void pressMoveKeys(String direction, boolean sprint) {
        return pressMoveKeys(direction, sprint, false);
    }

    private Void pressMoveKeys(String direction, boolean sprint, boolean swim) {
        return pressMoveKeys(direction, sprint, swim, swim);
    }

    private Void pressMoveKeys(String direction, boolean sprint, boolean swim, boolean jump) {
        releaseMovementKeys();
        client.options.sprintKey.setPressed(sprint && direction.equals("forward"));
        client.options.jumpKey.setPressed(jump);
        switch (direction) {
            case "forward" -> client.options.forwardKey.setPressed(true);
            case "back" -> client.options.backKey.setPressed(true);
            case "left" -> client.options.leftKey.setPressed(true);
            case "right" -> client.options.rightKey.setPressed(true);
            default -> {
            }
        }
        return null;
    }

    private boolean canStandAt(Vec3d pos) {
        StepSafety safety = stepSafety(pos);
        return safety.safe();
    }

    private boolean hasBlockingCollision(BlockPos pos) {
        if (client.world == null) {
            return true;
        }
        BlockState state = client.world.getBlockState(pos);
        return !state.getCollisionShape(client.world, pos).isEmpty();
    }

    private boolean isUnsafeFluid(BlockPos pos) {
        if (client.world == null) {
            return true;
        }
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA) || !state.getFluidState().isEmpty();
    }

    private ExecutorProtocol.StepResult attackEntityUntilDone(Entity target, String group, double maxDistance, int timeoutTicks, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("entity_group", group);
        data.addProperty("entity", Registries.ENTITY_TYPE.getId(target.getType()).toString());
        data.addProperty("name", target.getName().getString());
        int ticks = 0;
        while (ticks < timeoutTicks && !aborted) {
            Boolean done = callOnClientThread(() -> {
                if (client.player == null || client.interactionManager == null || !(target instanceof LivingEntity living)) {
                    return true;
                }
                if (!living.isAlive() || target.isRemoved()) {
                    return true;
                }
                double distance = client.player.getEyePos().squaredDistanceTo(target.getPos());
                if (distance > maxDistance * maxDistance) {
                    return false;
                }
                lookAtPosition(target.getEyePos());
                client.interactionManager.attackEntity(client.player, target);
                client.player.swingHand(Hand.MAIN_HAND);
                client.options.attackKey.setPressed(true);
                return false;
            });
            if (done) {
                releaseKeys();
                data.addProperty("ticks", ticks);
                data.addProperty("completed", true);
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Target entity was defeated or removed.", data);
            }
            sleepTicks(group.equals("hostile") ? 8 : 10);
            ticks += group.equals("hostile") ? 8 : 10;
        }
        releaseKeys();
        data.addProperty("ticks", ticks);
        data.addProperty("completed", false);
        return new ExecutorProtocol.StepResult(actionType, aborted ? "aborted" : "blocked", aborted ? "Entity attack was interrupted." : "Timed out before target entity was defeated.", data);
    }

    private BlockPos findNearestWorkstation(String station, int radius, boolean avoidOccupied) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -Math.min(radius, 4); y <= Math.min(radius, 4); y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!blockIdAt(pos).equals(station)) {
                        continue;
                    }
                    double distance = client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distance >= bestDistance) {
                        continue;
                    }
                    if (avoidOccupied && isLikelyOccupiedByOtherPlayer(pos)) {
                        continue;
                    }
                    bestDistance = distance;
                    best = pos;
                }
            }
        }
        return best;
    }

    private boolean isLikelyOccupiedByOtherPlayer(BlockPos pos) {
        if (client.player == null || client.world == null) {
            return false;
        }
        Box box = new Box(pos).expand(config.occupiedWorkstationRadius());
        for (Entity entity : client.world.getOtherEntities(client.player, box)) {
            if (entity.getType() == EntityType.PLAYER) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> findNearestContainers(int radius, String supplyKey) {
        if (client.player == null || client.world == null) {
            return List.of();
        }
        BlockPos origin = client.player.getBlockPos();
        ArrayList<BlockPos> candidates = new ArrayList<>();
        ContainerSupplyCacheEntry cached = containerSupplyHits.get(supplyKey);
        if (cached != null && cached.expiresAtMs() > System.currentTimeMillis() && isContainerCandidateUsable(cached.pos(), origin, radius, supplyKey)) {
            candidates.add(cached.pos());
        }
        for (int y = -Math.min(radius, 4); y <= Math.min(radius, 4); y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!isContainerCandidateUsable(pos, origin, radius, supplyKey)) {
                        continue;
                    }
                    if (!candidates.isEmpty() && candidates.get(0).equals(pos)) {
                        continue;
                    }
                    candidates.add(pos);
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(
            client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(a)),
            client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(b))
        ));
        if (cached != null && candidates.remove(cached.pos())) {
            candidates.add(0, cached.pos());
        }
        return candidates;
    }

    private boolean isContainerCandidateUsable(BlockPos pos, BlockPos origin, int radius, String supplyKey) {
        if (!isSupportedSupplyContainer(blockIdAt(pos))) {
            return false;
        }
        if (Math.abs(pos.getX() - origin.getX()) > radius
            || Math.abs(pos.getZ() - origin.getZ()) > radius
            || Math.abs(pos.getY() - origin.getY()) > Math.min(radius, 4)) {
            return false;
        }
        if (isLikelyOccupiedByOtherPlayer(pos)) {
            return false;
        }
        return !isRecentContainerMiss(supplyKey, pos);
    }

    private boolean isSupportedSupplyContainer(String blockId) {
        return blockId.equals("minecraft:chest")
            || blockId.equals("minecraft:trapped_chest")
            || blockId.equals("minecraft:barrel");
    }

    private BlockPos findNearestHarvestableBeeNest(int radius, boolean requireSmoke) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -Math.min(radius, 6); y <= Math.min(radius, 6); y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!isBeeNestOrHive(blockIdAt(pos))) {
                        continue;
                    }
                    if (!isMatureBeeNest(pos)) {
                        continue;
                    }
                    if (requireSmoke && !hasCampfireSmokeBelow(pos)) {
                        continue;
                    }
                    double distance = client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distance >= bestDistance) {
                        continue;
                    }
                    bestDistance = distance;
                    best = pos;
                }
            }
        }
        return best;
    }

    private boolean isBeeNestOrHive(String blockId) {
        return blockId.equals("minecraft:bee_nest") || blockId.equals("minecraft:beehive");
    }

    private boolean isMatureBeeNest(BlockPos pos) {
        if (client.world == null) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        return state.toString().contains("honey_level=5");
    }

    private boolean hasCampfireSmokeBelow(BlockPos pos) {
        for (int depth = 1; depth <= 5; depth++) {
            String blockId = blockIdAt(pos.down(depth));
            if (blockId.equals("minecraft:campfire") || blockId.equals("minecraft:soul_campfire")) {
                return true;
            }
            if (!blockId.equals("minecraft:air") && !blockId.equals("minecraft:water")) {
                return false;
            }
        }
        return false;
    }

    private ExecutorProtocol.StepResult openContainerAt(BlockPos pos, String actionType) {
        JsonObject data = new JsonObject();
        addBlockPos(data, "container", pos);
        data.addProperty("container_block", callOnClientThread(() -> blockIdAt(pos)));
        for (int attempt = 0; attempt < 3; attempt++) {
            int currentAttempt = attempt;
            callOnClientThread(() -> {
                if (client.player == null || client.interactionManager == null) {
                    return null;
                }
                Direction side = breakSide(pos);
                Vec3d hitPos = workstationHitPosition(pos, currentAttempt);
                lookAtPosition(hitPos);
                BlockHitResult hit = new BlockHitResult(hitPos, side, pos, false);
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                client.player.swingHand(Hand.MAIN_HAND);
                return null;
            });
            int openWaitTicks = waitForGenericContainerOpen(10);
            if (openWaitTicks >= 0) {
                data.addProperty("attempts", attempt + 1);
                data.addProperty("open_wait_ticks", openWaitTicks);
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Opened nearby container screen.", data);
            }
        }
        data.addProperty("attempts", 3);
        return new ExecutorProtocol.StepResult(actionType, "blocked", "Interacted with container but a container screen did not open.", data);
    }

    private int quickMoveMatchingContainerStacks(String item, String group, int requestedCount, int beforeCount) {
        int containerSlots = callOnClientThread(() -> {
            if (client.player == null || !(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
                return 0;
            }
            return Math.max(0, handler.slots.size() - 36);
        });
        if (containerSlots <= 0) {
            return 0;
        }
        int movedStacks = 0;
        for (int slot = 0; slot < containerSlots; slot++) {
            int currentSlot = slot;
            boolean moved = Boolean.TRUE.equals(callOnClientThread(() -> {
                if (client.player == null || client.interactionManager == null || !(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
                    return false;
                }
                if (currentSlot < 0 || currentSlot >= handler.slots.size()) {
                    return false;
                }
                ItemStack stack = handler.getSlot(currentSlot).getStack();
                if (stack.isEmpty()) {
                    return false;
                }
                String itemId = itemId(stack);
                if (!item.isBlank() ? !item.equals(itemId) : !MinecraftItemGroups.matches(group, itemId)) {
                    return false;
                }
                client.interactionManager.clickSlot(handler.syncId, currentSlot, 0, SlotActionType.QUICK_MOVE, client.player);
                return true;
            }));
            if (!moved) {
                continue;
            }
            movedStacks++;
            int current = waitForInventorySupplyCount(item, group, beforeCount, 6);
            if (current - beforeCount >= requestedCount) {
                break;
            }
        }
        return movedStacks;
    }

    private int waitForInventorySupplyCount(String item, String group, int before, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            int current = callOnClientThread(() -> item.isBlank() ? countInventoryGroup(group) : countInventoryItem(item));
            if (current > before) {
                return current;
            }
            sleepTicks(1);
        }
        return callOnClientThread(() -> item.isBlank() ? countInventoryGroup(group) : countInventoryItem(item));
    }

    private String containerSupplyKey(String item, String group) {
        return !item.isBlank() ? "item:" + item : "group:" + group;
    }

    private String containerMissKey(String supplyKey, BlockPos pos) {
        return supplyKey + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private boolean isPositiveCachedContainer(String supplyKey, BlockPos pos) {
        ContainerSupplyCacheEntry entry = containerSupplyHits.get(supplyKey);
        return entry != null && entry.expiresAtMs() > System.currentTimeMillis() && entry.pos().equals(pos);
    }

    private boolean isRecentContainerMiss(String supplyKey, BlockPos pos) {
        Long expiresAt = containerSupplyMisses.get(containerMissKey(supplyKey, pos));
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private void rememberContainerHit(String supplyKey, BlockPos pos) {
        long expiresAt = System.currentTimeMillis() + CONTAINER_SUPPLY_CACHE_TTL_MS;
        containerSupplyHits.put(supplyKey, new ContainerSupplyCacheEntry(pos.toImmutable(), expiresAt));
        containerSupplyMisses.remove(containerMissKey(supplyKey, pos));
    }

    private void rememberContainerMiss(String supplyKey, BlockPos pos) {
        containerSupplyMisses.put(containerMissKey(supplyKey, pos), System.currentTimeMillis() + CONTAINER_SUPPLY_CACHE_TTL_MS);
    }

    private void pruneContainerSupplyCache() {
        long now = System.currentTimeMillis();
        containerSupplyHits.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
        containerSupplyMisses.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private ExecutorProtocol.StepResult interactWithWorkstation(BlockPos pos, String station, String actionType, String source) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player, world, or interaction manager is not ready.");
        }
        if (!blockIdAt(pos).equals(station)) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Target workstation no longer exists.");
        }
        JsonObject data = new JsonObject();
        data.addProperty("station", station);
        data.addProperty("source", source);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("occupied_heuristic", isLikelyOccupiedByOtherPlayer(pos));
        Direction side = breakSide(pos);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (isExpectedScreenOpen(station)) {
                data.addProperty("attempts", attempt);
                data.addProperty("open_wait_ticks", 0);
                data.addProperty("opened_before_interact", true);
                addCurrentScreenState(data, "screen");
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Opened workstation screen.", data);
            }
            Vec3d hitPos = workstationHitPosition(pos, attempt);
            lookAtPosition(hitPos);
            BlockHitResult hit = new BlockHitResult(hitPos, side, pos, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            client.player.swingHand(Hand.MAIN_HAND);
            int openWaitTicks = waitForExpectedScreenOpen(station, 32);
            if (openWaitTicks >= 0) {
                data.addProperty("attempts", attempt + 1);
                data.addProperty("open_wait_ticks", openWaitTicks);
                addCurrentScreenState(data, "screen");
                sleepTicks(2);
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Opened workstation screen.", data);
            }
        }
        data.addProperty("attempts", 3);
        int finalOpenWaitTicks = waitForExpectedScreenOpen(station, 40);
        data.addProperty("final_open_wait_ticks", finalOpenWaitTicks);
        addCurrentScreenState(data, "screen");
        if (!isExpectedScreenOpen(station)) {
            sleepTicks(4);
            int lateOpenWaitTicks = waitForExpectedScreenOpen(station, 24);
            data.addProperty("late_open_wait_ticks", lateOpenWaitTicks);
            addCurrentScreenState(data, "late_screen");
        }
        if (!isExpectedScreenOpen(station)) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Interacted with workstation but the expected screen did not open.", data);
        }
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Opened workstation screen.", data);
    }

    private void addCurrentScreenState(JsonObject data, String prefix) {
        String key = prefix == null || prefix.isBlank() ? "" : prefix + "_";
        data.addProperty(key + "current_screen", client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName());
        data.addProperty(key + "current_screen_class", client.currentScreen == null ? "none" : client.currentScreen.getClass().getName());
        ScreenHandler handler = client.player == null ? null : client.player.currentScreenHandler;
        data.addProperty(key + "current_handler", handler == null ? "none" : handler.getClass().getSimpleName());
        data.addProperty(key + "current_handler_class", handler == null ? "none" : handler.getClass().getName());
    }

    private int waitForBlockIdAt(BlockPos pos, String expectedBlock, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (blockIdAt(pos).equals(expectedBlock)) {
                return tick;
            }
            sleepTicks(1);
        }
        return -1;
    }

    private int waitForExpectedScreenOpen(String station, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (isExpectedScreenOpen(station)) {
                return tick;
            }
            sleepTicks(1);
        }
        return -1;
    }

    private int waitForGenericContainerOpen(int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (client.player != null && client.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                return tick;
            }
            sleepTicks(1);
        }
        return -1;
    }

    private int waitForInventoryScreenOpen(int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (client.player != null && client.player.currentScreenHandler instanceof PlayerScreenHandler && client.currentScreen instanceof InventoryScreen) {
                return tick;
            }
            sleepTicks(1);
        }
        return -1;
    }

    private int waitForHandledScreenClosed(int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (client.currentScreen == null) {
                return tick;
            }
            sleepTicks(1);
        }
        return -1;
    }

    private Vec3d workstationHitPosition(BlockPos pos, int attempt) {
        Vec3d center = Vec3d.ofCenter(pos);
        if (attempt == 1) {
            return center.add(0.0D, 0.22D, 0.0D);
        }
        if (attempt == 2) {
            return center.add(0.0D, -0.22D, 0.0D);
        }
        return center;
    }

    private boolean isExpectedScreenOpen(String station) {
        if (client.player == null) {
            return false;
        }
        if (station.equals("minecraft:crafting_table") && client.currentScreen instanceof CraftingScreen) {
            return true;
        }
        if (station.equals("minecraft:furnace") && client.currentScreen instanceof AbstractFurnaceScreen<?>) {
            return true;
        }
        if (station.equals("minecraft:stonecutter") && client.currentScreen instanceof StonecutterScreen) {
            return true;
        }
        if (station.equals("minecraft:smithing_table") && client.currentScreen instanceof SmithingScreen) {
            return true;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (station.equals("minecraft:crafting_table")) {
            return handler instanceof CraftingScreenHandler;
        }
        if (station.equals("minecraft:furnace")) {
            return handler instanceof AbstractFurnaceScreenHandler;
        }
        if (station.equals("minecraft:stonecutter")) {
            return handler instanceof StonecutterScreenHandler;
        }
        if (station.equals("minecraft:smithing_table")) {
            return handler instanceof SmithingScreenHandler;
        }
        return false;
    }

    private String stationItem(String station) {
        return station;
    }

    private String blockIdAt(BlockPos pos) {
        if (client.world == null) {
            return "";
        }
        return Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
    }

    private String blockProgressKey(BlockPos pos) {
        if (client.world == null) {
            return "world_not_ready";
        }
        BlockState state = client.world.getBlockState(pos);
        return Registries.BLOCK.getId(state.getBlock()) + "|" + state.toString();
    }

    private BlockHitResult resolveBreakTarget(ExecutorProtocol.Action action) {
        JsonObject target = action.objectField("target");
        if (target.has("kind") && "crosshair_block".equals(target.get("kind").getAsString())) {
            HitResult hit = client.crosshairTarget;
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                return blockHit;
            }
            return null;
        }

        if (target.has("x") && target.has("y") && target.has("z") && client.player != null) {
            BlockPos pos = new BlockPos(target.get("x").getAsInt(), target.get("y").getAsInt(), target.get("z").getAsInt());
            Vec3d eye = client.player.getEyePos();
            Vec3d center = Vec3d.ofCenter(pos);
            Vec3d delta = center.subtract(eye);
            Direction side = Direction.getFacing(delta.x, delta.y, delta.z).getOpposite();
            return new BlockHitResult(center, side, pos, false);
        }
        return null;
    }

    private ExecutorProtocol.StepResult scanBuildArea(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
            }
            int radiusChunks = Math.max(1, Math.min(action.intField("radius_chunks", 8), 8));
            int samplesPerChunk = Math.max(2, Math.min(action.intField("samples_per_chunk", 4), 4));
            BlockPos origin = client.player.getBlockPos();
            int originChunkX = Math.floorDiv(origin.getX(), 16);
            int originChunkZ = Math.floorDiv(origin.getZ(), 16);
            JsonArray candidates = new JsonArray();
            int scannedChunks = 0;
            int scannedSamples = 0;
            double bestScore = Double.POSITIVE_INFINITY;
            JsonObject best = null;
            for (int chunkX = originChunkX - radiusChunks; chunkX <= originChunkX + radiusChunks; chunkX++) {
                for (int chunkZ = originChunkZ - radiusChunks; chunkZ <= originChunkZ + radiusChunks; chunkZ++) {
                    scannedChunks++;
                    int safeSamples = 0;
                    int flatSamples = 0;
                    int fluidSamples = 0;
                    int minY = Integer.MAX_VALUE;
                    int maxY = Integer.MIN_VALUE;
                    int step = 16 / samplesPerChunk;
                    for (int sx = 0; sx < samplesPerChunk; sx++) {
                        for (int sz = 0; sz < samplesPerChunk; sz++) {
                            int x = chunkX * 16 + sx * step + step / 2;
                            int z = chunkZ * 16 + sz * step + step / 2;
                            BlockPos stand = findStandableNearColumn(x, z, origin.getY(), 8);
                            scannedSamples++;
                            if (stand == null) {
                                continue;
                            }
                            String below = blockIdAt(stand.down());
                            if (below.equals("minecraft:water") || below.equals("minecraft:lava")) {
                                fluidSamples++;
                                continue;
                            }
                            if (isDangerNear(stand, 2)) {
                                continue;
                            }
                            safeSamples++;
                            minY = Math.min(minY, stand.getY());
                            maxY = Math.max(maxY, stand.getY());
                        }
                    }
                    if (safeSamples <= 0) {
                        continue;
                    }
                    if (maxY != Integer.MIN_VALUE && maxY - minY <= 2) {
                        flatSamples = safeSamples;
                    }
                    BlockPos center = new BlockPos(chunkX * 16 + 8, origin.getY(), chunkZ * 16 + 8);
                    double distance = Math.sqrt(origin.getSquaredDistance(center));
                    double score = distance + Math.max(0, 8 - safeSamples) * 5.0D + Math.max(0, 4 - flatSamples) * 8.0D + fluidSamples * 10.0D;
                    JsonObject candidate = new JsonObject();
                    candidate.addProperty("chunk_x", chunkX);
                    candidate.addProperty("chunk_z", chunkZ);
                    candidate.addProperty("center_x", center.getX());
                    candidate.addProperty("center_z", center.getZ());
                    candidate.addProperty("safe_samples", safeSamples);
                    candidate.addProperty("flat_samples", flatSamples);
                    candidate.addProperty("fluid_samples", fluidSamples);
                    candidate.addProperty("score", score);
                    if (candidates.size() < 16) {
                        candidates.add(candidate);
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            JsonObject data = new JsonObject();
            data.addProperty("radius_chunks", radiusChunks);
            data.addProperty("scanned_chunks", scannedChunks);
            data.addProperty("scanned_samples", scannedSamples);
            data.add("candidates", candidates);
            if (best != null) {
                data.add("best_candidate", best);
            }
            data.addProperty("has_safe_candidate", best != null);
            return new ExecutorProtocol.StepResult(action.type(), "accepted", best == null ? "Scanned nearby chunks, but no safe build-area candidate was found in the sampled summary." : "Scanned nearby chunks for build-area suitability.", data);
        });
    }

    private ExecutorProtocol.StepResult scanExploreArea(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
            }
            String targetGroup = action.stringField("target_group");
            if (!isSupportedExplorationTargetGroup(targetGroup)) {
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported exploration scan target group: " + targetGroup);
            }
            int radiusChunks = Math.max(1, Math.min(action.intField("radius_chunks", 8), 8));
            int samplesPerChunk = Math.max(2, Math.min(action.intField("samples_per_chunk", 4), 4));
            BlockPos origin = client.player.getBlockPos();
            int originChunkX = Math.floorDiv(origin.getX(), 16);
            int originChunkZ = Math.floorDiv(origin.getZ(), 16);
            JsonArray candidates = new JsonArray();
            JsonObject best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            int scannedChunks = 0;
            int scannedSamples = 0;
            for (int chunkX = originChunkX - radiusChunks; chunkX <= originChunkX + radiusChunks; chunkX++) {
                for (int chunkZ = originChunkZ - radiusChunks; chunkZ <= originChunkZ + radiusChunks; chunkZ++) {
                    scannedChunks++;
                    int standableSamples = 0;
                    int targetHintSamples = 0;
                    int fluidSamples = 0;
                    int step = 16 / samplesPerChunk;
                    for (int sx = 0; sx < samplesPerChunk; sx++) {
                        for (int sz = 0; sz < samplesPerChunk; sz++) {
                            int x = chunkX * 16 + sx * step + step / 2;
                            int z = chunkZ * 16 + sz * step + step / 2;
                            BlockPos stand = findStandableNearColumn(x, z, origin.getY(), 10);
                            scannedSamples++;
                            if (stand == null) {
                                continue;
                            }
                            standableSamples++;
                            String below = blockIdAt(stand.down());
                            if (below.equals("minecraft:water") || below.equals("minecraft:lava")) {
                                fluidSamples++;
                            }
                            if (hasNearbyGroupAtSample(stand, targetGroup, 4)) {
                                targetHintSamples++;
                            }
                        }
                    }
                    if (standableSamples <= 0 && targetHintSamples <= 0) {
                        continue;
                    }
                    BlockPos center = new BlockPos(chunkX * 16 + 8, origin.getY(), chunkZ * 16 + 8);
                    double distance = Math.sqrt(origin.getSquaredDistance(center));
                    double score = distance - targetHintSamples * 120.0D + Math.max(0, 4 - standableSamples) * 6.0D + fluidSamples * 8.0D;
                    JsonObject candidate = new JsonObject();
                    candidate.addProperty("chunk_x", chunkX);
                    candidate.addProperty("chunk_z", chunkZ);
                    candidate.addProperty("center_x", center.getX());
                    candidate.addProperty("center_z", center.getZ());
                    candidate.addProperty("standable_samples", standableSamples);
                    candidate.addProperty("target_hint_samples", targetHintSamples);
                    candidate.addProperty("fluid_samples", fluidSamples);
                    candidate.addProperty("score", score);
                    if (candidates.size() < 20) {
                        candidates.add(candidate);
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            JsonObject data = new JsonObject();
            data.addProperty("target_group", targetGroup);
            data.addProperty("radius_chunks", radiusChunks);
            data.addProperty("scanned_chunks", scannedChunks);
            data.addProperty("scanned_samples", scannedSamples);
            data.addProperty("summary_only", true);
            data.add("candidates", candidates);
            if (best != null) {
                data.add("best_candidate", best);
                data.addProperty("has_target_hint", best.get("target_hint_samples").getAsInt() > 0);
            } else {
                data.addProperty("has_target_hint", false);
            }
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Scanned nearby chunks for exploration hints using a bounded summary.", data);
        });
    }

    private boolean hasNearbyGroupAtSample(BlockPos center, String group, int radius) {
        if (client.world == null) {
            return false;
        }
        for (int y = -2; y <= 3; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (BlockGroups.matches(group, blockIdAt(center.add(x, y, z)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findStandableNearColumn(int x, int z, int centerY, int verticalRadius) {
        if (client.world == null) {
            return null;
        }
        for (int dy = verticalRadius; dy >= -verticalRadius; dy--) {
            BlockPos feet = new BlockPos(x, centerY + dy, z);
            if (isStandableBlockPos(feet)) {
                return feet;
            }
        }
        return null;
    }

    private ExecutorProtocol.StepResult scanState(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            long scanStartedNanos = System.nanoTime();
            if (client.player == null || client.world == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready.");
            }

            int radius = Math.max(1, Math.min(action.intField("radius", 8), config.maxScanRadius()));
            boolean includeBlocks = action.booleanField("include_blocks", true);
            boolean includeEntities = action.booleanField("include_entities", true);
            String profile = action.stringField("profile");
            if (profile.isBlank()) {
                profile = "default";
            }
            int detailBudget = Math.max(0, Math.min(action.intField("detail_budget", profile.equals("voice_default") ? 80 : MAX_BLOCK_SAMPLES), MAX_BLOCK_SAMPLES));
            if (profile.equals("summary_only")) {
                detailBudget = 0;
            }
            int verticalRadius = Math.max(1, Math.min(action.intField("vertical_radius", Math.min(radius, 8)), Math.min(radius, 32)));
            JsonObject data = new JsonObject();
            JsonObject player = new JsonObject();
            Vec3d pos = client.player.getPos();
            player.addProperty("health", client.player.getHealth());
            player.addProperty("hunger", client.player.getHungerManager().getFoodLevel());
            player.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
            player.add("position", vec(pos.x, pos.y, pos.z));
            data.add("player", player);
            data.addProperty("radius", radius);
            data.addProperty("scan_profile", profile);
            data.addProperty("detail_budget", detailBudget);
            data.addProperty("vertical_radius", verticalRadius);
            data.addProperty("light_level", client.world.getLightLevel(client.player.getBlockPos()));
            data.addProperty("sky_light_level", client.world.getLightLevel(LightType.SKY, client.player.getBlockPos()));
            data.addProperty("block_light_level", client.world.getLightLevel(LightType.BLOCK, client.player.getBlockPos()));
            data.add("inventory", scanInventory());
            data.add("recipebook", scanRecipeBook(shouldSummarizeRecipeBook(profile)));
            data.add("dangerous_conditions", scanDangerousConditions(radius));

            if (includeBlocks) {
                BlockScanResult blockScan = cachedScanBlocks(radius, verticalRadius, detailBudget, profile);
                data.add("nearby_blocks", blockScan.details());
                data.add("block_summary", blockScan.summary());
                data.addProperty("block_scan_scanned", blockScan.scannedBlocks());
                data.addProperty("block_details_truncated", blockScan.detailTruncated());
            }
            if (includeEntities) {
                data.add("nearby_entities", cachedScanEntities(radius));
                data.add("nearby_items", cachedScanItems(radius));
            }
            data.addProperty("scan_elapsed_ms", elapsedMillis(scanStartedNanos));
            data.addProperty("scan_json_bytes_estimate", data.toString().getBytes(StandardCharsets.UTF_8).length);

            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Scanned nearby player state.", data);
        });
    }

    private BlockScanResult cachedScanBlocks(int radius, int verticalRadius, int detailBudget, String profile) {
        if (client.player == null || client.world == null) {
            return scanBlocks(radius, verticalRadius, detailBudget, profile);
        }
        pruneScanContextCache();
        String key = scanContextCacheKey("blocks", radius, verticalRadius, detailBudget, profile);
        BlockScanCacheEntry cached = blockScanCache.get(key);
        if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
            return copyBlockScanResult(cached.result());
        }
        BlockScanResult result = scanBlocks(radius, verticalRadius, detailBudget, profile);
        rememberBlockScan(key, result);
        return copyBlockScanResult(result);
    }

    private JsonArray cachedScanEntities(int radius) {
        if (client.player == null || client.world == null) {
            return scanEntities(radius);
        }
        pruneScanContextCache();
        String key = scanContextCacheKey("entities", radius, 0, 0, "");
        JsonArrayCacheEntry cached = entityScanCache.get(key);
        if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
            return cached.value().deepCopy();
        }
        JsonArray result = scanEntities(radius);
        rememberJsonArrayScan(entityScanCache, key, result);
        return result.deepCopy();
    }

    private JsonArray cachedScanItems(int radius) {
        if (client.player == null || client.world == null) {
            return scanItems(radius);
        }
        pruneScanContextCache();
        String key = scanContextCacheKey("items", radius, 0, 0, "");
        JsonArrayCacheEntry cached = itemScanCache.get(key);
        if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
            return cached.value().deepCopy();
        }
        JsonArray result = scanItems(radius);
        rememberJsonArrayScan(itemScanCache, key, result);
        return result.deepCopy();
    }

    private String scanContextCacheKey(String kind, int radius, int verticalRadius, int detailBudget, String profile) {
        BlockPos origin = client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos();
        int bucket = SCAN_CONTEXT_CACHE_BUCKET_SIZE;
        int bx = Math.floorDiv(origin.getX(), bucket);
        int by = Math.floorDiv(origin.getY(), bucket);
        int bz = Math.floorDiv(origin.getZ(), bucket);
        String dimension = client.world == null ? "unknown" : client.world.getRegistryKey().getValue().toString();
        return kind + "|" + dimension + "|" + bx + "," + by + "," + bz + "|r=" + radius + "|vr=" + verticalRadius + "|detail=" + detailBudget + "|p=" + profile;
    }

    private void rememberBlockScan(String key, BlockScanResult result) {
        if (blockScanCache.size() >= MAX_SCAN_CONTEXT_CACHE_ENTRIES) {
            pruneScanContextCache();
            if (blockScanCache.size() >= MAX_SCAN_CONTEXT_CACHE_ENTRIES) {
                blockScanCache.clear();
            }
        }
        blockScanCache.put(key, new BlockScanCacheEntry(copyBlockScanResult(result), System.currentTimeMillis() + SCAN_CONTEXT_CACHE_TTL_MS));
    }

    private void rememberJsonArrayScan(Map<String, JsonArrayCacheEntry> cache, String key, JsonArray result) {
        if (cache.size() >= MAX_SCAN_CONTEXT_CACHE_ENTRIES) {
            pruneScanContextCache();
            if (cache.size() >= MAX_SCAN_CONTEXT_CACHE_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new JsonArrayCacheEntry(result.deepCopy(), System.currentTimeMillis() + SCAN_CONTEXT_CACHE_TTL_MS));
    }

    private BlockScanResult copyBlockScanResult(BlockScanResult result) {
        return new BlockScanResult(
            result.details().deepCopy(),
            result.summary().deepCopy(),
            result.scannedBlocks(),
            result.detailTruncated()
        );
    }

    private void pruneScanContextCache() {
        long now = System.currentTimeMillis();
        blockScanCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
        entityScanCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
        itemScanCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
    }

    private BlockScanResult scanBlocks(int radius, int verticalRadius, int detailBudget, String profile) {
        JsonArray details = new JsonArray();
        JsonObject summary = new JsonObject();
        if (client.player == null || client.world == null) {
            return new BlockScanResult(details, summary, 0, false);
        }

        BlockPos origin = client.player.getBlockPos();
        int scanned = 0;
        boolean truncated = false;
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    var state = client.world.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    double distance = Math.sqrt(origin.getSquaredDistance(pos));
                    addBlockSummary(summary, blockId, distance);
                    scanned++;
                    if (details.size() >= detailBudget) {
                        truncated = true;
                        continue;
                    }
                    if (!shouldIncludeBlockDetail(profile, blockId, state)) {
                        continue;
                    }
                    JsonObject block = blockDetail(blockId, pos, distance, state);
                    details.add(block);
                }
            }
        }
        return new BlockScanResult(details, summary, scanned, truncated);
    }

    private JsonObject blockDetail(String blockId, BlockPos pos, double distance, BlockState state) {
        JsonObject block = new JsonObject();
        block.addProperty("block", blockId);
        block.addProperty("x", pos.getX());
        block.addProperty("y", pos.getY());
        block.addProperty("z", pos.getZ());
        block.addProperty("distance", distance);
        block.addProperty("light_level", client.world == null ? 0 : client.world.getLightLevel(pos));
        addCropMetadata(block, state);
        return block;
    }

    private void addBlockSummary(JsonObject summary, String blockId, double distance) {
        JsonObject entry;
        if (summary.has(blockId) && summary.get(blockId).isJsonObject()) {
            entry = summary.getAsJsonObject(blockId);
        } else {
            entry = new JsonObject();
            entry.addProperty("count", 0);
            entry.addProperty("nearest_distance", distance);
            summary.add(blockId, entry);
        }
        entry.addProperty("count", entry.get("count").getAsInt() + 1);
        if (distance < entry.get("nearest_distance").getAsDouble()) {
            entry.addProperty("nearest_distance", distance);
        }
    }

    private ExecutorProtocol.StepResult breakBlockAtOrExpose(BlockPos target, double breakReach, int timeoutTicks, String actionType) {
        ExecutorProtocol.StepResult result = breakBlockAt(target, breakReach, timeoutTicks, actionType);
        if ("accepted".equals(result.status()) || aborted) {
            return result;
        }

        ExecutorProtocol.StepResult lastBreakResult = result;
        int maxExposureAttempts = config.maxExposureAttempts();
        for (int exposureAttempt = 1; exposureAttempt <= maxExposureAttempts && !aborted; exposureAttempt++) {
            ExecutorProtocol.StepResult exposed = exposeTargetBlock(target, breakReach, actionType);
            if (!"accepted".equals(exposed.status())) {
                return lastBreakResult;
            }

            ExecutorProtocol.StepResult retry = breakBlockAt(target, breakReach, timeoutTicks, actionType);
            if (retry.data() != null) {
                retry.data().addProperty("exposure_retry", true);
                retry.data().addProperty("exposure_attempt", exposureAttempt);
                retry.data().addProperty("max_exposure_attempts", maxExposureAttempts);
                if (exposed.data() != null) {
                    retry.data().add("exposure_result", exposed.data());
                }
            }
            if ("accepted".equals(retry.status())) {
                return retry;
            }
            lastBreakResult = retry;
        }
        return lastBreakResult;
    }

    private boolean shouldIncludeBlockDetail(String profile, String blockId, BlockState state) {
        if (profile.equals("default")) {
            return true;
        }
        if (profile.equals("summary_only")) {
            return false;
        }
        return isHighValueScanBlock(blockId, state);
    }

    private boolean isHighValueScanBlock(String blockId, BlockState state) {
        return blockId.contains("lava")
            || blockId.contains("water")
            || blockId.contains("fire")
            || blockId.endsWith("_ore")
            || blockId.endsWith("_log")
            || blockId.endsWith("_stem")
            || blockId.endsWith("_door")
            || blockId.endsWith("_fence_gate")
            || blockId.equals("minecraft:crafting_table")
            || blockId.equals("minecraft:furnace")
            || blockId.equals("minecraft:blast_furnace")
            || blockId.equals("minecraft:smoker")
            || blockId.equals("minecraft:stonecutter")
            || blockId.equals("minecraft:smithing_table")
            || blockId.equals("minecraft:chest")
            || blockId.equals("minecraft:barrel")
            || blockId.equals("minecraft:bell")
            || blockId.equals("minecraft:composter")
            || blockId.equals("minecraft:hay_block")
            || state.getBlock() instanceof CropBlock
            || state.getBlock() instanceof SweetBerryBushBlock
            || state.getBlock() instanceof NetherWartBlock
            || state.getBlock() instanceof CocoaBlock;
    }

    private JsonArray scanEntities(int radius) {
        JsonArray entities = new JsonArray();
        if (client.player == null || client.world == null) {
            return entities;
        }

        Box box = new Box(client.player.getBlockPos()).expand(radius);
        List<Entity> nearby = client.world.getOtherEntities(client.player, box);
        int samples = 0;
        Vec3d origin = client.player.getPos();
        for (Entity entity : nearby) {
            if (samples >= MAX_ENTITY_SAMPLES) {
                break;
            }
            JsonObject item = new JsonObject();
            String entityId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            item.addProperty("type", entityId);
            item.addProperty("name", entity.getName().getString());
            item.add("position", vec(entity.getX(), entity.getY(), entity.getZ()));
            item.addProperty("distance", origin.distanceTo(entity.getPos()));
            item.addProperty("food_animal", isFoodAnimal(entityId));
            item.addProperty("hostile", isHostileEntity(entityId));
            entities.add(item);
            samples++;
        }
        return entities;
    }

    private JsonArray scanItems(int radius) {
        JsonArray items = new JsonArray();
        if (client.player == null || client.world == null) {
            return items;
        }

        Box box = new Box(client.player.getBlockPos()).expand(radius);
        List<Entity> nearby = client.world.getOtherEntities(client.player, box);
        int samples = 0;
        Vec3d origin = client.player.getPos();
        for (Entity entity : nearby) {
            if (samples >= MAX_ENTITY_SAMPLES) {
                break;
            }
            if (!(entity instanceof ItemEntity itemEntity) || itemEntity.getStack().isEmpty()) {
                continue;
            }
            JsonObject item = new JsonObject();
            String itemId = itemId(itemEntity.getStack());
            item.addProperty("item", itemId);
            item.addProperty("count", itemEntity.getStack().getCount());
            item.addProperty("distance", origin.distanceTo(itemEntity.getPos()));
            item.addProperty("food_drop", isFoodDrop(itemId));
            item.add("position", vec(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ()));
            items.add(item);
            samples++;
        }
        return items;
    }

    private boolean isFoodAnimal(String entityId) {
        return Set.of(
            "minecraft:cow",
            "minecraft:mooshroom",
            "minecraft:pig",
            "minecraft:chicken",
            "minecraft:sheep",
            "minecraft:rabbit",
            "minecraft:cod",
            "minecraft:salmon"
        ).contains(entityId);
    }

    private boolean isSupportedEntityGroup(String group) {
        return Set.of("food_animal", "hostile", "sheep", "cow", "chicken", "squid", "skeleton", "spider", "creeper", "enderman", "blaze", "slime", "rabbit", "guardian", "ghast", "shulker", "wither_skeleton", "breeze", "drowned").contains(group);
    }

    private boolean matchesEntityGroup(String group, String entityId) {
        return switch (group) {
            case "food_animal" -> isFoodAnimal(entityId);
            case "hostile" -> isHostileEntity(entityId);
            case "sheep" -> entityId.equals("minecraft:sheep");
            case "cow" -> entityId.equals("minecraft:cow") || entityId.equals("minecraft:mooshroom");
            case "chicken" -> entityId.equals("minecraft:chicken");
            case "squid" -> entityId.equals("minecraft:squid") || entityId.equals("minecraft:glow_squid");
            case "skeleton" -> entityId.equals("minecraft:skeleton") || entityId.equals("minecraft:stray");
            case "spider" -> entityId.equals("minecraft:spider") || entityId.equals("minecraft:cave_spider");
            case "creeper" -> entityId.equals("minecraft:creeper");
            case "enderman" -> entityId.equals("minecraft:enderman");
            case "blaze" -> entityId.equals("minecraft:blaze");
            case "slime" -> entityId.equals("minecraft:slime") || entityId.equals("minecraft:magma_cube");
            case "rabbit" -> entityId.equals("minecraft:rabbit");
            case "guardian" -> entityId.equals("minecraft:guardian") || entityId.equals("minecraft:elder_guardian");
            case "ghast" -> entityId.equals("minecraft:ghast");
            case "shulker" -> entityId.equals("minecraft:shulker");
            case "wither_skeleton" -> entityId.equals("minecraft:wither_skeleton");
            case "breeze" -> entityId.equals("minecraft:breeze");
            case "drowned" -> entityId.equals("minecraft:drowned");
            default -> false;
        };
    }

    private boolean isHostileEntity(String entityId) {
        return Set.of(
            "minecraft:zombie",
            "minecraft:zombie_villager",
            "minecraft:husk",
            "minecraft:drowned",
            "minecraft:skeleton",
            "minecraft:stray",
            "minecraft:spider",
            "minecraft:cave_spider",
            "minecraft:creeper",
            "minecraft:enderman",
            "minecraft:witch",
            "minecraft:slime",
            "minecraft:magma_cube",
            "minecraft:phantom",
            "minecraft:pillager",
            "minecraft:vindicator",
            "minecraft:evoker",
            "minecraft:ravager",
            "minecraft:blaze",
            "minecraft:ghast",
            "minecraft:wither_skeleton",
            "minecraft:piglin_brute",
            "minecraft:warden"
        ).contains(entityId);
    }

    private JsonArray scanInventory() {
        JsonArray inventory = new JsonArray();
        if (client.player == null) {
            return inventory;
        }
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
            item.addProperty("count", stack.getCount());
            item.addProperty("slot", slot);
            item.addProperty("hotbar", slot >= 0 && slot < 9);
            inventory.add(item);
        }
        return inventory;
    }

    private boolean shouldSummarizeRecipeBook(String profile) {
        return profile.equals("voice_default") || profile.equals("summary_only");
    }

    private JsonObject scanRecipeBook(boolean summaryOnly) {
        JsonObject recipeBook = new JsonObject();
        recipeBook.addProperty("quick_craft_supported", true);
        recipeBook.addProperty("bundled_crafting_recipe_count", vanillaCraftRecipes().size());
        recipeBook.addProperty("bundled_stonecutting_recipe_count", vanillaStonecuttingRecipes().size());
        recipeBook.addProperty("bundled_smithing_recipe_count", vanillaSmithingRecipes().size());
        recipeBook.addProperty("bundled_smithing_trim_recipe_count", vanillaSmithingTrimRecipes().size());
        recipeBook.addProperty("execution_order", "recipebook_then_bundled_crafting_stonecutting_smithing_or_component_verified_trim_layout_then_allowlisted_slot_layout");
        recipeBook.addProperty("summary_only", summaryOnly);
        JsonArray entries = new JsonArray();
        if (client.player == null) {
            recipeBook.addProperty("available", false);
            recipeBook.add("entries", entries);
            return recipeBook;
        }
        recipeBook.addProperty("available", true);
        int collections = 0;
        int entryCount = 0;
        int craftableCount = 0;
        for (RecipeResultCollection collection : client.player.getRecipeBook().getOrderedResults()) {
            collections++;
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                String output = recipeDisplayOutputItem(entry);
                if (output.isBlank()) {
                    continue;
                }
                entryCount++;
                if (collection.isCraftable(entry.id())) {
                    craftableCount++;
                }
                if (summaryOnly) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("network_recipe_id", entry.id().index());
                item.addProperty("output", output);
                item.addProperty("craftable", collection.isCraftable(entry.id()));
                item.addProperty("category", entry.category().toString());
                entries.add(item);
            }
        }
        recipeBook.addProperty("collection_count", collections);
        recipeBook.addProperty("entry_count", entryCount);
        recipeBook.addProperty("craftable_entry_count", craftableCount);
        recipeBook.addProperty("entries_omitted", summaryOnly);
        recipeBook.add("entries", entries);
        return recipeBook;
    }

    private void addCropMetadata(JsonObject block, BlockState state) {
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        if (state.getBlock() instanceof CropBlock crop) {
            block.addProperty("crop", true);
            block.addProperty("crop_age", crop.getAge(state));
            block.addProperty("crop_max_age", crop.getMaxAge());
            block.addProperty("crop_mature", crop.isMature(state));
            return;
        }
        if (state.getBlock() instanceof SweetBerryBushBlock && state.contains(SweetBerryBushBlock.AGE)) {
            int age = state.get(SweetBerryBushBlock.AGE);
            block.addProperty("crop", true);
            block.addProperty("crop_age", age);
            block.addProperty("crop_max_age", SweetBerryBushBlock.MAX_AGE);
            block.addProperty("crop_mature", age >= 2);
            return;
        }
        if (state.getBlock() instanceof CocoaBlock && state.contains(CocoaBlock.AGE)) {
            int age = state.get(CocoaBlock.AGE);
            block.addProperty("crop", true);
            block.addProperty("crop_age", age);
            block.addProperty("crop_max_age", CocoaBlock.MAX_AGE);
            block.addProperty("crop_mature", age >= CocoaBlock.MAX_AGE);
            return;
        }
        if (state.getBlock() instanceof NetherWartBlock && state.contains(NetherWartBlock.AGE)) {
            int age = state.get(NetherWartBlock.AGE);
            block.addProperty("crop", true);
            block.addProperty("crop_age", age);
            block.addProperty("crop_max_age", NetherWartBlock.MAX_AGE);
            block.addProperty("crop_mature", age >= NetherWartBlock.MAX_AGE);
            return;
        }
        if (blockId.equals("minecraft:pumpkin") || blockId.equals("minecraft:melon") || blockId.equals("minecraft:sugar_cane")) {
            block.addProperty("crop", true);
            block.addProperty("crop_mature", true);
        }
    }

    private boolean isMatureFoodCrop(BlockState state, String blockId) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        if (state.getBlock() instanceof SweetBerryBushBlock && state.contains(SweetBerryBushBlock.AGE)) {
            return state.get(SweetBerryBushBlock.AGE) >= 2;
        }
        if (state.getBlock() instanceof CocoaBlock && state.contains(CocoaBlock.AGE)) {
            return state.get(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        }
        if (state.getBlock() instanceof NetherWartBlock && state.contains(NetherWartBlock.AGE)) {
            return state.get(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        }
        return blockId.equals("minecraft:pumpkin") || blockId.equals("minecraft:melon") || blockId.equals("minecraft:sugar_cane");
    }

    private JsonArray scanDangerousConditions(int radius) {
        JsonArray conditions = new JsonArray();
        if (client.player == null || client.world == null) {
            return conditions;
        }
        if (client.player.getHealth() < 8.0F) {
            conditions.add("health_below_8");
        }
        if (client.player.getHungerManager().getFoodLevel() < 6) {
            conditions.add("hunger_below_6");
        }
        if (hasHostileMobWithin(Math.min(radius, 5))) {
            conditions.add("hostile_mob_within_5_blocks");
        }
        if (hasDangerBlockWithin("minecraft:lava", 2) || hasDangerBlockWithin("minecraft:fire", 2) || hasDangerBlockWithin("minecraft:soul_fire", 2)) {
            conditions.add("lava_within_2_blocks");
        }
        if (blockIdAt(client.player.getBlockPos()).equals("minecraft:lava") || blockIdAt(client.player.getBlockPos().up()).equals("minecraft:lava")) {
            conditions.add("in_lava");
        }
        if (blockIdAt(client.player.getBlockPos()).equals("minecraft:water") || blockIdAt(client.player.getBlockPos().up()).equals("minecraft:water")) {
            conditions.add("in_water");
        }
        if (blockIdAt(client.player.getBlockPos()).equals("minecraft:fire") || blockIdAt(client.player.getBlockPos()).equals("minecraft:soul_fire")) {
            conditions.add("fire_within_2_blocks");
        }
        if (hasFallRisk()) {
            conditions.add("fall_risk_detected");
        }
        if (isInventoryFull()) {
            conditions.add("inventory_full");
        }
        return conditions;
    }

    private boolean hasHostileMobWithin(int radius) {
        if (client.player == null || client.world == null) {
            return false;
        }
        Box box = new Box(client.player.getBlockPos()).expand(radius);
        for (Entity entity : client.world.getOtherEntities(client.player, box)) {
            String id = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            if (isHostileEntity(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDangerBlockWithin(String blockId, int radius) {
        if (client.player == null || client.world == null) {
            return false;
        }
        BlockPos origin = client.player.getBlockPos();
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (blockIdAt(origin.add(x, y, z)).equals(blockId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasFallRisk() {
        if (client.player == null || client.world == null) {
            return false;
        }
        BlockPos below = client.player.getBlockPos().down();
        if (!client.world.getBlockState(below).isAir()) {
            return false;
        }
        int airBelow = 0;
        for (int depth = 1; depth <= 5; depth++) {
            if (client.world.getBlockState(client.player.getBlockPos().down(depth)).isAir()) {
                airBelow++;
            }
        }
        return airBelow >= 3;
    }

    private boolean isInventoryFull() {
        if (client.player == null) {
            return false;
        }
        int checkedSlots = Math.min(36, client.player.getInventory().size());
        for (int slot = 0; slot < checkedSlots; slot++) {
            if (client.player.getInventory().getStack(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasImmediateAbortDanger() {
        if (client.player == null || client.world == null) {
            return true;
        }
        return client.player.getHealth() < 8.0F
            || hasDangerBlockWithin("minecraft:lava", 2)
            || hasDangerBlockWithin("minecraft:fire", 2)
            || hasDangerBlockWithin("minecraft:soul_fire", 2)
            || hasFallRisk();
    }

    private JsonArray vec(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    private void addBlockPos(JsonObject data, String prefix, BlockPos pos) {
        data.addProperty(prefix + "_x", pos.getX());
        data.addProperty(prefix + "_y", pos.getY());
        data.addProperty(prefix + "_z", pos.getZ());
        data.addProperty(prefix + "_block", blockIdAt(pos));
    }

    private double numberField(JsonObject object, String name, double fallback) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive() || !object.get(name).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return object.get(name).getAsDouble();
    }

    private ExecutorProtocol.StepResult placeBlock(ExecutorProtocol.Action action) {
        String targetKind = action.objectField("target").has("kind")
            ? action.objectField("target").get("kind").getAsString()
            : "";
        if (targetKind.equals("nearby_dark_spot")) {
            BlockHitResult candidate = callOnClientThread(() -> findNearbyTorchPlacement(8));
            if (candidate == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No nearby dark floor spot found for torch placement.");
            }
            ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(candidate.getBlockPos(), config.maxReach(), 120, action.type());
            if (!"accepted".equals(approached.status())) {
                return approached;
            }
        }
        return runOnClientThread(() -> placeBlockNow(action, targetKind));
    }

    private ExecutorProtocol.StepResult buildBlueprint(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }
            String style = action.stringField("style");
            if (!style.equals("small_house") && !style.equals("cute_house") && !style.equals("hideout")) {
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported blueprint style: " + style);
            }
            String palette = action.stringField("palette");
            String material = preferredBlueprintMaterial(palette);
            JsonObject data = new JsonObject();
            data.addProperty("style", style);
            data.addProperty("palette", palette);
            boolean programmaticBuild = action.booleanField("programmatic_build", false) && config.worldAssistSmallBuildsEnabled();
            boolean terrainAware = action.booleanField("terrain_aware", programmaticBuild);
            int animationDelayTicks = Math.max(1, Math.min(action.intField("animation_delay_ticks", 2), 8));
            data.addProperty("programmatic_build", programmaticBuild);
            data.addProperty("world_assist", programmaticBuild);
            data.addProperty("terrain_aware", terrainAware);
            data.addProperty("animated", action.booleanField("animated", programmaticBuild));
            data.addProperty("animation_delay_ticks", animationDelayTicks);
            if (programmaticBuild) {
                return buildProgrammaticBlueprint(action, style, palette, data, animationDelayTicks);
            }
            int maxBlocks = Math.max(8, Math.min(action.intField("max_blocks", 18), 48));
            data.addProperty("material", material);
            if (material.isBlank()) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No placeable building material is available.", data);
            }
            int available = countInventoryItem(material);
            int budget = Math.min(maxBlocks, available);
            data.addProperty("available_material_count", available);
            data.addProperty("block_budget", budget);
            if (budget <= 0) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Selected building material is not available.", data);
            }
            Direction forward = client.player.getHorizontalFacing();
            Direction right = forward.rotateYClockwise();
            BlockPos base = client.player.getBlockPos().offset(forward, 1).offset(right, -1);
            List<BlockPos> targets = blueprintTargets(base, right, forward, style);
            JsonArray placedBlocks = new JsonArray();
            int placed = 0;
            int skippedOccupied = 0;
            int skippedOutOfReach = 0;
            for (BlockPos target : targets) {
                if (aborted) {
                    break;
                }
                if (placed >= budget || countInventoryItem(material) <= 0) {
                    break;
                }
                String current = blockIdAt(target);
                if (!current.equals("minecraft:air") && !current.equals("minecraft:water")) {
                    skippedOccupied++;
                    continue;
                }
                if (isDangerNear(target, 1)) {
                    addBlockPos(data, "danger_target", target);
                    break;
                }
                if (!canReachPlacementTarget(target)) {
                    skippedOutOfReach++;
                    continue;
                }
                if (!placeItemAt(material, target)) {
                    addBlockPos(data, "failed_target", target);
                    data.addProperty("placed_count", placed);
                    data.addProperty("skipped_occupied", skippedOccupied);
                    data.addProperty("skipped_out_of_reach", skippedOutOfReach);
                    data.add("placed", placedBlocks);
                    return new ExecutorProtocol.StepResult(action.type(), placed > 0 ? "partial" : "blocked", "Blueprint placement stopped because a block could not be placed.", data);
                }
                JsonObject one = new JsonObject();
                one.addProperty("x", target.getX());
                one.addProperty("y", target.getY());
                one.addProperty("z", target.getZ());
                placedBlocks.add(one);
                placed++;
                sleepTicks(2);
            }
            data.addProperty("requested_positions", targets.size());
            data.addProperty("placed_count", placed);
            data.addProperty("skipped_occupied", skippedOccupied);
            data.addProperty("skipped_out_of_reach", skippedOutOfReach);
            data.add("placed", placedBlocks);
            if (aborted) {
                return new ExecutorProtocol.StepResult(action.type(), "aborted", "Blueprint construction was interrupted.", data);
            }
            if (placed == 0) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No blueprint blocks could be placed safely.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), placed >= Math.min(12, targets.size()) ? "accepted" : "partial", "Built a bounded decorative blueprint from available survival blocks.", data);
        });
    }

    private ExecutorProtocol.StepResult buildProgrammaticBlueprint(ExecutorProtocol.Action action, String style, String palette, JsonObject data, int animationDelayTicks) {
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or world is not ready for world-assist blueprint.", data);
        }
        int maxBlocks = Math.max(24, Math.min(action.intField("max_blocks", 96), 160));
        String size = action.stringField("size");
        int width = size.equals("small") ? 5 : 5;
        int depth = size.equals("small") ? 5 : 5;
        BlueprintSite site = chooseBlueprintSite(width, depth, client.player.getHorizontalFacing(), 9);
        if (site == null) {
            data.addProperty("site_found", false);
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No nearby terrain-aware build site was found.", data);
        }
        data.addProperty("site_found", true);
        addBlockPos(data, "base", site.base());
        data.addProperty("width", site.width());
        data.addProperty("depth", site.depth());
        data.addProperty("site_score", site.score());
        String wallBlock = programmaticWallBlock(palette, style);
        String floorBlock = programmaticFloorBlock(palette);
        String roofBlock = programmaticRoofBlock(palette, style);
        String windowBlock = style.equals("cute_house") ? "minecraft:glass" : "";
        data.addProperty("floor_block", floorBlock);
        data.addProperty("wall_block", wallBlock);
        data.addProperty("roof_block", roofBlock);
        if (!windowBlock.isBlank()) {
            data.addProperty("window_block", windowBlock);
        }

        List<BlueprintBlock> blocks = programmaticHouseBlocks(site, style, floorBlock, wallBlock, roofBlock, windowBlock);
        JsonArray placedBlocks = new JsonArray();
        int placed = 0;
        int skippedOccupied = 0;
        int skippedInvalid = 0;
        for (BlueprintBlock block : blocks) {
            if (aborted || placed >= maxBlocks) {
                break;
            }
            if (!isProgrammaticBuildBlockAllowed(block.blockId())) {
                skippedInvalid++;
                continue;
            }
            boolean allowSolidFloor = block.role().equals("floor") || block.role().equals("foundation");
            if (!isReplaceableForBlueprint(block.pos(), allowSolidFloor)) {
                skippedOccupied++;
                continue;
            }
            if (isDangerNear(block.pos(), 1)) {
                addBlockPos(data, "danger_target", block.pos());
                break;
            }
            ProgrammaticBlockPlacementResult result = placeProgrammaticBlock(block.blockId(), block.pos());
            if (!result.placed()) {
                skippedInvalid++;
                data.addProperty("last_place_reason", result.reason());
                continue;
            }
            JsonObject one = new JsonObject();
            one.addProperty("x", block.pos().getX());
            one.addProperty("y", block.pos().getY());
            one.addProperty("z", block.pos().getZ());
            one.addProperty("block", block.blockId());
            one.addProperty("role", block.role());
            placedBlocks.add(one);
            placed++;
            sleepTicks(animationDelayTicks);
        }
        data.addProperty("requested_positions", blocks.size());
        data.addProperty("placed_count", placed);
        data.addProperty("skipped_occupied", skippedOccupied);
        data.addProperty("skipped_invalid", skippedInvalid);
        data.addProperty("programmatic_assist", true);
        data.addProperty("world_assist", true);
        data.addProperty("command_free", true);
        data.addProperty("assist_mode", config.assistMode());
        data.add("placed", placedBlocks);
        if (aborted) {
            return new ExecutorProtocol.StepResult(action.type(), "aborted", "Programmatic blueprint construction was interrupted.", data);
        }
        if (placed == 0) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "No world-assist blueprint blocks could be placed safely.", data);
        }
        int minimumHouseBlocks = Math.min(60, blocks.size());
        return new ExecutorProtocol.StepResult(
            action.type(),
            placed >= minimumHouseBlocks ? "accepted" : "partial",
            "Built an animated terrain-aware house blueprint with world assist.",
            data
        );
    }

    private BlueprintSite chooseBlueprintSite(int width, int depth, Direction forward, int radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Direction right = forward.rotateYClockwise();
        BlockPos origin = client.player.getBlockPos();
        BlueprintSite best = null;
        for (int forwardOffset = 3; forwardOffset <= radius; forwardOffset++) {
            for (int lateral = -5; lateral <= 5; lateral++) {
                BlockPos center = origin.offset(forward, forwardOffset).offset(right, lateral);
                BlockPos baseColumn = center.offset(right, -(width / 2));
                int minSupportY = Integer.MAX_VALUE;
                int maxSupportY = Integer.MIN_VALUE;
                boolean allColumnsUsable = true;
                for (int x = 0; x < width && allColumnsUsable; x++) {
                    for (int z = 0; z < depth; z++) {
                        BlockPos column = baseColumn.offset(right, x).offset(forward, z);
                        int supportY = findBuildSupportY(column.getX(), column.getZ(), origin.getY(), 4);
                        if (supportY == Integer.MIN_VALUE) {
                            allColumnsUsable = false;
                            break;
                        }
                        minSupportY = Math.min(minSupportY, supportY);
                        maxSupportY = Math.max(maxSupportY, supportY);
                    }
                }
                if (!allColumnsUsable || maxSupportY - minSupportY > 1) {
                    continue;
                }
                BlockPos base = new BlockPos(baseColumn.getX(), maxSupportY, baseColumn.getZ());
                if (!isBlueprintSiteClear(base, right, forward, width, depth)) {
                    continue;
                }
                double score = forwardOffset + Math.abs(lateral) * 0.35D + (maxSupportY - minSupportY) * 5.0D;
                BlueprintSite site = new BlueprintSite(base, right, forward, width, depth, score);
                if (best == null || site.score() < best.score()) {
                    best = site;
                }
            }
        }
        return best;
    }

    private int findBuildSupportY(int x, int z, int centerY, int verticalRadius) {
        for (int dy = verticalRadius; dy >= -verticalRadius; dy--) {
            BlockPos support = new BlockPos(x, centerY + dy, z);
            if (!hasBlockingCollision(support) || isUnsafeFluid(support)) {
                continue;
            }
            if (hasBlockingCollision(support.up()) || hasBlockingCollision(support.up(2))) {
                continue;
            }
            return support.getY();
        }
        return Integer.MIN_VALUE;
    }

    private boolean isBlueprintSiteClear(BlockPos base, Direction right, Direction forward, int width, int depth) {
        if (client.player == null || client.world == null) {
            return false;
        }
        BlockPos playerPos = client.player.getBlockPos();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                BlockPos floor = base.offset(right, x).offset(forward, z);
                if (isDangerNear(floor, 1)) {
                    return false;
                }
                if (playerPos.getX() == floor.getX() && playerPos.getZ() == floor.getZ() && Math.abs(playerPos.getY() - floor.getY()) <= 3) {
                    return false;
                }
                if (!isReplaceableForBlueprint(floor, true)) {
                    return false;
                }
                for (int y = 1; y <= 3; y++) {
                    if (!isReplaceableForBlueprint(floor.up(y), false)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private List<BlueprintBlock> programmaticHouseBlocks(BlueprintSite site, String style, String floorBlock, String wallBlock, String roofBlock, String windowBlock) {
        ArrayList<BlueprintBlock> blocks = new ArrayList<>();
        int middle = site.width() / 2;
        for (int x = 0; x < site.width(); x++) {
            for (int z = 0; z < site.depth(); z++) {
                blocks.add(new BlueprintBlock(site.base().offset(site.right(), x).offset(site.forward(), z), floorBlock, "floor"));
            }
        }
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x < site.width(); x++) {
                for (int z = 0; z < site.depth(); z++) {
                    boolean edge = x == 0 || x == site.width() - 1 || z == 0 || z == site.depth() - 1;
                    if (!edge) {
                        continue;
                    }
                    boolean door = z == 0 && x == middle && y <= 2;
                    boolean sideWindow = !windowBlock.isBlank() && y == 2 && z == site.depth() / 2 && (x == 0 || x == site.width() - 1);
                    boolean backWindow = !windowBlock.isBlank() && y == 2 && z == site.depth() - 1 && x == middle;
                    if (door) {
                        continue;
                    }
                    BlockPos pos = site.base().offset(site.right(), x).offset(site.forward(), z).up(y);
                    if (sideWindow || backWindow) {
                        blocks.add(new BlueprintBlock(pos, windowBlock, "window"));
                    } else {
                        blocks.add(new BlueprintBlock(pos, wallBlock, "wall"));
                    }
                }
            }
        }
        for (int x = -1; x <= site.width(); x++) {
            for (int z = -1; z <= site.depth(); z++) {
                blocks.add(new BlueprintBlock(site.base().offset(site.right(), x).offset(site.forward(), z).up(3), roofBlock, "roof"));
            }
        }
        if (style.equals("cute_house")) {
            for (int z = 0; z < site.depth(); z++) {
                blocks.add(new BlueprintBlock(site.base().offset(site.right(), middle).offset(site.forward(), z).up(4), roofBlock, "roof_ridge"));
            }
        }
        return blocks;
    }

    private String programmaticFloorBlock(String palette) {
        return switch (palette) {
            case "dirt" -> "minecraft:dirt";
            case "sand" -> "minecraft:sandstone";
            default -> "minecraft:oak_planks";
        };
    }

    private String programmaticWallBlock(String palette, String style) {
        return switch (palette) {
            case "dirt" -> "minecraft:dirt";
            case "sand" -> "minecraft:sandstone";
            default -> "minecraft:oak_planks";
        };
    }

    private String programmaticRoofBlock(String palette, String style) {
        if (palette.equals("dirt")) {
            return "minecraft:dirt";
        }
        if (palette.equals("sand")) {
            return "minecraft:smooth_sandstone";
        }
        return style.equals("hideout") ? "minecraft:oak_planks" : "minecraft:cobblestone";
    }

    private boolean isProgrammaticBuildBlockAllowed(String blockId) {
        return blockId.equals("minecraft:oak_planks")
            || blockId.equals("minecraft:cobblestone")
            || blockId.equals("minecraft:glass")
            || blockId.equals("minecraft:dirt")
            || blockId.equals("minecraft:sandstone")
            || blockId.equals("minecraft:smooth_sandstone");
    }

    private boolean isReplaceableForBlueprint(BlockPos pos, boolean allowSolidFloor) {
        String current = blockIdAt(pos);
        if (current.equals("minecraft:air")
            || current.equals("minecraft:cave_air")
            || current.equals("minecraft:void_air")
            || current.equals("minecraft:water")
            || current.equals("minecraft:grass")
            || current.equals("minecraft:tall_grass")
            || current.equals("minecraft:fern")
            || current.equals("minecraft:large_fern")
            || current.endsWith("_flower")
            || current.equals("minecraft:dandelion")
            || current.equals("minecraft:poppy")
            || current.equals("minecraft:snow")) {
            return true;
        }
        return allowSolidFloor && (current.equals("minecraft:grass_block")
            || current.equals("minecraft:dirt")
            || current.equals("minecraft:coarse_dirt")
            || current.equals("minecraft:sand")
            || current.equals("minecraft:red_sand")
            || current.equals("minecraft:gravel")
            || current.equals("minecraft:podzol")
            || current.equals("minecraft:mycelium"));
    }

    private ProgrammaticBlockPlacementResult placeProgrammaticBlock(String blockId, BlockPos pos) {
        if (client.player == null || client.world == null) {
            return new ProgrammaticBlockPlacementResult(false, "player_or_world_not_ready");
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            return new ProgrammaticBlockPlacementResult(false, "integrated_server_not_available");
        }
        RegistryKey<World> dimensionKey = client.world.getRegistryKey();
        CompletableFuture<ProgrammaticBlockPlacementResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld serverWorld = server.getWorld(dimensionKey);
                if (serverWorld == null) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "server_world_not_ready"));
                    return;
                }
                net.minecraft.block.Block block = Registries.BLOCK.get(Identifier.of(blockId));
                if (block == Blocks.AIR) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "invalid_block"));
                    return;
                }
                boolean placed = serverWorld.setBlockState(pos, block.getDefaultState(), 3);
                future.complete(new ProgrammaticBlockPlacementResult(placed, placed ? "ok" : "set_block_state_failed"));
            } catch (RuntimeException error) {
                future.complete(new ProgrammaticBlockPlacementResult(false, error.toString()));
            }
        });
        try {
            return future.get(800, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            return new ProgrammaticBlockPlacementResult(false, error.toString());
        }
    }

    private ProgrammaticBlockBreakResult breakProgrammaticCommonBlock(BlockPos pos) {
        if (client.player == null || client.world == null) {
            return new ProgrammaticBlockBreakResult(false, "", "", 0, 0, 0, "player_or_world_not_ready");
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            return new ProgrammaticBlockBreakResult(false, "", "", 0, 0, 0, "integrated_server_not_available");
        }
        RegistryKey<World> dimensionKey = client.world.getRegistryKey();
        UUID playerUuid = client.player.getUuid();
        CompletableFuture<ProgrammaticBlockBreakResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld serverWorld = server.getWorld(dimensionKey);
                ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);
                if (serverWorld == null || serverPlayer == null) {
                    future.complete(new ProgrammaticBlockBreakResult(false, "", "", 0, 0, 0, "server_world_or_player_not_ready"));
                    return;
                }
                BlockState state = serverWorld.getBlockState(pos);
                String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                if (state.isAir()) {
                    future.complete(new ProgrammaticBlockBreakResult(true, blockId, "", 0, 0, 0, "already_air"));
                    return;
                }
                if (pos.equals(serverPlayer.getBlockPos()) || pos.equals(serverPlayer.getBlockPos().down())) {
                    future.complete(new ProgrammaticBlockBreakResult(false, blockId, "", 0, 0, 0, "refuse_player_feet_block"));
                    return;
                }
                if (!isWorldAssistDirectBreakAllowed(blockId, state, pos, serverWorld)) {
                    future.complete(new ProgrammaticBlockBreakResult(false, blockId, "", 0, 0, 0, "block_not_allowed"));
                    return;
                }
                boolean broken = serverWorld.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                if (!broken) {
                    future.complete(new ProgrammaticBlockBreakResult(false, blockId, "", 0, 0, 0, "set_air_failed"));
                    return;
                }
                String dropItem = worldAssistDirectBreakDropItem(blockId);
                int dropCount = worldAssistDirectBreakDropCount(blockId);
                int inserted = 0;
                int spawned = 0;
                Item item = itemById(dropItem);
                if (item != null && dropCount > 0 && isVoiceAssistGrantAllowedItem(dropItem)) {
                    ItemStack stack = new ItemStack(item, dropCount);
                    serverPlayer.getInventory().insertStack(stack);
                    inserted = dropCount - stack.getCount();
                    if (inserted > 0) {
                        serverPlayer.getInventory().markDirty();
                        serverPlayer.playerScreenHandler.sendContentUpdates();
                        serverPlayer.currentScreenHandler.sendContentUpdates();
                    }
                    if (!stack.isEmpty()) {
                        ItemEntity dropped = new ItemEntity(
                            serverWorld,
                            pos.getX() + 0.5D,
                            pos.getY() + 0.5D,
                            pos.getZ() + 0.5D,
                            stack
                        );
                        if (serverWorld.spawnEntity(dropped)) {
                            spawned = stack.getCount();
                        }
                    }
                }
                future.complete(new ProgrammaticBlockBreakResult(true, blockId, dropItem, dropCount, inserted, spawned, "ok"));
            } catch (RuntimeException error) {
                future.complete(new ProgrammaticBlockBreakResult(false, "", "", 0, 0, 0, error.toString()));
            }
        });
        try {
            return future.get(800, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            return new ProgrammaticBlockBreakResult(false, "", "", 0, 0, 0, error.toString());
        }
    }

    private boolean isWorldAssistDirectBreakAllowed(String blockId, BlockState state, BlockPos pos) {
        return client.world != null && isWorldAssistDirectBreakAllowed(blockId, state, pos, client.world);
    }

    private boolean isWorldAssistDirectBreakAllowed(String blockId, BlockState state, BlockPos pos, World world) {
        if (world == null || blockId == null || blockId.isBlank()) {
            return false;
        }
        if (state.isAir() || !state.getFluidState().isEmpty() || blockId.contains("water") || blockId.contains("lava") || state.getHardness(world, pos) < 0.0F) {
            return false;
        }
        if (blockId.endsWith("_ore")
            || blockId.contains("diamond")
            || blockId.contains("emerald")
            || blockId.contains("netherite")
            || blockId.contains("debris")
            || blockId.contains("obsidian")
            || blockId.contains("spawner")
            || blockId.contains("chest")
            || blockId.contains("shulker")
            || blockId.contains("dragon")
            || blockId.contains("wither")) {
            return false;
        }
        return blockId.equals("minecraft:stone")
            || blockId.equals("minecraft:cobblestone")
            || blockId.equals("minecraft:deepslate")
            || blockId.equals("minecraft:cobbled_deepslate")
            || blockId.equals("minecraft:granite")
            || blockId.equals("minecraft:diorite")
            || blockId.equals("minecraft:andesite")
            || blockId.equals("minecraft:tuff")
            || blockId.equals("minecraft:calcite")
            || blockId.equals("minecraft:dripstone_block")
            || blockId.equals("minecraft:netherrack")
            || blockId.equals("minecraft:end_stone")
            || blockId.equals("minecraft:dirt")
            || blockId.equals("minecraft:grass_block")
            || blockId.equals("minecraft:coarse_dirt")
            || blockId.equals("minecraft:rooted_dirt")
            || blockId.equals("minecraft:podzol")
            || blockId.equals("minecraft:mycelium")
            || blockId.equals("minecraft:gravel")
            || blockId.equals("minecraft:sand")
            || blockId.equals("minecraft:red_sand")
            || blockId.equals("minecraft:clay")
            || blockId.equals("minecraft:short_grass")
            || blockId.equals("minecraft:tall_grass")
            || blockId.equals("minecraft:fern")
            || blockId.equals("minecraft:large_fern")
            || blockId.equals("minecraft:snow")
            || blockId.equals("minecraft:snow_block")
            || blockId.endsWith("_log")
            || blockId.endsWith("_wood")
            || blockId.endsWith("_stem")
            || blockId.endsWith("_hyphae")
            || blockId.endsWith("_leaves");
    }

    private String worldAssistDirectBreakDropItem(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "";
        }
        return switch (blockId) {
            case "minecraft:stone" -> "minecraft:cobblestone";
            case "minecraft:deepslate" -> "minecraft:cobbled_deepslate";
            case "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:podzol", "minecraft:mycelium" -> "minecraft:dirt";
            case "minecraft:clay" -> "minecraft:clay_ball";
            case "minecraft:snow", "minecraft:snow_block" -> "minecraft:snowball";
            case "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern" -> "";
            default -> blockId.endsWith("_leaves") || itemById(blockId) == null ? "" : blockId;
        };
    }

    private int worldAssistDirectBreakDropCount(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return 0;
        }
        return switch (blockId) {
            case "minecraft:clay" -> 4;
            case "minecraft:snow_block" -> 4;
            case "minecraft:snow" -> 1;
            case "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern" -> 0;
            default -> worldAssistDirectBreakDropItem(blockId).isBlank() ? 0 : 1;
        };
    }

    private ProgrammaticBlockPlacementResult placeProgrammaticWorkstationBlock(String station, BlockPos pos) {
        if (!isProgrammaticWorkstationAllowed(station)) {
            return new ProgrammaticBlockPlacementResult(false, "unsupported_workstation");
        }
        if (client.player == null || client.world == null) {
            return new ProgrammaticBlockPlacementResult(false, "player_or_world_not_ready");
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            return new ProgrammaticBlockPlacementResult(false, "integrated_server_not_available");
        }
        RegistryKey<World> dimensionKey = client.world.getRegistryKey();
        UUID playerUuid = client.player.getUuid();
        CompletableFuture<ProgrammaticBlockPlacementResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerWorld serverWorld = server.getWorld(dimensionKey);
                if (serverWorld == null) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "server_world_not_ready"));
                    return;
                }
                ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);
                if (serverPlayer == null) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "server_player_not_found"));
                    return;
                }
                BlockState current = serverWorld.getBlockState(pos);
                if (!current.isReplaceable() && !current.isAir()) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "target_not_replaceable"));
                    return;
                }
                if (serverWorld.getBlockState(pos.down()).isAir()) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "support_block_missing"));
                    return;
                }
                int inventorySlot = -1;
                if (config.worldAssistConsumeItemsWhenPossible()) {
                    inventorySlot = findServerInventorySlot(serverPlayer, stationItem(station));
                    if (inventorySlot < 0) {
                        future.complete(new ProgrammaticBlockPlacementResult(false, "station_item_not_found_server_inventory"));
                        return;
                    }
                }
                net.minecraft.block.Block block = Registries.BLOCK.get(Identifier.of(station));
                if (block == Blocks.AIR) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "invalid_workstation_block"));
                    return;
                }
                boolean placed = serverWorld.setBlockState(pos, block.getDefaultState(), 3);
                if (!placed) {
                    future.complete(new ProgrammaticBlockPlacementResult(false, "set_block_state_failed"));
                    return;
                }
                if (inventorySlot >= 0) {
                    serverPlayer.getInventory().removeStack(inventorySlot, 1);
                    serverPlayer.getInventory().markDirty();
                    serverPlayer.currentScreenHandler.sendContentUpdates();
                    future.complete(new ProgrammaticBlockPlacementResult(true, "ok_consumed_inventory_item"));
                    return;
                }
                future.complete(new ProgrammaticBlockPlacementResult(true, "ok_without_inventory_consumption"));
            } catch (RuntimeException error) {
                future.complete(new ProgrammaticBlockPlacementResult(false, error.toString()));
            }
        });
        try {
            return future.get(800, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            return new ProgrammaticBlockPlacementResult(false, error.toString());
        }
    }

    private boolean isProgrammaticWorkstationAllowed(String station) {
        return station.equals("minecraft:crafting_table")
            || station.equals("minecraft:furnace")
            || station.equals("minecraft:stonecutter")
            || station.equals("minecraft:smithing_table");
    }

    private int findServerInventorySlot(ServerPlayerEntity player, String itemId) {
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
                return slot;
            }
        }
        return -1;
    }

    private List<BlockPos> blueprintTargets(BlockPos base, Direction right, Direction forward, String style) {
        ArrayList<BlockPos> targets = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                targets.add(base.offset(right, x).offset(forward, z).down());
            }
        }
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    boolean edge = x == 0 || x == 2 || z == 0 || z == 2;
                    boolean door = z == 0 && x == 1 && y <= 1;
                    boolean window = style.equals("cute_house") && y == 1 && ((x == 0 || x == 2) && z == 1);
                    if (edge && !door && !window) {
                        targets.add(base.offset(right, x).offset(forward, z).up(y));
                    }
                }
            }
        }
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                targets.add(base.offset(right, x).offset(forward, z).up(2));
            }
        }
        if (style.equals("hideout")) {
            targets.add(base.offset(right, 1).offset(forward, 2).up(3));
        }
        return targets;
    }

    private boolean canReachPlacementTarget(BlockPos target) {
        if (client.player == null || client.world == null) {
            return false;
        }
        BlockHitResult hit = placementHitFor(target);
        if (hit == null) {
            return false;
        }
        return client.player.getEyePos().squaredDistanceTo(hit.getPos()) <= config.maxReach() * config.maxReach();
    }

    private String preferredBlueprintMaterial(String palette) {
        String[] wood = {
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks",
            "minecraft:cherry_planks",
            "minecraft:pale_oak_planks",
            "minecraft:bamboo_planks"
        };
        if (palette.equals("wood") || palette.equals("available")) {
            for (String item : wood) {
                if (countInventoryItem(item) > 0) {
                    return item;
                }
            }
        }
        if ((palette.equals("sand") || palette.equals("available")) && countInventoryItem("minecraft:sandstone") > 0) {
            return "minecraft:sandstone";
        }
        if ((palette.equals("sand") || palette.equals("available")) && countInventoryItem("minecraft:sand") > 0) {
            return "minecraft:sand";
        }
        if ((palette.equals("dirt") || palette.equals("available")) && countInventoryItem("minecraft:dirt") > 0) {
            return "minecraft:dirt";
        }
        return palette.equals("wood") || palette.equals("dirt") || palette.equals("sand") ? "" : firstPlaceableSupportItem();
    }

    private ExecutorProtocol.StepResult placeBlockNow(ExecutorProtocol.Action action, String targetKind) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
        }

        String item = action.stringField("item");
        if (!"minecraft:torch".equals(item)) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Only minecraft:torch placement is supported in this executor slice.");
        }

        if (!targetKind.equals("nearby_dark_spot") && !targetKind.equals("crosshair_block")) {
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported place target kind: " + targetKind);
        }

        if (!selectHotbarTorch()) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "minecraft:torch is not available in the hotbar. Move a torch to the hotbar before placement.");
        }

        BlockHitResult blockHit;
        if (targetKind.equals("nearby_dark_spot")) {
            blockHit = findNearbyTorchPlacement(5);
            if (blockHit == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No reachable nearby dark floor spot found for torch placement.");
            }
            lookAtPosition(blockHit.getPos());
        } else {
            HitResult hit = client.crosshairTarget;
            if (!(hit instanceof BlockHitResult crosshairHit) || hit.getType() != HitResult.Type.BLOCK) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Crosshair is not targeting a reachable block face.");
            }
            blockHit = crosshairHit;
        }

        double distance = client.player.getEyePos().squaredDistanceTo(blockHit.getPos());
        if (distance > config.maxReach() * config.maxReach()) {
            return new ExecutorProtocol.StepResult(action.type(), "blocked", "Target block face is outside survival reach.");
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
        client.player.swingHand(Hand.MAIN_HAND);
        JsonObject data = new JsonObject();
        data.addProperty("support_x", blockHit.getBlockPos().getX());
        data.addProperty("support_y", blockHit.getBlockPos().getY());
        data.addProperty("support_z", blockHit.getBlockPos().getZ());
        data.addProperty("side", blockHit.getSide().asString());
        data.addProperty("target_kind", targetKind);
        data.addProperty("path_aware_approach", targetKind.equals("nearby_dark_spot"));
        return new ExecutorProtocol.StepResult(action.type(), "accepted", "Placed minecraft:torch using a survival block interaction.", data);
    }

    private ExecutorProtocol.StepResult collectFluid(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }
            String fluid = action.stringField("fluid");
            if (!fluid.equals("minecraft:water") && !fluid.equals("minecraft:lava")) {
                return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported fluid: " + fluid);
            }
            if (!selectHotbarItem("minecraft:bucket")) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "An empty bucket is required before collecting " + fluid + ".");
            }
            int radius = Math.max(1, Math.min(action.intField("search_radius", 8), config.maxScanRadius()));
            BlockPos source = findNearestBlock(fluid, radius);
            JsonObject data = new JsonObject();
            data.addProperty("fluid", fluid);
            if (source == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No nearby source block found for " + fluid + ".", data);
            }
            ExecutorProtocol.StepResult approached = moveWithinReachOfBlock(source, config.maxReach(), 120, action.type());
            if (!"accepted".equals(approached.status())) return approached;
            lookAtPosition(Vec3d.ofCenter(source));
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(source), Direction.UP, source, false);
            int before = countInventoryItem(fluid.equals("minecraft:water") ? "minecraft:water_bucket" : "minecraft:lava_bucket");
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            client.player.swingHand(Hand.MAIN_HAND);
            int after = waitForInventoryItemCount(fluid.equals("minecraft:water") ? "minecraft:water_bucket" : "minecraft:lava_bucket", before, 8);
            addBlockPos(data, "source", source);
            data.addProperty("before_bucket_count", before);
            data.addProperty("after_bucket_count", after);
            if (after <= before) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Fluid bucket collection was not confirmed.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Collected " + fluid + " with a bucket.", data);
        });
    }

    private ExecutorProtocol.StepResult buildNetherPortal(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }
            String method = action.stringField("method");
            if (method.equals("obsidian_frame")) return buildObsidianPortalFrame(action.type());
            if (method.equals("lava_cast")) return buildLavaCastPortalFrame(action.type());
            return new ExecutorProtocol.StepResult(action.type(), "rejected", "Unsupported nether portal build method: " + method);
        });
    }

    private ExecutorProtocol.StepResult buildObsidianPortalFrame(String actionType) {
        JsonObject data = new JsonObject();
        if (countInventoryItem("minecraft:obsidian") < 14) {
            data.addProperty("obsidian_count", countInventoryItem("minecraft:obsidian"));
            return new ExecutorProtocol.StepResult(actionType, "blocked", "A stable full portal frame route requires 14 obsidian.", data);
        }
        if (!selectHotbarItem("minecraft:obsidian")) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Obsidian could not be selected.");
        }
        List<BlockPos> frame = plannedPortalFramePositions();
        if (!validatePortalBuildSpace(frame, data)) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe empty space for a nether portal frame was found in front of the player.", data);
        }
        int placed = 0;
        for (BlockPos target : frame) {
            if (aborted) return new ExecutorProtocol.StepResult(actionType, "aborted", "Portal construction was interrupted.", data);
            if (blockIdAt(target).equals("minecraft:obsidian")) continue;
            if (!placeItemAt("minecraft:obsidian", target)) {
                addBlockPos(data, "failed_target", target);
                data.addProperty("placed_count", placed);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Obsidian placement was not confirmed.", data);
            }
            placed++;
            sleepTicks(2);
        }
        data.addProperty("placed_count", placed);
        data.addProperty("required_obsidian", 14);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Built a full obsidian nether portal frame.", data);
    }

    private ExecutorProtocol.StepResult buildLavaCastPortalFrame(String actionType) {
        JsonObject data = new JsonObject();
        if (countInventoryItem("minecraft:water_bucket") <= 0) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "A water bucket is required for lava-cast portal construction.", data);
        }
        List<BlockPos> frame = plannedPortalFramePositions();
        if (!validatePortalBuildSpace(frame, data)) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No safe empty space for lava-cast portal construction was found in front of the player.", data);
        }
        int cast = 0;
        for (BlockPos target : frame) {
            if (aborted) return new ExecutorProtocol.StepResult(actionType, "aborted", "Lava-cast portal construction was interrupted.", data);
            if (blockIdAt(target).equals("minecraft:obsidian")) continue;
            if (countInventoryItem("minecraft:lava_bucket") <= 0) {
                BlockPos lava = findNearestBlock("minecraft:lava", 12);
                if (lava == null) {
                    addBlockPos(data, "missing_lava_for", target);
                    data.addProperty("cast_count", cast);
                    return new ExecutorProtocol.StepResult(actionType, "blocked", "No nearby lava source remained for lava-cast portal construction.", data);
                }
                ExecutorProtocol.StepResult collected = collectFluidNow("minecraft:lava", lava, actionType);
                if (!"accepted".equals(collected.status())) return collected;
            }
            if (!placeItemAt("minecraft:lava_bucket", target)) {
                addBlockPos(data, "failed_lava_target", target);
                data.addProperty("cast_count", cast);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Lava placement was not confirmed during portal casting.", data);
            }
            sleepTicks(2);
            BlockPos waterPos = target.up();
            if (!placeItemAt("minecraft:water_bucket", waterPos)) {
                addBlockPos(data, "failed_water_target", waterPos);
                data.addProperty("cast_count", cast);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Water placement was not confirmed during portal casting.", data);
            }
            sleepTicks(6);
            if (!blockIdAt(target).equals("minecraft:obsidian")) {
                addBlockPos(data, "failed_obsidian_target", target);
                data.addProperty("target_block_after_cast", blockIdAt(target));
                data.addProperty("cast_count", cast);
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Lava did not convert into obsidian during portal casting.", data);
            }
            collectFluidNow("minecraft:water", waterPos, actionType);
            cast++;
        }
        data.addProperty("cast_count", cast);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Built a nether portal frame by lava casting.", data);
    }

    private ExecutorProtocol.StepResult igniteNetherPortal(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.world == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player, world, or interaction manager is not ready.");
            }
            if (!selectHotbarItem("minecraft:flint_and_steel")) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Flint and steel is required before portal ignition.");
            }
            BlockPos target = findPortalIgnitionTarget(Math.max(1, Math.min(action.intField("search_radius", 8), 12)));
            JsonObject data = new JsonObject();
            if (target == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "No nearby nether portal frame interior was found for ignition.", data);
            }
            if (!interactAt(target, "minecraft:flint_and_steel")) {
                addBlockPos(data, "target", target);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Portal ignition interaction was not confirmed.", data);
            }
            sleepTicks(8);
            addBlockPos(data, "target", target);
            data.addProperty("target_block_after_ignite", blockIdAt(target));
            if (!blockIdAt(target).equals("minecraft:nether_portal") && !hasNearbyBlock("minecraft:nether_portal", 3)) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Nether portal block was not detected after ignition.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Ignited the nether portal frame.", data);
        });
    }

    private List<BlockPos> plannedPortalFramePositions() {
        Direction forward = client.player == null ? Direction.NORTH : client.player.getHorizontalFacing();
        Direction right = forward.rotateYClockwise();
        BlockPos base = (client.player == null ? BlockPos.ORIGIN : client.player.getBlockPos()).offset(forward, 3).offset(right, -1);
        List<BlockPos> positions = new ArrayList<>();
        for (int x = 0; x <= 3; x++) {
            positions.add(base.offset(right, x));
            positions.add(base.offset(right, x).up(4));
        }
        for (int y = 1; y <= 3; y++) {
            positions.add(base.up(y));
            positions.add(base.offset(right, 3).up(y));
        }
        return positions;
    }

    private boolean validatePortalBuildSpace(List<BlockPos> frame, JsonObject data) {
        if (client.world == null || client.player == null) return false;
        for (BlockPos pos : frame) {
            String id = blockIdAt(pos);
            if (!id.equals("minecraft:air") && !id.equals("minecraft:obsidian")) {
                addBlockPos(data, "blocked", pos);
                return false;
            }
            if (pos.getSquaredDistance(client.player.getPos()) > config.maxReach() * config.maxReach() * 9) {
                addBlockPos(data, "too_far", pos);
                return false;
            }
        }
        for (BlockPos pos : frame) {
            if (!client.world.getBlockState(pos.down()).isAir() || frame.contains(pos.down())) {
                return true;
            }
        }
        return false;
    }

    private boolean placeItemAt(String itemId, BlockPos target) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;
        if (!selectHotbarItem(itemId)) return false;
        if (!blockIdAt(target).equals("minecraft:air") && !blockIdAt(target).equals("minecraft:water") && !blockIdAt(target).equals("minecraft:lava")) {
            return blockIdAt(target).equals(itemId.replace("_bucket", ""));
        }
        BlockHitResult hit = placementHitFor(target);
        if (hit == null) return false;
        lookAtPosition(hit.getPos());
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
        if (itemId.endsWith("_bucket")) {
            String fluid = itemId.equals("minecraft:water_bucket") ? "minecraft:water" : "minecraft:lava";
            return waitForBlockIdAt(target, fluid, 8) >= 0;
        }
        return waitForBlockIdAt(target, itemId, 8) >= 0;
    }

    private boolean interactAt(BlockPos target, String itemId) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;
        if (!selectHotbarItem(itemId)) return false;
        BlockHitResult hit = placementHitFor(target);
        if (hit == null) {
            hit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
        }
        lookAtPosition(hit.getPos());
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private BlockHitResult placementHitFor(BlockPos target) {
        if (client.world == null) return null;
        for (Direction side : Direction.values()) {
            BlockPos neighbor = target.offset(side.getOpposite());
            if (!client.world.getBlockState(neighbor).isAir() && !blockIdAt(neighbor).equals("minecraft:water") && !blockIdAt(neighbor).equals("minecraft:lava")) {
                Vec3d hitPos = Vec3d.ofCenter(neighbor).add(side.getOffsetX() * 0.5D, side.getOffsetY() * 0.5D, side.getOffsetZ() * 0.5D);
                return new BlockHitResult(hitPos, side, neighbor, false);
            }
        }
        return null;
    }

    private ExecutorProtocol.StepResult collectFluidNow(String fluid, BlockPos source, String actionType) {
        JsonObject data = new JsonObject();
        data.addProperty("fluid", fluid);
        addBlockPos(data, "source", source);
        if (!selectHotbarItem("minecraft:bucket")) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "An empty bucket is required before collecting " + fluid + ".", data);
        }
        lookAtPosition(Vec3d.ofCenter(source));
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(source), Direction.UP, source, false);
        String bucket = fluid.equals("minecraft:water") ? "minecraft:water_bucket" : "minecraft:lava_bucket";
        int before = countInventoryItem(bucket);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
        int after = waitForInventoryItemCount(bucket, before, 8);
        data.addProperty("before_bucket_count", before);
        data.addProperty("after_bucket_count", after);
        if (after <= before) return new ExecutorProtocol.StepResult(actionType, "blocked", "Fluid bucket collection was not confirmed.", data);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Collected " + fluid + " with a bucket.", data);
    }

    private BlockPos findNearestBlock(String blockId, int radius) {
        if (client.player == null || client.world == null) return null;
        BlockPos origin = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!blockIdAt(pos).equals(blockId)) continue;
                    double distance = pos.getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findNearestClosedPassage(int radius) {
        if (client.player == null || client.world == null) return null;
        BlockPos origin = client.player.getBlockPos();
        Direction forward = client.player.getHorizontalFacing();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = -1; y <= 2; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    String id = blockIdAt(pos);
                    if (!isPassageBlock(id) || !hasBlockingCollision(pos)) {
                        continue;
                    }
                    double forwardBias = pos.subtract(origin).getX() * forward.getOffsetX() + pos.subtract(origin).getZ() * forward.getOffsetZ();
                    double score = pos.getSquaredDistance(origin) - Math.max(0.0D, forwardBias) * 2.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private boolean isPassageBlock(String blockId) {
        return blockId.endsWith("_door") || blockId.endsWith("_fence_gate");
    }

    private void interactWithBlock(BlockPos pos) {
        if (client.player == null || client.interactionManager == null) return;
        lookAtPosition(Vec3d.ofCenter(pos));
        Direction side = client.player.getHorizontalFacing().getOpposite();
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean matchesToolGroup(String itemId, String group) {
        return switch (group) {
            case "pickaxe" -> itemId.endsWith("_pickaxe");
            case "axe" -> itemId.endsWith("_axe");
            case "shovel" -> itemId.endsWith("_shovel");
            case "weapon" -> itemId.endsWith("_sword") || itemId.endsWith("_axe") || itemId.equals("minecraft:bow") || itemId.equals("minecraft:crossbow") || itemId.equals("minecraft:trident");
            case "any_tool" -> itemId.endsWith("_pickaxe") || itemId.endsWith("_axe") || itemId.endsWith("_shovel") || itemId.endsWith("_hoe") || itemId.endsWith("_sword") || itemId.equals("minecraft:shears") || itemId.equals("minecraft:flint_and_steel");
            default -> false;
        };
    }

    private int remainingDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (!stack.isDamageable()) return 512;
        return Math.max(0, stack.getMaxDamage() - stack.getDamage());
    }

    private List<BlockPos> emergencyShelterTargets(BlockPos base, int radius) {
        ArrayList<BlockPos> highPriority = new ArrayList<>();
        ArrayList<BlockPos> normalPriority = new ArrayList<>();
        int r = Math.max(1, Math.min(radius, 2));
        Direction threat = nearestHostileDirectionFrom(base);
        for (int y = 0; y <= 1; y++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos target = base.add(dx, y, dz);
                    if (isThreatSide(dx, dz, threat)) {
                        highPriority.add(target);
                    } else {
                        normalPriority.add(target);
                    }
                }
            }
        }
        ArrayList<BlockPos> roof = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                roof.add(base.add(dx, 2, dz));
            }
        }
        ArrayList<BlockPos> targets = new ArrayList<>();
        targets.addAll(highPriority);
        targets.addAll(roof);
        targets.addAll(normalPriority);
        return targets;
    }

    private Direction nearestHostileDirectionFrom(BlockPos base) {
        if (client.player == null || client.world == null) {
            return client.player == null ? Direction.NORTH : client.player.getHorizontalFacing();
        }
        Entity hostile = findNearestMatchingEntity("hostile", 8, 8.0D);
        if (hostile == null) {
            return client.player.getHorizontalFacing();
        }
        Vec3d delta = hostile.getPos().subtract(Vec3d.ofBottomCenter(base));
        return Direction.getFacing(delta.x, 0.0D, delta.z);
    }

    private boolean isThreatSide(int dx, int dz, Direction threat) {
        return dx * threat.getOffsetX() + dz * threat.getOffsetZ() > 0;
    }

    private BlockPos findNearestSafeGround(int radius) {
        if (client.player == null || client.world == null) return null;
        BlockPos origin = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -2; y <= 3; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!isStandableBlockPos(pos) || isDangerNear(pos, 1)) {
                        continue;
                    }
                    List<BlockPos> path = planLocalPath(client.player.getPos(), Vec3d.ofBottomCenter(pos));
                    if (path.size() < 2 && pos.getSquaredDistance(origin) > 2.0D) {
                        continue;
                    }
                    double distance = pos.getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private EscapePlan findBestEscapePlan(int radius) {
        if (client.player == null || client.world == null) return null;
        BlockPos start = client.player.getBlockPos();
        int limit = Math.max(2, Math.min(radius, 16));
        PriorityQueue<EscapeNode> queue = new PriorityQueue<>((a, b) -> Double.compare(a.cost(), b.cost()));
        Map<BlockPos, Double> costSoFar = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        queue.add(new EscapeNode(start, 0.0D));
        costSoFar.put(start, 0.0D);
        EscapePlan best = null;
        int visited = 0;

        while (!queue.isEmpty() && visited < 768) {
            EscapeNode currentNode = queue.poll();
            BlockPos current = currentNode.pos();
            visited++;
            double currentCost = costSoFar.getOrDefault(current, Double.POSITIVE_INFINITY);
            if (isStandableBlockPos(current) && !isDangerNear(current, 1)) {
                List<BlockPos> path = reconstructPath(start, current, cameFrom);
                double score = currentCost + hazardExposureCost(current) + path.size() * 0.25D;
                if (best == null || score < best.score()) {
                    best = new EscapePlan(current, path, score);
                    if (path.size() <= 4 && hazardExposureCost(current) <= 1.0D) {
                        break;
                    }
                }
            }
            for (BlockPos next : escapeNeighbors(current)) {
                if (Math.abs(next.getX() - start.getX()) > limit || Math.abs(next.getZ() - start.getZ()) > limit || Math.abs(next.getY() - start.getY()) > 4) {
                    continue;
                }
                if (!isTraversableForEscape(next)) {
                    continue;
                }
                double stepCost = 1.0D + Math.abs(next.getY() - current.getY()) * 0.8D + hazardExposureCost(next);
                double newCost = currentCost + stepCost;
                if (newCost >= costSoFar.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    continue;
                }
                costSoFar.put(next, newCost);
                cameFrom.put(next, current);
                queue.add(new EscapeNode(next, newCost + next.getSquaredDistance(start) * 0.01D));
            }
        }
        return best;
    }

    private Vec3d nextEscapeWaypoint(Vec3d origin, BlockPos target, int radius) {
        EscapePlan plan = findBestEscapePlan(radius);
        List<BlockPos> path = plan != null ? plan.path() : planLocalPath(origin, Vec3d.ofBottomCenter(target));
        if (path.size() >= 2) {
            return Vec3d.ofBottomCenter(path.get(Math.min(2, path.size() - 1)));
        }
        return Vec3d.ofBottomCenter(target);
    }

    private List<BlockPos> escapeNeighbors(BlockPos current) {
        ArrayList<BlockPos> neighbors = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy : new int[] {0, 1, -1}) {
                    neighbors.add(current.add(dx, dy, dz));
                }
            }
        }
        neighbors.add(current.up());
        neighbors.add(current.down());
        return neighbors;
    }

    private boolean isTraversableForEscape(BlockPos feet) {
        if (client.world == null) return false;
        return !hasBlockingCollision(feet) && !hasBlockingCollision(feet.up());
    }

    private double hazardExposureCost(BlockPos pos) {
        if (client.world == null) return 1000.0D;
        double cost = 0.0D;
        for (int y = -1; y <= 1; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos candidate = pos.add(x, y, z);
                    String id = blockIdAt(candidate);
                    int manhattan = Math.abs(x) + Math.abs(y) + Math.abs(z);
                    double distanceFactor = 1.0D / Math.max(1.0D, manhattan);
                    if (id.equals("minecraft:lava") || id.equals("minecraft:fire") || id.equals("minecraft:soul_fire")) {
                        cost += 80.0D * distanceFactor;
                    } else if (id.equals("minecraft:water")) {
                        cost += 8.0D * distanceFactor;
                    }
                }
            }
        }
        Entity hostile = findNearestMatchingEntity("hostile", 8, 8.0D);
        if (hostile != null) {
            double distance = Math.max(1.0D, Vec3d.ofBottomCenter(pos).distanceTo(hostile.getPos()));
            cost += 18.0D / distance;
        }
        if (!hasBlockingCollision(pos.down())) {
            cost += 25.0D;
        }
        return cost;
    }

    private ExecutorProtocol.StepResult placeEmergencyEscapeSupport(String actionType) {
        JsonObject data = new JsonObject();
        if (client.player == null || client.world == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player or world is not ready.", data);
        }
        String material = firstPlaceableSupportItem();
        data.addProperty("material", material);
        if (material.isBlank()) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "No solid block is available for escape support placement.", data);
        }
        BlockPos origin = client.player.getBlockPos();
        Direction forward = client.player.getHorizontalFacing();
        for (BlockPos target : List.of(origin.down(), origin.offset(forward).down(), origin.offset(forward))) {
            String id = blockIdAt(target);
            if (!id.equals("minecraft:air") && !id.equals("minecraft:water") && !id.equals("minecraft:lava")) {
                continue;
            }
            if (placeItemAt(material, target)) {
                addBlockPos(data, "target", target);
                return new ExecutorProtocol.StepResult(actionType, "accepted", "Placed one emergency support block for escape.", data);
            }
        }
        return new ExecutorProtocol.StepResult(actionType, "blocked", "Could not place an emergency support block.", data);
    }

    private boolean isDangerNear(BlockPos pos, int radius) {
        if (client.world == null) return true;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = pos.add(x, y, z);
                    String id = blockIdAt(candidate);
                    if (id.equals("minecraft:lava") || id.equals("minecraft:fire") || id.equals("minecraft:soul_fire")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasNearbyBlock(String blockId, int radius) {
        return findNearestBlock(blockId, radius) != null;
    }

    private BlockPos findPortalIgnitionTarget(int radius) {
        if (client.player == null || client.world == null) return null;
        BlockPos origin = client.player.getBlockPos();
        for (int y = -1; y <= 4; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!blockIdAt(pos).equals("minecraft:air")) continue;
                    if (hasObsidianNeighbor(pos)) return pos;
                }
            }
        }
        return null;
    }

    private boolean hasObsidianNeighbor(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (blockIdAt(pos.offset(direction)).equals("minecraft:obsidian")) return true;
        }
        return false;
    }

    private BlockHitResult findNearbyTorchPlacement(int radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -2; y <= 2; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos torchPos = origin.add(x, y, z);
                    if (!client.world.getBlockState(torchPos).isAir()) {
                        continue;
                    }
                    if (client.world.getLightLevel(torchPos) > 7) {
                        continue;
                    }
                    BlockPos support = torchPos.down();
                    if (client.world.getBlockState(support).isAir()) {
                        continue;
                    }
                    Vec3d hitPos = Vec3d.ofCenter(support).add(0.0D, 0.5D, 0.0D);
                    double distance = client.player.getEyePos().squaredDistanceTo(hitPos);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    bestDistance = distance;
                    best = new BlockHitResult(hitPos, Direction.UP, support, false);
                }
            }
        }
        return best;
    }

    private BlockHitResult findNearbySolidFloorPlacement(int radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        BlockHitResult best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = -1; y <= 1; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos targetPos = origin.add(x, y, z);
                    if (!client.world.getBlockState(targetPos).isAir()) {
                        continue;
                    }
                    if (!client.world.getBlockState(targetPos.up()).isAir()) {
                        continue;
                    }
                    BlockPos support = targetPos.down();
                    if (!hasBlockingCollision(support) || isUnsafeFluid(targetPos) || isUnsafeFluid(support)) {
                        continue;
                    }
                    if (!hasUsableAdjacentStand(targetPos)) {
                        continue;
                    }
                    Vec3d hitPos = Vec3d.ofCenter(support).add(0.0D, 0.5D, 0.0D);
                    double distance = client.player.getEyePos().squaredDistanceTo(hitPos);
                    double score = workstationPlacementScore(targetPos, hitPos);
                    if (distance > config.maxReach() * config.maxReach() || score >= bestScore) {
                        continue;
                    }
                    bestScore = score;
                    best = new BlockHitResult(hitPos, Direction.UP, support, false);
                }
            }
        }
        return best;
    }

    private BlockHitResult findNearbyUsableWorkstationPlacement(int radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        BlockPos origin = client.player.getBlockPos();
        BlockHitResult best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = -1; y <= 1; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos targetPos = origin.add(x, y, z);
                    if (targetPos.equals(origin) || targetPos.equals(origin.up()) || !client.world.getBlockState(targetPos).isAir()) {
                        continue;
                    }
                    if (!client.world.getBlockState(targetPos.up()).isAir()) {
                        continue;
                    }
                    BlockPos support = targetPos.down();
                    if (!hasBlockingCollision(support) || isUnsafeFluid(targetPos) || isUnsafeFluid(support)) {
                        continue;
                    }
                    if (!hasUsableAdjacentStand(targetPos)) {
                        continue;
                    }
                    Vec3d hitPos = Vec3d.ofCenter(support).add(0.0D, 0.5D, 0.0D);
                    double distance = client.player.getEyePos().squaredDistanceTo(hitPos);
                    double score = workstationPlacementScore(targetPos, hitPos);
                    if (distance > config.maxReach() * config.maxReach() || score >= bestScore) {
                        continue;
                    }
                    bestScore = score;
                    best = new BlockHitResult(hitPos, Direction.UP, support, false);
                }
            }
        }
        return best;
    }

    private double workstationPlacementScore(BlockPos targetPos, Vec3d hitPos) {
        if (client.player == null) {
            return Double.MAX_VALUE;
        }
        Vec3d toTarget = Vec3d.ofCenter(targetPos).subtract(client.player.getPos());
        Direction facing = client.player.getHorizontalFacing();
        double forward = toTarget.x * facing.getOffsetX() + toTarget.z * facing.getOffsetZ();
        Direction right = facing.rotateYClockwise();
        double side = Math.abs(toTarget.x * right.getOffsetX() + toTarget.z * right.getOffsetZ());
        double distance = client.player.getEyePos().squaredDistanceTo(hitPos);
        double backPenalty = forward < -0.25D ? Math.abs(forward) * 4.0D : 0.0D;
        double tooFarForwardPenalty = forward > 2.5D ? (forward - 2.5D) * 1.5D : 0.0D;
        double verticalPenalty = Math.abs(targetPos.getY() - client.player.getBlockY()) * 2.0D;
        return distance + side * 0.35D + backPenalty + tooFarForwardPenalty + verticalPenalty - Math.max(0.0D, forward) * 0.9D;
    }

    private boolean hasUsableAdjacentStand(BlockPos targetPos) {
        if (client.player == null) {
            return false;
        }
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            BlockPos stand = targetPos.offset(direction);
            if (!isStandableBlockPos(stand)) {
                continue;
            }
            if (Vec3d.ofBottomCenter(stand).add(0.0D, client.player.getEyeHeight(client.player.getPose()), 0.0D).distanceTo(Vec3d.ofCenter(targetPos)) <= config.maxReach()) {
                return true;
            }
        }
        return false;
    }

    private void lookAtPosition(Vec3d target) {
        if (client.player == null) {
            return;
        }
        Vec3d eye = client.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0F / Math.PI) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0F / Math.PI);
        client.player.setYaw(yaw);
        client.player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
    }

    private boolean selectHotbarTorch() {
        return selectHotbarItem("minecraft:torch");
    }

    private ExecutorProtocol.StepResult selectRequiredToolForBlock(String blockId, String actionType) {
        String tool = bestToolForBlock(blockId);
        JsonObject data = new JsonObject();
        data.addProperty("block", blockId);
        data.addProperty("selected_tool", tool);
        if (tool.isBlank()) {
            data.addProperty("tool_required", false);
            return new ExecutorProtocol.StepResult(actionType, "accepted", "No required tool selection is needed for this block.", data);
        }
        data.addProperty("tool_required", true);
        ExecutorProtocol.StepResult ensured = ensureHotbarItem(tool, 1, actionType);
        if (!"accepted".equals(ensured.status())) {
            if (ensured.data() != null) {
                data.add("ensure_hotbar", ensured.data());
            }
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Required tool is not available for this block: " + tool, data);
        }
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Selected required tool before block breaking.", data);
    }

    private String bestToolForBlock(String blockId) {
        if (requiresPickaxe(blockId)) {
            return bestAvailablePickaxe(requiredPickaxeTier(blockId));
        }
        if (blockId.endsWith("_log") || blockId.endsWith("_stem") || blockId.endsWith("_hyphae") || blockId.endsWith("_wood")) {
            return bestAvailableTool("axe");
        }
        if (blockId.equals("minecraft:dirt") || blockId.equals("minecraft:grass_block") || blockId.equals("minecraft:coarse_dirt") || blockId.equals("minecraft:rooted_dirt") || blockId.equals("minecraft:podzol") || blockId.equals("minecraft:mycelium") || blockId.equals("minecraft:sand") || blockId.equals("minecraft:red_sand") || blockId.equals("minecraft:gravel") || blockId.equals("minecraft:clay")) {
            return bestAvailableTool("shovel");
        }
        return "";
    }

    private boolean requiresPickaxe(String blockId) {
        return blockId.contains("stone")
            || blockId.contains("deepslate")
            || blockId.contains("ore")
            || blockId.equals("minecraft:cobblestone")
            || blockId.equals("minecraft:cobbled_deepslate")
            || blockId.equals("minecraft:granite")
            || blockId.equals("minecraft:diorite")
            || blockId.equals("minecraft:andesite")
            || blockId.equals("minecraft:tuff")
            || blockId.equals("minecraft:calcite")
            || blockId.equals("minecraft:dripstone_block")
            || blockId.equals("minecraft:obsidian");
    }

    private int requiredPickaxeTier(String blockId) {
        if (blockId.equals("minecraft:obsidian") || blockId.contains("ancient_debris")) {
            return 3;
        }
        if (blockId.contains("diamond_ore") || blockId.contains("emerald_ore") || blockId.contains("gold_ore") || blockId.contains("redstone_ore") || blockId.contains("lapis_ore")) {
            return 2;
        }
        if (blockId.contains("iron_ore") || blockId.contains("copper_ore")) {
            return 1;
        }
        return 0;
    }

    private String bestAvailablePickaxe(int minTier) {
        String[] tools = {
            "minecraft:netherite_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:wooden_pickaxe"
        };
        for (String tool : tools) {
            if (pickaxeTier(tool) >= minTier && countInventoryItem(tool) > 0) {
                return tool;
            }
        }
        return "";
    }

    private int pickaxeTier(String tool) {
        return switch (tool) {
            case "minecraft:netherite_pickaxe", "minecraft:diamond_pickaxe" -> 3;
            case "minecraft:iron_pickaxe" -> 2;
            case "minecraft:stone_pickaxe" -> 1;
            case "minecraft:wooden_pickaxe" -> 0;
            default -> -1;
        };
    }

    private String bestAvailableTool(String suffix) {
        String[] materials = {"netherite", "diamond", "iron", "stone", "wooden", "golden"};
        for (String material : materials) {
            String tool = "minecraft:" + material + "_" + suffix;
            if (countInventoryItem(tool) > 0) {
                return tool;
            }
        }
        return "";
    }

    private String bestAvailableCombatWeapon() {
        String[] weapons = {
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:stone_sword",
            "minecraft:netherite_axe",
            "minecraft:diamond_axe",
            "minecraft:iron_axe",
            "minecraft:stone_axe",
            "minecraft:wooden_sword",
            "minecraft:wooden_axe"
        };
        for (String weapon : weapons) {
            if (countInventoryItem(weapon) > 0) {
                return weapon;
            }
        }
        return "";
    }

    private String firstPlaceableSupportItem() {
        if (client.player == null || client.world == null) {
            return "";
        }
        String[] preferred = {
            "minecraft:dirt",
            "minecraft:sandstone",
            "minecraft:sand",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:stone",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks",
            "minecraft:cherry_planks",
            "minecraft:pale_oak_planks",
            "minecraft:bamboo_planks",
            "minecraft:netherrack"
        };
        for (String itemId : preferred) {
            if (countInventoryItem(itemId) > 0) {
                return itemId;
            }
        }
        int checkedSlots = Math.min(36, client.player.getInventory().size());
        for (int slot = 0; slot < checkedSlots; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            String itemId = itemId(stack);
            if (isMovementSupportBlockItem(itemId, blockItem)) {
                return itemId;
            }
        }
        return "";
    }

    private boolean isMovementSupportBlockItem(String itemId, BlockItem blockItem) {
        if (itemId.isBlank()
            || itemId.contains("sand")
            || itemId.contains("gravel")
            || itemId.contains("concrete_powder")
            || itemId.contains("torch")
            || itemId.contains("lantern")
            || itemId.contains("candle")
            || itemId.contains("button")
            || itemId.contains("pressure_plate")
            || itemId.contains("sign")
            || itemId.contains("door")
            || itemId.contains("trapdoor")
            || itemId.contains("fence")
            || itemId.contains("wall")
            || itemId.contains("pane")
            || itemId.contains("carpet")
            || itemId.contains("rail")
            || itemId.contains("ladder")
            || itemId.contains("scaffolding")
            || itemId.contains("bed")) {
            return false;
        }
        BlockState state = blockItem.getBlock().getDefaultState();
        return state.isFullCube(client.world, client.player.getBlockPos());
    }

    private String[] bestArmorPieces() {
        return new String[] {
            "minecraft:netherite_helmet",
            "minecraft:diamond_helmet",
            "minecraft:iron_helmet",
            "minecraft:chainmail_helmet",
            "minecraft:leather_helmet",
            "minecraft:netherite_chestplate",
            "minecraft:diamond_chestplate",
            "minecraft:iron_chestplate",
            "minecraft:chainmail_chestplate",
            "minecraft:leather_chestplate",
            "minecraft:netherite_leggings",
            "minecraft:diamond_leggings",
            "minecraft:iron_leggings",
            "minecraft:chainmail_leggings",
            "minecraft:leather_leggings",
            "minecraft:netherite_boots",
            "minecraft:diamond_boots",
            "minecraft:iron_boots",
            "minecraft:chainmail_boots",
            "minecraft:leather_boots"
        };
    }

    private boolean selectHotbarItem(String itemId) {
        ExecutorProtocol.StepResult result = ensureHotbarItem(itemId, 1, "ensure_hotbar");
        return "accepted".equals(result.status());
    }

    private ExecutorProtocol.StepResult ensureHotbarItem(String itemId, int count, String actionType) {
        if (client.player == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player is not ready.");
        }
        if (client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Interaction manager is not ready.");
        }
        if (itemId == null || itemId.isBlank() || !itemId.startsWith("minecraft:")) {
            return new ExecutorProtocol.StepResult(actionType, "rejected", "ensure_hotbar item must be a minecraft id.");
        }

        JsonObject data = new JsonObject();
        data.addProperty("item", itemId);
        data.addProperty("requested_count", count);
        int existingHotbar = findHotbarInventoryIndex(itemId, count);
        if (existingHotbar >= 0) {
            selectHotbarSlot(existingHotbar);
            data.addProperty("hotbar_index", existingHotbar);
            data.addProperty("moved", false);
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Item is already available in the hotbar.", data);
        }

        if (countInventoryItem(itemId) < count) {
            data.addProperty("inventory_count", countInventoryItem(itemId));
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Required item is not available in inventory.", data);
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (!handler.getCursorStack().isEmpty()) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Cursor stack is not empty; refusing to move hotbar item.");
        }
        int sourceSlot = findHandlerSlotForItem(handler, itemId);
        if (sourceSlot < 0) {
            data.addProperty("inventory_count", countInventoryItem(itemId));
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Required item exists, but no safe movable inventory slot was found.", data);
        }
        int hotbarIndex = findEmptyHotbarInventoryIndex();
        boolean displaced = false;
        if (hotbarIndex < 0) {
            hotbarIndex = client.player.getInventory().selectedSlot;
            displaced = true;
        }

        client.interactionManager.clickSlot(handler.syncId, sourceSlot, hotbarIndex, SlotActionType.SWAP, client.player);
        sleepTicks(2);
        int selected = findHotbarInventoryIndex(itemId, 1);
        if (selected < 0) {
            data.addProperty("source_slot", sourceSlot);
            data.addProperty("target_hotbar_index", hotbarIndex);
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Tried to move item to hotbar, but it was not confirmed.", data);
        }
        selectHotbarSlot(selected);
        data.addProperty("source_slot", sourceSlot);
        data.addProperty("hotbar_index", selected);
        data.addProperty("moved", true);
        data.addProperty("displaced_existing_hotbar_stack", displaced);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Moved item to the hotbar and selected it.", data);
    }

    private void selectHotbarSlot(int hotbarSlot) {
        if (client.player == null || hotbarSlot < 0 || hotbarSlot >= 9) {
            return;
        }
        client.player.getInventory().setSelectedSlot(hotbarSlot);
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
        }
        sleepTicks(1);
    }

    private int findHotbarInventoryIndex(String itemId, int minCount) {
        if (client.player == null) {
            return -1;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.getCount() >= minCount && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
                return slot;
            }
        }
        return -1;
    }

    private int findEmptyHotbarInventoryIndex() {
        if (client.player == null) {
            return -1;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private int findHandlerSlotForItem(ScreenHandler handler, String itemId) {
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (isProtectedRecipeSlot(handler, slot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack findInventoryStackForItem(ScreenHandler handler, String itemId) {
        int slot = findHandlerSlotForItem(handler, itemId);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        return handler.getSlot(slot).getStack().copy();
    }

    private boolean inventoryContainsEquivalentStack(ItemStack expected) {
        if (client.player == null || expected.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (ItemStack.areItemsAndComponentsEqual(stack, expected)) {
                return true;
            }
        }
        return false;
    }

    private int countInventoryEquivalentStack(ItemStack expected) {
        if (client.player == null || expected.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (ItemStack.areItemsAndComponentsEqual(stack, expected)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private String componentSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return stack.getComponents().toString();
    }

    private String trimSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "none";
        }
        Object trim = stack.get(DataComponentTypes.TRIM);
        return trim == null ? "none" : trim.toString();
    }

    private String componentValueSignature(ItemStack stack, String recipeKind) {
        if (stack == null || stack.isEmpty()) {
            return "none";
        }
        Object component = switch (recipeKind) {
            case "armor_dye" -> stack.get(DataComponentTypes.DYED_COLOR);
            case "banner_duplicate" -> stack.get(DataComponentTypes.BANNER_PATTERNS);
            case "map_cloning" -> stack.get(DataComponentTypes.MAP_ID);
            case "map_extending" -> stack.get(DataComponentTypes.MAP_POST_PROCESSING);
            case "firework_rocket" -> stack.get(DataComponentTypes.FIREWORKS);
            case "firework_star", "firework_star_fade" -> stack.get(DataComponentTypes.FIREWORK_EXPLOSION);
            default -> null;
        };
        return component == null ? "none" : component.toString();
    }

    private String componentNameForRecipeKind(String recipeKind) {
        return switch (recipeKind) {
            case "armor_dye" -> "minecraft:dyed_color";
            case "banner_duplicate" -> "minecraft:banner_patterns";
            case "map_cloning" -> "minecraft:map_id";
            case "map_extending" -> "minecraft:map_post_processing";
            case "firework_rocket" -> "minecraft:fireworks";
            case "firework_star", "firework_star_fade" -> "minecraft:firework_explosion";
            default -> "unknown";
        };
    }

    private boolean isKnownCraftRecipe(String recipe) {
        return isToolCraftRecipe(recipe)
            || isArmorCraftRecipe(recipe)
            || isWoodCraftRecipe(recipe)
            || isColorCraftRecipe(recipe)
            || isStoneFamilyCraftRecipe(recipe)
            || recipe.equals("minecraft:oak_planks")
            || recipe.equals("minecraft:stick")
            || recipe.equals("minecraft:crafting_table")
            || recipe.equals("minecraft:furnace")
            || recipe.equals("minecraft:torch")
            || recipe.equals("minecraft:bread")
            || recipe.equals("minecraft:shield")
            || recipe.equals("minecraft:chest")
            || recipe.equals("minecraft:ladder")
            || recipe.equals("minecraft:bowl")
            || recipe.equals("minecraft:bow")
            || recipe.equals("minecraft:arrow")
            || recipe.equals("minecraft:fishing_rod")
            || recipe.equals("minecraft:bucket")
            || recipe.equals("minecraft:shears")
            || recipe.equals("minecraft:flint_and_steel")
            || recipe.equals("minecraft:paper")
            || recipe.equals("minecraft:book")
            || recipe.equals("minecraft:bookshelf")
            || recipe.equals("minecraft:compass")
            || recipe.equals("minecraft:clock")
            || recipe.equals("minecraft:map")
            || recipe.equals("minecraft:item_frame")
            || recipe.equals("minecraft:painting")
            || recipe.equals("minecraft:white_bed")
            || hasBundledCraftRecipe(recipe)
            || hasBundledStonecuttingRecipe(recipe)
            || hasBundledSmithingRecipe(recipe);
    }

    private boolean requiresCraftingTable(String recipe) {
        if (hasBundledCraftRecipe(recipe) && !hasBundledCraftRecipeForGrid(recipe, 2)) {
            return true;
        }
        return isToolCraftRecipe(recipe)
            || isArmorCraftRecipe(recipe)
            || woodRecipeRequiresCraftingTable(recipe)
            || colorRecipeRequiresCraftingTable(recipe)
            || isStoneFamilyCraftRecipe(recipe)
            || recipe.equals("minecraft:bread")
            || recipe.equals("minecraft:shield")
            || recipe.equals("minecraft:chest")
            || recipe.equals("minecraft:ladder")
            || recipe.equals("minecraft:bowl")
            || recipe.equals("minecraft:bow")
            || recipe.equals("minecraft:arrow")
            || recipe.equals("minecraft:fishing_rod")
            || recipe.equals("minecraft:bucket")
            || recipe.equals("minecraft:paper")
            || recipe.equals("minecraft:bookshelf")
            || recipe.equals("minecraft:compass")
            || recipe.equals("minecraft:clock")
            || recipe.equals("minecraft:map")
            || recipe.equals("minecraft:item_frame")
            || recipe.equals("minecraft:painting")
            || recipe.equals("minecraft:white_bed");
    }

    private boolean hasKnownRecipeMaterials(String recipe) {
        if (isToolCraftRecipe(recipe)) {
            return countToolMaterial(recipe) >= toolMaterialCount(recipe) && countInventoryItem("minecraft:stick") >= toolStickCount(recipe);
        }
        if (isArmorCraftRecipe(recipe)) {
            return countInventoryItem(armorMaterialItem(recipe)) >= armorMaterialCount(recipe);
        }
        if (isWoodCraftRecipe(recipe)) {
            return hasWoodRecipeMaterials(recipe);
        }
        if (isColorCraftRecipe(recipe)) {
            return hasColorRecipeMaterials(recipe);
        }
        if (isStoneFamilyCraftRecipe(recipe)) {
            return hasStoneFamilyRecipeMaterials(recipe);
        }
        return switch (recipe) {
            case "minecraft:oak_planks" -> countInventoryItem("minecraft:oak_log") >= 1;
            case "minecraft:stick" -> countInventoryGroup("planks") >= 2;
            case "minecraft:crafting_table" -> countInventoryGroup("planks") >= 4;
            case "minecraft:furnace" -> countInventoryGroup("cobblestone") >= 8;
            case "minecraft:torch" -> countInventoryItem("minecraft:stick") >= 1 && countInventoryGroup("coal_or_charcoal") >= 1;
            case "minecraft:bread" -> countInventoryItem("minecraft:wheat") >= 3;
            case "minecraft:shield" -> countInventoryItem("minecraft:iron_ingot") >= 1 && countInventoryGroup("planks") >= 6;
            case "minecraft:chest" -> countInventoryGroup("planks") >= 8;
            case "minecraft:ladder" -> countInventoryItem("minecraft:stick") >= 7;
            case "minecraft:bowl" -> countInventoryGroup("planks") >= 3;
            case "minecraft:bow" -> countInventoryItem("minecraft:stick") >= 3 && countInventoryItem("minecraft:string") >= 3;
            case "minecraft:arrow" -> countInventoryItem("minecraft:flint") >= 1 && countInventoryItem("minecraft:stick") >= 1 && countInventoryItem("minecraft:feather") >= 1;
            case "minecraft:fishing_rod" -> countInventoryItem("minecraft:stick") >= 3 && countInventoryItem("minecraft:string") >= 2;
            case "minecraft:bucket" -> countInventoryItem("minecraft:iron_ingot") >= 3;
            case "minecraft:shears" -> countInventoryItem("minecraft:iron_ingot") >= 2;
            case "minecraft:flint_and_steel" -> countInventoryItem("minecraft:iron_ingot") >= 1 && countInventoryItem("minecraft:flint") >= 1;
            case "minecraft:paper" -> countInventoryItem("minecraft:sugar_cane") >= 3;
            case "minecraft:book" -> countInventoryItem("minecraft:paper") >= 3 && countInventoryItem("minecraft:leather") >= 1;
            case "minecraft:bookshelf" -> countInventoryGroup("planks") >= 6 && countInventoryItem("minecraft:book") >= 3;
            case "minecraft:compass" -> countInventoryItem("minecraft:iron_ingot") >= 4 && countInventoryItem("minecraft:redstone") >= 1;
            case "minecraft:clock" -> countInventoryItem("minecraft:gold_ingot") >= 4 && countInventoryItem("minecraft:redstone") >= 1;
            case "minecraft:map" -> countInventoryItem("minecraft:paper") >= 8 && countInventoryItem("minecraft:compass") >= 1;
            case "minecraft:item_frame" -> countInventoryItem("minecraft:stick") >= 8 && countInventoryItem("minecraft:leather") >= 1;
            case "minecraft:painting" -> countInventoryItem("minecraft:stick") >= 8 && countInventoryGroup("wool") >= 1;
            case "minecraft:white_bed" -> countInventoryGroup("wool") >= 3 && countInventoryGroup("planks") >= 3;
            default -> hasBundledRecipeMaterials(recipe);
        };
    }

    private ExecutorProtocol.StepResult craftOnce(String actionType, String recipe) {
        return craftOnce(actionType, recipe, "");
    }

    private ExecutorProtocol.StepResult craftOnce(String actionType, String recipe, String expectedStation) {
        if (client.player == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Player or interaction manager is not ready.");
        }
        if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Cursor stack is not empty; refusing to craft to avoid item loss.");
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        boolean isPlayerInventory = handler instanceof PlayerScreenHandler;
        boolean isCraftingTable = handler instanceof CraftingScreenHandler;
        if ("minecraft:stonecutter".equals(expectedStation) && !(handler instanceof StonecutterScreenHandler)) {
            int screenWaitTicks = waitForExpectedScreenOpen("minecraft:stonecutter", 12);
            handler = client.player.currentScreenHandler;
            if (!(handler instanceof StonecutterScreenHandler)) {
                return recipeBlockedWithScreenState(actionType, recipe, "Open a stonecutter before crafting this recipe.", expectedStation, screenWaitTicks);
            }
        }
        if ("minecraft:smithing_table".equals(expectedStation) && !(handler instanceof SmithingScreenHandler)) {
            int screenWaitTicks = waitForExpectedScreenOpen("minecraft:smithing_table", 12);
            handler = client.player.currentScreenHandler;
            if (!(handler instanceof SmithingScreenHandler)) {
                return recipeBlockedWithScreenState(actionType, recipe, "Open a smithing table before crafting this recipe.", expectedStation, screenWaitTicks);
            }
        }
        if (handler instanceof StonecutterScreenHandler stonecutterHandler) {
            return stonecutOnce(actionType, recipe, stonecutterHandler);
        }
        if (handler instanceof SmithingScreenHandler smithingHandler) {
            return smithOnce(actionType, recipe, smithingHandler);
        }
        if (requiresCraftingTable(recipe) && !isCraftingTable) {
            int screenWaitTicks = waitForExpectedScreenOpen("minecraft:crafting_table", 12);
            handler = client.player.currentScreenHandler;
            isPlayerInventory = handler instanceof PlayerScreenHandler;
            isCraftingTable = handler instanceof CraftingScreenHandler;
            if (!isCraftingTable) {
                return recipeBlockedWithScreenState(actionType, recipe, "Open a crafting table before crafting this recipe.", "minecraft:crafting_table", screenWaitTicks);
            }
        }
        if (!isPlayerInventory && !isCraftingTable) {
            return recipeBlocked(actionType, recipe, "Open the inventory or a crafting table before crafting.");
        }
        if (isPlayerInventory && client.currentScreen == null) {
            client.setScreen(new InventoryScreen(client.player));
            waitForInventoryScreenOpen(4);
            handler = client.player.currentScreenHandler;
            isPlayerInventory = handler instanceof PlayerScreenHandler;
        }

        int before = countInventoryItem(recipe);
        ExecutorProtocol.StepResult directCraft = tryDirectCraftOnce(actionType, recipe, before);
        if (directCraft != null) {
            return directCraft;
        }
        ExecutorProtocol.StepResult existingOutput = takeExistingCraftOutputIfMatches(actionType, recipe, handler, isCraftingTable, before);
        if (existingOutput != null) {
            return existingOutput;
        }

        Optional<RecipeBookMatch> recipeBookMatch = findRecipeBookMatch(recipe);
        if (recipeBookMatch.isPresent()) {
            RecipeBookMatch match = recipeBookMatch.get();
            if (match.craftable()) {
                client.interactionManager.clickRecipe(handler.syncId, match.networkRecipeId(), false);
                sleepTicks(3);
                ExecutorProtocol.StepResult recipeBookOutput = takeExistingCraftOutputIfMatches(actionType, recipe, handler, isCraftingTable, before);
                if (recipeBookOutput != null) {
                    if (recipeBookOutput.data() != null) {
                        recipeBookOutput.data().addProperty("network_recipe_id", match.networkRecipeId().index());
                        recipeBookOutput.data().addProperty("recipebook_match", true);
                    }
                    return recipeBookOutput;
                }
                int after = countInventoryItem(recipe);
                if (after > before) {
                    JsonObject data = recipeCraftData(recipe, isCraftingTable ? "crafting_table" : "inventory", "recipebook", before, after);
                    data.addProperty("network_recipe_id", match.networkRecipeId().index());
                    return new ExecutorProtocol.StepResult(actionType, "accepted", "Crafted one recipe batch through RecipeBook quick craft.", data);
                }
            }
        }

        CraftIngredient[] ingredients = bundledCraftIngredients(recipe, handler, isCraftingTable);
        String method = "bundled_recipe_layout";
        if (ingredients.length == 0) {
            ingredients = craftIngredients(recipe, isCraftingTable);
            method = "slot_layout";
        }
        if (ingredients.length == 0) {
            return recipeBlocked(actionType, recipe, "No executor crafting pattern is available for this recipe yet.");
        }
        if (!craftingIngredientsAvailable(handler, ingredients)) {
            return recipeBlocked(actionType, recipe, "Required ingredients are not available in usable inventory slots.");
        }
        if (!craftingInputSlotsEmpty(handler, ingredients)) {
            clearCraftingInputSlots(handler, isCraftingTable);
            sleepTicks(2);
            if (!craftingInputSlotsEmpty(handler, ingredients)) {
                return recipeBlocked(actionType, recipe, "Crafting input slots could not be cleared safely.");
            }
        }

        for (CraftIngredient ingredient : ingredients) {
            if (!placeOneIngredient(handler, ingredient)) {
                return recipeBlocked(actionType, recipe, "Required ingredient is not available in a usable inventory slot.");
            }
        }

        ItemStack output = waitForCraftOutput(handler, recipe, 12);
        if (output.isEmpty()) {
            JsonObject data = recipeCraftData(recipe, isCraftingTable ? "crafting_table" : "inventory", method, before, before);
            data.addProperty("inventory_has_materials", hasKnownRecipeMaterials(recipe));
            data.addProperty("requires_crafting_table", requiresCraftingTable(recipe));
            data.addProperty("output_wait_ticks", 12);
            data.add("crafting_slots", craftingSlotSnapshot(handler, isCraftingTable, ingredients));
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Recipe output slot did not become available after placing ingredients.", data);
        }
        String outputItem = itemId(output);
        if (!recipe.equals(outputItem)) {
            JsonObject data = recipeCraftData(recipe, isCraftingTable ? "crafting_table" : "inventory", method, before, before);
            data.addProperty("actual_output_item", outputItem);
            data.addProperty("actual_output_count", output.getCount());
            data.add("crafting_slots", craftingSlotSnapshot(handler, isCraftingTable, ingredients));
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Crafting grid produced a different output than requested.", data);
        }
        JsonObject outputTake = takeCraftOutputIntoInventory(handler, recipe, before);
        int after = intData(outputTake, "inventory_after", countInventoryItem(recipe));
        if (after <= before) {
            JsonObject data = recipeCraftData(recipe, isCraftingTable ? "crafting_table" : "inventory", method, before, after);
            data.addProperty("recipebook_match", recipeBookMatch.isPresent());
            data.add("output_take", outputTake);
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Crafting output was not confirmed by inventory delta.", data);
        }

        JsonObject data = recipeCraftData(recipe, isCraftingTable ? "crafting_table" : "inventory", method, before, after);
        data.addProperty("recipebook_match", recipeBookMatch.isPresent());
        data.add("output_take", outputTake);
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Crafted one recipe batch.", data);
    }

    private ExecutorProtocol.StepResult tryDirectCraftOnce(String actionType, String recipe, int before) {
        if (!config.worldAssistDirectCraftEnabled() || recipe == null || recipe.isBlank() || vanillaCraftRecipesForOutput(recipe).isEmpty()) {
            return null;
        }
        DirectContainerContext context = callOnClientThread(() -> {
            if (client.player == null || client.world == null) {
                return null;
            }
            MinecraftServer server = client.getServer();
            if (server == null) {
                return null;
            }
            return new DirectContainerContext(server, client.world.getRegistryKey(), client.player.getUuid());
        });
        if (context == null) {
            return null;
        }
        CompletableFuture<ExecutorProtocol.StepResult> future = new CompletableFuture<>();
        context.server().execute(() -> {
            try {
                ServerPlayerEntity serverPlayer = context.server().getPlayerManager().getPlayer(context.playerUuid());
                future.complete(directCraftOnceOnServer(actionType, recipe, before, serverPlayer, false));
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        });
        try {
            ExecutorProtocol.StepResult result = future.get(800, TimeUnit.MILLISECONDS);
            if (result != null && "accepted".equals(result.status()) && result.data() != null) {
                publishStatus("[World Assist] クラフト高速化: " + shortMinecraftId(recipe));
            }
            return result != null && !"blocked".equals(result.status()) ? result : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ExecutorProtocol.StepResult tryWorldAssistCraftFallbackOnce(
        String actionType,
        String recipe,
        int before,
        ExecutorProtocol.StepResult original
    ) {
        if (!config.worldAssistDirectCraftEnabled() || !config.worldAssistCommonMaterialTopUpEnabled()) {
            return original;
        }
        if (recipe == null || recipe.isBlank() || vanillaCraftRecipesForOutput(recipe).isEmpty()) {
            return original;
        }
        DirectContainerContext context = callOnClientThread(() -> {
            if (client.player == null || client.world == null) {
                return null;
            }
            MinecraftServer server = client.getServer();
            if (server == null) {
                return null;
            }
            return new DirectContainerContext(server, client.world.getRegistryKey(), client.player.getUuid());
        });
        if (context == null) {
            return original;
        }
        CompletableFuture<ExecutorProtocol.StepResult> future = new CompletableFuture<>();
        context.server().execute(() -> {
            try {
                ServerPlayerEntity serverPlayer = context.server().getPlayerManager().getPlayer(context.playerUuid());
                future.complete(directCraftOnceOnServer(actionType, recipe, before, serverPlayer, true));
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        });
        try {
            ExecutorProtocol.StepResult fallback = future.get(800, TimeUnit.MILLISECONDS);
            if (fallback == null || !"accepted".equals(fallback.status())) {
                return original;
            }
            if (fallback.data() != null) {
                fallback.data().addProperty("world_assist_fallback_from_status", original.status());
                fallback.data().addProperty("world_assist_fallback_from_message", original.message());
                if (original.data() != null) {
                    fallback.data().add("original_failure_data", original.data());
                }
            }
            publishStatus("[World Assist] クラフト補助: " + shortMinecraftId(recipe));
            return fallback;
        } catch (Exception ignored) {
            return original;
        }
    }

    private ExecutorProtocol.StepResult directCraftOnceOnServer(String actionType, String recipe, int before, ServerPlayerEntity serverPlayer, boolean allowTopUp) {
        if (serverPlayer == null) {
            return null;
        }
        boolean requiresTable = requiresCraftingTable(recipe);
        int grid = requiresTable ? 3 : 2;
        for (VanillaCraftRecipe candidate : vanillaCraftRecipesForOutput(recipe)) {
            if (!candidate.fitsGrid(grid)) {
                continue;
            }
            List<List<VanillaIngredientAlternative>> ingredientSlots = candidate.ingredientSlots();
            if (ingredientSlots.isEmpty()) {
                continue;
            }
            Inventory inventory = serverPlayer.getInventory();
            List<DirectCraftIngredientUse> ingredientUses = matchingInventorySlotsForRecipe(inventory, ingredientSlots, allowTopUp);
            if (ingredientUses.isEmpty()) {
                continue;
            }
            int outputCount = Math.max(1, candidate.count());
            Item outputItem = Registries.ITEM.get(Identifier.of(recipe));
            ItemStack output = new ItemStack(outputItem, outputCount);
            if (output.isEmpty() || !canInsertIntoInventory(inventory, output)) {
                continue;
            }
            JsonArray consumed = new JsonArray();
            JsonArray generated = new JsonArray();
            int generatedCount = 0;
            for (DirectCraftIngredientUse ingredient : ingredientUses) {
                JsonObject one = new JsonObject();
                one.addProperty("slot", ingredient.slot());
                one.addProperty("item", ingredient.itemId());
                one.addProperty("count", 1);
                one.addProperty("generated", ingredient.generated());
                if (ingredient.generated()) {
                    generated.add(one.deepCopy());
                    generatedCount++;
                } else {
                    ItemStack stack = inventory.getStack(ingredient.slot());
                    stack.decrement(1);
                }
                consumed.add(one);
            }
            ItemStack inserted = output.copy();
            serverPlayer.getInventory().insertStack(inserted);
            int insertedCount = outputCount - inserted.getCount();
            if (insertedCount <= 0) {
                return null;
            }
            inventory.markDirty();
            serverPlayer.playerScreenHandler.sendContentUpdates();
            serverPlayer.currentScreenHandler.sendContentUpdates();
            String method = allowTopUp ? "world_assist_direct_recipe" : "integrated_server_direct_recipe";
            JsonObject data = recipeCraftData(recipe, requiresTable ? "direct_3x3" : "direct_2x2", method, before, before + insertedCount);
            data.addProperty("recipe_id", candidate.recipeId());
            data.addProperty("output_count", insertedCount);
            data.addProperty("requires_crafting_table", requiresTable);
            data.addProperty("ui_bypassed", true);
            data.addProperty("world_assist", allowTopUp || config.worldAssistEnabled());
            data.addProperty("world_assist_generated_ingredient_count", generatedCount);
            data.addProperty("consume_items_when_possible", true);
            data.add("consumed_items", consumed);
            data.add("generated_ingredients", generated);
            return new ExecutorProtocol.StepResult(actionType, "accepted", allowTopUp
                ? "World assist crafted one recipe batch with deterministic recipe fallback."
                : "Crafted one recipe batch by deterministic direct inventory craft.", data);
        }
        return null;
    }

    private List<DirectCraftIngredientUse> matchingInventorySlotsForRecipe(
        Inventory inventory,
        List<List<VanillaIngredientAlternative>> ingredientSlots,
        boolean allowTopUp
    ) {
        Map<Integer, Integer> remaining = new HashMap<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                remaining.put(slot, stack.getCount());
            }
        }
        List<DirectCraftIngredientUse> matchedSlots = new ArrayList<>();
        for (List<VanillaIngredientAlternative> alternatives : ingredientSlots) {
            int matchedSlot = -1;
            for (Map.Entry<Integer, Integer> entry : remaining.entrySet()) {
                if (entry.getValue() <= 0) {
                    continue;
                }
                ItemStack stack = inventory.getStack(entry.getKey());
                String itemId = itemId(stack);
                if (matchesAnyAlternative(stack, itemId, alternatives)) {
                    matchedSlot = entry.getKey();
                    break;
                }
            }
            if (matchedSlot < 0) {
                if (!allowTopUp) {
                    return List.of();
                }
                String generatedItem = worldAssistIngredientItem(alternatives);
                if (generatedItem.isBlank()) {
                    return List.of();
                }
                matchedSlots.add(new DirectCraftIngredientUse(-1, generatedItem, true));
                continue;
            }
            ItemStack stack = inventory.getStack(matchedSlot);
            matchedSlots.add(new DirectCraftIngredientUse(matchedSlot, itemId(stack), false));
            remaining.put(matchedSlot, remaining.get(matchedSlot) - 1);
        }
        return matchedSlots;
    }

    private String worldAssistIngredientItem(List<VanillaIngredientAlternative> alternatives) {
        for (VanillaIngredientAlternative alternative : alternatives) {
            String item = worldAssistConcreteIngredientItem(alternative);
            if (!item.isBlank() && isVoiceAssistGrantAllowedItem(item)) {
                return item;
            }
        }
        return "";
    }

    private String worldAssistConcreteIngredientItem(VanillaIngredientAlternative alternative) {
        if (alternative == null) {
            return "";
        }
        if (!alternative.itemId().isBlank()) {
            return alternative.itemId();
        }
        return switch (alternative.tagId()) {
            case "minecraft:planks", "minecraft:wooden_tool_materials" -> "minecraft:oak_planks";
            case "minecraft:wooden_slabs" -> "minecraft:oak_slab";
            case "minecraft:logs", "minecraft:oak_logs" -> "minecraft:oak_log";
            case "minecraft:spruce_logs" -> "minecraft:spruce_log";
            case "minecraft:birch_logs" -> "minecraft:birch_log";
            case "minecraft:jungle_logs" -> "minecraft:jungle_log";
            case "minecraft:acacia_logs" -> "minecraft:acacia_log";
            case "minecraft:dark_oak_logs" -> "minecraft:dark_oak_log";
            case "minecraft:mangrove_logs" -> "minecraft:mangrove_log";
            case "minecraft:cherry_logs" -> "minecraft:cherry_log";
            case "minecraft:pale_oak_logs" -> "minecraft:pale_oak_log";
            case "minecraft:bamboo_blocks" -> "minecraft:bamboo_block";
            case "minecraft:crimson_stems" -> "minecraft:crimson_stem";
            case "minecraft:warped_stems" -> "minecraft:warped_stem";
            case "minecraft:stone_tool_materials" -> "minecraft:cobblestone";
            case "minecraft:stone_crafting_materials" -> "minecraft:stone";
            case "minecraft:coals" -> "minecraft:charcoal";
            case "minecraft:wool" -> "minecraft:white_wool";
            case "minecraft:iron_tool_materials" -> "minecraft:iron_ingot";
            case "minecraft:gold_tool_materials" -> "minecraft:gold_ingot";
            case "minecraft:diamond_tool_materials" -> "minecraft:diamond";
            case "minecraft:soul_fire_base_blocks" -> "minecraft:soul_sand";
            default -> "";
        };
    }

    private boolean matchesAnyAlternative(ItemStack stack, String itemId, List<VanillaIngredientAlternative> alternatives) {
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (alternative.matches(stack, itemId)) {
                return true;
            }
        }
        return false;
    }

    private boolean canInsertIntoInventory(Inventory inventory, ItemStack output) {
        int remaining = output.getCount();
        String outputItem = itemId(output);
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                remaining -= output.getMaxCount();
            } else if (outputItem.equals(itemId(stack))) {
                remaining -= Math.max(0, stack.getMaxCount() - stack.getCount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private ExecutorProtocol.StepResult takeExistingCraftOutputIfMatches(String actionType, String recipe, ScreenHandler handler, boolean isCraftingTable, int before) {
        if (client.player == null || client.interactionManager == null) {
            return null;
        }
        ItemStack output = handler.getSlot(0).getStack();
        if (output.isEmpty() || !itemId(output).equals(recipe)) {
            return null;
        }
        JsonObject outputTake = takeCraftOutputIntoInventory(handler, recipe, before);
        int after = intData(outputTake, "inventory_after", countInventoryItem(recipe));
        JsonObject data = recipeCraftData(recipe, isCraftingTable ? "crafting_table" : "inventory", "existing_grid_output", before, after);
        data.add("output_take", outputTake);
        if (after <= before) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Existing crafting output was not confirmed by inventory delta.", data);
        }
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Collected existing crafting output from the current grid.", data);
    }

    private JsonObject takeCraftOutputIntoInventory(ScreenHandler handler, String recipe, int before) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe", recipe);
        data.addProperty("inventory_before", before);
        data.addProperty("primary_method", "quick_move_result_slot");
        if (client.player == null || client.interactionManager == null) {
            data.addProperty("inventory_after", before);
            data.addProperty("error", "player_or_interaction_manager_not_ready");
            return data;
        }
        client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
        int afterQuickMove = waitForInventoryItemCount(recipe, before, 8);
        data.addProperty("after_quick_move", afterQuickMove);
        if (afterQuickMove > before) {
            data.addProperty("inventory_after", afterQuickMove);
            data.addProperty("take_confirmed_by", "quick_move_inventory_delta");
            return data;
        }

        ItemStack output = handler.getSlot(0).getStack();
        int targetSlot = findEmptyInventoryHandlerSlot(handler);
        data.addProperty("fallback_method", "pickup_result_to_empty_inventory_slot");
        data.addProperty("fallback_target_slot", targetSlot);
        if (targetSlot >= 0 && !output.isEmpty() && recipe.equals(itemId(output)) && handler.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.PICKUP, client.player);
            sleepTicks(1);
            if (!handler.getCursorStack().isEmpty() && recipe.equals(itemId(handler.getCursorStack()))) {
                client.interactionManager.clickSlot(handler.syncId, targetSlot, 0, SlotActionType.PICKUP, client.player);
            }
            int afterFallback = waitForInventoryItemCount(recipe, before, 8);
            data.addProperty("inventory_after", afterFallback);
            data.addProperty("cursor_empty", handler.getCursorStack().isEmpty());
            if (!handler.getCursorStack().isEmpty()) {
                data.addProperty("cursor_item", itemId(handler.getCursorStack()));
                data.addProperty("cursor_count", handler.getCursorStack().getCount());
            }
            data.addProperty("take_confirmed_by", afterFallback > before ? "pickup_fallback_inventory_delta" : "unconfirmed");
            return data;
        }
        int after = countInventoryItem(recipe);
        data.addProperty("inventory_after", after);
        data.addProperty("cursor_empty", handler.getCursorStack().isEmpty());
        data.addProperty("take_confirmed_by", "unconfirmed");
        return data;
    }

    private int waitForInventoryItemCount(String item, int before, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            int current = countInventoryItem(item);
            if (current > before) {
                return current;
            }
            sleepTicks(1);
        }
        return countInventoryItem(item);
    }

    private int findEmptyInventoryHandlerSlot(ScreenHandler handler) {
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (isProtectedRecipeSlot(handler, slot)) {
                continue;
            }
            if (handler.getSlot(slot).getStack().isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack waitForCraftOutput(ScreenHandler handler, String recipe, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            ItemStack output = handler.getSlot(0).getStack();
            if (!output.isEmpty() && recipe.equals(itemId(output))) {
                return output.copy();
            }
            if (!output.isEmpty()) {
                return output.copy();
            }
            sleepTicks(1);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack waitForSlotOutput(ScreenHandler handler, int slot, String expectedItem, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            if (slot >= 0 && slot < handler.slots.size()) {
                ItemStack output = handler.getSlot(slot).getStack();
                if (!output.isEmpty() && (expectedItem.isBlank() || expectedItem.equals(itemId(output)))) {
                    return output.copy();
                }
                if (!output.isEmpty()) {
                    return output.copy();
                }
            }
            sleepTicks(1);
        }
        return ItemStack.EMPTY;
    }

    private int waitForEquivalentStackCount(ItemStack expected, int before, int maxTicks) {
        for (int tick = 0; tick <= maxTicks; tick++) {
            int current = countInventoryEquivalentStack(expected);
            if (current > before) {
                return current;
            }
            sleepTicks(1);
        }
        return countInventoryEquivalentStack(expected);
    }

    private ExecutorProtocol.StepResult recipeBlocked(String actionType, String recipe, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe", recipe);
        data.addProperty("inventory_has_materials", hasKnownRecipeMaterials(recipe));
        data.addProperty("requires_crafting_table", requiresCraftingTable(recipe));
        findRecipeBookMatch(recipe).ifPresent(match -> {
            data.addProperty("recipebook_match", true);
            data.addProperty("recipebook_craftable", match.craftable());
            data.addProperty("network_recipe_id", match.networkRecipeId().index());
        });
        return new ExecutorProtocol.StepResult(actionType, "blocked", message, data);
    }

    private ExecutorProtocol.StepResult recipeBlockedWithScreenState(String actionType, String recipe, String message, String expectedStation, int screenWaitTicks) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe", recipe);
        data.addProperty("expected_station", expectedStation);
        data.addProperty("screen_wait_ticks", screenWaitTicks);
        data.addProperty("current_screen", client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName());
        ScreenHandler handler = client.player == null ? null : client.player.currentScreenHandler;
        data.addProperty("current_handler", handler == null ? "none" : handler.getClass().getSimpleName());
        data.addProperty("inventory_has_materials", hasKnownRecipeMaterials(recipe));
        data.addProperty("requires_crafting_table", requiresCraftingTable(recipe));
        return new ExecutorProtocol.StepResult(actionType, "blocked", message, data);
    }

    private JsonObject recipeCraftData(String recipe, String screen, String method, int before, int after) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe", recipe);
        data.addProperty("output_count", recipeOutputCount(recipe));
        data.addProperty("screen", screen);
        data.addProperty("method", method);
        data.addProperty("inventory_before", before);
        data.addProperty("inventory_after", after);
        data.addProperty("inventory_delta", after - before);
        return data;
    }

    private JsonObject craftingSlotSnapshot(ScreenHandler handler, boolean craftingTable, CraftIngredient[] ingredients) {
        JsonObject snapshot = new JsonObject();
        int maxInputSlot = craftingTable ? 9 : 4;
        snapshot.addProperty("cursor_empty", handler.getCursorStack().isEmpty());
        if (!handler.getCursorStack().isEmpty()) {
            snapshot.addProperty("cursor_item", itemId(handler.getCursorStack()));
            snapshot.addProperty("cursor_count", handler.getCursorStack().getCount());
        }
        ItemStack output = handler.getSlot(0).getStack();
        snapshot.addProperty("output_empty", output.isEmpty());
        if (!output.isEmpty()) {
            snapshot.addProperty("output_item", itemId(output));
            snapshot.addProperty("output_count", output.getCount());
        }
        JsonArray inputs = new JsonArray();
        for (int slot = 1; slot <= maxInputSlot && slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            JsonObject one = new JsonObject();
            one.addProperty("slot", slot);
            one.addProperty("empty", stack.isEmpty());
            if (!stack.isEmpty()) {
                one.addProperty("item", itemId(stack));
                one.addProperty("count", stack.getCount());
            }
            inputs.add(one);
        }
        snapshot.add("inputs", inputs);
        snapshot.add("expected_ingredients", craftIngredientSnapshot(ingredients));
        return snapshot;
    }

    private JsonArray craftIngredientSnapshot(CraftIngredient[] ingredients) {
        JsonArray result = new JsonArray();
        for (CraftIngredient ingredient : ingredients) {
            JsonObject one = new JsonObject();
            one.addProperty("slot", ingredient.slot());
            if (!ingredient.itemId().isBlank()) {
                one.addProperty("item", ingredient.itemId());
            }
            if (!ingredient.group().isBlank()) {
                one.addProperty("group", ingredient.group());
            }
            if (!ingredient.fallbackGroup().isBlank()) {
                one.addProperty("fallback_group", ingredient.fallbackGroup());
            }
            if (!ingredient.alternatives().isEmpty()) {
                JsonArray alternatives = new JsonArray();
                for (VanillaIngredientAlternative alternative : ingredient.alternatives()) {
                    JsonObject candidate = new JsonObject();
                    if (!alternative.itemId().isBlank()) {
                        candidate.addProperty("item", alternative.itemId());
                    }
                    if (!alternative.tagId().isBlank()) {
                        candidate.addProperty("tag", alternative.tagId());
                    }
                    alternatives.add(candidate);
                }
                one.add("alternatives", alternatives);
            }
            result.add(one);
        }
        return result;
    }

    private int stepOutputCount(ExecutorProtocol.StepResult result, String recipe) {
        JsonObject data = result.data();
        if (data != null && data.has("output_count") && data.get("output_count").isJsonPrimitive()) {
            return Math.max(1, data.get("output_count").getAsInt());
        }
        return recipeOutputCount(recipe);
    }

    private ExecutorProtocol.StepResult stonecutOnce(String actionType, String outputItem, StonecutterScreenHandler handler) {
        List<VanillaStonecuttingRecipe> candidates = vanillaStonecuttingRecipesForOutput(outputItem);
        if (candidates.isEmpty()) {
            return recipeBlocked(actionType, outputItem, "No bundled stonecutting recipe is available for this output.");
        }
        if (!handler.getCursorStack().isEmpty()) {
            return recipeBlocked(actionType, outputItem, "Cursor stack is not empty; refusing to stonecut to avoid item loss.");
        }
        if (!handler.getSlot(0).getStack().isEmpty()) {
            return recipeBlocked(actionType, outputItem, "Stonecutter input slot is not empty; refusing to overwrite existing items.");
        }

        int before = countInventoryItem(outputItem);
        VanillaStonecuttingRecipe selected = null;
        for (VanillaStonecuttingRecipe candidate : candidates) {
            CraftIngredient ingredient = CraftIngredient.vanilla(0, candidate.ingredient());
            if (craftingIngredientsAvailable(handler, new CraftIngredient[] { ingredient })) {
                selected = candidate;
                if (!placeOneIngredient(handler, ingredient)) {
                    return recipeBlocked(actionType, outputItem, "Required stonecutting ingredient is not available in a usable inventory slot.");
                }
                break;
            }
        }
        if (selected == null) {
            return recipeBlocked(actionType, outputItem, "Required stonecutting ingredient is not available in inventory.");
        }

        sleepTicks(2);
        int recipeIndex = findStonecutterRecipeIndex(handler, outputItem);
        if (recipeIndex < 0) {
            return recipeBlocked(actionType, outputItem, "Stonecutter did not expose the requested output for the inserted ingredient.");
        }
        client.interactionManager.clickButton(handler.syncId, recipeIndex);
        ItemStack output = waitForSlotOutput(handler, 1, outputItem, 12);
        if (output.isEmpty() || !outputItem.equals(itemId(output))) {
            return recipeBlocked(actionType, outputItem, "Stonecutter output slot did not become available.");
        }
        client.interactionManager.clickSlot(handler.syncId, 1, 0, SlotActionType.QUICK_MOVE, client.player);

        int after = waitForInventoryItemCount(outputItem, before, 8);
        JsonObject data = recipeCraftData(outputItem, "stonecutter", "stonecutting_layout", before, after);
        data.addProperty("recipe_id", selected.recipeId());
        data.addProperty("output_count", selected.count());
        if (after <= before) {
            return new ExecutorProtocol.StepResult(actionType, "blocked", "Stonecutting output was not confirmed by inventory delta.", data);
        }
        return new ExecutorProtocol.StepResult(actionType, "accepted", "Crafted one stonecutting recipe batch.", data);
    }

    private int findStonecutterRecipeIndex(StonecutterScreenHandler handler, String outputItem) {
        var entries = handler.getAvailableRecipes().entries();
        for (int index = 0; index < entries.size(); index++) {
            String output = slotDisplayOutputItem(entries.get(index).recipe().optionDisplay());
            if (outputItem.equals(output)) {
                return index;
            }
        }
        return -1;
    }

    private ExecutorProtocol.StepResult smithOnce(String actionType, String outputItem, SmithingScreenHandler handler) {
        List<VanillaSmithingRecipe> candidates = vanillaSmithingRecipesForOutput(outputItem);
        if (candidates.isEmpty()) {
            return recipeBlocked(actionType, outputItem, "No bundled smithing transform recipe is available for this output.");
        }
        if (!handler.getCursorStack().isEmpty()) {
            return recipeBlocked(actionType, outputItem, "Cursor stack is not empty; refusing to smith to avoid item loss.");
        }
        for (int slot = 0; slot <= 2; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                return recipeBlocked(actionType, outputItem, "Smithing input slots are not empty; refusing to overwrite existing items.");
            }
        }

        int before = countInventoryItem(outputItem);
        for (VanillaSmithingRecipe candidate : candidates) {
            CraftIngredient[] ingredients = candidate.toCraftIngredients();
            if (!craftingIngredientsAvailable(handler, ingredients)) {
                continue;
            }
            for (CraftIngredient ingredient : ingredients) {
                if (!placeOneIngredient(handler, ingredient)) {
                    return recipeBlocked(actionType, outputItem, "Required smithing ingredient is not available in a usable inventory slot.");
                }
            }
            ItemStack output = waitForSlotOutput(handler, 3, outputItem, 12);
            if (output.isEmpty() || !outputItem.equals(itemId(output))) {
                return recipeBlocked(actionType, outputItem, "Smithing output slot did not become available for the requested output.");
            }
            client.interactionManager.clickSlot(handler.syncId, 3, 0, SlotActionType.QUICK_MOVE, client.player);
            int after = waitForInventoryItemCount(outputItem, before, 8);
            JsonObject data = recipeCraftData(outputItem, "smithing_table", "smithing_transform_layout", before, after);
            data.addProperty("recipe_id", candidate.recipeId());
            data.addProperty("output_count", candidate.count());
            if (after <= before) {
                return new ExecutorProtocol.StepResult(actionType, "blocked", "Smithing output was not confirmed by inventory delta.", data);
            }
            return new ExecutorProtocol.StepResult(actionType, "accepted", "Crafted one smithing transform recipe batch.", data);
        }
        return recipeBlocked(actionType, outputItem, "Required smithing ingredients are not available in inventory.");
    }

    private ExecutorProtocol.StepResult smithingTrim(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or interaction manager is not ready.");
            }
            String template = action.stringField("template");
            String base = action.stringField("base");
            String addition = action.stringField("addition");
            ScreenHandler rawHandler = client.player.currentScreenHandler;
            if (!(rawHandler instanceof SmithingScreenHandler handler)) {
                return trimBlocked(action, "Open a smithing table before applying armor trim.");
            }
            if (!handler.getCursorStack().isEmpty()) {
                return trimBlocked(action, "Cursor stack is not empty; refusing to smith to avoid item loss.");
            }
            for (int slot = 0; slot <= 2; slot++) {
                if (!handler.getSlot(slot).getStack().isEmpty()) {
                    return trimBlocked(action, "Smithing input slots are not empty; refusing to overwrite existing items.");
                }
            }

            VanillaSmithingTrimRecipe selected = findSmithingTrimRecipe(template).orElse(null);
            if (selected == null) {
                return trimBlocked(action, "No bundled smithing trim recipe is available for this template.");
            }
            if (!itemMatchesAlternatives(base, selected.base())) {
                return trimBlocked(action, "Requested base item is not a trimmable armor item for the bundled recipe.");
            }
            if (!itemMatchesAlternatives(addition, selected.addition())) {
                return trimBlocked(action, "Requested addition item is not a trim material for the bundled recipe.");
            }

            ItemStack baseBefore = findInventoryStackForItem(handler, base);
            if (baseBefore.isEmpty()) {
                return trimBlocked(action, "Requested armor base item is not available in inventory.");
            }
            String beforeComponents = componentSignature(baseBefore);
            String beforeTrim = trimSignature(baseBefore);

            CraftIngredient[] ingredients = new CraftIngredient[] {
                CraftIngredient.item(0, template),
                CraftIngredient.item(1, base),
                CraftIngredient.item(2, addition)
            };
            if (!craftingIngredientsAvailable(handler, ingredients)) {
                return trimBlocked(action, "Required smithing trim ingredients are not available in usable inventory slots.");
            }
            for (CraftIngredient ingredient : ingredients) {
                if (!placeOneIngredient(handler, ingredient)) {
                    return trimBlocked(action, "Required smithing trim ingredient is not available in a usable inventory slot.");
                }
            }

            ItemStack output = waitForSlotOutput(handler, 3, base, 12);
            if (output.isEmpty() || !base.equals(itemId(output))) {
                return trimBlocked(action, "Smithing trim output slot did not expose the requested base item.");
            }
            String afterComponents = componentSignature(output);
            String afterTrim = trimSignature(output);
            boolean componentChanged = !afterComponents.equals(beforeComponents);
            boolean trimChanged = !afterTrim.equals(beforeTrim) && !"none".equals(afterTrim);
            if (!componentChanged || !trimChanged) {
                JsonObject data = smithingTrimData(action, selected.recipeId(), beforeComponents, afterComponents, beforeTrim, afterTrim);
                data.addProperty("component_delta_confirmed", componentChanged);
                data.addProperty("trim_delta_confirmed", trimChanged);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Smithing trim output did not change the armor trim component.", data);
            }

            ItemStack expectedOutput = output.copy();
            int beforeEquivalent = countInventoryEquivalentStack(expectedOutput);
            client.interactionManager.clickSlot(handler.syncId, 3, 0, SlotActionType.QUICK_MOVE, client.player);
            int afterEquivalent = waitForEquivalentStackCount(expectedOutput, beforeEquivalent, 8);
            boolean inventoryConfirmed = afterEquivalent > beforeEquivalent;
            JsonObject data = smithingTrimData(action, selected.recipeId(), beforeComponents, afterComponents, beforeTrim, afterTrim);
            data.addProperty("component_delta_confirmed", true);
            data.addProperty("trim_delta_confirmed", true);
            data.addProperty("inventory_equivalent_before", beforeEquivalent);
            data.addProperty("inventory_equivalent_after", afterEquivalent);
            data.addProperty("inventory_component_match_confirmed", inventoryConfirmed);
            if (!inventoryConfirmed) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Smithing trim output was not confirmed by an equivalent component stack in inventory.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Applied armor trim using smithing table UI and component-delta verification.", data);
        });
    }

    private ExecutorProtocol.StepResult trimBlocked(ExecutorProtocol.Action action, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("template", action.stringField("template"));
        data.addProperty("base", action.stringField("base"));
        data.addProperty("addition", action.stringField("addition"));
        data.addProperty("bundled_smithing_trim_recipe_count", vanillaSmithingTrimRecipes().size());
        return new ExecutorProtocol.StepResult(action.type(), "blocked", message, data);
    }

    private JsonObject smithingTrimData(
        ExecutorProtocol.Action action,
        String recipeId,
        String beforeComponents,
        String afterComponents,
        String beforeTrim,
        String afterTrim
    ) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe_id", recipeId);
        data.addProperty("template", action.stringField("template"));
        data.addProperty("base", action.stringField("base"));
        data.addProperty("addition", action.stringField("addition"));
        data.addProperty("screen", "smithing_table");
        data.addProperty("method", "smithing_trim_component_layout");
        data.addProperty("component_before", beforeComponents);
        data.addProperty("component_after", afterComponents);
        data.addProperty("trim_before", beforeTrim);
        data.addProperty("trim_after", afterTrim);
        return data;
    }

    private ExecutorProtocol.StepResult componentCraft(ExecutorProtocol.Action action) {
        return runOnClientThread(() -> {
            if (client.player == null || client.interactionManager == null) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Player or interaction manager is not ready.");
            }
            String recipeKind = action.stringField("recipe_kind");
            String outputItem = action.stringField("output");
            List<String> ingredients = stringArrayField(action, "ingredients");
            if (!isSupportedComponentRecipeKind(recipeKind)) {
                return componentCraftBlocked(action, "Unsupported component craft recipe kind.");
            }
            if (ingredients.size() < 2 || ingredients.size() > 9) {
                return componentCraftBlocked(action, "Component craft requires 2..9 explicit ingredients.");
            }

            ScreenHandler handler = client.player.currentScreenHandler;
            boolean isPlayerInventory = handler instanceof PlayerScreenHandler;
            boolean isCraftingTable = handler instanceof CraftingScreenHandler;
            if (ingredients.size() > 4 && !isCraftingTable) {
                int screenWaitTicks = waitForExpectedScreenOpen("minecraft:crafting_table", 12);
                handler = client.player.currentScreenHandler;
                isPlayerInventory = handler instanceof PlayerScreenHandler;
                isCraftingTable = handler instanceof CraftingScreenHandler;
                if (!isCraftingTable) {
                    JsonObject data = componentCraftData(action, "none", "none", false, 0, 0);
                    data.addProperty("expected_station", "minecraft:crafting_table");
                    data.addProperty("screen_wait_ticks", screenWaitTicks);
                    data.addProperty("current_screen", client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName());
                    data.addProperty("current_handler", handler == null ? "none" : handler.getClass().getSimpleName());
                    return new ExecutorProtocol.StepResult(action.type(), "blocked", "Open a crafting table before this component recipe; inventory crafting grid is too small.", data);
                }
            }
            if (!isPlayerInventory && !isCraftingTable) {
                return componentCraftBlocked(action, "Open the inventory or a crafting table before component crafting.");
            }
            if (isPlayerInventory && client.currentScreen == null) {
                client.setScreen(new InventoryScreen(client.player));
                waitForInventoryScreenOpen(4);
                handler = client.player.currentScreenHandler;
                isPlayerInventory = handler instanceof PlayerScreenHandler;
            }
            if (!handler.getCursorStack().isEmpty()) {
                return componentCraftBlocked(action, "Cursor stack is not empty; refusing to craft to avoid item loss.");
            }

            int[] inputSlots = componentCraftInputSlots(recipeKind, ingredients, isCraftingTable);
            if (ingredients.size() > inputSlots.length) {
                return componentCraftBlocked(action, "Open a crafting table before this component recipe; inventory crafting grid is too small.");
            }
            CraftIngredient[] craftIngredients = new CraftIngredient[ingredients.size()];
            for (int index = 0; index < ingredients.size(); index++) {
                craftIngredients[index] = CraftIngredient.item(inputSlots[index], ingredients.get(index));
            }
            if (!craftingInputSlotsEmpty(handler, craftIngredients)) {
                return componentCraftBlocked(action, "Crafting input slots are not empty; refusing to overwrite existing items.");
            }

            String sourceItem = action.stringField("source_item");
            if (sourceItem.isBlank()) {
                sourceItem = defaultComponentSourceItem(recipeKind, outputItem, ingredients);
            }
            ItemStack sourceBefore = sourceItem.isBlank() ? ItemStack.EMPTY : findInventoryStackForItem(handler, sourceItem);
            String componentBefore = componentValueSignature(sourceBefore, recipeKind);

            if (!craftingIngredientsAvailable(handler, craftIngredients)) {
                return componentCraftBlocked(action, "Required component craft ingredients are not available in usable inventory slots.");
            }
            for (CraftIngredient ingredient : craftIngredients) {
                if (!placeOneIngredient(handler, ingredient)) {
                    return componentCraftBlocked(action, "Required component craft ingredient is not available in a usable inventory slot.");
                }
            }

            ItemStack output = waitForSlotOutput(handler, 0, outputItem, 12);
            if (output.isEmpty() || !outputItem.equals(itemId(output))) {
                return componentCraftBlocked(action, "Component craft output slot did not expose the requested output item.");
            }
            String componentAfter = componentValueSignature(output, recipeKind);
            boolean expectedComponentPresent = !"none".equals(componentAfter);
            boolean componentChanged = !componentAfter.equals(componentBefore);
            if (!expectedComponentPresent || !componentChanged) {
                JsonObject data = componentCraftData(action, componentBefore, componentAfter, false, 0, 0);
                data.addProperty("expected_component_present", expectedComponentPresent);
                data.addProperty("component_delta_confirmed", componentChanged);
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Component craft output did not expose the expected component delta.", data);
            }

            ItemStack expectedOutput = output.copy();
            int beforeEquivalent = countInventoryEquivalentStack(expectedOutput);
            client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
            int afterEquivalent = waitForEquivalentStackCount(expectedOutput, beforeEquivalent, 8);
            boolean inventoryConfirmed = afterEquivalent > beforeEquivalent;
            JsonObject data = componentCraftData(action, componentBefore, componentAfter, true, beforeEquivalent, afterEquivalent);
            data.addProperty("output_count", expectedOutput.getCount());
            data.addProperty("inventory_component_match_confirmed", inventoryConfirmed);
            if (!inventoryConfirmed) {
                return new ExecutorProtocol.StepResult(action.type(), "blocked", "Component craft output was not confirmed by an equivalent component stack inventory delta.", data);
            }
            return new ExecutorProtocol.StepResult(action.type(), "accepted", "Crafted special recipe using component-delta verification.", data);
        });
    }

    private ExecutorProtocol.StepResult componentCraftBlocked(ExecutorProtocol.Action action, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe_kind", action.stringField("recipe_kind"));
        data.addProperty("output", action.stringField("output"));
        JsonArray ingredients = new JsonArray();
        for (String ingredient : stringArrayField(action, "ingredients")) {
            ingredients.add(ingredient);
        }
        data.add("ingredients", ingredients);
        return new ExecutorProtocol.StepResult(action.type(), "blocked", message, data);
    }

    private JsonObject componentCraftData(
        ExecutorProtocol.Action action,
        String componentBefore,
        String componentAfter,
        boolean componentDeltaConfirmed,
        int beforeEquivalent,
        int afterEquivalent
    ) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe_kind", action.stringField("recipe_kind"));
        data.addProperty("output", action.stringField("output"));
        data.addProperty("component_name", componentNameForRecipeKind(action.stringField("recipe_kind")));
        data.addProperty("component_before", componentBefore);
        data.addProperty("component_after", componentAfter);
        data.addProperty("component_delta_confirmed", componentDeltaConfirmed);
        data.addProperty("equivalent_inventory_before", beforeEquivalent);
        data.addProperty("equivalent_inventory_after", afterEquivalent);
        data.addProperty("equivalent_inventory_delta", afterEquivalent - beforeEquivalent);
        return data;
    }

    private List<String> stringArrayField(ExecutorProtocol.Action action, String field) {
        JsonElement value = action.body().get(field);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element != null && element.isJsonPrimitive()) {
                String text = element.getAsString();
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private boolean isSupportedComponentRecipeKind(String recipeKind) {
        return recipeKind.equals("armor_dye")
            || recipeKind.equals("banner_duplicate")
            || recipeKind.equals("map_cloning")
            || recipeKind.equals("map_extending")
            || recipeKind.equals("firework_rocket")
            || recipeKind.equals("firework_star")
            || recipeKind.equals("firework_star_fade");
    }

    private int[] componentCraftInputSlots(String recipeKind, List<String> ingredients, boolean craftingTable) {
        if (recipeKind.equals("map_extending") && craftingTable && ingredients.size() == 9) {
            return new int[] { 5, 1, 2, 3, 4, 6, 7, 8, 9 };
        }
        return craftingTable ? new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 } : new int[] { 1, 2, 3, 4 };
    }

    private String defaultComponentSourceItem(String recipeKind, String outputItem, List<String> ingredients) {
        if (recipeKind.equals("armor_dye") || recipeKind.equals("firework_star_fade")) {
            return outputItem;
        }
        if (recipeKind.equals("map_cloning") || recipeKind.equals("map_extending")) {
            return "minecraft:filled_map";
        }
        if (recipeKind.equals("banner_duplicate")) {
            for (String ingredient : ingredients) {
                if (ingredient.endsWith("_banner")) {
                    return ingredient;
                }
            }
        }
        return "";
    }

    private Optional<RecipeBookMatch> findRecipeBookMatch(String outputItem) {
        if (client.player == null || outputItem == null || outputItem.isBlank()) {
            return Optional.empty();
        }
        for (RecipeResultCollection collection : client.player.getRecipeBook().getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                if (outputItem.equals(recipeDisplayOutputItem(entry))) {
                    return Optional.of(new RecipeBookMatch(entry.id(), collection.isCraftable(entry.id())));
                }
            }
        }
        return Optional.empty();
    }

    private String recipeDisplayOutputItem(RecipeDisplayEntry entry) {
        return slotDisplayOutputItem(entry.display().result());
    }

    private String slotDisplayOutputItem(SlotDisplay display) {
        if (display instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
            return Registries.ITEM.getId(itemDisplay.item().value()).toString();
        }
        if (display instanceof SlotDisplay.StackSlotDisplay stackDisplay && !stackDisplay.stack().isEmpty()) {
            return itemId(stackDisplay.stack());
        }
        if (display instanceof SlotDisplay.WithRemainderSlotDisplay remainderDisplay) {
            return slotDisplayOutputItem(remainderDisplay.input());
        }
        if (display instanceof SlotDisplay.CompositeSlotDisplay compositeDisplay) {
            for (SlotDisplay child : compositeDisplay.contents()) {
                String output = slotDisplayOutputItem(child);
                if (!output.isBlank()) {
                    return output;
                }
            }
        }
        return "";
    }

    private CraftIngredient[] bundledCraftIngredients(String outputItem, ScreenHandler handler, boolean craftingTable) {
        int grid = craftingTable ? 3 : 2;
        List<VanillaCraftRecipe> candidates = vanillaCraftRecipesForOutput(outputItem);
        for (VanillaCraftRecipe recipe : candidates) {
            if (!recipe.fitsGrid(grid)) {
                continue;
            }
            CraftIngredient[] ingredients = recipe.toCraftIngredients(grid);
            if (ingredients.length > 0 && craftingIngredientsAvailable(handler, ingredients)) {
                return ingredients;
            }
        }
        return new CraftIngredient[0];
    }

    private boolean hasBundledCraftRecipe(String outputItem) {
        return !vanillaCraftRecipesForOutput(outputItem).isEmpty();
    }

    private boolean hasBundledCraftRecipeForGrid(String outputItem, int grid) {
        for (VanillaCraftRecipe recipe : vanillaCraftRecipesForOutput(outputItem)) {
            if (recipe.fitsGrid(grid)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBundledRecipeMaterials(String outputItem) {
        if (client.player == null) {
            return false;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        boolean craftingTable = handler instanceof CraftingScreenHandler;
        return bundledCraftIngredients(outputItem, handler, craftingTable).length > 0;
    }

    private List<VanillaCraftRecipe> vanillaCraftRecipesForOutput(String outputItem) {
        List<VanillaCraftRecipe> matches = new ArrayList<>();
        for (VanillaCraftRecipe recipe : vanillaCraftRecipes()) {
            if (recipe.output().equals(outputItem)) {
                matches.add(recipe);
            }
        }
        matches.sort((a, b) -> {
            boolean aExact = a.recipeId().equals(outputItem);
            boolean bExact = b.recipeId().equals(outputItem);
            if (aExact != bExact) {
                return aExact ? -1 : 1;
            }
            return a.recipeId().compareTo(b.recipeId());
        });
        return matches;
    }

    private List<VanillaStonecuttingRecipe> vanillaStonecuttingRecipesForOutput(String outputItem) {
        List<VanillaStonecuttingRecipe> matches = new ArrayList<>();
        for (VanillaStonecuttingRecipe recipe : vanillaStonecuttingRecipes()) {
            if (recipe.output().equals(outputItem)) {
                matches.add(recipe);
            }
        }
        matches.sort((a, b) -> a.recipeId().compareTo(b.recipeId()));
        return matches;
    }

    private boolean hasBundledStonecuttingRecipe(String outputItem) {
        return !vanillaStonecuttingRecipesForOutput(outputItem).isEmpty();
    }

    private List<VanillaSmithingRecipe> vanillaSmithingRecipesForOutput(String outputItem) {
        List<VanillaSmithingRecipe> matches = new ArrayList<>();
        for (VanillaSmithingRecipe recipe : vanillaSmithingRecipes()) {
            if (recipe.output().equals(outputItem)) {
                matches.add(recipe);
            }
        }
        matches.sort((a, b) -> a.recipeId().compareTo(b.recipeId()));
        return matches;
    }

    private boolean hasBundledSmithingRecipe(String outputItem) {
        return !vanillaSmithingRecipesForOutput(outputItem).isEmpty();
    }

    private Optional<VanillaSmithingTrimRecipe> findSmithingTrimRecipe(String templateItem) {
        for (VanillaSmithingTrimRecipe recipe : vanillaSmithingTrimRecipes()) {
            if (itemMatchesAlternatives(templateItem, recipe.template())) {
                return Optional.of(recipe);
            }
        }
        return Optional.empty();
    }

    private boolean itemMatchesAlternatives(String itemId, List<VanillaIngredientAlternative> alternatives) {
        if (client.player == null || itemId == null || itemId.isBlank()) {
            return false;
        }
        ItemStack stack = ItemStack.EMPTY;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack candidate = client.player.getInventory().getStack(slot);
            if (!candidate.isEmpty() && itemId.equals(itemId(candidate))) {
                stack = candidate;
                break;
            }
        }
        for (VanillaIngredientAlternative alternative : alternatives) {
            if (alternative.matches(stack, itemId)) {
                return true;
            }
        }
        return false;
    }

    private List<VanillaSmithingTrimRecipe> vanillaSmithingTrimRecipes() {
        return vanillaRecipeCatalog.smithingTrimRecipes();
    }

    private List<VanillaSmithingRecipe> vanillaSmithingRecipes() {
        return vanillaRecipeCatalog.smithingRecipes();
    }

    private List<VanillaStonecuttingRecipe> vanillaStonecuttingRecipes() {
        return vanillaRecipeCatalog.stonecuttingRecipes();
    }

    private List<VanillaCraftRecipe> vanillaCraftRecipes() {
        return vanillaRecipeCatalog.craftRecipes();
    }

    private boolean craftingInputSlotsEmpty(ScreenHandler handler, CraftIngredient[] ingredients) {
        for (CraftIngredient ingredient : ingredients) {
            if (ingredient.slot() < 0 || ingredient.slot() >= handler.slots.size()) {
                return false;
            }
        }
        int maxInputSlot = handler instanceof CraftingScreenHandler ? 9 : 4;
        for (int slot = 1; slot <= maxInputSlot && slot < handler.slots.size(); slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void clearCraftingInputSlots(ScreenHandler handler, boolean craftingTable) {
        if (client.player == null || client.interactionManager == null) {
            return;
        }
        int maxInputSlot = craftingTable ? 9 : 4;
        for (int slot = 1; slot <= maxInputSlot && slot < handler.slots.size(); slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, client.player);
                sleepTicks(1);
            }
        }
    }

    private boolean craftingIngredientsAvailable(ScreenHandler handler, CraftIngredient[] ingredients) {
        Map<Integer, Integer> remaining = new HashMap<>();
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (isProtectedRecipeSlot(handler, slot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty()) {
                remaining.put(slot, stack.getCount());
            }
        }
        for (CraftIngredient ingredient : ingredients) {
            int matchedSlot = -1;
            for (Map.Entry<Integer, Integer> entry : remaining.entrySet()) {
                if (entry.getValue() <= 0) {
                    continue;
                }
                ItemStack stack = handler.getSlot(entry.getKey()).getStack();
                if (ingredient.matches(stack)) {
                    matchedSlot = entry.getKey();
                    break;
                }
            }
            if (matchedSlot < 0) {
                return false;
            }
            remaining.put(matchedSlot, remaining.get(matchedSlot) - 1);
        }
        return true;
    }

    private boolean placeOneIngredient(ScreenHandler handler, CraftIngredient ingredient) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        int sourceSlot = findInventorySlot(handler, ingredient);
        if (sourceSlot < 0) {
            return false;
        }
        client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, client.player);
        client.interactionManager.clickSlot(handler.syncId, ingredient.slot(), 1, SlotActionType.PICKUP, client.player);
        if (!handler.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, client.player);
        }
        sleepTicks(1);
        return handler.getCursorStack().isEmpty() && !handler.getSlot(ingredient.slot()).getStack().isEmpty();
    }

    private int findInventorySlot(ScreenHandler handler, CraftIngredient ingredient) {
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (isProtectedRecipeSlot(handler, slot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (ingredient.matches(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private int findHandlerSlotForLowValueDrop(ScreenHandler handler) {
        int fallback = -1;
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (isProtectedRecipeSlot(handler, slot)) {
                continue;
            }
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = itemId(stack);
            if (!isLowValueDropItem(itemId)) {
                continue;
            }
            if (slot >= 9) {
                return slot;
            }
            if (fallback < 0) {
                fallback = slot;
            }
        }
        return fallback;
    }

    private boolean isLowValueDropItem(String itemId) {
        return Set.of(
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:gravel",
            "minecraft:sand",
            "minecraft:red_sand",
            "minecraft:netherrack",
            "minecraft:deepslate",
            "minecraft:tuff",
            "minecraft:granite",
            "minecraft:diorite",
            "minecraft:andesite",
            "minecraft:flint",
            "minecraft:rotten_flesh"
        ).contains(itemId);
    }

    private boolean isProtectedRecipeSlot(ScreenHandler handler, int slot) {
        if (handler instanceof AbstractFurnaceScreenHandler) {
            return slot <= 2;
        }
        if (handler instanceof StonecutterScreenHandler) {
            return slot <= 1;
        }
        if (handler instanceof SmithingScreenHandler) {
            return slot <= 3;
        }
        if (handler instanceof CraftingScreenHandler) {
            return slot <= 9;
        }
        if (handler instanceof PlayerScreenHandler) {
            return slot <= 4;
        }
        return slot == 0;
    }

    private boolean isToolCraftRecipe(String recipe) {
        return recipe.startsWith("minecraft:")
            && (recipe.endsWith("_pickaxe")
                || recipe.endsWith("_axe")
                || recipe.endsWith("_shovel")
                || recipe.endsWith("_sword")
                || recipe.endsWith("_hoe"))
            && (recipe.contains(":wooden_")
                || recipe.contains(":stone_")
                || recipe.contains(":iron_")
                || recipe.contains(":golden_")
                || recipe.contains(":diamond_"));
    }

    private int countToolMaterial(String recipe) {
        String group = toolMaterialGroup(recipe);
        if (!group.isBlank()) {
            return countInventoryGroup(group);
        }
        String item = toolMaterialItem(recipe);
        return item.isBlank() ? 0 : countInventoryItem(item);
    }

    private int toolMaterialCount(String recipe) {
        if (recipe.endsWith("_pickaxe") || recipe.endsWith("_axe")) return 3;
        if (recipe.endsWith("_sword") || recipe.endsWith("_hoe")) return 2;
        if (recipe.endsWith("_shovel")) return 1;
        return 0;
    }

    private int toolStickCount(String recipe) {
        return recipe.endsWith("_sword") ? 1 : 2;
    }

    private String toolMaterialGroup(String recipe) {
        if (recipe.contains(":wooden_")) return "planks";
        if (recipe.contains(":stone_")) return "cobblestone";
        return "";
    }

    private String toolMaterialItem(String recipe) {
        if (recipe.contains(":iron_")) return "minecraft:iron_ingot";
        if (recipe.contains(":golden_")) return "minecraft:gold_ingot";
        if (recipe.contains(":diamond_")) return "minecraft:diamond";
        return "";
    }

    private CraftIngredient toolMaterialIngredient(int slot, String recipe) {
        String group = toolMaterialGroup(recipe);
        if (!group.isBlank()) {
            return CraftIngredient.group(slot, group);
        }
        return CraftIngredient.item(slot, toolMaterialItem(recipe));
    }

    private CraftIngredient[] toolCraftIngredients(String recipe) {
        if (recipe.endsWith("_pickaxe")) {
            return new CraftIngredient[] {
                toolMaterialIngredient(1, recipe),
                toolMaterialIngredient(2, recipe),
                toolMaterialIngredient(3, recipe),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.endsWith("_axe")) {
            return new CraftIngredient[] {
                toolMaterialIngredient(1, recipe),
                toolMaterialIngredient(2, recipe),
                toolMaterialIngredient(4, recipe),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.endsWith("_shovel")) {
            return new CraftIngredient[] {
                toolMaterialIngredient(2, recipe),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.endsWith("_sword")) {
            return new CraftIngredient[] {
                toolMaterialIngredient(2, recipe),
                toolMaterialIngredient(5, recipe),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.endsWith("_hoe")) {
            return new CraftIngredient[] {
                toolMaterialIngredient(1, recipe),
                toolMaterialIngredient(2, recipe),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        return new CraftIngredient[0];
    }

    private boolean isArmorCraftRecipe(String recipe) {
        return recipe.startsWith("minecraft:")
            && (recipe.contains(":leather_") || recipe.contains(":iron_") || recipe.contains(":golden_") || recipe.contains(":diamond_"))
            && (recipe.endsWith("_helmet") || recipe.endsWith("_chestplate") || recipe.endsWith("_leggings") || recipe.endsWith("_boots"));
    }

    private String armorMaterialItem(String recipe) {
        if (recipe.contains(":leather_")) return "minecraft:leather";
        if (recipe.contains(":iron_")) return "minecraft:iron_ingot";
        if (recipe.contains(":golden_")) return "minecraft:gold_ingot";
        if (recipe.contains(":diamond_")) return "minecraft:diamond";
        return "";
    }

    private int armorMaterialCount(String recipe) {
        if (recipe.endsWith("_helmet")) return 5;
        if (recipe.endsWith("_chestplate")) return 8;
        if (recipe.endsWith("_leggings")) return 7;
        if (recipe.endsWith("_boots")) return 4;
        return 0;
    }

    private CraftIngredient armorIngredient(int slot, String recipe) {
        return CraftIngredient.item(slot, armorMaterialItem(recipe));
    }

    private CraftIngredient[] armorCraftIngredients(String recipe) {
        if (recipe.endsWith("_helmet")) {
            return new CraftIngredient[] {
                armorIngredient(1, recipe), armorIngredient(2, recipe), armorIngredient(3, recipe),
                armorIngredient(4, recipe), armorIngredient(6, recipe)
            };
        }
        if (recipe.endsWith("_chestplate")) {
            return new CraftIngredient[] {
                armorIngredient(1, recipe), armorIngredient(3, recipe),
                armorIngredient(4, recipe), armorIngredient(5, recipe), armorIngredient(6, recipe),
                armorIngredient(7, recipe), armorIngredient(8, recipe), armorIngredient(9, recipe)
            };
        }
        if (recipe.endsWith("_leggings")) {
            return new CraftIngredient[] {
                armorIngredient(1, recipe), armorIngredient(2, recipe), armorIngredient(3, recipe),
                armorIngredient(4, recipe), armorIngredient(6, recipe),
                armorIngredient(7, recipe), armorIngredient(9, recipe)
            };
        }
        if (recipe.endsWith("_boots")) {
            return new CraftIngredient[] {
                armorIngredient(4, recipe), armorIngredient(6, recipe),
                armorIngredient(7, recipe), armorIngredient(9, recipe)
            };
        }
        return new CraftIngredient[0];
    }

    private String woodKey(String recipe) {
        if (!recipe.startsWith("minecraft:")) {
            return "";
        }
        String name = recipe.substring("minecraft:".length());
        for (String key : MinecraftRecipeFamilies.WOOD_KEYS) {
            if (name.equals(key + "_planks") || name.startsWith(key + "_")) {
                return key;
            }
        }
        if (name.equals("bamboo_raft") || name.equals("bamboo_chest_raft")) {
            return "bamboo";
        }
        return "";
    }

    private boolean isWoodCraftRecipe(String recipe) {
        String key = woodKey(recipe);
        if (key.isBlank()) return false;
        return recipe.equals("minecraft:" + key + "_planks")
            || recipe.equals("minecraft:" + key + "_button")
            || recipe.equals("minecraft:" + key + "_pressure_plate")
            || recipe.equals("minecraft:" + key + "_slab")
            || recipe.equals("minecraft:" + key + "_stairs")
            || recipe.equals("minecraft:" + key + "_door")
            || recipe.equals("minecraft:" + key + "_trapdoor")
            || recipe.equals("minecraft:" + key + "_fence")
            || recipe.equals("minecraft:" + key + "_fence_gate")
            || recipe.equals("minecraft:" + key + "_sign")
            || recipe.equals("minecraft:" + key + "_hanging_sign")
            || (key.equals("bamboo") && recipe.equals("minecraft:bamboo_raft"))
            || (key.equals("bamboo") && recipe.equals("minecraft:bamboo_chest_raft"))
            || (!key.equals("crimson") && !key.equals("warped") && recipe.equals("minecraft:" + key + "_boat"))
            || (!key.equals("crimson") && !key.equals("warped") && recipe.equals("minecraft:" + key + "_chest_boat"));
    }

    private boolean woodRecipeRequiresCraftingTable(String recipe) {
        if (!isWoodCraftRecipe(recipe)) return false;
        return !(recipe.endsWith("_planks") || recipe.endsWith("_button") || recipe.endsWith("_pressure_plate"));
    }

    private String woodLogItem(String key) {
        return switch (key) {
            case "bamboo" -> "minecraft:bamboo_block";
            case "crimson" -> "minecraft:crimson_stem";
            case "warped" -> "minecraft:warped_stem";
            default -> "minecraft:" + key + "_log";
        };
    }

    private String strippedWoodLogItem(String key) {
        return switch (key) {
            case "bamboo" -> "minecraft:stripped_bamboo_block";
            case "crimson" -> "minecraft:stripped_crimson_stem";
            case "warped" -> "minecraft:stripped_warped_stem";
            default -> "minecraft:stripped_" + key + "_log";
        };
    }

    private List<String> woodLogLikeItems(String key) {
        return switch (key) {
            case "bamboo" -> List.of("minecraft:bamboo_block", "minecraft:stripped_bamboo_block");
            case "crimson" -> List.of("minecraft:crimson_stem", "minecraft:crimson_hyphae", "minecraft:stripped_crimson_stem", "minecraft:stripped_crimson_hyphae");
            case "warped" -> List.of("minecraft:warped_stem", "minecraft:warped_hyphae", "minecraft:stripped_warped_stem", "minecraft:stripped_warped_hyphae");
            default -> List.of(
                "minecraft:" + key + "_log",
                "minecraft:" + key + "_wood",
                "minecraft:stripped_" + key + "_log",
                "minecraft:stripped_" + key + "_wood"
            );
        };
    }

    private String woodPlanksItem(String key) {
        return "minecraft:" + key + "_planks";
    }

    private String bestAvailablePlanksRecipeFromLog() {
        for (String key : MinecraftRecipeFamilies.WOOD_KEYS) {
            if (countAnyInventoryItem(woodLogLikeItems(key)) > 0) {
                return woodPlanksItem(key);
            }
        }
        return "";
    }

    private boolean hasWoodRecipeMaterials(String recipe) {
        String key = woodKey(recipe);
        String planks = woodPlanksItem(key);
        if (recipe.endsWith("_planks")) return countAnyInventoryItem(woodLogLikeItems(key)) >= 1;
        if (recipe.endsWith("_button")) return countInventoryItem(planks) >= 1;
        if (recipe.endsWith("_pressure_plate")) return countInventoryItem(planks) >= 2;
        if (recipe.endsWith("_slab")) return countInventoryItem(planks) >= 3;
        if (recipe.endsWith("_stairs") || recipe.endsWith("_door") || recipe.endsWith("_trapdoor")) return countInventoryItem(planks) >= 6;
        if (recipe.endsWith("_fence")) return countInventoryItem(planks) >= 4 && countInventoryItem("minecraft:stick") >= 2;
        if (recipe.endsWith("_fence_gate")) return countInventoryItem(planks) >= 2 && countInventoryItem("minecraft:stick") >= 4;
        if (recipe.endsWith("_hanging_sign")) return countInventoryItem(strippedWoodLogItem(key)) >= 6 && countInventoryItem("minecraft:chain") >= 2;
        if (recipe.endsWith("_sign")) return countInventoryItem(planks) >= 6 && countInventoryItem("minecraft:stick") >= 1;
        if (recipe.equals("minecraft:bamboo_chest_raft")) return countInventoryItem("minecraft:bamboo_raft") >= 1 && countInventoryItem("minecraft:chest") >= 1;
        if (recipe.endsWith("_chest_boat")) return countInventoryItem("minecraft:" + key + "_boat") >= 1 && countInventoryItem("minecraft:chest") >= 1;
        if (recipe.equals("minecraft:bamboo_raft")) return countInventoryItem(planks) >= 5;
        if (recipe.endsWith("_boat")) return countInventoryItem(planks) >= 5;
        return false;
    }

    private int countAnyInventoryItem(List<String> itemIds) {
        int count = 0;
        for (String itemId : itemIds) {
            count += countInventoryItem(itemId);
        }
        return count;
    }

    private CraftIngredient woodIngredient(int slot, String recipe) {
        return CraftIngredient.item(slot, woodPlanksItem(woodKey(recipe)));
    }

    private CraftIngredient[] woodCraftIngredients(String recipe, boolean craftingTable) {
        String key = woodKey(recipe);
        if (recipe.endsWith("_planks")) {
            return new CraftIngredient[] { CraftIngredient.item(1, woodLogItem(key)) };
        }
        if (recipe.endsWith("_button")) {
            return new CraftIngredient[] { CraftIngredient.item(1, woodPlanksItem(key)) };
        }
        if (recipe.endsWith("_pressure_plate")) {
            return new CraftIngredient[] { CraftIngredient.item(1, woodPlanksItem(key)), CraftIngredient.item(2, woodPlanksItem(key)) };
        }
        if (!craftingTable) return new CraftIngredient[0];
        if (recipe.endsWith("_slab")) {
            return new CraftIngredient[] { woodIngredient(7, recipe), woodIngredient(8, recipe), woodIngredient(9, recipe) };
        }
        if (recipe.endsWith("_stairs")) {
            return new CraftIngredient[] {
                woodIngredient(1, recipe), woodIngredient(4, recipe), woodIngredient(5, recipe),
                woodIngredient(7, recipe), woodIngredient(8, recipe), woodIngredient(9, recipe)
            };
        }
        if (recipe.endsWith("_door") || recipe.endsWith("_trapdoor")) {
            return new CraftIngredient[] {
                woodIngredient(1, recipe), woodIngredient(2, recipe),
                woodIngredient(4, recipe), woodIngredient(5, recipe),
                woodIngredient(7, recipe), woodIngredient(8, recipe)
            };
        }
        if (recipe.endsWith("_fence")) {
            return new CraftIngredient[] {
                woodIngredient(4, recipe), CraftIngredient.item(5, "minecraft:stick"), woodIngredient(6, recipe),
                woodIngredient(7, recipe), CraftIngredient.item(8, "minecraft:stick"), woodIngredient(9, recipe)
            };
        }
        if (recipe.endsWith("_fence_gate")) {
            return new CraftIngredient[] {
                CraftIngredient.item(4, "minecraft:stick"), woodIngredient(5, recipe), CraftIngredient.item(6, "minecraft:stick"),
                CraftIngredient.item(7, "minecraft:stick"), woodIngredient(8, recipe), CraftIngredient.item(9, "minecraft:stick")
            };
        }
        if (recipe.endsWith("_hanging_sign")) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:chain"), CraftIngredient.item(3, "minecraft:chain"),
                CraftIngredient.item(4, strippedWoodLogItem(key)), CraftIngredient.item(5, strippedWoodLogItem(key)), CraftIngredient.item(6, strippedWoodLogItem(key)),
                CraftIngredient.item(7, strippedWoodLogItem(key)), CraftIngredient.item(8, strippedWoodLogItem(key)), CraftIngredient.item(9, strippedWoodLogItem(key))
            };
        }
        if (recipe.endsWith("_sign")) {
            return new CraftIngredient[] {
                woodIngredient(1, recipe), woodIngredient(2, recipe), woodIngredient(3, recipe),
                woodIngredient(4, recipe), woodIngredient(5, recipe), woodIngredient(6, recipe),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.equals("minecraft:bamboo_chest_raft")) {
            return new CraftIngredient[] { CraftIngredient.item(1, "minecraft:bamboo_raft"), CraftIngredient.item(2, "minecraft:chest") };
        }
        if (recipe.endsWith("_chest_boat")) {
            return new CraftIngredient[] { CraftIngredient.item(1, "minecraft:" + key + "_boat"), CraftIngredient.item(2, "minecraft:chest") };
        }
        if (recipe.endsWith("_boat") || recipe.equals("minecraft:bamboo_raft")) {
            return new CraftIngredient[] {
                woodIngredient(4, recipe), woodIngredient(6, recipe),
                woodIngredient(7, recipe), woodIngredient(8, recipe), woodIngredient(9, recipe)
            };
        }
        return new CraftIngredient[0];
    }

    private String colorKey(String recipe) {
        if (!recipe.startsWith("minecraft:")) {
            return "";
        }
        String name = recipe.substring("minecraft:".length());
        for (String key : MinecraftRecipeFamilies.COLOR_KEYS) {
            if (name.startsWith(key + "_")) {
                return key;
            }
        }
        return "";
    }

    private boolean isColorCraftRecipe(String recipe) {
        String key = colorKey(recipe);
        if (key.isBlank()) return false;
        return recipe.equals("minecraft:" + key + "_bed")
            || recipe.equals("minecraft:" + key + "_carpet")
            || recipe.equals("minecraft:" + key + "_banner")
            || recipe.equals("minecraft:" + key + "_wool")
            || recipe.equals("minecraft:" + key + "_concrete_powder")
            || recipe.equals("minecraft:" + key + "_stained_glass")
            || recipe.equals("minecraft:" + key + "_stained_glass_pane")
            || recipe.equals("minecraft:" + key + "_terracotta")
            || recipe.equals("minecraft:" + key + "_candle");
    }

    private boolean colorRecipeRequiresCraftingTable(String recipe) {
        return isColorCraftRecipe(recipe)
            && !(recipe.endsWith("_carpet")
                || recipe.endsWith("_wool")
                || recipe.endsWith("_candle"));
    }

    private String colorWoolItem(String key) {
        return "minecraft:" + key + "_wool";
    }

    private String colorDyeItem(String key) {
        return "minecraft:" + key + "_dye";
    }

    private boolean hasColorRecipeMaterials(String recipe) {
        String key = colorKey(recipe);
        String wool = colorWoolItem(key);
        if (recipe.endsWith("_bed")) return countInventoryItem(wool) >= 3 && countInventoryGroup("planks") >= 3;
        if (recipe.endsWith("_carpet")) return countInventoryItem(wool) >= 2;
        if (recipe.endsWith("_banner")) return countInventoryItem(wool) >= 6 && countInventoryItem("minecraft:stick") >= 1;
        if (recipe.endsWith("_wool")) return countInventoryGroup("wool") >= 1 && countInventoryItem(colorDyeItem(key)) >= 1;
        if (recipe.endsWith("_concrete_powder")) return countInventoryItem("minecraft:sand") >= 4 && countInventoryItem("minecraft:gravel") >= 4 && countInventoryItem(colorDyeItem(key)) >= 1;
        if (recipe.endsWith("_stained_glass")) return countInventoryItem("minecraft:glass") >= 8 && countInventoryItem(colorDyeItem(key)) >= 1;
        if (recipe.endsWith("_stained_glass_pane")) return countInventoryItem("minecraft:" + key + "_stained_glass") >= 6;
        if (recipe.endsWith("_terracotta")) return countInventoryItem("minecraft:terracotta") >= 8 && countInventoryItem(colorDyeItem(key)) >= 1;
        if (recipe.endsWith("_candle")) return countInventoryItem("minecraft:candle") >= 1 && countInventoryItem(colorDyeItem(key)) >= 1;
        return false;
    }

    private CraftIngredient[] colorCraftIngredients(String recipe, boolean craftingTable) {
        String key = colorKey(recipe);
        String wool = colorWoolItem(key);
        String dye = colorDyeItem(key);
        if (recipe.endsWith("_carpet")) {
            int s1 = 1;
            int s2 = 2;
            return new CraftIngredient[] {
                CraftIngredient.item(s1, wool),
                CraftIngredient.item(s2, wool)
            };
        }
        if (recipe.endsWith("_wool")) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "wool"),
                CraftIngredient.item(2, dye)
            };
        }
        if (recipe.endsWith("_candle")) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:candle"),
                CraftIngredient.item(2, dye)
            };
        }
        if (!craftingTable) return new CraftIngredient[0];
        if (recipe.endsWith("_bed")) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, wool),
                CraftIngredient.item(2, wool),
                CraftIngredient.item(3, wool),
                CraftIngredient.group(4, "planks"),
                CraftIngredient.group(5, "planks"),
                CraftIngredient.group(6, "planks")
            };
        }
        if (recipe.endsWith("_banner")) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, wool),
                CraftIngredient.item(2, wool),
                CraftIngredient.item(3, wool),
                CraftIngredient.item(4, wool),
                CraftIngredient.item(5, wool),
                CraftIngredient.item(6, wool),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.endsWith("_concrete_powder")) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:sand"),
                CraftIngredient.item(2, "minecraft:sand"),
                CraftIngredient.item(3, "minecraft:sand"),
                CraftIngredient.item(4, "minecraft:sand"),
                CraftIngredient.item(5, "minecraft:gravel"),
                CraftIngredient.item(6, "minecraft:gravel"),
                CraftIngredient.item(7, "minecraft:gravel"),
                CraftIngredient.item(8, "minecraft:gravel"),
                CraftIngredient.item(9, dye)
            };
        }
        if (recipe.endsWith("_stained_glass")) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:glass"),
                CraftIngredient.item(2, "minecraft:glass"),
                CraftIngredient.item(3, "minecraft:glass"),
                CraftIngredient.item(4, "minecraft:glass"),
                CraftIngredient.item(5, dye),
                CraftIngredient.item(6, "minecraft:glass"),
                CraftIngredient.item(7, "minecraft:glass"),
                CraftIngredient.item(8, "minecraft:glass"),
                CraftIngredient.item(9, "minecraft:glass")
            };
        }
        if (recipe.endsWith("_stained_glass_pane")) {
            String stainedGlass = "minecraft:" + key + "_stained_glass";
            return new CraftIngredient[] {
                CraftIngredient.item(4, stainedGlass),
                CraftIngredient.item(5, stainedGlass),
                CraftIngredient.item(6, stainedGlass),
                CraftIngredient.item(7, stainedGlass),
                CraftIngredient.item(8, stainedGlass),
                CraftIngredient.item(9, stainedGlass)
            };
        }
        if (recipe.endsWith("_terracotta")) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:terracotta"),
                CraftIngredient.item(2, "minecraft:terracotta"),
                CraftIngredient.item(3, "minecraft:terracotta"),
                CraftIngredient.item(4, "minecraft:terracotta"),
                CraftIngredient.item(5, dye),
                CraftIngredient.item(6, "minecraft:terracotta"),
                CraftIngredient.item(7, "minecraft:terracotta"),
                CraftIngredient.item(8, "minecraft:terracotta"),
                CraftIngredient.item(9, "minecraft:terracotta")
            };
        }
        return new CraftIngredient[0];
    }

    private String stoneFamilyKey(String recipe) {
        if (!recipe.startsWith("minecraft:")) {
            return "";
        }
        String name = recipe.substring("minecraft:".length());
        for (String key : MinecraftRecipeFamilies.STONE_FAMILY_KEYS) {
            if (name.startsWith(key + "_")) {
                return key;
            }
        }
        return "";
    }

    private boolean isStoneFamilyCraftRecipe(String recipe) {
        String key = stoneFamilyKey(recipe);
        if (key.isBlank()) return false;
        if (key.equals("stone") && recipe.endsWith("_wall")) return false;
        return recipe.equals("minecraft:" + key + "_slab")
            || recipe.equals("minecraft:" + key + "_stairs")
            || recipe.equals("minecraft:" + key + "_wall");
    }

    private String stoneFamilyMaterialItem(String key) {
        return switch (key) {
            case "brick" -> "minecraft:bricks";
            case "stone_brick" -> "minecraft:stone_bricks";
            case "mud_brick" -> "minecraft:mud_bricks";
            case "nether_brick" -> "minecraft:nether_bricks";
            default -> "minecraft:" + key;
        };
    }

    private boolean hasStoneFamilyRecipeMaterials(String recipe) {
        String material = stoneFamilyMaterialItem(stoneFamilyKey(recipe));
        if (recipe.endsWith("_slab")) return countInventoryItem(material) >= 3;
        if (recipe.endsWith("_stairs") || recipe.endsWith("_wall")) return countInventoryItem(material) >= 6;
        return false;
    }

    private CraftIngredient stoneFamilyIngredient(int slot, String recipe) {
        return CraftIngredient.item(slot, stoneFamilyMaterialItem(stoneFamilyKey(recipe)));
    }

    private CraftIngredient[] stoneFamilyCraftIngredients(String recipe, boolean craftingTable) {
        if (!craftingTable) return new CraftIngredient[0];
        if (recipe.endsWith("_slab")) {
            return new CraftIngredient[] {
                stoneFamilyIngredient(7, recipe),
                stoneFamilyIngredient(8, recipe),
                stoneFamilyIngredient(9, recipe)
            };
        }
        if (recipe.endsWith("_stairs")) {
            return new CraftIngredient[] {
                stoneFamilyIngredient(1, recipe),
                stoneFamilyIngredient(4, recipe),
                stoneFamilyIngredient(5, recipe),
                stoneFamilyIngredient(7, recipe),
                stoneFamilyIngredient(8, recipe),
                stoneFamilyIngredient(9, recipe)
            };
        }
        if (recipe.endsWith("_wall")) {
            return new CraftIngredient[] {
                stoneFamilyIngredient(4, recipe),
                stoneFamilyIngredient(5, recipe),
                stoneFamilyIngredient(6, recipe),
                stoneFamilyIngredient(7, recipe),
                stoneFamilyIngredient(8, recipe),
                stoneFamilyIngredient(9, recipe)
            };
        }
        return new CraftIngredient[0];
    }

    private CraftIngredient[] craftIngredients(String recipe, boolean craftingTable) {
        int s1 = 1;
        int s2 = 2;
        int s3 = craftingTable ? 4 : 3;
        int s4 = craftingTable ? 5 : 4;
        if (recipe.equals("minecraft:oak_planks")) {
            return new CraftIngredient[] { CraftIngredient.item(s1, "minecraft:oak_log") };
        }
        if (recipe.equals("minecraft:stick")) {
            return new CraftIngredient[] { CraftIngredient.group(s1, "planks"), CraftIngredient.group(s3, "planks") };
        }
        if (recipe.equals("minecraft:crafting_table")) {
            return new CraftIngredient[] {
                CraftIngredient.group(s1, "planks"),
                CraftIngredient.group(s2, "planks"),
                CraftIngredient.group(s3, "planks"),
                CraftIngredient.group(s4, "planks")
            };
        }
        if (isWoodCraftRecipe(recipe)) {
            return woodCraftIngredients(recipe, craftingTable);
        }
        if (isColorCraftRecipe(recipe)) {
            return colorCraftIngredients(recipe, craftingTable);
        }
        if (isStoneFamilyCraftRecipe(recipe)) {
            return stoneFamilyCraftIngredients(recipe, craftingTable);
        }
        if (recipe.equals("minecraft:torch")) {
            return new CraftIngredient[] { CraftIngredient.group(s1, "coal_or_charcoal"), CraftIngredient.item(s3, "minecraft:stick") };
        }
        if (recipe.equals("minecraft:bread") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:wheat"),
                CraftIngredient.item(2, "minecraft:wheat"),
                CraftIngredient.item(3, "minecraft:wheat")
            };
        }
        if (isToolCraftRecipe(recipe) && craftingTable) {
            return toolCraftIngredients(recipe);
        }
        if (isArmorCraftRecipe(recipe) && craftingTable) {
            return armorCraftIngredients(recipe);
        }
        if (recipe.equals("minecraft:shield") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "planks"),
                CraftIngredient.item(2, "minecraft:iron_ingot"),
                CraftIngredient.group(3, "planks"),
                CraftIngredient.group(4, "planks"),
                CraftIngredient.group(5, "planks"),
                CraftIngredient.group(6, "planks"),
                CraftIngredient.group(8, "planks")
            };
        }
        if (recipe.equals("minecraft:bow") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(2, "minecraft:stick"),
                CraftIngredient.item(4, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick"),
                CraftIngredient.item(3, "minecraft:string"),
                CraftIngredient.item(6, "minecraft:string"),
                CraftIngredient.item(9, "minecraft:string")
            };
        }
        if (recipe.equals("minecraft:arrow") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(2, "minecraft:flint"),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:feather")
            };
        }
        if (recipe.equals("minecraft:fishing_rod") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(3, "minecraft:stick"),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(7, "minecraft:stick"),
                CraftIngredient.item(6, "minecraft:string"),
                CraftIngredient.item(9, "minecraft:string")
            };
        }
        if (recipe.equals("minecraft:bucket") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(4, "minecraft:iron_ingot"),
                CraftIngredient.item(6, "minecraft:iron_ingot"),
                CraftIngredient.item(8, "minecraft:iron_ingot")
            };
        }
        if (recipe.equals("minecraft:shears")) {
            return new CraftIngredient[] {
                CraftIngredient.item(s2, "minecraft:iron_ingot"),
                CraftIngredient.item(s3, "minecraft:iron_ingot")
            };
        }
        if (recipe.equals("minecraft:flint_and_steel")) {
            return new CraftIngredient[] {
                CraftIngredient.item(s1, "minecraft:iron_ingot"),
                CraftIngredient.item(s4, "minecraft:flint")
            };
        }
        if (recipe.equals("minecraft:book")) {
            return new CraftIngredient[] {
                CraftIngredient.item(s1, "minecraft:paper"),
                CraftIngredient.item(s2, "minecraft:paper"),
                CraftIngredient.item(s3, "minecraft:paper"),
                CraftIngredient.item(s4, "minecraft:leather")
            };
        }
        if (recipe.equals("minecraft:paper") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(4, "minecraft:sugar_cane"),
                CraftIngredient.item(5, "minecraft:sugar_cane"),
                CraftIngredient.item(6, "minecraft:sugar_cane")
            };
        }
        if (recipe.equals("minecraft:bookshelf") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "planks"),
                CraftIngredient.group(2, "planks"),
                CraftIngredient.group(3, "planks"),
                CraftIngredient.item(4, "minecraft:book"),
                CraftIngredient.item(5, "minecraft:book"),
                CraftIngredient.item(6, "minecraft:book"),
                CraftIngredient.group(7, "planks"),
                CraftIngredient.group(8, "planks"),
                CraftIngredient.group(9, "planks")
            };
        }
        if (recipe.equals("minecraft:compass") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(2, "minecraft:iron_ingot"),
                CraftIngredient.item(4, "minecraft:iron_ingot"),
                CraftIngredient.item(5, "minecraft:redstone"),
                CraftIngredient.item(6, "minecraft:iron_ingot"),
                CraftIngredient.item(8, "minecraft:iron_ingot")
            };
        }
        if (recipe.equals("minecraft:clock") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(2, "minecraft:gold_ingot"),
                CraftIngredient.item(4, "minecraft:gold_ingot"),
                CraftIngredient.item(5, "minecraft:redstone"),
                CraftIngredient.item(6, "minecraft:gold_ingot"),
                CraftIngredient.item(8, "minecraft:gold_ingot")
            };
        }
        if (recipe.equals("minecraft:map") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:paper"),
                CraftIngredient.item(2, "minecraft:paper"),
                CraftIngredient.item(3, "minecraft:paper"),
                CraftIngredient.item(4, "minecraft:paper"),
                CraftIngredient.item(5, "minecraft:compass"),
                CraftIngredient.item(6, "minecraft:paper"),
                CraftIngredient.item(7, "minecraft:paper"),
                CraftIngredient.item(8, "minecraft:paper"),
                CraftIngredient.item(9, "minecraft:paper")
            };
        }
        if (recipe.equals("minecraft:item_frame") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:stick"),
                CraftIngredient.item(2, "minecraft:stick"),
                CraftIngredient.item(3, "minecraft:stick"),
                CraftIngredient.item(4, "minecraft:stick"),
                CraftIngredient.item(5, "minecraft:leather"),
                CraftIngredient.item(6, "minecraft:stick"),
                CraftIngredient.item(7, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick"),
                CraftIngredient.item(9, "minecraft:stick")
            };
        }
        if (recipe.equals("minecraft:painting") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:stick"),
                CraftIngredient.item(2, "minecraft:stick"),
                CraftIngredient.item(3, "minecraft:stick"),
                CraftIngredient.item(4, "minecraft:stick"),
                CraftIngredient.group(5, "wool"),
                CraftIngredient.item(6, "minecraft:stick"),
                CraftIngredient.item(7, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick"),
                CraftIngredient.item(9, "minecraft:stick")
            };
        }
        if (recipe.equals("minecraft:white_bed") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "wool"),
                CraftIngredient.group(2, "wool"),
                CraftIngredient.group(3, "wool"),
                CraftIngredient.group(4, "planks"),
                CraftIngredient.group(5, "planks"),
                CraftIngredient.group(6, "planks")
            };
        }
        if (recipe.equals("minecraft:chest") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "planks"),
                CraftIngredient.group(2, "planks"),
                CraftIngredient.group(3, "planks"),
                CraftIngredient.group(4, "planks"),
                CraftIngredient.group(6, "planks"),
                CraftIngredient.group(7, "planks"),
                CraftIngredient.group(8, "planks"),
                CraftIngredient.group(9, "planks")
            };
        }
        if (recipe.equals("minecraft:ladder") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.item(1, "minecraft:stick"),
                CraftIngredient.item(3, "minecraft:stick"),
                CraftIngredient.item(4, "minecraft:stick"),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(6, "minecraft:stick"),
                CraftIngredient.item(7, "minecraft:stick"),
                CraftIngredient.item(9, "minecraft:stick")
            };
        }
        if (recipe.equals("minecraft:bowl") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(4, "planks"),
                CraftIngredient.group(6, "planks"),
                CraftIngredient.group(8, "planks")
            };
        }
        if (recipe.equals("minecraft:oak_boat") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(4, "planks"),
                CraftIngredient.group(6, "planks"),
                CraftIngredient.group(7, "planks"),
                CraftIngredient.group(8, "planks"),
                CraftIngredient.group(9, "planks")
            };
        }
        if (recipe.equals("minecraft:wooden_pickaxe") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "planks"),
                CraftIngredient.group(2, "planks"),
                CraftIngredient.group(3, "planks"),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.equals("minecraft:stone_pickaxe") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "cobblestone"),
                CraftIngredient.group(2, "cobblestone"),
                CraftIngredient.group(3, "cobblestone"),
                CraftIngredient.item(5, "minecraft:stick"),
                CraftIngredient.item(8, "minecraft:stick")
            };
        }
        if (recipe.equals("minecraft:furnace") && craftingTable) {
            return new CraftIngredient[] {
                CraftIngredient.group(1, "cobblestone"),
                CraftIngredient.group(2, "cobblestone"),
                CraftIngredient.group(3, "cobblestone"),
                CraftIngredient.group(4, "cobblestone"),
                CraftIngredient.group(6, "cobblestone"),
                CraftIngredient.group(7, "cobblestone"),
                CraftIngredient.group(8, "cobblestone"),
                CraftIngredient.group(9, "cobblestone")
            };
        }
        return new CraftIngredient[0];
    }

    private int recipeOutputCount(String recipe) {
        int bundledCount = bundledRecipeOutputCount(recipe);
        if (bundledCount > 0) {
            return bundledCount;
        }
        if (isWoodCraftRecipe(recipe)) {
            if (recipe.equals("minecraft:bamboo_planks")) return 2;
            if (recipe.endsWith("_planks")) return 4;
            if (recipe.endsWith("_slab") || recipe.endsWith("_hanging_sign")) return 6;
            if (recipe.endsWith("_stairs")) return 4;
            if (recipe.endsWith("_door") || recipe.endsWith("_fence") || recipe.endsWith("_sign")) return 3;
            if (recipe.endsWith("_trapdoor")) return 2;
            return 1;
        }
        if (isColorCraftRecipe(recipe)) {
            if (recipe.endsWith("_carpet")) return 3;
            if (recipe.endsWith("_concrete_powder") || recipe.endsWith("_stained_glass") || recipe.endsWith("_terracotta")) return 8;
            if (recipe.endsWith("_stained_glass_pane")) return 16;
            return 1;
        }
        if (isStoneFamilyCraftRecipe(recipe)) {
            if (recipe.endsWith("_slab") || recipe.endsWith("_wall")) return 6;
            if (recipe.endsWith("_stairs")) return 4;
            return 1;
        }
        return switch (recipe) {
            case "minecraft:oak_planks", "minecraft:stick", "minecraft:torch", "minecraft:arrow" -> 4;
            case "minecraft:paper" -> 3;
            case "minecraft:ladder" -> 3;
            case "minecraft:bowl" -> 4;
            default -> 1;
        };
    }

    private int bundledRecipeOutputCount(String outputItem) {
        for (VanillaCraftRecipe recipe : vanillaCraftRecipesForOutput(outputItem)) {
            if (recipe.recipeId().equals(outputItem)) {
                return recipe.count();
            }
        }
        List<VanillaCraftRecipe> recipes = vanillaCraftRecipesForOutput(outputItem);
        return recipes.isEmpty() ? 0 : recipes.get(0).count();
    }

    private String findAvailableFoodItem() {
        for (String food : foodPriority()) {
            if (countInventoryItem(food) > 0) {
                return food;
            }
        }
        return "";
    }

    private boolean isKnownFood(String itemId) {
        for (String food : foodPriority()) {
            if (food.equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private String findAvailableRawFoodItem() {
        for (String food : rawFoodPriority()) {
            if (countInventoryItem(food) > 0) {
                return food;
            }
        }
        return "";
    }

    private String cookedFoodOutput(String input) {
        return switch (input) {
            case "minecraft:beef" -> "minecraft:cooked_beef";
            case "minecraft:porkchop" -> "minecraft:cooked_porkchop";
            case "minecraft:chicken" -> "minecraft:cooked_chicken";
            case "minecraft:mutton" -> "minecraft:cooked_mutton";
            case "minecraft:rabbit" -> "minecraft:cooked_rabbit";
            case "minecraft:cod" -> "minecraft:cooked_cod";
            case "minecraft:salmon" -> "minecraft:cooked_salmon";
            default -> "";
        };
    }

    private boolean isKnownSmeltOutput(String input, String output) {
        if (output.equals("minecraft:charcoal") && input.endsWith("_log")) {
            return true;
        }
        if (input.equals("minecraft:raw_iron") && output.equals("minecraft:iron_ingot")) {
            return true;
        }
        if (input.equals("minecraft:raw_copper") && output.equals("minecraft:copper_ingot")) {
            return true;
        }
        if (input.equals("minecraft:raw_gold") && output.equals("minecraft:gold_ingot")) {
            return true;
        }
        if (input.equals("minecraft:cobblestone") && output.equals("minecraft:stone")) {
            return true;
        }
        if (input.equals("minecraft:stone") && output.equals("minecraft:smooth_stone")) {
            return true;
        }
        if (input.equals("minecraft:clay_ball") && output.equals("minecraft:brick")) {
            return true;
        }
        if (input.equals("minecraft:netherrack") && output.equals("minecraft:nether_brick")) {
            return true;
        }
        if (input.equals("minecraft:ancient_debris") && output.equals("minecraft:netherite_scrap")) {
            return true;
        }
        if (input.equals("minecraft:potato") && output.equals("minecraft:baked_potato")) {
            return true;
        }
        if (input.equals("minecraft:quartz_block") && output.equals("minecraft:smooth_quartz")) {
            return true;
        }
        if (input.equals("minecraft:sandstone") && output.equals("minecraft:smooth_sandstone")) {
            return true;
        }
        if (input.equals("minecraft:red_sandstone") && output.equals("minecraft:smooth_red_sandstone")) {
            return true;
        }
        if (input.equals("minecraft:chorus_fruit") && output.equals("minecraft:popped_chorus_fruit")) {
            return true;
        }
        if (input.equals("minecraft:resin_clump") && output.equals("minecraft:resin_brick")) {
            return true;
        }
        if (input.equals("minecraft:kelp") && output.equals("minecraft:dried_kelp")) {
            return true;
        }
        if (input.equals("minecraft:sand") && output.equals("minecraft:glass")) {
            return true;
        }
        if (input.equals("minecraft:cactus") && output.equals("minecraft:green_dye")) {
            return true;
        }
        return cookedFoodOutput(input).equals(output);
    }

    private boolean isFoodDrop(String itemId) {
        for (String food : rawFoodPriority()) {
            if (food.equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private String[] rawFoodPriority() {
        return new String[] {
            "minecraft:beef",
            "minecraft:porkchop",
            "minecraft:chicken",
            "minecraft:mutton",
            "minecraft:rabbit",
            "minecraft:cod",
            "minecraft:salmon"
        };
    }

    private String findAvailableSeedItem() {
        for (String seed : seedPriority()) {
            if (countInventoryItem(seed) > 0) {
                return seed;
            }
        }
        return "";
    }

    private boolean isKnownSeedItem(String itemId) {
        for (String seed : seedPriority()) {
            if (seed.equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private String[] seedPriority() {
        return new String[] {
            "minecraft:wheat_seeds",
            "minecraft:carrot",
            "minecraft:potato",
            "minecraft:beetroot_seeds",
            "minecraft:pumpkin_seeds",
            "minecraft:melon_seeds"
        };
    }

    private String[] foodPriority() {
        return new String[] {
            "minecraft:bread",
            "minecraft:cooked_beef",
            "minecraft:cooked_porkchop",
            "minecraft:cooked_chicken",
            "minecraft:cooked_mutton",
            "minecraft:cooked_rabbit",
            "minecraft:baked_potato",
            "minecraft:apple",
            "minecraft:carrot",
            "minecraft:sweet_berries",
            "minecraft:glow_berries",
            "minecraft:cooked_cod",
            "minecraft:cooked_salmon",
            "minecraft:beetroot",
            "minecraft:potato",
            "minecraft:beef",
            "minecraft:porkchop",
            "minecraft:chicken",
            "minecraft:mutton",
            "minecraft:rabbit",
            "minecraft:cod",
            "minecraft:salmon"
        };
    }

    private ExecutorProtocol.StepResult prepareFurnaceOnce(String input, String fuel, String output) {
        if (client.player == null || client.interactionManager == null) {
            return new ExecutorProtocol.StepResult("smelt", "blocked", "Player or interaction manager is not ready.");
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (!(handler instanceof AbstractFurnaceScreenHandler)) {
            int screenWaitTicks = waitForExpectedScreenOpen("minecraft:furnace", 12);
            handler = client.player.currentScreenHandler;
            if (!(handler instanceof AbstractFurnaceScreenHandler)) {
                ExecutorProtocol.StepResult blocked = furnaceBlocked(input, fuel, output, "Open a furnace before smelting.");
                if (blocked.data() != null) {
                    blocked.data().addProperty("screen_wait_ticks", screenWaitTicks);
                    blocked.data().addProperty("current_screen", client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName());
                    blocked.data().addProperty("current_handler", handler == null ? "none" : handler.getClass().getSimpleName());
                }
                return blocked;
            }
        }
        if (!handler.getCursorStack().isEmpty()) {
            return furnaceBlocked(input, fuel, output, "Cursor stack is not empty; refusing to smelt to avoid item loss.");
        }
        if (!handler.getSlot(0).getStack().isEmpty() || !handler.getSlot(1).getStack().isEmpty()) {
            return furnaceBlocked(input, fuel, output, "Furnace input or fuel slot is not empty.");
        }
        if (!placeOneIngredient(handler, CraftIngredient.item(0, input))) {
            return furnaceBlocked(input, fuel, output, "Smelting input is not available in inventory.");
        }
        if (!placeOneIngredient(handler, CraftIngredient.itemOrGroup(1, fuel, "fuel"))) {
            return furnaceBlocked(input, fuel, output, "Fuel is not available in inventory.");
        }
        JsonObject data = new JsonObject();
        data.addProperty("input", input);
        data.addProperty("fuel", fuel);
        data.addProperty("output", output);
        return new ExecutorProtocol.StepResult("smelt", "accepted", "Inserted one smelting input and fuel item.", data);
    }

    private ExecutorProtocol.StepResult waitForFurnaceOutput(String output, int timeoutTicks) {
        int ticks = 0;
        while (ticks < timeoutTicks && !aborted) {
            if (ticks % 10 == 0 && callOnClientThread(this::hasImmediateAbortDanger)) {
                JsonObject data = new JsonObject();
                data.addProperty("output", output);
                data.addProperty("ticks", ticks);
                data.addProperty("danger_stop", true);
                return new ExecutorProtocol.StepResult("smelt", "blocked", "Stopped waiting for furnace output because an immediate survival danger appeared.", data);
            }
            boolean taken = callOnClientThread(() -> {
                if (client.player == null || client.interactionManager == null) {
                    return false;
                }
                ScreenHandler handler = client.player.currentScreenHandler;
                if (!(handler instanceof AbstractFurnaceScreenHandler)) {
                    return false;
                }
                ItemStack stack = handler.getSlot(2).getStack();
                if (stack.isEmpty()) {
                    return false;
                }
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                if (!itemId.equals(output)) {
                    return false;
                }
                int before = countInventoryItem(output);
                client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player);
                int after = waitForInventoryItemCount(output, before, 8);
                return after > before;
            });
            if (taken) {
                JsonObject data = new JsonObject();
                data.addProperty("output", output);
                data.addProperty("ticks", ticks);
                return new ExecutorProtocol.StepResult("smelt", "accepted", "Collected furnace output.", data);
            }
            sleepTicks(1);
            ticks++;
        }
        JsonObject data = new JsonObject();
        data.addProperty("output", output);
        data.addProperty("ticks", ticks);
        return new ExecutorProtocol.StepResult("smelt", aborted ? "aborted" : "blocked", aborted ? "Smelting was interrupted." : "Timed out waiting for furnace output.", data);
    }

    private ExecutorProtocol.StepResult furnaceBlocked(String input, String fuel, String output, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("input", input);
        data.addProperty("fuel", fuel);
        data.addProperty("output", output);
        data.addProperty("has_input", countInventoryItem(input) > 0);
        data.addProperty("has_fuel", isFuelAvailable(fuel));
        return new ExecutorProtocol.StepResult("smelt", "blocked", message, data);
    }

    private record RecipeBookMatch(NetworkRecipeId networkRecipeId, boolean craftable) {
    }

    private int countInventoryGroup(String group) {
        if (client.player == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            if (MinecraftItemGroups.matches(group, itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countInventoryItem(String itemId) {
        if (client.player == null || itemId.isBlank()) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (Registries.ITEM.getId(item).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int totalInventoryItems() {
        if (client.player == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isFuelAvailable(String fuel) {
        return countInventoryItem(fuel) > 0 || countInventoryGroup("fuel") > 0;
    }

    private enum DropMoveStatus {
        MOVING,
        COLLECTED,
        BLOCKED
    }

    private enum BlockMoveStatus {
        MOVING,
        REACHED,
        BLOCKED
    }

    private record ExploreDirection(String name, Vec3d vector, String currentSector, String targetSector) {}

    private record StepSafety(boolean safe, double score) {}

    private record SteeringDecision(Vec3d direction, boolean jump, double score) {
        boolean safe() {
            return score < Double.MAX_VALUE;
        }
    }

    private record MoveExecutionPlan(
        String keyDirection,
        Vec3d movement,
        Vec3d targetPos,
        int effectiveDistanceBlocks,
        int laneSafeSteps,
        double laneScore,
        boolean laneAdjusted,
        boolean facedMoveDirection,
        boolean laneAvailable
    ) {}

    private record MoveLane(Vec3d direction, int safeSteps, double score, boolean adjusted) {}

    private record LocalNavigationPlan(
        BlockPos target,
        List<BlockPos> path,
        double cost,
        boolean reached,
        String blockedReason,
        int visitedNodes,
        int digSteps,
        int placeSteps,
        int openSteps,
        KoeCraftLocalGoalPathfinder.AssistStep firstAssistStep
    ) {
        static LocalNavigationPlan blocked(BlockPos target, String reason) {
            return new LocalNavigationPlan(target, List.of(), 0.0D, false, reason, 0, 0, 0, 0, KoeCraftLocalGoalPathfinder.AssistStep.none());
        }

        Vec3d waypoint() {
            if (path.isEmpty()) {
                return target == null ? Vec3d.ZERO : Vec3d.ofBottomCenter(target);
            }
            return Vec3d.ofBottomCenter(path.get(Math.min(2, path.size() - 1)));
        }
    }

    private record BlockApproachPlan(BlockPos standPos, LocalNavigationPlan navigation, double score) {
        Vec3d waypoint() {
            return navigation.waypoint();
        }
    }

    private record DownwardApproachPlan(Direction direction, BlockPos landing, List<BlockPos> digTargets) {}

    private record DigStepPlan(String strategy, String direction, BlockPos landing, List<BlockPos> digTargets) {}

    private record EscapeNode(BlockPos pos, double cost) {}

    private record EscapePlan(BlockPos target, List<BlockPos> path, double score) {}

    private record ItemMagnetTarget(UUID uuid) {}

    private record VoiceAssistGrantResult(int insertedCount, String mode, String reason) {}

    private ExecutorProtocol.StepResult runOnClientThread(Supplier<ExecutorProtocol.StepResult> action) {
        return callOnClientThread(action);
    }

    private <T> T callOnClientThread(Supplier<T> action) {
        if (client.isOnThread()) {
            return action.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.join();
    }

    private void sleepTicks(int ticks) {
        try {
            Thread.sleep(Math.max(1, ticks) * 50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            aborted = true;
        }
    }

    private void releaseMovementKeys() {
        if (client.options == null) {
            return;
        }
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    private void releaseKeys() {
        if (client.options == null) {
            return;
        }
        releaseMovementKeys();
        client.options.sneakKey.setPressed(false);
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);
    }
}
