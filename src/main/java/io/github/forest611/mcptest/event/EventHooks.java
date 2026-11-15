package io.github.forest611.mcptest.event;

import com.google.gson.JsonObject;
import io.github.forest611.mcptest.ClientClock;
import io.github.forest611.mcptest.McpTestMod;
import io.github.forest611.mcptest.util.Describe;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Entry points called by mixins and Fabric events; each turns one observation into an event. */
public final class EventHooks {
    public static final String SOURCE_SERVER = "server";
    public static final String SOURCE_PREDICTION = "prediction";
    public static final String SOURCE_CORRECTION = "server_correction";
    public static final String SOURCE_CLIENT = "client";
    /** Not an event source: used only towards {@link BlockListener}s for server reports that changed nothing. */
    public static final String SOURCE_CONFIRM = "confirm";

    public static final String KIND_CHAT = "chat";
    public static final String KIND_SYSTEM = "system";
    public static final String KIND_ACTION_BAR = "action_bar";
    public static final String KIND_TITLE = "title";
    public static final String KIND_SUBTITLE = "subtitle";

    /** Spawns are reported at the end of the tick so the event carries the entity data that follows the spawn packet. */
    private static final Set<Entity> pendingSpawns = new LinkedHashSet<>();
    private static boolean pendingLeave;
    private static boolean inSession;
    private static final List<BlockListener> blockListeners = new ArrayList<>();

    /**
     * A block state seen by the client. For {@link #SOURCE_CONFIRM}, {@code before} is what the client shows and
     * {@code after} is what the server reported; nothing changed on screen.
     */
    public record BlockChange(BlockPos pos, BlockState before, BlockState after, String source) {
    }

    @FunctionalInterface
    public interface BlockListener {
        void onBlock(BlockChange change);
    }
    private static volatile JsonObject lastDisconnect;

    private EventHooks() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Overlay messages reach Gui.setOverlayMessage and are recorded there as action_bar.
            if (!overlay) {
                message(KIND_SYSTEM, message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> message(KIND_CHAT, message));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            JsonObject data = new JsonObject();
            ServerData server = handler.getServerData();
            if (server != null) {
                data.addProperty("server", server.ip);
            }
            McpTestMod.events().add("join", data);
            inSession = true;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            pendingLeave = true;
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity != Minecraft.getInstance().player) {
                pendingSpawns.add(entity);
            }
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity == Minecraft.getInstance().player) {
                return;
            }
            if (pendingSpawns.remove(entity)) {
                entityEvent("entity_spawn", entity);
            }
            entityEvent("entity_remove", entity);
        });
    }

    public static void blockChanged(BlockPos pos, BlockState before, BlockState after, String source) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        notifyBlockListeners(new BlockChange(pos.immutable(), before, after, source));
        JsonObject data = new JsonObject();
        data.add("pos", Describe.pos(pos));
        data.add("old", Describe.block(before));
        data.add("new", Describe.block(after));
        data.addProperty("source", source);
        McpTestMod.events().add("block_change", data);
    }

    /** The server sent a block state that did not change what the client shows. */
    public static void blockConfirmed(BlockPos pos, BlockState serverState, BlockState clientState) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        JsonObject data = new JsonObject();
        data.add("pos", Describe.pos(pos));
        data.add("server", Describe.block(serverState));
        if (serverState != clientState) {
            // A client prediction is pending for this block; the server state is applied when it is acknowledged.
            data.add("client", Describe.block(clientState));
            data.addProperty("deferred", true);
        }
        McpTestMod.events().add("block_confirm", data);
        notifyBlockListeners(new BlockChange(pos.immutable(), clientState, serverState, SOURCE_CONFIRM));
    }

    private static void notifyBlockListeners(BlockChange change) {
        for (BlockListener listener : List.copyOf(blockListeners)) {
            listener.onBlock(change);
        }
    }

    /** Listeners see every block change and server confirmation; actions use them to follow their own predictions. */
    public static void addBlockListener(BlockListener listener) {
        blockListeners.add(listener);
    }

    public static void removeBlockListener(BlockListener listener) {
        blockListeners.remove(listener);
    }

    public static void message(String kind, Component text) {
        if (text == null) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("kind", kind);
        data.addProperty("text", text.getString());
        McpTestMod.events().add("message", data);
    }

    public static void entityDataUpdated(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(entityId);
        if (entity != null && entity != mc.player && !pendingSpawns.contains(entity)) {
            entityEvent("entity_update", entity);
        }
    }

    public static void sound(String sound, String category, double x, double y, double z, float volume, float pitch) {
        JsonObject data = soundData(sound, category, volume, pitch);
        data.add("pos", Describe.vec(new Vec3(x, y, z)));
        McpTestMod.events().add("sound", data);
    }

    public static void entitySound(String sound, String category, int entityId, float volume, float pitch) {
        JsonObject data = soundData(sound, category, volume, pitch);
        data.addProperty("entity_id", entityId);
        McpTestMod.events().add("sound", data);
    }

    private static JsonObject soundData(String sound, String category, float volume, float pitch) {
        JsonObject data = new JsonObject();
        data.addProperty("sound", sound);
        data.addProperty("category", category);
        data.addProperty("volume", Describe.round(volume));
        data.addProperty("pitch", Describe.round(pitch));
        return data;
    }

    public static void particle(ParticleType<?> type, double x, double y, double z, int count) {
        JsonObject data = new JsonObject();
        data.addProperty("particle", String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(type)));
        data.add("pos", Describe.vec(new Vec3(x, y, z)));
        data.addProperty("count", count);
        McpTestMod.events().add("particle", data);
    }

    /**
     * Records the end of a session, or a failed attempt to start one. Several hooks can observe the same disconnect;
     * only the first one per session is recorded.
     *
     * @param reason the text shown to the player, or null when the client left on its own
     */
    public static void disconnected(String title, String reason) {
        pendingLeave = false;
        if (!inSession && reason == null) {
            return;
        }
        inSession = false;
        JsonObject data = new JsonObject();
        if (title != null) {
            data.addProperty("title", title);
        }
        if (reason != null) {
            data.addProperty("reason", reason);
            data.addProperty("text", reason);
        }
        data.addProperty("by", reason == null ? "client" : "server");
        McpTestMod.events().add("disconnect", data);

        JsonObject last = data.deepCopy();
        last.remove("text");
        last.addProperty("tick", ClientClock.tick());
        last.addProperty("time", Instant.now().toString());
        lastDisconnect = last;
    }

    public static JsonObject lastDisconnect() {
        return lastDisconnect;
    }

    static void endTick() {
        if (!pendingSpawns.isEmpty()) {
            for (Entity entity : pendingSpawns) {
                entityEvent("entity_spawn", entity);
            }
            pendingSpawns.clear();
        }
        if (pendingLeave) {
            disconnected(null, null);
        }
    }

    private static void entityEvent(String type, Entity entity) {
        JsonObject data = new JsonObject();
        data.add("entity", Describe.entity(entity));
        McpTestMod.events().add(type, data);
    }
}
