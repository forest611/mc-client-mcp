package io.github.forest611.mcptest.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.forest611.mcptest.ClientClock;
import io.github.forest611.mcptest.McpTestMod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Everything the client observed, in order: a bounded in-memory ring plus an append-only JSONL file. */
public final class EventLog {
    public record Event(long seq, long tick, long timeMs, String type, JsonObject data) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("seq", seq);
            json.addProperty("tick", tick);
            json.addProperty("time", Instant.ofEpochMilli(timeMs).toString());
            json.addProperty("type", type);
            for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                json.add(entry.getKey(), entry.getValue());
            }
            return json;
        }
    }

    private final Event[] ring;
    private long lastSeq;
    private BufferedWriter writer;

    public EventLog(int capacity, Path file) {
        this.ring = new Event[Math.max(capacity, 100)];
        try {
            Files.createDirectories(file.getParent());
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            McpTestMod.LOGGER.error("Cannot open event log file {}", file, e);
        }
    }

    public synchronized long add(String type, JsonObject data) {
        Event event = new Event(++lastSeq, ClientClock.tick(), System.currentTimeMillis(), type, data);
        ring[(int) (event.seq % ring.length)] = event;
        if (writer != null) {
            try {
                writer.write(event.toJson().toString());
                writer.newLine();
            } catch (IOException e) {
                McpTestMod.LOGGER.error("Event log file write failed; file logging disabled", e);
                writer = null;
            }
        }
        return event.seq;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    /** Sequence number of the oldest event still held in memory, or lastSeq + 1 when empty. */
    public synchronized long firstSeq() {
        return Math.max(1, lastSeq - ring.length + 1);
    }

    /** Events with a sequence number greater than {@code afterSeq}, oldest first. */
    public synchronized List<Event> after(long afterSeq) {
        List<Event> out = new ArrayList<>();
        for (long seq = Math.max(afterSeq + 1, firstSeq()); seq <= lastSeq; seq++) {
            out.add(ring[(int) (seq % ring.length)]);
        }
        return out;
    }

    public synchronized void flush() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                McpTestMod.LOGGER.error("Event log file flush failed; file logging disabled", e);
                writer = null;
            }
        }
    }
}
