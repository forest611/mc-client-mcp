package io.github.forest611.mcptest.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.forest611.mcptest.ClientClock;
import io.github.forest611.mcptest.McpTestMod;
import io.github.forest611.mcptest.op.Sequence;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP over Streamable HTTP, bound to the loopback interface only. Every request is answered with a single JSON
 * response (no SSE stream), which is all a tools-only server needs.
 */
public final class McpHttpServer {
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final String INSTRUCTIONS = """
            Drives a real Minecraft client for plugin verification. Server-side setup (op, gamemode, tp, give, setblock) \
            is not covered; do it over RCON.
            Time is in client ticks (20 per second). Every result carries 'tick' and 'event_seq'. 'event_seq' is the \
            event cursor from just before the call took effect: pass it as 'after_seq' to events / wait_event / \
            wait_message to see exactly what the call caused, including proof that nothing happened.
            Block interactions aim the player's view first and go through the normal client interaction code, so reach \
            and line of sight apply; on a mismatch the result tells what was hit instead.
            Use run_sequence when the spacing between actions matters: a separate call per action costs several ticks.""";

    private final ToolRegistry registry;
    private final long callTimeoutMs;
    private final String version;
    private final CallLog callLog;
    private HttpServer server;

    public McpHttpServer(ToolRegistry registry, long callTimeoutMs, String version, CallLog callLog) {
        this.registry = registry;
        this.callTimeoutMs = callTimeoutMs;
        this.version = version;
        this.callLog = callLog;
    }

    /** @return the port actually bound */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        AtomicInteger threadIds = new AtomicInteger();
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mcptest-http-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/mcp", exchange -> guard(exchange, this::handleMcp));
        server.createContext("/health", exchange -> guard(exchange, this::handleHealth));
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void guard(HttpExchange exchange, Handler handler) throws IOException {
        try {
            if (!isLocalRequest(exchange)) {
                respond(exchange, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            handler.handle(exchange);
        } catch (RuntimeException e) {
            McpTestMod.LOGGER.error("MCP request failed", e);
            respond(exchange, 500, "{\"error\":\"internal error\"}");
        } finally {
            exchange.close();
        }
    }

    /** Rejects DNS-rebinding attempts: a browser page on another origin must not reach this endpoint. */
    private static boolean isLocalRequest(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !isLocalHostName(stripPort(host))) {
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        try {
            String originHost = URI.create(origin).getHost();
            return originHost != null && isLocalHostName(originHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.lastIndexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static boolean isLocalHostName(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("status", "ok");
        body.addProperty("ready", McpTestMod.isReady());
        body.addProperty("tick", ClientClock.tick());
        body.addProperty("version", version);
        respond(exchange, 200, body.toString());
    }

    private void handleMcp(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            respond(exchange, 405, "");
            return;
        }
        JsonElement message;
        try {
            message = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            respond(exchange, 400, rpcError(null, -32700, "Parse error").toString());
            return;
        }
        if (message.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement element : message.getAsJsonArray()) {
                JsonObject response = element.isJsonObject() ? handleMessage(element.getAsJsonObject()) : rpcError(null, -32600, "Invalid Request");
                if (response != null) {
                    responses.add(response);
                }
            }
            if (responses.isEmpty()) {
                respond(exchange, 202, "");
            } else {
                respond(exchange, 200, responses.toString());
            }
        } else if (message.isJsonObject()) {
            JsonObject response = handleMessage(message.getAsJsonObject());
            if (response == null) {
                respond(exchange, 202, "");
            } else {
                respond(exchange, 200, response.toString());
            }
        } else {
            respond(exchange, 400, rpcError(null, -32600, "Invalid Request").toString());
        }
    }

    /** @return the JSON-RPC response, or null for notifications and client responses */
    private JsonObject handleMessage(JsonObject message) {
        JsonElement id = message.get("id");
        JsonElement method = message.get("method");
        if (method == null || !method.isJsonPrimitive() || id == null || id.isJsonNull()) {
            return null;
        }
        JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
        return switch (method.getAsString()) {
            case "initialize" -> rpcResult(id, initialize(params));
            case "ping" -> rpcResult(id, new JsonObject());
            case "tools/list" -> rpcResult(id, listTools());
            case "tools/call" -> callTool(id, params);
            default -> rpcError(id, -32601, "Method not found: " + method.getAsString());
        };
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "";
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", McpTestMod.MOD_ID);
        serverInfo.addProperty("version", version);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    private JsonObject listTools() {
        JsonArray tools = new JsonArray();
        for (Tool tool : registry.all()) {
            tools.add(describe(tool.name(), tool.description(), tool.inputSchema()));
        }
        tools.add(describe(Sequence.TOOL_NAME, SequenceSchema.DESCRIPTION, SequenceSchema.build()));
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private static JsonObject describe(String name, String description, JsonObject inputSchema) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("description", description);
        json.add("inputSchema", inputSchema);
        return json;
    }

    private JsonObject callTool(JsonElement id, JsonObject params) {
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            return rpcError(id, -32602, "Missing tool name");
        }
        String name = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
        boolean isSequence = Sequence.TOOL_NAME.equals(name);
        Tool tool = registry.get(name);
        if (!isSequence && tool == null) {
            return rpcError(id, -32602, "Unknown tool: " + name);
        }

        long startedAt = System.currentTimeMillis();
        long startTick = ClientClock.tick();
        Sequence.Outcome outcome;
        try {
            Sequence sequence = isSequence ? Sequence.fromArgs(registry, new Args(arguments)) : Sequence.single(tool, arguments);
            McpTestMod.runner().submit(sequence);
            try {
                outcome = sequence.future().get(callTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                sequence.cancel();
                throw new ToolException("timeout", "the call did not finish within " + callTimeoutMs + " ms; the client thread may be stalled");
            } catch (InterruptedException e) {
                sequence.cancel();
                Thread.currentThread().interrupt();
                throw new ToolException("cancelled", "interrupted while waiting for the client thread");
            } catch (ExecutionException e) {
                McpTestMod.LOGGER.error("Tool {} failed", name, e.getCause());
                throw new ToolException("internal_error", String.valueOf(e.getCause()));
            }
        } catch (ToolException e) {
            outcome = new Sequence.Outcome(null, null, e);
        }

        JsonObject body;
        if (outcome.error() == null) {
            body = outcome.result();
        } else {
            body = new JsonObject();
            body.add("error", outcome.error().toJson());
        }
        callLog.write(name, arguments, body, outcome.error() == null, startTick, System.currentTimeMillis() - startedAt);

        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", body.toString());
        content.add(text);
        if (outcome.image() != null) {
            content.add(outcome.image());
        }
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", outcome.error() != null);
        return rpcResult(id, result);
    }

    private static JsonObject rpcResult(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("error", error);
        return response;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
