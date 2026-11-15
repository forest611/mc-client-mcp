package io.github.forest611.mcclientmcp.tools;

import com.google.common.net.InetAddresses;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.McClientMcpMod;
import io.github.forest611.mcclientmcp.event.EventHooks;
import io.github.forest611.mcclientmcp.input.InputLock;
import io.github.forest611.mcclientmcp.input.ScriptedInput;
import io.github.forest611.mcclientmcp.mcp.Args;
import io.github.forest611.mcclientmcp.mcp.Schema;
import io.github.forest611.mcclientmcp.mcp.ToolException;
import io.github.forest611.mcclientmcp.mcp.ToolRegistry;
import io.github.forest611.mcclientmcp.mixin.DisconnectedScreenAccessor;
import io.github.forest611.mcclientmcp.op.Op;
import io.github.forest611.mcclientmcp.util.Client;
import io.github.forest611.mcclientmcp.util.Describe;
import io.github.forest611.mcclientmcp.util.Screens;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;

import java.util.List;

final class ConnectionTools {
    private static String lastAddress;

    private ConnectionTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("status",
                "Connection and client state: whether in a world, server address, player name and UUID, dimension, position, "
                        + "game mode, the reason for the last disconnect or kick, the open screen, and window state.",
                Schema.of(), args -> status());

        registry.register("connect",
                "Connects to a Minecraft server and returns once the player is in the world (same fields as status). "
                        + "Only loopback and private addresses are allowed unless the host is listed in the mod's allowedHosts. "
                        + "If already in a world, leaves it first. Omit 'host' to reconnect to the previous server. "
                        + "Fails with connect_failed (details.reason holds the kick/refusal text), host_not_allowed, or timeout.",
                Schema.of()
                        .str("host", "Server host name or IP; may include ':port'")
                        .integer("port", "Server port (default 25565)")
                        .integer("timeout_ticks", "Give up after this many client ticks (default 600 = 30 s)"),
                ConnectOp::new);

        registry.registerInstant("disconnect",
                "Leaves the current server and returns to the title screen. Also cancels a connection attempt in progress.",
                Schema.of(), args -> disconnect());

        registry.register("respawn",
                "Respawns the player after death and returns once alive again. Fails with not_dead otherwise.",
                Schema.of().integer("timeout_ticks", "Give up after this many ticks (default 100)"),
                RespawnOp::new);

