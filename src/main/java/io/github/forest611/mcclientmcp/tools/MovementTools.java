package io.github.forest611.mcclientmcp.tools;

import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.input.ScriptedInput;
import io.github.forest611.mcclientmcp.mcp.Args;
import io.github.forest611.mcclientmcp.mcp.Schema;
import io.github.forest611.mcclientmcp.mcp.ToolException;
import io.github.forest611.mcclientmcp.mcp.ToolRegistry;
import io.github.forest611.mcclientmcp.op.Op;
import io.github.forest611.mcclientmcp.util.Aim;
import io.github.forest611.mcclientmcp.util.Client;
import io.github.forest611.mcclientmcp.util.Describe;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

final class MovementTools {
    /**
     * How far the player still travels after input stops, as a multiple of the current speed: drag keeps 54.6% of the
     * horizontal speed per tick on ordinary ground and 91% in the air.
     */
    private static final double GROUND_COAST = 0.546 / (1 - 0.546);
    private static final double AIR_COAST = 0.91 / (1 - 0.91);
    /** Speed gained per tick at full input. */
    private static final double GROUND_ACCELERATION = 0.098;
    private static final double AIR_ACCELERATION = 0.02;
    /** Input strength used close to a drop so the player leaves the edge slowly enough to land where aimed. */
    private static final float EDGE_APPROACH_IMPULSE = 0.3F;
    private static final double EDGE_APPROACH_DISTANCE = 1.5;

    private MovementTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("look_at",
                "Turns the player's view. Give 'pos' (optionally with 'face' and 'face_pos') to look at a block, "
                        + "'point' for exact world coordinates, or 'yaw' and 'pitch' directly "
                        + "(yaw 0 = south/+z, 90 = west/-x; pitch 90 = straight down). Returns the new view and what the crosshair hits.",
                Schema.of()
                        .pos("pos", "Block to look at")
                        .enumStr("face", "Face of that block (default: its center)", "up", "down", "north", "south", "east", "west")
                        .numbers("face_pos", 2, "Position within the face as [u, v] 0..1 (see use_on_block)")
                        .numbers("point", 3, "World coordinates [x, y, z] to look at")
                        .num("yaw", "Yaw in degrees")
                        .num("pitch", "Pitch in degrees"),
                MovementTools::lookAt);

        registry.register("move_to",
                "Walks in a straight line to x/z by holding the movement input, and returns when the player has stopped there. "
                        + "No path finding: meant for short, unobstructed distances (use the server's tp for long ones). "
                        + "Fails with stuck if blocked, or timeout.",
                Schema.of()
                        .reqNum("x", "Target x")
                        .reqNum("z", "Target z")
                        .num("tolerance", "Accepted horizontal distance from the target (default 0.2)")
                        .bool("sprint", "Sprint while moving (default false)")
                        .bool("keep_rotation", "Strafe without turning the view (default false: face the target)")
                        .integer("timeout_ticks", "Give up after this many ticks (default 200)"),
                MoveToOp::new);

        registry.register("jump",
                "Presses jump for one tick. By default returns after landing, with the peak height reached.",
                Schema.of()
                        .bool("wait_landing", "Wait until back on the ground (default true)")
                        .integer("timeout_ticks", "Give up waiting for the landing after this many ticks (default 60)"),
                JumpOp::new);

        registry.register("set_sneak",
                "Holds or releases sneak; the state persists until changed. Returns one tick later, once the server has been told.",
                Schema.of().add("enabled", Schema.typed("boolean", "true to sneak, false to stand"), true),
                SneakOp::new);

        registry.registerInstant("set_flying",
                "Starts or stops flying. Starting fails with cannot_fly unless the player is allowed to (creative/spectator).",
                Schema.of().add("enabled", Schema.typed("boolean", "true to fly, false to stop flying"), true),
                args -> {
                    boolean enabled = args.bool("enabled", true);
                    LocalPlayer player = Client.player();
                    if (enabled && !player.getAbilities().mayfly) {
                        throw new ToolException("cannot_fly", "the player is not allowed to fly in this game mode");
                    }
                    if (player.getAbilities().flying != enabled) {
                        player.getAbilities().flying = enabled;
                        player.onUpdateAbilities();
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("flying", enabled);
                    return result;
                });

        registry.register("fall",
                "A real fall: stops flying if needed, walks off the current footing toward x/z, and waits for the landing. "
                        + "If the player is already airborne, just waits for the landing. "
                        + "Returns the height fallen and the block landed on.",
                Schema.of()
                        .num("x", "x to walk and steer toward (needed when standing on something)")
                        .num("z", "z to walk and steer toward")
                        .integer("timeout_ticks", "Give up after this many ticks (default 200)"),
                FallOp::new);
    }

