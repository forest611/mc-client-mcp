package io.github.forest611.mcclientmcp.mixin;

import io.github.forest611.mcclientmcp.ClientClock;
import io.github.forest611.mcclientmcp.input.ScriptedInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void mcclientmcp$countFrame(boolean renderLevel, CallbackInfo ci) {
        ClientClock.advanceFrame();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void mcclientmcp$keepScriptedBreak(boolean leftClick, CallbackInfo ci) {
        if (ScriptedInput.isHoldingAttack()) {
            ci.cancel();
        }
    }
}
