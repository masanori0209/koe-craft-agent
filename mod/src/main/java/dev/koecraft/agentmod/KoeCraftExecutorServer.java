package dev.koecraft.agentmod;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Consumer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KoeCraftExecutorServer extends WebSocketServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(KoeCraftAgentClient.MOD_ID);
    private final SurvivalActionExecutor executor;
    private final int port;
    private final Consumer<String> statusSink;

    public KoeCraftExecutorServer(int port, SurvivalActionExecutor executor, Consumer<String> statusSink) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.executor = executor;
        this.port = port;
        this.statusSink = statusSink;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("[KoeCraft] Agent connected from {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (executor.isExecuting()) {
            executor.abort("websocket_closed");
        }
        LOGGER.info("[KoeCraft] Agent disconnected: {}", reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        ExecutorProtocol.ExecuteRequest request = ExecutorProtocol.parseExecuteRequest(message);
        if (!request.valid()) {
            conn.send(ExecutorProtocol.errorResponse("unknown", "invalid_request", request.error()));
            return;
        }

        if (request.hasBannedCommandText()) {
            executor.abort("banned_command_text");
            conn.send(ExecutorProtocol.errorResponse(request.requestId(), "safety_rejected", "banned command text detected"));
            return;
        }

        List<ExecutorProtocol.StepResult> results = executor.execute(request.goal(), request.actions());
        if (conn.isOpen()) {
            conn.send(ExecutorProtocol.successResponse(request.requestId(), results));
        } else {
            LOGGER.info("[KoeCraft] Skipped executor response for closed connection: {}", request.requestId());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        executor.abort("websocket_error");
        statusSink.accept("KoeCraft executor failed on 127.0.0.1:" + port);
        LOGGER.warn("[KoeCraft] Executor websocket error", ex);
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(120);
        statusSink.accept("KoeCraft executor listening on ws://127.0.0.1:" + port);
        LOGGER.info("[KoeCraft] Executor WebSocket listening on ws://127.0.0.1:{}", port);
    }

    public void stopGracefully() {
        executor.abort("client_stopping");
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
