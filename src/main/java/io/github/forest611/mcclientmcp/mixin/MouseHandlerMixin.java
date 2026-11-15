package io.github.forest611.mcclientmcp.mixin;

import io.github.forest611.mcclientmcp.input.InputLock;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = {"onPress", "onScroll", "onMove", "grabMouse"}, at = @At("HEAD"), cancellable = true)
    private void mcclientmcp$ignoreWhileLocked(CallbackInfo ci) {
        if (InputLock.blocksInput()) {
            ci.cancel();
        }
    }
}
