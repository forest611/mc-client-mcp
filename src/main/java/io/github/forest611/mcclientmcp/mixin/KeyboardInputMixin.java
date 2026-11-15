package io.github.forest611.mcclientmcp.mixin;

import io.github.forest611.mcclientmcp.input.ScriptedInput;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mcclientmcp$applyScriptedInput(CallbackInfo ci) {
        if (ScriptedInput.isActive()) {
            this.keyPresses = ScriptedInput.keyPresses(this.keyPresses);
            this.moveVector = ScriptedInput.moveVector(this.moveVector);
        }
    }
}
