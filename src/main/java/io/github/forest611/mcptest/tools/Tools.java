package io.github.forest611.mcptest.tools;

import io.github.forest611.mcptest.mcp.ToolRegistry;

public final class Tools {
    private Tools() {
    }

    public static void registerAll(ToolRegistry registry) {
        EventTools.register(registry);
    }
}
