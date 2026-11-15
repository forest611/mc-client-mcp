package io.github.forest611.mcptest.mixin;

import io.github.forest611.mcptest.event.EventHooks;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void mcptest$recordTitle(Component text, CallbackInfo ci) {
        EventHooks.message(EventHooks.KIND_TITLE, text);
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void mcptest$recordSubtitle(Component text, CallbackInfo ci) {
        EventHooks.message(EventHooks.KIND_SUBTITLE, text);
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void mcptest$recordActionBar(Component text, boolean animateColor, CallbackInfo ci) {
        EventHooks.message(EventHooks.KIND_ACTION_BAR, text);
    }
}
