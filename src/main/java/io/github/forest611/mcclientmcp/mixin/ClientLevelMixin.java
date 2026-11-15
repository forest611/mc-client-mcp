package io.github.forest611.mcclientmcp.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.forest611.mcclientmcp.event.EventHooks;
import io.github.forest611.mcclientmcp.event.PredictionTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records every block change the client sees and why it happened: server update, local prediction, or a correction. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Shadow
    @Final
    private BlockStatePredictionHandler blockStatePredictionHandler;

    @Unique
    private boolean mcclientmcp$correcting;

    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean mcclientmcp$recordSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, Operation<Boolean> original) {
        ClientLevel self = (ClientLevel) (Object) this;
        BlockState before = self.getBlockState(pos);
        boolean changed = original.call(pos, state, flags, recursionLeft);
        BlockState after = self.getBlockState(pos);
        if (before != after) {
            String source = mcclientmcp$correcting ? EventHooks.SOURCE_CORRECTION
                    : blockStatePredictionHandler.isPredicting() ? EventHooks.SOURCE_PREDICTION : EventHooks.SOURCE_CLIENT;
            EventHooks.blockChanged(pos, before, after, source);
        }
        return changed;
    }

    @WrapMethod(method = "setServerVerifiedBlockState")
    private void mcclientmcp$recordServerBlock(BlockPos pos, BlockState state, int flags, Operation<Void> original) {
        ClientLevel self = (ClientLevel) (Object) this;
        BlockState before = self.getBlockState(pos);
        original.call(pos, state, flags);
        BlockState after = self.getBlockState(pos);
        if (before != after) {
            EventHooks.blockChanged(pos, before, after, EventHooks.SOURCE_SERVER);
        } else {
            EventHooks.blockConfirmed(pos, state, after);
        }
    }

    /** The server's acknowledgement disagreed with what the client predicted, so the client's change is undone. */
    @WrapMethod(method = "syncBlockState")
    private void mcclientmcp$markCorrection(BlockPos pos, BlockState state, Vec3 playerPos, Operation<Void> original) {
        mcclientmcp$correcting = true;
        try {
            original.call(pos, state, playerPos);
        } finally {
            mcclientmcp$correcting = false;
        }
    }

    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void mcclientmcp$recordAck(int sequence, CallbackInfo ci) {
        PredictionTracker.onAck((ClientLevel) (Object) this, sequence);
    }
}
