package dev.koecraft.agentmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.util.math.BlockPos;

final class KoeCraftLocalGoalPathfinder {
    private KoeCraftLocalGoalPathfinder() {
    }

    static Plan plan(BlockPos start, Goal goal, Terrain terrain, Options options) {
        if (start == null || goal == null || terrain == null || options == null) {
            return Plan.blocked("invalid_local_goal_request");
        }
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::estimatedTotalCost));
        Map<BlockPos, Double> bestCost = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, StepMeta> stepMeta = new HashMap<>();
        SearchNode startNode = new SearchNode(start, 0.0D, goal.heuristic(start), 0, 0, 0);
        open.add(startNode);
        bestCost.put(start, 0.0D);

        BlockPos best = start;
        double bestGoalScore = goal.progressScore(start);
        int visited = 0;
        int bestDigSteps = 0;
        int bestPlaceSteps = 0;
        int bestOpenSteps = 0;

        while (!open.isEmpty() && visited < options.maxNodes()) {
            SearchNode current = open.poll();
            Double knownCost = bestCost.get(current.pos());
            if (knownCost == null || current.costFromStart() > knownCost + 0.0001D) {
                continue;
            }
            visited++;
            double goalScore = goal.progressScore(current.pos());
            if (goalScore < bestGoalScore) {
                bestGoalScore = goalScore;
                best = current.pos();
                bestDigSteps = current.digSteps();
                bestPlaceSteps = current.placeSteps();
                bestOpenSteps = current.openSteps();
            }
            if (goal.reached(current.pos())) {
                List<BlockPos> path = reconstructPath(start, current.pos(), cameFrom);
                return new Plan(path, current.costFromStart(), true, "", visited, current.digSteps(), current.placeSteps(), current.openSteps(), firstAssistStep(path, stepMeta));
            }

            for (StepCandidate candidate : neighbors(current.pos(), start, terrain, options, goal)) {
                double newCost = current.costFromStart() + candidate.cost();
                Double previous = bestCost.get(candidate.pos());
                if (previous != null && previous <= newCost) {
                    continue;
                }
                bestCost.put(candidate.pos(), newCost);
                cameFrom.put(candidate.pos(), current.pos());
                stepMeta.put(candidate.pos(), new StepMeta(candidate.primitive(), candidate.digSteps(), candidate.placeSteps(), candidate.openSteps()));
                int digSteps = current.digSteps() + candidate.digSteps();
                int placeSteps = current.placeSteps() + candidate.placeSteps();
                int openSteps = current.openSteps() + candidate.openSteps();
                open.add(new SearchNode(candidate.pos(), newCost, newCost + goal.heuristic(candidate.pos()), digSteps, placeSteps, openSteps));
            }
        }

        List<BlockPos> partial = reconstructPath(start, best, cameFrom);
        StepMeta meta = stepMeta.get(best);
        if (meta != null) {
            bestDigSteps = Math.max(bestDigSteps, meta.digSteps());
            bestPlaceSteps = Math.max(bestPlaceSteps, meta.placeSteps());
            bestOpenSteps = Math.max(bestOpenSteps, meta.openSteps());
        }
        return new Plan(partial, bestCost.getOrDefault(best, 0.0D), false, "local_goal_unreachable", visited, bestDigSteps, bestPlaceSteps, bestOpenSteps, firstAssistStep(partial, stepMeta));
    }

    private static List<StepCandidate> neighbors(BlockPos current, BlockPos start, Terrain terrain, Options options, Goal goal) {
        ArrayList<StepCandidate> candidates = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy : new int[] {0, 1, -1}) {
                    BlockPos next = current.add(dx, dy, dz);
                    if (Math.abs(next.getX() - start.getX()) > options.horizontalLimit()
                        || Math.abs(next.getZ() - start.getZ()) > options.horizontalLimit()
                        || Math.abs(next.getY() - start.getY()) > options.verticalLimit()) {
                        continue;
                    }
                    boolean diagonal = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                    if (diagonal && (!isTraversable(current.add(dx, dy, 0), terrain, options).traversable()
                        || !isTraversable(current.add(0, dy, dz), terrain, options).traversable())) {
                        continue;
                    }
                    Traversal traversal = isTraversable(next, terrain, options);
                    if (!traversal.traversable()) {
                        continue;
                    }
                    double base = diagonal ? 1.414D : 1.0D;
                    double vertical = dy > 0 ? options.jumpCost() : dy < 0 ? options.stepDownCost() : 0.0D;
                    double assist = traversal.digSteps() * options.digCost()
                        + traversal.placeSteps() * options.placeCost()
                        + traversal.openSteps() * options.openPassageCost();
                    double hazard = terrain.hasNearbyHazard(next) ? options.hazardPenalty() : 0.0D;
                    double away = goal.heuristic(next) > goal.heuristic(current) + 0.01D ? options.awayFromGoalPenalty() : 0.0D;
                    candidates.add(new StepCandidate(next, base + vertical + assist + hazard + away, traversal.primitive(), traversal.digSteps(), traversal.placeSteps(), traversal.openSteps()));
                }
            }
        }
        return candidates;
    }

    private static Traversal isTraversable(BlockPos pos, Terrain terrain, Options options) {
        if (terrain.isStandable(pos)) {
            return new Traversal(true, MovementPrimitive.WALK, 0, 0, 0);
        }
        if (options.allowOpenPassage() && terrain.canOpenPassage(pos)) {
            return new Traversal(true, MovementPrimitive.OPEN_PASSAGE, 0, 0, 1);
        }
        if (options.allowDig() && terrain.canDigToStandable(pos)) {
            return new Traversal(true, MovementPrimitive.DIG, 1, 0, 0);
        }
        if (options.allowPlace() && terrain.canPlaceSupport(pos)) {
            return new Traversal(true, MovementPrimitive.PLACE_SUPPORT, 0, 1, 0);
        }
        return new Traversal(false, MovementPrimitive.WALK, 0, 0, 0);
    }

    private static AssistStep firstAssistStep(List<BlockPos> path, Map<BlockPos, StepMeta> stepMeta) {
        for (int i = 1; i < path.size(); i++) {
            StepMeta meta = stepMeta.get(path.get(i));
            if (meta != null && meta.primitive() != MovementPrimitive.WALK) {
                return new AssistStep(meta.primitive(), path.get(i));
            }
        }
        return AssistStep.none();
    }

    private static List<BlockPos> reconstructPath(BlockPos start, BlockPos end, Map<BlockPos, BlockPos> cameFrom) {
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

    interface Terrain {
        boolean isStandable(BlockPos pos);

        default boolean canDigToStandable(BlockPos pos) {
            return false;
        }

        default boolean canPlaceSupport(BlockPos pos) {
            return false;
        }

        default boolean canOpenPassage(BlockPos pos) {
            return false;
        }

        default boolean hasNearbyHazard(BlockPos pos) {
            return false;
        }
    }

    record Goal(BlockPos target, int rangeBlocks) {
        static Goal near(BlockPos target, int rangeBlocks) {
            return new Goal(target, Math.max(0, rangeBlocks));
        }

        boolean reached(BlockPos pos) {
            return pos.getSquaredDistance(target) <= rangeBlocks * rangeBlocks;
        }

        double heuristic(BlockPos pos) {
            double dx = Math.abs(pos.getX() - target.getX());
            double dy = Math.abs(pos.getY() - target.getY());
            double dz = Math.abs(pos.getZ() - target.getZ());
            double diagonal = Math.min(dx, dz);
            double straight = Math.max(dx, dz) - diagonal;
            return diagonal * 1.414D + straight + dy * 1.2D;
        }

        double progressScore(BlockPos pos) {
            return pos.getSquaredDistance(target);
        }
    }

    record Options(
        int horizontalLimit,
        int verticalLimit,
        int maxNodes,
        boolean allowDig,
        boolean allowPlace,
        boolean allowOpenPassage,
        double digCost,
        double placeCost,
        double openPassageCost,
        double jumpCost,
        double stepDownCost,
        double hazardPenalty,
        double awayFromGoalPenalty
    ) {
        static Options localWalk(int horizontalLimit, int verticalLimit, int maxNodes) {
            return new Options(
                Math.max(1, horizontalLimit),
                Math.max(1, verticalLimit),
                Math.max(16, maxNodes),
                false,
                false,
                false,
                8.0D,
                6.0D,
                2.0D,
                0.8D,
                0.25D,
                20.0D,
                0.8D
            );
        }

        static Options localAssist(int horizontalLimit, int verticalLimit, int maxNodes, boolean allowDig, boolean allowPlace, boolean allowOpenPassage) {
            return new Options(
                Math.max(1, horizontalLimit),
                Math.max(1, verticalLimit),
                Math.max(16, maxNodes),
                allowDig,
                allowPlace,
                allowOpenPassage,
                9.0D,
                6.0D,
                2.0D,
                0.8D,
                0.25D,
                20.0D,
                0.8D
            );
        }
    }

    enum MovementPrimitive {
        WALK,
        DIG,
        PLACE_SUPPORT,
        OPEN_PASSAGE
    }

    record AssistStep(MovementPrimitive primitive, BlockPos pos) {
        static AssistStep none() {
            return new AssistStep(MovementPrimitive.WALK, null);
        }

        boolean actionable() {
            return pos != null && primitive != MovementPrimitive.WALK;
        }
    }

    record Plan(List<BlockPos> path, double cost, boolean reached, String blockedReason, int visitedNodes, int digSteps, int placeSteps, int openSteps, AssistStep firstAssistStep) {
        static Plan blocked(String reason) {
            return new Plan(List.of(), 0.0D, false, reason, 0, 0, 0, 0, AssistStep.none());
        }
    }

    private record SearchNode(BlockPos pos, double costFromStart, double estimatedTotalCost, int digSteps, int placeSteps, int openSteps) {}

    private record StepCandidate(BlockPos pos, double cost, MovementPrimitive primitive, int digSteps, int placeSteps, int openSteps) {}

    private record Traversal(boolean traversable, MovementPrimitive primitive, int digSteps, int placeSteps, int openSteps) {}

    private record StepMeta(MovementPrimitive primitive, int digSteps, int placeSteps, int openSteps) {}
}
