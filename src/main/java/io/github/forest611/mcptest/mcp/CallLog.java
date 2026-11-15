package io.github.forest611.mcptest.mcp;

import com.google.gson.JsonObject;
import io.github.forest611.mcptest.McpTestMod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Appends every tool call with its arguments and outcome to a JSONL file for post-mortem inspection. */
public final class CallLog {
    private BufferedWriter writer;

    public CallLog(Path file) {
        try {
            Files.createDirectories(file.getParent());
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            McpTestMod.LOGGER.error("Cannot open call log file {}", file, e);
        }
    }

    public synchronized void write(String tool, JsonObject args, JsonObject result, boolean ok, long startTick, long durationMs) {
        if (writer == null) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("time", Instant.now().toString());
        line.addProperty("tick", startTick);
        line.addProperty("duration_ms", durationMs);
        line.addProperty("tool", tool);
        line.add("args", args);
        line.addProperty("ok", ok);
        line.add("result", result);
        try {
            writer.write(line.toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            McpTestMod.LOGGER.error("Call log file write failed; file logging disabled", e);
            writer = null;
        }
    }
}
