package io.github.forest611.mcptest.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the frame rate up while the window is minimized or idle. Packets are only processed between frames, so a
 * throttled client would run its ticks in bursts and lose tick-accurate timing.
 */
@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
    private void mcptest$neverThrottleInWorld(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        if (minecraft.level != null) {
            cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
        }
    }
}
