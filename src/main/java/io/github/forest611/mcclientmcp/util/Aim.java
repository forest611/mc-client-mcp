package io.github.forest611.mcclientmcp.util;

import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.mcp.Args;
import io.github.forest611.mcclientmcp.mcp.Schema;
import io.github.forest611.mcclientmcp.mcp.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Turns the player's view toward a target and reads back what the crosshair then hits. */
public final class Aim {
    private static final double EDGE_MARGIN = 0.001;
    private static final AABB FULL_BLOCK = new AABB(0, 0, 0, 1, 1, 1);

    private Aim() {
    }

    public static Schema describeBlockTarget(Schema schema) {
        return schema
                .reqPos("pos", "Block coordinates [x, y, z]")
                .enumStr("face", "Face to aim at. Omit to aim at the block's center and take whichever face is hit.",
                        "up", "down", "north", "south", "east", "west")
                .numbers("face_pos", 2, "Position within the face as [u, v], each 0..1 across the block cell (default [0.5, 0.5]). "
                        + "up/down: u = x, v = z. north/south: u = x, v = y. east/west: u = z, v = y. "
                        + "E.g. face up with [0.9, 0.9] is the top surface near the +x/+z corner.")
                .enumStr("on_mismatch", "What to do when the crosshair ends up on something other than the requested block and face "
                        + "(e.g. a torch in the way): 'error' (default) does nothing and reports what was hit; "
                        + "'use_hit' acts on the block actually hit.", "error", "use_hit");
    }

    public static void setRotation(LocalPlayer player, float yaw, float pitch) {
        yaw = Mth.wrapDegrees(yaw);
        pitch = Mth.clamp(pitch, -90.0F, 90.0F);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
    }

    public static void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        setRotation(player, yaw, pitch);
    }

    /** Recomputes the crosshair target exactly as the game does each tick. */
    public static HitResult pick() {
        Minecraft mc = Minecraft.getInstance();
        mc.gameRenderer.pick(1.0F);
        return mc.hitResult;
    }

    private static AABB outline(ClientLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        return shape.isEmpty() ? null : shape.bounds();
    }

    public static Vec3 blockCenter(ClientLevel level, BlockPos pos) {
        AABB box = outline(level, pos);
        Vec3 center = (box == null ? FULL_BLOCK : box).getCenter();
        return new Vec3(pos.getX() + center.x, pos.getY() + center.y, pos.getZ() + center.z);
    }

    /**
     * The point on a block face for cell-relative coordinates (u, v). The point lies on the block's actual outline
     * (so the top of farmland is at 15/16) and is kept just inside its edges.
     */
    public static Vec3 facePoint(ClientLevel level, BlockPos pos, Direction face, double u, double v) {
        AABB outline = outline(level, pos);
        AABB box = outline == null ? FULL_BLOCK : outline;
        double x;
        double y;
        double z;
        switch (face.getAxis()) {
            case Y -> {
                x = within(u, box.minX, box.maxX);
                z = within(v, box.minZ, box.maxZ);
                y = face == Direction.UP ? box.maxY : box.minY;
            }
            case Z -> {
                x = within(u, box.minX, box.maxX);
                y = within(v, box.minY, box.maxY);
                z = face == Direction.SOUTH ? box.maxZ : box.minZ;
            }
            default -> {
                z = within(u, box.minZ, box.maxZ);
                y = within(v, box.minY, box.maxY);
                x = face == Direction.EAST ? box.maxX : box.minX;
            }
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    private static double within(double value, double min, double max) {
        return Mth.clamp(value, min + EDGE_MARGIN, max - EDGE_MARGIN);
    }

    public record BlockAim(BlockHitResult hit, boolean mismatch) {
    }

    /**
     * Aims at the block described by the arguments and verifies the crosshair. Fails with {@code no_target},
     * {@code out_of_reach} or {@code target_mismatch} unless the requested block (and face) is what a click would hit.
     */
    public static BlockAim atBlock(Args args) throws ToolException {
        LocalPlayer player = Client.player();
        ClientLevel level = Client.level();
        BlockPos pos = args.requirePos("pos");
        Direction face = args.face("face");
        double[] facePos = args.numbers("face_pos", 2);
        boolean useHit = "use_hit".equals(args.string("on_mismatch", "error"));
        return atBlock(player, level, pos, face, facePos == null ? 0.5 : facePos[0], facePos == null ? 0.5 : facePos[1], useHit);
    }

    public static BlockAim atBlock(LocalPlayer player, ClientLevel level, BlockPos pos, Direction face, double u, double v, boolean useHit)
            throws ToolException {
        if (outline(level, pos) == null) {
            JsonObject details = new JsonObject();
            details.add("pos", Describe.pos(pos));
            details.add("block", Describe.block(level.getBlockState(pos)));
            throw new ToolException("no_target", "the block at pos has no outline to click (air or fluid)", details);
        }
        Vec3 target = face == null ? blockCenter(level, pos) : facePoint(level, pos, face, u, v);
        lookAt(player, target);
        HitResult hit = pick();
        boolean isBlock = hit instanceof BlockHitResult && hit.getType() == HitResult.Type.BLOCK;
        if (isBlock) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            if (blockHit.getBlockPos().equals(pos) && (face == null || blockHit.getDirection() == face)) {
                return new BlockAim(blockHit, false);
            }
            if (useHit) {
                return new BlockAim(blockHit, true);
            }
        }
        JsonObject details = new JsonObject();
        JsonObject expected = new JsonObject();
        expected.add("pos", Describe.pos(pos));
        if (face != null) {
            expected.addProperty("face", face.getName());
        }
        expected.add("point", Describe.vec(target));
        details.add("expected", expected);
        details.add("hit", Describe.hit(hit));
        details.add("eye", Describe.vec(player.getEyePosition()));
        double distance = player.getEyePosition().distanceTo(target);
        details.addProperty("distance", Describe.round(distance));
        details.addProperty("reach", Describe.round(player.blockInteractionRange()));
        if (!isBlock && distance > player.blockInteractionRange()) {
            throw new ToolException("out_of_reach", "the target point is farther than the player's block reach", details);
        }
        throw new ToolException("target_mismatch",
                "the crosshair does not land on the requested block face; see details.hit for what is in the way", details);
    }
}