        registry.registerInstant("set_input_lock",
                "Enables or disables the input lock. While locked (the default) the real keyboard and mouse are ignored "
                        + "in a world so that nothing but MCP calls moves the player; unlock to let a human take over.",
                Schema.of().add("enabled", Schema.typed("boolean", "true to lock, false to unlock"), true),
                args -> {
                    boolean enabled = args.bool("enabled", true);
                    InputLock.setEnabled(enabled);
                    if (enabled) {
                        Client.mc().mouseHandler.releaseMouse();
                        KeyMapping.releaseAll();
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("input_locked", enabled);
                    return result;
                });

        registry.registerInstant("set_window",
                "Minimizes or restores the game window. The client keeps ticking, rendering and taking screenshots while minimized.",
                Schema.of().add("minimized", Schema.typed("boolean", "true to minimize, false to restore"), true),
                args -> {
                    boolean minimized = args.bool("minimized", true);
                    long window = Client.mc().getWindow().getWindow();
                    if (minimized) {
                        GLFW.glfwIconifyWindow(window);
                    } else {
                        GLFW.glfwRestoreWindow(window);
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("minimized", minimized);
                    return result;
                });
    }

    static JsonObject status() {
        Minecraft mc = Client.mc();
        JsonObject json = new JsonObject();
        json.addProperty("ready", McClientMcpMod.isReady());
        json.addProperty("connected", Client.inWorld());

        JsonObject account = new JsonObject();
        account.addProperty("name", mc.getUser().getName());
        account.addProperty("uuid", mc.getUser().getProfileId().toString());
        account.addProperty("type", mc.getUser().getType().getName());
        json.add("account", account);

        if (Client.inWorld()) {
            LocalPlayer player = mc.player;
            ServerData server = mc.getCurrentServer();
            json.addProperty("server", server != null ? server.ip : mc.isLocalServer() ? "singleplayer" : null);
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("name", player.getScoreboardName());
            playerJson.addProperty("uuid", player.getUUID().toString());
            playerJson.addProperty("entity_id", player.getId());
            json.add("player", playerJson);
            json.addProperty("dimension", mc.level.dimension().location().toString());
            json.add("pos", Describe.vec(player.position()));
            json.addProperty("gamemode", mc.gameMode.getPlayerMode().getName());
        }
        JsonObject lastDisconnect = EventHooks.lastDisconnect();
        json.add("last_disconnect", lastDisconnect == null ? JsonNull.INSTANCE : lastDisconnect);
        json.add("screen", mc.screen == null ? JsonNull.INSTANCE : Screens.summary(mc.screen));

        JsonObject window = new JsonObject();
        window.addProperty("focused", mc.isWindowActive());
        window.addProperty("minimized", mc.getWindow().isIconified());
        window.addProperty("width", mc.getWindow().getWidth());
        window.addProperty("height", mc.getWindow().getHeight());
        window.addProperty("fps", mc.getFps());
        json.add("window", window);
        json.addProperty("input_locked", InputLock.isEnabled());
        json.addProperty("minecraft_version", SharedConstants.getCurrentVersion().name());
        json.addProperty("mod_version", McClientMcpMod.version());
        return json;
    }

    private static JsonObject disconnect() {
        Minecraft mc = Client.mc();
        JsonObject result = new JsonObject();
        boolean wasConnected = mc.level != null;
        if (wasConnected) {
            leaveWorld(mc);
        } else if (mc.screen instanceof ConnectScreen) {
            cancelConnecting(mc);
        }
        result.addProperty("was_connected", wasConnected);
        return result;
    }

    /** The same steps as the pause menu's Disconnect button, ending on the title screen. */
    private static void leaveWorld(Minecraft mc) {
        boolean local = mc.isLocalServer();
        if (mc.level != null) {
            mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        if (local) {
            mc.disconnectWithSavingScreen();
        } else {
            mc.disconnectWithProgressScreen();
        }
        mc.setScreen(new TitleScreen());
        ScriptedInput.reset();
        EventHooks.disconnected(null, null);
    }

    private static void cancelConnecting(Minecraft mc) {
        List<AbstractButton> buttons = Screens.buttons(mc.screen);
        if (!buttons.isEmpty()) {
            buttons.get(0).onPress();
        }
    }

    private static final class ConnectOp extends Op {
        private final String address;
        private final int timeoutTicks;
        private long startTick = -1;

        ConnectOp(Args args) throws ToolException {
            String host = args.string("host", null);
            if (host == null) {
                if (lastAddress == null) {
                    throw ToolException.invalidArgs("'host' is required: there is no previous server to reconnect to");
                }
                address = lastAddress;
            } else {
                address = args.has("port") ? host + ":" + args.integer("port", 25565) : host;
            }
            if (!ServerAddress.isValidAddress(address)) {
                throw ToolException.invalidArgs("not a valid server address: " + address);
            }
            timeoutTicks = args.integer("timeout_ticks", 600);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            Minecraft mc = Client.mc();
            if (startTick < 0) {
                start(mc);
                startTick = tick;
                return false;
            }
            if (mc.screen instanceof DisconnectedScreen screen) {
                String reason = ((DisconnectedScreenAccessor) screen).mcclientmcp$getDetails().reason().getString();
                JsonObject details = new JsonObject();
                details.addProperty("address", address);
                details.addProperty("title", screen.getTitle().getString());
                details.addProperty("reason", reason);
                if (McClientMcpMod.allowlist().takeBlocked() != null) {
                    throw notAllowed(address);
                }
                throw new ToolException("connect_failed", "could not join " + address + ": " + reason, details);
            }
            if (Client.inWorld() && !(mc.screen instanceof ConnectScreen) && (mc.screen == null || !Screens.isLoading(mc.screen))) {
                result = status();
                return true;
            }
            if (tick - startTick >= timeoutTicks) {
                JsonObject details = new JsonObject();
                details.addProperty("address", address);
                details.add("screen", mc.screen == null ? JsonNull.INSTANCE : Screens.summary(mc.screen));
                if (mc.screen instanceof ConnectScreen) {
                    cancelConnecting(mc);
                }
                throw new ToolException("timeout", "not in the world after " + timeoutTicks + " ticks", details);
            }
            return false;
        }

        private void start(Minecraft mc) throws ToolException {
            if (!McClientMcpMod.isReady()) {
                throw new ToolException("not_ready", "the client is still starting up; poll status until ready is true");
            }
            if (mc.screen instanceof ConnectScreen) {
                throw new ToolException("busy", "a connection attempt is already in progress");
            }
            ServerAddress parsed = ServerAddress.parseString(address);
            String host = parsed.getHost();
            // IP literals can be checked without a DNS lookup; host names are checked when the socket is opened.
            if (InetAddresses.isInetAddress(host) && !McClientMcpMod.allowlist().isAllowed(InetAddresses.forString(host))) {
                throw notAllowed(address);
            }
            if (mc.level != null) {
                leaveWorld(mc);
            }
            McClientMcpMod.allowlist().takeBlocked();
            ServerData server = new ServerData(address, address, ServerData.Type.OTHER);
            server.setResourcePackStatus(McClientMcpMod.config().acceptResourcePacks
                    ? ServerData.ServerPackStatus.ENABLED : ServerData.ServerPackStatus.PROMPT);
            ConnectScreen.startConnecting(new TitleScreen(), mc, parsed, server, false, null);
            lastAddress = address;
        }

        private static ToolException notAllowed(String address) {
            JsonObject details = new JsonObject();
            details.addProperty("address", address);
            return new ToolException("host_not_allowed",
                    address + " is not a loopback or private address and is not listed in allowedHosts (config/mc-client-mcp.json)", details);
        }
    }

    private static final class RespawnOp extends Op {
        private final int timeoutTicks;
        private long startTick = -1;
        private LocalPlayer deadPlayer;

        RespawnOp(Args args) throws ToolException {
            timeoutTicks = args.integer("timeout_ticks", 100);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            Minecraft mc = Client.mc();
            if (startTick < 0) {
                LocalPlayer player = Client.player();
                if (!player.isDeadOrDying()) {
                    throw new ToolException("not_dead", "the player is alive");
                }
                startTick = tick;
                deadPlayer = player;
                player.respawn();
                mc.setScreen(null);
                return false;
            }
            if (mc.player != null && mc.player != deadPlayer && !mc.player.isDeadOrDying()) {
                result.add("pos", Describe.vec(mc.player.position()));
                result.addProperty("health", Describe.round(mc.player.getHealth()));
                return true;
            }
            if (tick - startTick >= timeoutTicks) {
                throw new ToolException("timeout", "the server did not respawn the player within " + timeoutTicks + " ticks");
            }
            return false;
        }
    }
}
