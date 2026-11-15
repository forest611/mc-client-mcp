package io.github.forest611.mcptest.mcp;

import com.google.gson.JsonObject;

final class SequenceSchema {
    static final String DESCRIPTION = "Runs several tool calls in one request with exact client-tick spacing. "
            + "Each step starts 'delay' ticks after the previous step finished acting (0 = the same tick). "
            + "Steps that wait for a server acknowledgement (use_on_block, break_block, ...) do not hold up the next step; "
            + "their outcomes are filled in before the call returns. "
            + "'repeat' runs the whole list that many times, 'interval' ticks apart. "
            + "Returns for every executed step its start tick, the tick its action completed, and its result or error.";

    private SequenceSchema() {
    }

    static JsonObject build() {
        JsonObject step = Schema.of()
                .reqStr("tool", "Name of any other tool")
                .obj("args", "Arguments for that tool")
                .integer("delay", "Ticks to wait after the previous step's action completed (default 0 = same tick)")
                .build();
        JsonObject steps = Schema.typed("array", "Steps to run in order");
        steps.add("items", step);
        return Schema.of()
                .add("steps", steps, true)
                .integer("repeat", "Number of times to run the whole list (default 1)")
                .integer("interval", "Ticks between the end of one repetition and the start of the next (default 0)")
                .enumStr("on_error", "What to do when a step fails (default abort: later steps are not run)", "abort", "continue")
                .build();
    }
}
