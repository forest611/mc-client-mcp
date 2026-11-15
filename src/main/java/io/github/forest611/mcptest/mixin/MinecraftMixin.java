package io.github.forest611.mcptest.mixin;

import io.github.forest611.mcptest.ClientClock;
import io.github.forest611.mcptest.input.ScriptedInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void mcptest$countFrame(boolean renderLevel, CallbackInfo ci) {
        ClientClock.advanceFrame();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void mcptest$keepScriptedBreak(boolean leftClick, CallbackInfo ci) {
        if (ScriptedInput.isHoldingAttack()) {
            ci.cancel();
        }
    }
}
