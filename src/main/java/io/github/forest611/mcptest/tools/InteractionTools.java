package io.github.forest611.mcptest.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.forest611.mcptest.event.EventHooks;
import io.github.forest611.mcptest.event.PredictionTracker;
import io.github.forest611.mcptest.input.ScriptedInput;
import io.github.forest611.mcptest.mcp.Args;
import io.github.forest611.mcptest.mcp.Schema;
import io.github.forest611.mcptest.mcp.ToolException;
import io.github.forest611.mcptest.mcp.ToolRegistry;
import io.github.forest611.mcptest.mixin.BlockStatePredictionHandlerAccessor;
import io.github.forest611.mcptest.mixin.ClientLevelAccessor;
import io.github.forest611.mcptest.mixin.ServerVerifiedStateAccessor;
import io.github.forest611.mcptest.op.Op;
import io.github.forest611.mcptest.util.Aim;
import io.github.forest611.mcptest.util.Client;
import io.github.forest611.mcptest.util.Describe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InteractionTools {
    private static final String ACK_NOTE = "After acting, waits for the server's acknowledgement and reports whether the server "
            + "accepted the block change the client predicted or undid it ('reverted'). ";

    private InteractionTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("raycast",
                "Reports what a click would hit. With 'pos', first aims at that block (and face) and tells whether the crosshair "
                        + "really lands on it; without, uses the current view. Never clicks.",
                Schema.of()
                        .pos("pos", "Block to aim at first (optional)")
                        .enumStr("face", "Face of that block", "up", "down", "north", "south", "east", "west")
                        .numbers("face_pos", 2, "Position within the face as [u, v] 0..1 (see use_on_block)"),
                InteractionTools::raycast);

        registry.register("use_on_block",
                "Right-clicks a block with the held item: plant seeds, apply an item, flip a lever, light with flint and steel. "
                        + "Turns the view to the given face position, checks that the crosshair lands on it, then clicks through the "
                        + "normal client interaction code. " + ACK_NOTE
                        + "Result: hit, per-hand interaction results, each predicted block change with the state the server "
                        + "reported for it, and outcome (applied | reverted | changed | no_change | superseded | no_prediction | unacknowledged). "
                        + "superseded: a later action changed the same block and the server answered both at once, so judge by that action.",
                ackOptions(Aim.describeBlockTarget(Schema.of())
                        .enumStr("hand", "Hand to use (default auto: main hand, then off hand if the main hand does nothing, like the game)",
                                "auto", "main", "off")),
                UseOnBlockOp::new);

        registry.register("place_block",
                "Places the held block at 'pos' by right-clicking an adjacent block. " + ACK_NOTE
                        + "Result outcome: placed | reverted (the server undid the placement) | superseded | not_placed | unacknowledged.",
                ackOptions(Schema.of()
                        .reqPos("pos", "Cell where the block should appear [x, y, z]")
                        .enumStr("against", "Which neighbor to click, as its direction from 'pos' "
                                + "(default: the first clickable one of down, north, south, west, east, up)",
                                "up", "down", "north", "south", "east", "west")
                        .numbers("face_pos", 2, "Position within the clicked face as [u, v] 0..1 (default center; see use_on_block)")
                        .enumStr("hand", "Hand holding the block (default auto)", "auto", "main", "off")),
                PlaceBlockOp::new);

        registry.register("attack_block",
                "Left-clicks a block once: a single short press. In creative this breaks it; in survival it only starts mining "
                        + "(blocks that break instantly do break). " + ACK_NOTE
                        + "Result outcome: broken | reverted | no_change | changed | superseded | not_broken | unacknowledged.",
                ackOptions(Aim.describeBlockTarget(Schema.of())),
                args -> new BreakBlockOp(args, false));

        registry.register("break_block",
                "Breaks a block: instantly in creative, otherwise by holding the attack until it breaks. " + ACK_NOTE
                        + "Result: the face hit, ticks held, client_broke, server_block, and outcome "
                        + "(broken | reverted: the server put the block back | no_change: the block existed only as an unconfirmed "
                        + "client prediction, so there was nothing to break on the server | changed | superseded | not_broken | unacknowledged).",
                ackOptions(Aim.describeBlockTarget(Schema.of())
                        .integer("timeout_ticks", "Stop mining after this many ticks (default 600)")),
                args -> new BreakBlockOp(args, true));

        registry.register("use_item",
                "Right-clicks with the held item without targeting a block (use in the air).",
                ackOptions(Schema.of()
                        .enumStr("hand", "Hand to use (default auto: main hand, then off hand)", "auto", "main", "off")
                        .integer("hold_ticks", "Keep the button held this many ticks for items with a use time (bow, food); default 0")),
                UseItemOp::new);

        registry.registerInstant("use_on_entity",
                "Right-clicks an entity, after aiming at it and checking the crosshair lands on it. "
                        + "Display and item entities have no hitbox and cannot be clicked.",
                Schema.of()
                        .reqInteger("entity_id", "Network id of the entity (see entities)")
                        .enumStr("hand", "Hand to use (default auto)", "auto", "main", "off"),
                args -> useOnEntity(args, false));

        registry.registerInstant("attack_entity",
                "Left-clicks (attacks) an entity, after aiming at it and checking the crosshair lands on it.",
                Schema.of().reqInteger("entity_id", "Network id of the entity (see entities)"),
                args -> useOnEntity(args, true));
    }

    private static Schema ackOptions(Schema schema) {
        return schema
                .bool("wait_ack", "Wait for the server's acknowledgement before returning (default true)")
                .integer("ack_timeout_ticks", "Stop waiting for the acknowledgement after this many ticks (default 40)");
    }

    private static JsonObject raycast(Args args) throws ToolException {
        LocalPlayer player = Client.player();
        ClientLevel level = Client.level();
        BlockPos pos = args.pos("pos");
        Direction face = args.face("face");
        if (pos != null) {
            double[] facePos = args.numbers("face_pos", 2);
            Aim.lookAt(player, face == null ? Aim.blockCenter(level, pos)
                    : Aim.facePoint(level, pos, face, facePos == null ? 0.5 : facePos[0], facePos == null ? 0.5 : facePos[1]));
        }
        HitResult hit = Aim.pick();
        JsonObject result = new JsonObject();
        result.add("hit", Describe.hit(hit));
        if (pos != null) {
            boolean matches = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                    && blockHit.getBlockPos().equals(pos) && (face == null || blockHit.getDirection() == face);
            result.addProperty("matches", matches);
        }
        result.addProperty("yaw", Describe.round(player.getYRot()));
        result.addProperty("pitch", Describe.round(player.getXRot()));
        result.add("eye", Describe.vec(player.getEyePosition()));
        result.addProperty("block_reach", Describe.round(player.blockInteractionRange()));
        result.addProperty("entity_reach", Describe.round(player.entityInteractionRange()));
        return result;
    }

    private static List<InteractionHand> hands(Args args) throws ToolException {
        return switch (args.string("hand", "auto")) {
            case "auto" -> List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND);
            case "main" -> List.of(InteractionHand.MAIN_HAND);
            case "off" -> List.of(InteractionHand.OFF_HAND);
            default -> throw ToolException.invalidArgs("'hand' must be auto, main or off");
        };
    }

    private static String handName(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? "main" : "off";
    }

    private static String resultName(InteractionResult result) {
        if (result instanceof InteractionResult.Success success) {
            return switch (success.swingSource()) {
                case CLIENT -> "success";
                case SERVER -> "success_server";
                case NONE -> "consume";
            };
        } else if (result instanceof InteractionResult.Fail) {
            return "fail";
        } else if (result instanceof InteractionResult.TryEmptyHandInteraction) {
            return "try_with_empty_hand";
        }
        return "pass";
    }

    private static JsonObject handResult(InteractionHand hand, ItemStack stack, String action, InteractionResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("hand", handName(hand));
        json.add("item", Describe.item(stack));
        json.addProperty("action", action);
        json.addProperty("result", resultName(result));
        return json;
    }

    /** The click sequence of the game's own use-key handling, for a block under the crosshair. */
    private static JsonArray clickBlock(BlockHitResult hit, List<InteractionHand> hands) throws ToolException {
        Minecraft mc = Client.mc();
        LocalPlayer player = Client.player();
        MultiPlayerGameMode gameMode = Client.gameMode();
        if (player.isHandsBusy()) {
            throw new ToolException("hands_busy", "the player cannot use items right now (e.g. rowing a boat)");
        }
        JsonArray results = new JsonArray();
        for (InteractionHand hand : hands) {
            ItemStack stack = player.getItemInHand(hand);
            ItemStack shown = stack.copy();
            int count = stack.getCount();
            InteractionResult result = gameMode.useItemOn(player, hand, hit);
            results.add(handResult(hand, shown, "use_on_block", result));
            if (result instanceof InteractionResult.Success success) {
                if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                    player.swing(hand);
                    if (!stack.isEmpty() && (stack.getCount() != count || player.hasInfiniteMaterials())) {
                        mc.gameRenderer.itemInHandRenderer.itemUsed(hand);
                    }
                }
                break;
            }
            if (result instanceof InteractionResult.Fail) {
                break;
            }
            if (!stack.isEmpty()) {
                InteractionResult used = gameMode.useItem(player, hand);
                results.add(handResult(hand, shown, "use_item", used));
                if (used instanceof InteractionResult.Success success) {
                    if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                        player.swing(hand);
                    }
                    mc.gameRenderer.itemInHandRenderer.itemUsed(hand);
                    break;
                }
            }
        }
        return results;
    }

    /** A block the client changed on its own during an action, and what the server said about it afterwards. */
    private static final class Prediction {
        /** What the client showed before the action. */
        final BlockState before;
        /**
         * What the server last said the block was before the action. Differs from {@link #before} when the client
         * was already showing an unconfirmed prediction there.
         */
        final BlockState serverBefore;
        BlockState predicted;
        /** Last state the server reported between the action and its acknowledgement; null if it reported nothing. */
        BlockState reported;
        /** The server reported the predicted state at least once. */
        boolean confirmed;
        /** The server reported some other state at least once. */
        boolean contradicted;
        /** Sequence number of the first later action that predicted this block again, or -1. */
        int supersededBy = -1;

        Prediction(BlockState before, BlockState serverBefore) {
            this.before = before;
            this.serverBefore = serverBefore;
        }

        /** With no report the server left the block as it was, and the client restores that on acknowledgement. */
        BlockState serverState() {
            return reported != null ? reported : serverBefore;
        }

        /**
         * @param ackSequence the sequence number carried by the acknowledgement that covered this action
         */
        String status(int ackSequence) {
            if (confirmed) {
                // The server already had the predicted state and never showed another: the action changed nothing
                // there (e.g. breaking a block that only ever existed as an unconfirmed prediction on the client).
                return serverBefore != predicted || contradicted ? "applied" : "no_change";
            } else if (supersededBy >= 0 && ackSequence >= supersededBy) {
                // The same acknowledgement covers a later action on this block, so the server's reports cannot be
                // attributed to one or the other; judge by the later action.
                return "superseded";
            }
            return serverState() == serverBefore ? "reverted" : "changed";
        }
    }

    /**
     * An action whose block changes the client predicts and the server later acknowledges. Subclasses perform the
     * action between {@link #beginAction} and {@link #endAction}; {@link #settle} then waits for the acknowledgement.
     *
     * <p>The outcome is judged from what the server reported for each predicted block between the action and its
     * acknowledgement, not from the client's block state, which a later action may already have changed again.
     */
    private abstract static class PredictedOp extends Op implements EventHooks.BlockListener, PredictionTracker.AckListener {
        private final boolean waitAck;
        private final int ackTimeoutTicks;
        private final Map<BlockPos, Prediction> predictions = new LinkedHashMap<>();
        protected ClientLevel level;
        private boolean acting;
        private int sequenceBefore;
        private int sequenceAfter;
        private int ackSequence = -1;
        private long ackDeadline;

        PredictedOp(Args args) throws ToolException {
            waitAck = args.bool("wait_ack", true);
            ackTimeoutTicks = args.integer("ack_timeout_ticks", 40);
        }

        private static int sequence(ClientLevel level) {
            return ((ClientLevelAccessor) level).mcptest$getPredictionHandler().currentSequence();
        }

        /**
         * What the server last reported for a block that has a prediction pending, which the client keeps aside until
         * the acknowledgement. Called right after the client recorded its own change at {@code pos}.
         */
        private BlockState serverState(BlockPos pos, BlockState fallback) {
            Object pending = ((BlockStatePredictionHandlerAccessor) ((ClientLevelAccessor) level).mcptest$getPredictionHandler())
                    .mcptest$getServerVerifiedStates().get(pos.asLong());
            return pending == null ? fallback : ((ServerVerifiedStateAccessor) pending).mcptest$getBlockState();
        }

        protected void beginAction() throws ToolException {
            level = Client.level();
            sequenceBefore = sequence(level);
            acting = true;
            EventHooks.addBlockListener(this);
            PredictionTracker.addListener(this);
        }

        protected void endAction(long tick) {
            endPrediction(tick);
            actionDone();
        }

        /** Marks the point after which the server's reports and acknowledgement are taken as answers to this action. */
        protected void endPrediction(long tick) {
            acting = false;
            sequenceAfter = sequence(level);
            ackDeadline = tick + ackTimeoutTicks;
        }

        private void stopListening() {
            EventHooks.removeBlockListener(this);
            PredictionTracker.removeListener(this);
        }

        @Override
        public void onBlock(EventHooks.BlockChange change) {
            boolean isPrediction = EventHooks.SOURCE_PREDICTION.equals(change.source());
            if (acting) {
                if (isPrediction) {
                    predictions.computeIfAbsent(change.pos(), pos -> new Prediction(change.before(), serverState(pos, change.before())))
                            .predicted = change.after();
                }
                return;
            }
            Prediction prediction = predictions.get(change.pos());
            if (prediction == null) {
                return;
            }
            if (isPrediction) {
                if (prediction.supersededBy < 0) {
                    prediction.supersededBy = sequence(level);
                }
            } else if (!EventHooks.SOURCE_CLIENT.equals(change.source())) {
                prediction.reported = change.after();
                prediction.confirmed |= change.after() == prediction.predicted;
                prediction.contradicted |= change.after() != prediction.predicted;
            }
        }

        @Override
        public void onAck(ClientLevel ackedLevel, int sequence) {
            if (!acting && ackedLevel == level && sequence >= sequenceAfter) {
                // Everything the server sent in answer to this action has arrived; later reports belong to later actions.
                ackSequence = sequence;
                stopListening();
            }
        }

        protected Prediction prediction(BlockPos pos) {
            return predictions.get(pos);
        }

        protected String status(Prediction prediction) {
            return prediction.status(ackSequence);
        }

        /** @return true once the server acknowledged the action (or waiting is over); {@code acked} is then set in the result */
        protected boolean settle(long tick) {
            if (result.has("acked")) {
                return true;
            }
            boolean sent = sequenceAfter != sequenceBefore;
            boolean acked = ackSequence >= 0;
            if (!acked && sent && waitAck && Client.mc().level == level) {
                if (tick < ackDeadline) {
                    return false;
                }
                result.addProperty("ack_timed_out", true);
            }
            stopListening();
            result.addProperty("acked", acked);
            return true;
        }

        protected boolean acked() {
            return result.has("acked") && result.get("acked").getAsBoolean();
        }

        /** Lists the predicted changes with what the server reported for each, and derives the overall outcome. */
        protected String describePredictions() {
            JsonArray array = new JsonArray();
            boolean reverted = false;
            boolean changed = false;
            boolean superseded = false;
            boolean unchanged = true;
            for (Map.Entry<BlockPos, Prediction> entry : predictions.entrySet()) {
                Prediction prediction = entry.getValue();
                String status = acked() ? status(prediction) : "unacknowledged";
                JsonObject json = new JsonObject();
                json.add("pos", Describe.pos(entry.getKey()));
                json.add("old", Describe.block(prediction.before));
                if (prediction.serverBefore != prediction.before) {
                    json.add("server_old", Describe.block(prediction.serverBefore));
                }
                json.add("new", Describe.block(prediction.predicted));
                if (acked()) {
                    json.add("server", Describe.block(prediction.serverState()));
                }
                json.addProperty("status", status);
                array.add(json);
                reverted |= status.equals("reverted");
                changed |= status.equals("changed");
                superseded |= status.equals("superseded");
                unchanged &= status.equals("no_change");
            }
            result.add("predicted", array);
            if (predictions.isEmpty()) {
                return "no_prediction";
            } else if (!acked()) {
                return "unacknowledged";
            }
            return reverted ? "reverted" : changed ? "changed" : superseded ? "superseded" : unchanged ? "no_change" : "applied";
        }

        @Override
        public void cancel() {
            stopListening();
        }
    }

    private static final class UseOnBlockOp extends PredictedOp {
        private final Args args;
        private final List<InteractionHand> hands;
        private boolean acted;

        UseOnBlockOp(Args args) throws ToolException {
            super(args);
            this.args = args;
            this.hands = hands(args);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            if (!acted) {
                acted = true;
                Aim.BlockAim aim = Aim.atBlock(args);
                result.add("hit", Describe.hit(aim.hit()));
                if (aim.mismatch()) {
                    result.addProperty("mismatch", true);
                }
                beginAction();
                try {
                    result.add("hands", clickBlock(aim.hit(), hands));
                } finally {
                    endAction(tick);
                }
            }
            if (!settle(tick)) {
                return false;
            }
            result.addProperty("outcome", describePredictions());
            return true;
        }
    }

    private static final class PlaceBlockOp extends PredictedOp {
        private static final List<Direction> NEIGHBOR_ORDER =
                List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP);

        private final BlockPos pos;
        private final Direction against;
        private final double[] facePos;
        private final List<InteractionHand> hands;
        private BlockState original;
        private boolean acted;

        PlaceBlockOp(Args args) throws ToolException {
            super(args);
            pos = args.requirePos("pos");
            against = args.face("against");
            facePos = args.numbers("face_pos", 2);
            hands = hands(args);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            if (!acted) {
                acted = true;
                BlockHitResult hit = aimAtNeighbor();
                original = Client.level().getBlockState(pos);
                result.add("hit", Describe.hit(hit));
                beginAction();
                try {
                    result.add("hands", clickBlock(hit, hands));
                } finally {
                    endAction(tick);
                }
            }
            if (!settle(tick)) {
                return false;
            }
            describePredictions();
            Prediction prediction = prediction(pos);
            String outcome;
            if (prediction == null) {
                // Nothing was predicted here; the server may still have placed something on its own.
                outcome = level.getBlockState(pos) != original ? "placed" : "not_placed";
            } else if (!acked()) {
                outcome = "unacknowledged";
            } else {
                outcome = switch (status(prediction)) {
                    case "applied", "changed" -> "placed";
                    case "no_change" -> "not_placed";
                    default -> status(prediction);
                };
            }
            result.addProperty("outcome", outcome);
            return true;
        }

        private BlockHitResult aimAtNeighbor() throws ToolException {
            LocalPlayer player = Client.player();
            ClientLevel level = Client.level();
            ToolException last = null;
            for (Direction direction : against == null ? NEIGHBOR_ORDER : List.of(against)) {
                try {
                    return Aim.atBlock(player, level, pos.relative(direction), direction.getOpposite(),
                            facePos == null ? 0.5 : facePos[0], facePos == null ? 0.5 : facePos[1], false).hit();
                } catch (ToolException e) {
                    last = e;
                }
            }
            throw new ToolException(last.code(), "no adjacent block face can be clicked to place at pos; last attempt: " + last.getMessage(),
                    last.toJson().getAsJsonObject("details"));
        }
    }

    private static final class BreakBlockOp extends PredictedOp {
        private final Args args;
        private final boolean hold;
        private final int timeoutTicks;
        private long startTick = -1;
        private BlockPos pos;
        private Direction face;
        private BlockState original;
        private boolean holding;
        private boolean clientBroke;

        BreakBlockOp(Args args, boolean hold) throws ToolException {
            super(args);
            this.args = args;
            this.hold = hold;
            this.timeoutTicks = args.integer("timeout_ticks", 600);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            if (startTick < 0) {
                startTick = tick;
                start(tick);
            } else if (holding) {
                continueMining(tick);
            }
            if (holding || !settle(tick)) {
                return false;
            }
            result.addProperty("client_broke", clientBroke);
            Prediction prediction = prediction(pos);
            String outcome;
            if (prediction == null) {
                outcome = "not_broken";
            } else if (!acked()) {
                outcome = "unacknowledged";
            } else {
                result.add("server_block", Describe.block(prediction.serverState()));
                String status = status(prediction);
                outcome = status.equals("applied") ? "broken" : status;
            }
            result.addProperty("outcome", outcome);
            return true;
        }

        private void start(long tick) throws ToolException {
            Aim.BlockAim aim = Aim.atBlock(args);
            LocalPlayer player = Client.player();
            MultiPlayerGameMode gameMode = Client.gameMode();
            pos = aim.hit().getBlockPos();
            face = aim.hit().getDirection();
            result.add("hit", Describe.hit(aim.hit()));
            if (aim.mismatch()) {
                result.addProperty("mismatch", true);
            }
            beginAction();
            original = level.getBlockState(pos);
            boolean started = gameMode.startDestroyBlock(pos, face);
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            clientBroke = level.getBlockState(pos) != original;
            if (!started) {
                result.addProperty("reason", "the game mode or world border does not allow breaking this block");
            }
            if (started && !clientBroke && hold) {
                holding = true;
                ScriptedInput.setHoldingAttack(true);
                return;
            }
            // A single click releases the button right away, which aborts any mining it started.
            gameMode.stopDestroyBlock();
            result.addProperty("ticks_held", 0);
            endAction(tick);
        }

        private void continueMining(long tick) throws ToolException {
            Minecraft mc = Client.mc();
            if (mc.level != level || mc.player == null) {
                release();
                throw new ToolException("not_in_world", "left the world while mining");
            }
            if (mc.gameMode.continueDestroyBlock(pos, face)) {
                mc.particleEngine.crack(pos, face);
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            clientBroke = level.getBlockState(pos) != original;
            boolean timedOut = tick - startTick >= timeoutTicks;
            if (clientBroke || timedOut) {
                if (timedOut && !clientBroke) {
                    mc.gameMode.stopDestroyBlock();
                    result.addProperty("reason", "not broken within " + timeoutTicks + " ticks");
                }
                release();
                result.addProperty("ticks_held", tick - startTick);
                endAction(tick);
            }
        }

        private void release() {
            holding = false;
            ScriptedInput.setHoldingAttack(false);
        }

        @Override
        public void cancel() {
            super.cancel();
            if (holding) {
                release();
                Minecraft mc = Client.mc();
                if (mc.gameMode != null && mc.level == level) {
                    mc.gameMode.stopDestroyBlock();
                }
            }
        }
    }

    private static final class UseItemOp extends PredictedOp {
        private final List<InteractionHand> hands;
        private final int holdTicks;
        private long startTick = -1;
        private boolean holding;

        UseItemOp(Args args) throws ToolException {
            super(args);
            hands = hands(args);
            holdTicks = args.integer("hold_ticks", 0);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            Minecraft mc = Client.mc();
            if (startTick < 0) {
                startTick = tick;
                LocalPlayer player = Client.player();
                MultiPlayerGameMode gameMode = Client.gameMode();
                JsonArray results = new JsonArray();
                beginAction();
                try {
                    for (InteractionHand hand : hands) {
                        ItemStack stack = player.getItemInHand(hand);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        ItemStack shown = stack.copy();
                        InteractionResult used = gameMode.useItem(player, hand);
                        results.add(handResult(hand, shown, "use_item", used));
                        if (used instanceof InteractionResult.Success success) {
                            if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                                player.swing(hand);
                            }
                            mc.gameRenderer.itemInHandRenderer.itemUsed(hand);
                            break;
                        }
                    }
                } finally {
                    result.add("hands", results);
                    endPrediction(tick);
                    if (holdTicks > 0 && player.isUsingItem()) {
                        // The game releases a used item on the next tick unless the use key is held.
                        holding = true;
                        mc.options.keyUse.setDown(true);
                    } else {
                        actionDone();
                    }
                }
            } else if (holding) {
                boolean stillUsing = mc.player != null && mc.player.isUsingItem();
                if (!stillUsing || tick - startTick >= holdTicks) {
                    stopHolding(mc, stillUsing);
                    result.addProperty("held_ticks", tick - startTick);
                    actionDone();
                }
            }
            if (holding || !settle(tick)) {
                return false;
            }
            result.addProperty("using_item", mc.player != null && mc.player.isUsingItem());
            return true;
        }

        private void stopHolding(Minecraft mc, boolean stillUsing) {
            holding = false;
            mc.options.keyUse.setDown(false);
            if (stillUsing && mc.gameMode != null) {
                mc.gameMode.releaseUsingItem(mc.player);
            }
        }

        @Override
        public void cancel() {
            super.cancel();
            if (holding) {
                Minecraft mc = Client.mc();
                stopHolding(mc, mc.player != null && mc.player.isUsingItem());
            }
        }
    }

    private static JsonObject useOnEntity(Args args, boolean attack) throws ToolException {
        LocalPlayer player = Client.player();
        MultiPlayerGameMode gameMode = Client.gameMode();
        int id = args.requireInteger("entity_id");
        Entity entity = Client.level().getEntity(id);
        if (entity == null) {
            throw new ToolException("no_target", "no entity with id " + id + " is known to the client");
        }
        Aim.lookAt(player, entity.getBoundingBox().getCenter());
        HitResult hit = Aim.pick();
        if (!(hit instanceof EntityHitResult entityHit) || entityHit.getEntity() != entity) {
            JsonObject details = new JsonObject();
            details.add("expected", Describe.entity(entity));
            details.add("hit", Describe.hit(hit));
            details.addProperty("distance", Describe.round(player.getEyePosition().distanceTo(entity.getBoundingBox().getCenter())));
            details.addProperty("reach", Describe.round(player.entityInteractionRange()));
            details.addProperty("pickable", entity.isPickable());
            throw new ToolException("target_mismatch",
                    "the crosshair does not land on the entity (out of reach, something in the way, or it has no hitbox)", details);
        }

        JsonObject result = new JsonObject();
        result.add("entity", Describe.entity(entity));
        if (attack) {
            gameMode.attack(player, entity);
            player.swing(InteractionHand.MAIN_HAND);
            return result;
        }
        JsonArray results = new JsonArray();
        for (InteractionHand hand : hands(args)) {
            ItemStack shown = player.getItemInHand(hand).copy();
            InteractionResult interaction = gameMode.interactAt(player, entity, entityHit, hand);
            if (!interaction.consumesAction()) {
                interaction = gameMode.interact(player, entity, hand);
            }
            results.add(handResult(hand, shown, "use_on_entity", interaction));
            if (interaction instanceof InteractionResult.Success success) {
                if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                    player.swing(hand);
                }
                break;
            }
        }
        result.add("hands", results);
        return result;
    }
}
