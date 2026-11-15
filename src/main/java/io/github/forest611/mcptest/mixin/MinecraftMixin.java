package io.github.forest611.mcptest.mixin;

import io.github.forest611.mcptest.ClientClock;
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
}
