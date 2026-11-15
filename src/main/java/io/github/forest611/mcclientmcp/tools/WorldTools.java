package io.github.forest611.mcclientmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.ClientClock;
import io.github.forest611.mcclientmcp.McClientMcpMod;
import io.github.forest611.mcclientmcp.mcp.Args;
import io.github.forest611.mcclientmcp.mcp.Schema;
import io.github.forest611.mcclientmcp.mcp.ToolException;
import io.github.forest611.mcclientmcp.mcp.ToolRegistry;
import io.github.forest611.mcclientmcp.op.Op;
import io.github.forest611.mcclientmcp.util.Client;
import io.github.forest611.mcclientmcp.util.Describe;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class WorldTools {
    private static final int MAX_REGION_BLOCKS = 32768;
    private static final int INLINE_MAX_SIZE = 1280;

    private WorldTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("get_block",
                "The block and its state as the client currently sees it (which can differ from the server while a prediction "
                        + "is pending or after packet-only changes). With 'to', returns every non-air block in the box from pos to to.",
                Schema.of()
                        .reqPos("pos", "Block coordinates [x, y, z]")
                        .pos("to", "Opposite corner of a box (optional, at most " + MAX_REGION_BLOCKS + " blocks)"),
                WorldTools::getBlock);

        registry.registerInstant("entities",
                "Entities the client knows about, nearest first, including ones that exist only as packets sent to this player. "
                        + "Each has id, uuid, type, pos, yaw/pitch and distance. Display entities add display: kind (item | block | text), "
                        + "the item / block / text shown, transformation (translation, scale, left_rotation, right_rotation), billboard. "
                        + "Item entities add item. An empty list means the client has no such entity.",
                Schema.of()
                        .numbers("center", 3, "Center of the search [x, y, z] (default: the player)")
                        .num("radius", "Search radius in blocks (default 32)")
                        .add("types", Schema.typed("array", "Entity type ids to include, e.g. [\"minecraft:item_display\"] (default all)"), false)
                        .bool("include_players", "Include other players (default true)")
                        .integer("limit", "Maximum number of entities (default 200)"),
                WorldTools::entities);

        registry.register("screenshot",
                "Saves a PNG of what the client is rendering and returns its absolute path. By default the image is also "
                        + "returned inline (scaled down) so it can be inspected directly. Works while the window is minimized.",
                Schema.of()
                        .bool("hud", "Include the HUD: hotbar, chat, crosshair (default true)")
                        .bool("inline", "Also return the image in the response (default true)")
                        .str("name", "Label added to the file name (letters, digits, '-' and '_')"),
                ScreenshotOp::new);
    }

    private static JsonObject getBlock(Args args) throws ToolException {
        ClientLevel level = Client.level();
        BlockPos pos = args.requirePos("pos");
        BlockPos to = args.pos("to");
        JsonObject result = new JsonObject();
        if (to == null) {
            result.add("pos", Describe.pos(pos));
            result.add("block", Describe.block(level.getBlockState(pos)));
            result.addProperty("loaded", level.isLoaded(pos));
            return result;
        }
        long volume = (long) (Math.abs(to.getX() - pos.getX()) + 1) * (Math.abs(to.getY() - pos.getY()) + 1) * (Math.abs(to.getZ() - pos.getZ()) + 1);
        if (volume > MAX_REGION_BLOCKS) {
            throw ToolException.invalidArgs("the box holds " + volume + " blocks; the limit is " + MAX_REGION_BLOCKS);
        }
        JsonArray blocks = new JsonArray();
        for (BlockPos current : BlockPos.betweenClosed(pos, to)) {
            BlockState state = level.getBlockState(current);
            if (!state.isAir()) {
                JsonObject entry = Describe.block(state);
                JsonObject ordered = new JsonObject();
                ordered.add("pos", Describe.pos(current));
                entry.entrySet().forEach(e -> ordered.add(e.getKey(), e.getValue()));
                blocks.add(ordered);
            }
        }
        result.add("blocks", blocks);
        result.addProperty("volume", volume);
        result.addProperty("loaded", level.isLoaded(pos) && level.isLoaded(to));
        return result;
    }

    private static JsonObject entities(Args args) throws ToolException {
        LocalPlayer player = Client.player();
        ClientLevel level = Client.level();
        double[] centerArg = args.numbers("center", 3);
        Vec3 center = centerArg == null ? player.position() : new Vec3(centerArg[0], centerArg[1], centerArg[2]);
        double radius = args.number("radius", 32);
        Set<String> types = new HashSet<>(args.stringList("types"));
        boolean includePlayers = args.bool("include_players", true);
        int limit = Math.max(1, args.integer("limit", 200));

        List<Entity> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player || (!includePlayers && entity instanceof Player)) {
                continue;
            }
            if (entity.position().distanceToSqr(center) > radius * radius) {
                continue;
            }
            if (!types.isEmpty() && !types.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())) {
                continue;
            }
            found.add(entity);
        }
        found.sort(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(center)));

        JsonArray array = new JsonArray();
        for (Entity entity : found.subList(0, Math.min(limit, found.size()))) {
            JsonObject json = Describe.entity(entity);
            json.addProperty("distance", Describe.round(entity.position().distanceTo(center)));
            array.add(json);
        }
        JsonObject result = new JsonObject();
        result.add("entities", array);
        result.addProperty("count", array.size());
        result.addProperty("truncated", found.size() > limit);
        return result;
    }

    private static final class ScreenshotOp extends Op {
        private static final int FRAMES_TO_WAIT = 2;
        private static final int TIMEOUT_TICKS = 200;

        private final boolean hud;
        private final boolean inline;
        private final String name;
        private long startTick = -1;
        private long startFrame;
        private boolean previousHideGui;
        private boolean captured;
        private volatile JsonObject finished;
        private volatile JsonObject inlineImage;
        private volatile String failure;

        ScreenshotOp(Args args) throws ToolException {
            hud = args.bool("hud", true);
            inline = args.bool("inline", true);
            name = args.string("name", "").replaceAll("[^A-Za-z0-9_-]", "");
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            Minecraft mc = Client.mc();
            if (startTick < 0) {
                startTick = tick;
                startFrame = ClientClock.frame();
                previousHideGui = mc.options.hideGui;
                mc.options.hideGui = !hud;
                return false;
            }
            if (!captured) {
                // Let complete frames render first so the image reflects the HUD setting and everything done so far.
                if (ClientClock.frame() - startFrame >= FRAMES_TO_WAIT) {
                    captured = true;
                    capture(mc);
                    mc.options.hideGui = previousHideGui;
                }
            } else if (failure != null) {
                throw new ToolException("screenshot_failed", failure);
            } else if (finished != null) {
                result = finished;
                image = inlineImage;
                return true;
            }
            if (tick - startTick >= TIMEOUT_TICKS) {
                cancel();
                throw new ToolException("timeout", "the screenshot was not written within " + TIMEOUT_TICKS + " ticks");
            }
            return false;
        }

        private void capture(Minecraft mc) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Path file = McClientMcpMod.dataDir().resolve("screenshots").resolve(stamp + (name.isEmpty() ? "" : "-" + name) + ".png");
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), nativeImage -> Util.ioPool().execute(() -> {
                try (nativeImage) {
                    Files.createDirectories(file.getParent());
                    nativeImage.writeToFile(file);
                    JsonObject json = new JsonObject();
                    json.addProperty("path", file.toString());
                    json.addProperty("width", nativeImage.getWidth());
                    json.addProperty("height", nativeImage.getHeight());
                    json.addProperty("hud", hud);
                    if (inline) {
                        inlineImage = encodeInline(file);
                    }
                    finished = json;
                } catch (IOException | RuntimeException e) {
                    McClientMcpMod.LOGGER.error("Screenshot failed", e);
                    failure = String.valueOf(e);
                }
            }));
        }

        /** A JPEG no larger than {@link #INLINE_MAX_SIZE} on its long side, as an MCP image content block. */
        private static JsonObject encodeInline(Path file) throws IOException {
            BufferedImage source = ImageIO.read(file.toFile());
            double scale = Math.min(1.0, (double) INLINE_MAX_SIZE / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(scaled, "jpg", bytes);
            JsonObject content = new JsonObject();
            content.addProperty("type", "image");
            content.addProperty("data", Base64.getEncoder().encodeToString(bytes.toByteArray()));
            content.addProperty("mimeType", "image/jpeg");
            return content;
        }

        @Override
        public void cancel() {
            if (startTick >= 0 && !captured) {
                Client.mc().options.hideGui = previousHideGui;
            }
        }
    }
}
