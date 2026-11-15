package io.github.forest611.mcptest.util;

import io.github.forest611.mcptest.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/** Accessors for the current world that fail with a structured error instead of a null. */
public final class Client {
    private Client() {
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static boolean inWorld() {
        Minecraft mc = mc();
        return mc.player != null && mc.level != null && mc.gameMode != null;
    }

    public static LocalPlayer player() throws ToolException {
        requireWorld();
        return mc().player;
    }

    public static ClientLevel level() throws ToolException {
        requireWorld();
        return mc().level;
    }

    public static MultiPlayerGameMode gameMode() throws ToolException {
        requireWorld();
        return mc().gameMode;
    }

    public static void requireWorld() throws ToolException {
        if (!inWorld()) {
            throw new ToolException("not_in_world", "the client is not in a world; call connect first");
        }
    }
}