    private static JsonObject lookAt(Args args) throws ToolException {
        LocalPlayer player = Client.player();
        ClientLevel level = Client.level();
        BlockPos pos = args.pos("pos");
        double[] point = args.numbers("point", 3);
        if (pos != null) {
            Direction face = args.face("face");
            double[] facePos = args.numbers("face_pos", 2);
            Aim.lookAt(player, face == null ? Aim.blockCenter(level, pos)
                    : Aim.facePoint(level, pos, face, facePos == null ? 0.5 : facePos[0], facePos == null ? 0.5 : facePos[1]));
        } else if (point != null) {
            Aim.lookAt(player, new Vec3(point[0], point[1], point[2]));
        } else if (args.has("yaw") || args.has("pitch")) {
            Aim.setRotation(player, (float) args.number("yaw", player.getYRot()), (float) args.number("pitch", player.getXRot()));
        } else {
            throw ToolException.invalidArgs("give 'pos', 'point', or 'yaw'/'pitch'");
        }
        JsonObject result = new JsonObject();
        result.addProperty("yaw", Describe.round(player.getYRot()));
        result.addProperty("pitch", Describe.round(player.getXRot()));
        result.add("hit", Describe.hit(Aim.pick()));
        return result;
    }

    private static double horizontalSpeed(LocalPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    /**
     * Pushes the movement input toward a point. Each tick it aims for the speed at which the player would coast to a
     * stop exactly on the target, accelerating or braking to get there, so the walk ends on the spot instead of
     * overshooting. This also steers (weakly, as in the game) while airborne.
     *
     * @param maxImpulse upper bound of the input strength, 0..1
     */
    private static void steer(LocalPlayer player, double x, double z, boolean faceTarget, boolean sprint, float maxImpulse) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1e-4) {
            ScriptedInput.stopMove();
            return;
        }
        if (faceTarget) {
            Aim.setRotation(player, (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F, player.getXRot());
        }
        Vec3 velocity = player.getDeltaMovement();
        double approachSpeed = (velocity.x * dx + velocity.z * dz) / distance;
        double coast = player.onGround() ? GROUND_COAST : AIR_COAST;
        double acceleration = player.onGround() ? GROUND_ACCELERATION : AIR_ACCELERATION;
        double impulse = Math.max(-maxImpulse, Math.min(maxImpulse, (distance / coast - approachSpeed) / acceleration));

        double yaw = Math.toRadians(player.getYRot());
        double forward = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / distance;
        double left = (Math.cos(yaw) * dx + Math.sin(yaw) * dz) / distance;
        ScriptedInput.setMove((float) (forward * impulse), (float) (left * impulse), sprint);
    }

    private static final class MoveToOp extends Op {
        private static final int STUCK_TICKS = 30;

        private final double x;
        private final double z;
        private final double tolerance;
        private final boolean sprint;
        private final boolean faceTarget;
        private final int timeoutTicks;
        private long startTick = -1;
        private Vec3 lastPos;
        private int stillTicks;

        MoveToOp(Args args) throws ToolException {
            x = args.requireNumber("x");
            z = args.requireNumber("z");
            tolerance = Math.max(0.02, args.number("tolerance", 0.2));
            sprint = args.bool("sprint", false);
            faceTarget = !args.bool("keep_rotation", false);
            timeoutTicks = args.integer("timeout_ticks", 200);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            LocalPlayer player = Client.player();
            if (startTick < 0) {
                startTick = tick;
                result.add("from", Describe.vec(player.position()));
            }
            double dx = x - player.getX();
            double dz = z - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance <= tolerance && horizontalSpeed(player) < 0.02) {
                ScriptedInput.stopMove();
                result.add("pos", Describe.vec(player.position()));
                result.addProperty("distance", Describe.round(distance));
                result.addProperty("ticks", tick - startTick);
                return true;
            }
            stillTicks = lastPos != null && lastPos.distanceToSqr(player.position()) < 1e-6 ? stillTicks + 1 : 0;
            lastPos = player.position();
            if (stillTicks >= STUCK_TICKS || tick - startTick >= timeoutTicks) {
                ScriptedInput.stopMove();
                JsonObject details = new JsonObject();
                details.add("pos", Describe.vec(player.position()));
                details.addProperty("distance", Describe.round(distance));
                if (stillTicks >= STUCK_TICKS) {
                    throw new ToolException("stuck", "the player stopped moving before reaching the target", details);
                }
                throw new ToolException("timeout", "target not reached within " + timeoutTicks + " ticks", details);
            }
            steer(player, x, z, faceTarget, sprint, 1.0F);
            return false;
        }

