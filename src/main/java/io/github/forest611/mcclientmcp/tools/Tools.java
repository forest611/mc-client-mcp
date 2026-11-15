package io.github.forest611.mcclientmcp.tools;

import io.github.forest611.mcclientmcp.mcp.ToolRegistry;

public final class Tools {
    private Tools() {
    }

    public static void registerAll(ToolRegistry registry) {
        ConnectionTools.register(registry);
        PlayerTools.register(registry);
        MovementTools.register(registry);
        InteractionTools.register(registry);
        ScreenTools.register(registry);
        ChatTools.register(registry);
        WorldTools.register(registry);
        EventTools.register(registry);
    }
}
