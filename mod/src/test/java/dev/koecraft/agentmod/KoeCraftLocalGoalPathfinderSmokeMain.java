package dev.koecraft.agentmod;

import java.util.Set;
import net.minecraft.util.math.BlockPos;

public final class KoeCraftLocalGoalPathfinderSmokeMain {
    private KoeCraftLocalGoalPathfinderSmokeMain() {
    }

    public static void main(String[] args) {
        checkDetoursAroundBlockedLane();
        checkAvoidsHazardWhenAlternativeExists();
        checkChoosesOpenPassagePrimitive();
        checkChoosesPlaceSupportPrimitive();
        checkChoosesDigPrimitive();
        checkReportsUnreachableGoal();
        System.out.println("[local-goal-pathfinder-smoke] passed");
    }

    private static void checkDetoursAroundBlockedLane() {
        Set<BlockPos> blocked = Set.of(
            new BlockPos(1, 0, 0),
            new BlockPos(2, 0, 0),
            new BlockPos(3, 0, 0)
        );
        KoeCraftLocalGoalPathfinder.Plan plan = plan(
            new BlockPos(0, 0, 0),
            new BlockPos(4, 0, 0),
            blocked,
            Set.of()
        );
        require(plan.reached(), "detour plan should reach the goal");
        require(plan.path().size() >= 5, "detour path should contain multiple waypoints");
        for (BlockPos pos : plan.path()) {
            require(!blocked.contains(pos), "detour path should avoid blocked pos " + pos);
        }
    }

    private static void checkAvoidsHazardWhenAlternativeExists() {
        BlockPos hazard = new BlockPos(1, 0, 0);
        KoeCraftLocalGoalPathfinder.Plan plan = plan(
            new BlockPos(0, 0, 0),
            new BlockPos(3, 0, 0),
            Set.of(),
            Set.of(hazard)
        );
        require(plan.reached(), "hazard-aware plan should reach the goal");
        require(!plan.path().contains(hazard), "hazard-aware plan should prefer a non-hazard waypoint");
    }

    private static void checkReportsUnreachableGoal() {
        BlockPos start = new BlockPos(0, 0, 0);
        KoeCraftLocalGoalPathfinder.Plan plan = KoeCraftLocalGoalPathfinder.plan(
            start,
            KoeCraftLocalGoalPathfinder.Goal.near(new BlockPos(4, 0, 0), 0),
            new KoeCraftLocalGoalPathfinder.Terrain() {
                @Override
                public boolean isStandable(BlockPos pos) {
                    return pos.equals(start);
                }
            },
            KoeCraftLocalGoalPathfinder.Options.localWalk(8, 2, 256)
        );
        require(!plan.reached(), "sealed plan should be unreachable");
        require("local_goal_unreachable".equals(plan.blockedReason()), "sealed plan should expose blocked reason");
    }

    private static void checkChoosesOpenPassagePrimitive() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos passage = new BlockPos(1, 0, 0);
        BlockPos goal = new BlockPos(2, 0, 0);
        KoeCraftLocalGoalPathfinder.Plan plan = KoeCraftLocalGoalPathfinder.plan(
            start,
            KoeCraftLocalGoalPathfinder.Goal.near(goal, 0),
            new KoeCraftLocalGoalPathfinder.Terrain() {
                @Override
                public boolean isStandable(BlockPos pos) {
                    return pos.equals(start) || pos.equals(goal);
                }

                @Override
                public boolean canOpenPassage(BlockPos pos) {
                    return pos.equals(passage);
                }
            },
            KoeCraftLocalGoalPathfinder.Options.localAssist(4, 1, 64, true, true, true)
        );
        require(plan.reached(), "open passage plan should reach goal");
        require(plan.openSteps() == 1, "open passage plan should count one open step");
        require(plan.firstAssistStep().primitive() == KoeCraftLocalGoalPathfinder.MovementPrimitive.OPEN_PASSAGE, "open passage should be first assist");
    }

    private static void checkChoosesPlaceSupportPrimitive() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos gap = new BlockPos(1, 0, 0);
        BlockPos goal = new BlockPos(2, 0, 0);
        KoeCraftLocalGoalPathfinder.Plan plan = KoeCraftLocalGoalPathfinder.plan(
            start,
            KoeCraftLocalGoalPathfinder.Goal.near(goal, 0),
            new KoeCraftLocalGoalPathfinder.Terrain() {
                @Override
                public boolean isStandable(BlockPos pos) {
                    return pos.equals(start) || pos.equals(goal);
                }

                @Override
                public boolean canPlaceSupport(BlockPos pos) {
                    return pos.equals(gap);
                }
            },
            KoeCraftLocalGoalPathfinder.Options.localAssist(4, 1, 64, true, true, true)
        );
        require(plan.reached(), "place support plan should reach goal");
        require(plan.placeSteps() == 1, "place support plan should count one place step");
        require(plan.firstAssistStep().primitive() == KoeCraftLocalGoalPathfinder.MovementPrimitive.PLACE_SUPPORT, "place support should be first assist");
    }

    private static void checkChoosesDigPrimitive() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos dirt = new BlockPos(1, 0, 0);
        BlockPos goal = new BlockPos(2, 0, 0);
        KoeCraftLocalGoalPathfinder.Plan plan = KoeCraftLocalGoalPathfinder.plan(
            start,
            KoeCraftLocalGoalPathfinder.Goal.near(goal, 0),
            new KoeCraftLocalGoalPathfinder.Terrain() {
                @Override
                public boolean isStandable(BlockPos pos) {
                    return pos.equals(start) || pos.equals(goal);
                }

                @Override
                public boolean canDigToStandable(BlockPos pos) {
                    return pos.equals(dirt);
                }
            },
            KoeCraftLocalGoalPathfinder.Options.localAssist(4, 1, 64, true, true, true)
        );
        require(plan.reached(), "dig plan should reach goal");
        require(plan.digSteps() == 1, "dig plan should count one dig step");
        require(plan.firstAssistStep().primitive() == KoeCraftLocalGoalPathfinder.MovementPrimitive.DIG, "dig should be first assist");
    }

    private static KoeCraftLocalGoalPathfinder.Plan plan(BlockPos start, BlockPos goal, Set<BlockPos> blocked, Set<BlockPos> hazards) {
        return KoeCraftLocalGoalPathfinder.plan(
            start,
            KoeCraftLocalGoalPathfinder.Goal.near(goal, 0),
            new KoeCraftLocalGoalPathfinder.Terrain() {
                @Override
                public boolean isStandable(BlockPos pos) {
                    return Math.abs(pos.getY()) <= 1 && !blocked.contains(pos);
                }

                @Override
                public boolean hasNearbyHazard(BlockPos pos) {
                    return hazards.contains(pos);
                }
            },
            KoeCraftLocalGoalPathfinder.Options.localWalk(8, 2, 256)
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
