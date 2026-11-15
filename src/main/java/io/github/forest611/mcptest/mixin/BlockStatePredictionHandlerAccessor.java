package io.github.forest611.mcptest.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockStatePredictionHandler.class)
public interface BlockStatePredictionHandlerAccessor {
    /** Values are {@code ServerVerifiedState}, which is not accessible; see {@link ServerVerifiedStateAccessor}. */
    @Accessor("serverVerifiedStates")
    Long2ObjectOpenHashMap<?> mcptest$getServerVerifiedStates();
}
