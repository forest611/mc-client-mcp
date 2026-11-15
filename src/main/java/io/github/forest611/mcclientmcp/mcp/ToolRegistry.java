package io.github.forest611.mcclientmcp.mcp;

import io.github.forest611.mcclientmcp.op.Op;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(String name, String description, Schema schema, Tool.OpFactory factory) {
        if (tools.put(name, new Tool(name, description, schema.build(), factory)) != null) {
            throw new IllegalStateException("Duplicate tool: " + name);
        }
    }

    /** Registers a tool that completes within the tick it starts in. */
    public void registerInstant(String name, String description, Schema schema, InstantFactory factory) {
        register(name, description, schema, args -> Op.instant(() -> factory.run(args)));
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    @FunctionalInterface
    public interface InstantFactory {
        com.google.gson.JsonObject run(Args args) throws ToolException;
    }
}
