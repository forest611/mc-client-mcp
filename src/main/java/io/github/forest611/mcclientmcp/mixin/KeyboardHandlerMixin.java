package io.github.forest611.mcclientmcp.mixin;

import io.github.forest611.mcclientmcp.input.InputLock;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
    @Inject(method = {"keyPress", "charTyped"}, at = @At("HEAD"), cancellable = true)
    private void mcclientmcp$ignoreWhileLocked(CallbackInfo ci) {
        if (InputLock.blocksInput()) {
            ci.cancel();
        }
    }
}