        @Override
        public void cancel() {
            ScriptedInput.stopMove();
        }
    }

    private static final class JumpOp extends Op {
        private final boolean waitLanding;
        private final int timeoutTicks;
        private long startTick = -1;
        private double startY;
        private double peakY;
        private boolean leftGround;

        JumpOp(Args args) throws ToolException {
            waitLanding = args.bool("wait_landing", true);
            timeoutTicks = args.integer("timeout_ticks", 60);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            LocalPlayer player = Client.player();
            if (startTick < 0) {
                startTick = tick;
                startY = player.getY();
                peakY = startY;
                result.addProperty("was_on_ground", player.onGround());
                ScriptedInput.setJump(true);
                return false;
            }
            ScriptedInput.setJump(false);
            actionDone();
            peakY = Math.max(peakY, player.getY());
            leftGround |= !player.onGround();
            boolean landed = leftGround && player.onGround();
            if (!waitLanding || landed || tick - startTick >= timeoutTicks) {
                result.addProperty("landed", landed);
                result.addProperty("peak_height", Describe.round(peakY - startY));
                result.add("pos", Describe.vec(player.position()));
                result.addProperty("ticks", tick - startTick);
                return true;
            }
            return false;
        }

        @Override
        public void cancel() {
            ScriptedInput.setJump(false);
        }
    }

    private static final class SneakOp extends Op {
        private final boolean enabled;
        private boolean applied;

        SneakOp(Args args) throws ToolException {
            enabled = args.bool("enabled", true);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            if (!applied) {
                LocalPlayer player = Client.player();
                ScriptedInput.setSneak(enabled);
                // Interactions later in this same tick read the sneak state from the input object, so update it now
                // instead of waiting for the player's own tick.
                Input keys = player.input.keyPresses;
                player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), enabled, keys.sprint());
                applied = true;
                return false;
            }
            result.addProperty("sneaking", enabled);
            return true;
        }
    }

    private static final class FallOp extends Op {
        private final Double x;
        private final Double z;
        private final int timeoutTicks;
        private long startTick = -1;
        private double startY;
        private double maxFallDistance;
        private boolean airborne;

        FallOp(Args args) throws ToolException {
            if (args.has("x") != args.has("z")) {
                throw ToolException.invalidArgs("give both 'x' and 'z', or neither");
            }
            x = args.has("x") ? args.requireNumber("x") : null;
            z = args.has("z") ? args.requireNumber("z") : null;
            timeoutTicks = args.integer("timeout_ticks", 200);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            LocalPlayer player = Client.player();
            if (startTick < 0) {
                startTick = tick;
                startY = player.getY();
                if (player.getAbilities().flying) {
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    result.addProperty("stopped_flying", true);
                }
                if (player.onGround()) {
                    if (x == null) {
                        throw ToolException.invalidArgs("the player is standing on something: give 'x' and 'z' to walk off toward");
                    }
                    if (ScriptedInput.isSneaking()) {
                        throw new ToolException("sneaking", "sneaking keeps the player from walking off edges; call set_sneak with enabled=false first");
                    }
                }
            }
            maxFallDistance = Math.max(maxFallDistance, player.fallDistance);
            boolean supported = player.onGround() || player.isInWater();
            if (!airborne && !supported) {
                airborne = true;
                result.add("left_ground_at", Describe.vec(player.position()));
            }
            if (airborne && supported) {
                ScriptedInput.stopMove();
                result.addProperty("start_y", Describe.round(startY));
                result.addProperty("end_y", Describe.round(player.getY()));
                result.addProperty("fall_height", Describe.round(startY - player.getY()));
                result.addProperty("fall_distance", Describe.round(maxFallDistance));
                result.addProperty("in_water", player.isInWater());
                result.add("pos", Describe.vec(player.position()));
                BlockPos below = player.getOnPos();
                result.add("landed_on_pos", Describe.pos(below));
                result.add("landed_on", Describe.block(Client.level().getBlockState(below)));
                result.addProperty("ticks", tick - startTick);
                return true;
            }
            if (tick - startTick >= timeoutTicks) {
                ScriptedInput.stopMove();
                JsonObject details = new JsonObject();
                details.add("pos", Describe.vec(player.position()));
                details.addProperty("airborne", airborne);
                throw new ToolException("timeout", airborne ? "did not land within " + timeoutTicks + " ticks"
                        : "did not leave the ground within " + timeoutTicks + " ticks; is there an edge between the player and x/z?", details);
            }
            if (x != null) {
                double dx = x - player.getX();
                double dz = z - player.getZ();
                boolean nearTarget = dx * dx + dz * dz < EDGE_APPROACH_DISTANCE * EDGE_APPROACH_DISTANCE;
                steer(player, x, z, true, false, !airborne && nearTarget ? EDGE_APPROACH_IMPULSE : 1.0F);
            }
            return false;
        }

        @Override
        public void cancel() {
            ScriptedInput.stopMove();
        }
    }
}
