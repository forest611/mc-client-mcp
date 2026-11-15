package io.github.forest611.mcptest.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.forest611.mcptest.McpTestMod;
import io.github.forest611.mcptest.event.EventFilter;
import io.github.forest611.mcptest.event.EventLog;
import io.github.forest611.mcptest.mcp.Args;
import io.github.forest611.mcptest.mcp.Schema;
import io.github.forest611.mcptest.mcp.ToolException;
import io.github.forest611.mcptest.mcp.ToolRegistry;
import io.github.forest611.mcptest.op.Op;

import java.util.List;

final class EventTools {
    private static final String EVENT_TYPES = "Event types: message (kind: chat | system | action_bar | title | subtitle; text), "
            + "block_change (pos, old, new, source: server | prediction | server_correction | client), "
            + "block_confirm (the server sent a block state that changed nothing on screen; deferred=true while a prediction is pending), "
            + "entity_spawn / entity_update / entity_remove (entity), inventory_change (slot, old, new), "
            + "container_change (container_id, slot, old, new), screen_open / screen_close (screen), "
            + "join, disconnect (reason), sound, particle. "
            + "A block_change with source server_correction means the server undid a change the client had predicted "
            + "(a broken block coming back, a placed block disappearing).";

    private EventTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("events",
                "Returns recorded events after a given sequence number, oldest first, each with seq, client tick and time. "
                        + "An empty list is positive proof that nothing of that kind happened. " + EVENT_TYPES,
                EventFilter.describe(Schema.of()
                        .integer("after_seq", "Only events with a larger seq (default 0 = everything still in memory). "
                                + "Use the event_seq returned by an earlier call."))
                        .integer("limit", "Maximum number of events to return (default 200)"),
                EventTools::events);

        registry.register("wait_event",
                "Waits until an event matching the filter is recorded and returns it. Fails with timeout if none arrives. "
                        + "Events recorded after 'after_seq' but before this call also count, so pass the event_seq of the "
                        + "action that should cause the event to avoid missing it. " + EVENT_TYPES,
                EventFilter.describe(Schema.of()
                        .integer("after_seq", "Consider events with a larger seq (default: only events from now on)"))
                        .integer("timeout_ticks", "Give up after this many ticks (default 100 = 5 s)"),
                args -> new WaitEventOp(EventFilter.fromArgs(args), args, false));

        registry.register("wait_ticks",
                "Waits the given number of client ticks (20 per second).",
                Schema.of().reqInteger("ticks", "Number of ticks to wait"),
                WaitTicksOp::new);
    }

    private static JsonObject events(Args args) throws ToolException {
        EventFilter filter = EventFilter.fromArgs(args);
        long afterSeq = args.longValue("after_seq", 0);
        int limit = Math.max(1, args.integer("limit", 200));
        EventLog log = McpTestMod.events();
        JsonArray array = new JsonArray();
        long nextAfterSeq = log.lastSeq();
        boolean truncated = false;
        for (EventLog.Event event : log.after(afterSeq)) {
            if (!filter.test(event)) {
                continue;
            }
            if (array.size() >= limit) {
                truncated = true;
                break;
            }
            array.add(event.toJson());
            nextAfterSeq = event.seq();
        }
        JsonObject result = new JsonObject();
        result.add("events", array);
        result.addProperty("count", array.size());
        result.addProperty("truncated", truncated);
        // When truncated, continue from the last event returned; otherwise from the end of the log.
        result.addProperty("next_after_seq", truncated ? nextAfterSeq : log.lastSeq());
        result.addProperty("oldest_available_seq", log.firstSeq());
        return result;
    }

    static final class WaitEventOp extends Op {
        private final EventFilter filter;
        private final int timeoutTicks;
        private final boolean listMessagesOnTimeout;
        private final long afterSeq;
        private long cursor;
        private long startTick = -1;

        WaitEventOp(EventFilter filter, Args args, boolean listMessagesOnTimeout) throws ToolException {
            this.filter = filter;
            this.timeoutTicks = args.integer("timeout_ticks", 100);
            this.listMessagesOnTimeout = listMessagesOnTimeout;
            this.afterSeq = args.longValue("after_seq", McpTestMod.events().lastSeq());
            this.cursor = afterSeq;
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            if (startTick < 0) {
                startTick = tick;
            }
            for (EventLog.Event event : McpTestMod.events().after(cursor)) {
                cursor = event.seq();
                if (filter.test(event)) {
                    result.add("event", event.toJson());
                    result.addProperty("waited_ticks", tick - startTick);
                    return true;
                }
            }
            if (tick - startTick >= timeoutTicks) {
                JsonObject details = new JsonObject();
                details.addProperty("waited_ticks", tick - startTick);
                details.addProperty("last_seq", cursor);
                if (listMessagesOnTimeout) {
                    JsonArray seen = new JsonArray();
                    List<EventLog.Event> since = McpTestMod.events().after(afterSeq);
                    for (EventLog.Event event : since) {
                        if (event.type().equals("message") && seen.size() < 30) {
                            seen.add(event.toJson());
                        }
                    }
                    details.add("messages_received", seen);
                }
                throw new ToolException("timeout", "nothing matched within " + timeoutTicks + " ticks", details);
            }
            return false;
        }
    }

    private static final class WaitTicksOp extends Op {
        private final int ticks;
        private long startTick = -1;

        WaitTicksOp(Args args) throws ToolException {
            ticks = args.requireInteger("ticks");
            if (ticks < 0) {
                throw ToolException.invalidArgs("'ticks' must be >= 0");
            }
        }

        @Override
        public boolean tick(long tick) {
            if (startTick < 0) {
                startTick = tick;
            }
            if (tick - startTick < ticks) {
                return false;
            }
            result.addProperty("waited_ticks", ticks);
            return true;
        }
    }
}
