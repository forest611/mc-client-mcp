package io.github.forest611.mcclientmcp.input;

import net.minecraft.client.Minecraft;

/** While locked and in a world, the real keyboard and mouse are ignored so that only MCP calls drive the client. */
public final class InputLock {
    private static volatile boolean enabled;

    private InputLock() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean blocksInput() {
        return enabled && Minecraft.getInstance().level != null;
    }
}
