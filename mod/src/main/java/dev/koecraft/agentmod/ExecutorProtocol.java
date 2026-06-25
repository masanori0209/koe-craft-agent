package dev.koecraft.agentmod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExecutorProtocol {
    private static final Gson GSON = new Gson();
    private static final String[] BANNED_COMMANDS = {"/give", "/fill", "/setblock", "/tp", "/summon", "/kill"};

    private ExecutorProtocol() {}

    public record Action(String type, JsonObject body) {
        public String stringField(String name) {
            JsonElement value = body.get(name);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        }

        public JsonObject objectField(String name) {
            JsonElement value = body.get(name);
            return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        }

        public int intField(String name, int fallback) {
            JsonElement value = body.get(name);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return fallback;
            }
            return value.getAsInt();
        }

        public boolean booleanField(String name, boolean fallback) {
            JsonElement value = body.get(name);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                return fallback;
            }
            return value.getAsBoolean();
        }

        public double doubleField(String name, double fallback) {
            JsonElement value = body.get(name);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return fallback;
            }
            return value.getAsDouble();
        }
    }

    public record ExecuteRequest(boolean valid, String requestId, String goal, List<Action> actions, String rawJson, String error) {
        public boolean hasBannedCommandText() {
            String lower = rawJson.toLowerCase(Locale.ROOT);
            for (String command : BANNED_COMMANDS) {
                if (lower.contains(command)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record StepResult(String type, String status, String message, JsonObject data) {
        public StepResult(String type, String status, String message) {
            this(type, status, message, null);
        }
    }

    public static ExecuteRequest parseExecuteRequest(String json) {
        if (json == null || json.isBlank()) {
            return invalid("empty message", "");
        }

        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return invalid("request must be a JSON object", json);
            }

            JsonObject root = parsed.getAsJsonObject();
            String messageType = stringField(root, "type");
            String requestId = stringField(root, "request_id");
            String goal = stringField(root, "goal");
            if (!"execute_actions".equals(messageType)) {
                return invalid("message type must be execute_actions", json);
            }
            if (requestId.isBlank()) {
                return invalid("missing request_id", json);
            }

            JsonElement actionsElement = root.get("actions");
            if (actionsElement == null || !actionsElement.isJsonArray()) {
                return invalid("actions must be an array", json);
            }

            List<Action> actions = parseActions(actionsElement.getAsJsonArray());
            if (actions.isEmpty()) {
                return invalid("actions must not be empty", json);
            }

            return new ExecuteRequest(true, requestId, goal, actions, json, "");
        } catch (RuntimeException ex) {
            return invalid("invalid JSON: " + ex.getMessage(), json);
        }
    }

    public static String successResponse(String requestId, List<StepResult> results) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "execute_result");
        root.addProperty("request_id", requestId);
        root.addProperty("ok", true);
        JsonArray steps = new JsonArray();
        for (StepResult result : results) {
            JsonObject step = new JsonObject();
            step.addProperty("type", result.type());
            step.addProperty("status", result.status());
            step.addProperty("message", result.message());
            if (result.data() != null) {
                step.add("data", result.data());
            }
            steps.add(step);
        }
        root.add("steps", steps);
        return GSON.toJson(root);
    }

    public static String errorResponse(String requestId, String code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "execute_result");
        root.addProperty("request_id", requestId);
        root.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        root.add("error", error);
        return GSON.toJson(root);
    }

    private static List<Action> parseActions(JsonArray array) {
        List<Action> actions = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject action = element.getAsJsonObject();
            String type = stringField(action, "type");
            if (!type.isBlank()) {
                actions.add(new Action(type, action.deepCopy()));
            }
        }
        return actions;
    }

    private static ExecuteRequest invalid(String error, String rawJson) {
        return new ExecuteRequest(false, "", "", List.of(), rawJson, error);
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
