package io.github.forest611.mcclientmcp.mcp;

import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.op.Op;

public record Tool(String name, String description, JsonObject inputSchema, OpFactory factory) {
    @FunctionalInterface
    public interface OpFactory {
        /** Validates the arguments and builds the op; runs on the client thread in the tick the op starts. */
        Op create(Args args) throws ToolException;
    }
}
