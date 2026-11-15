package io.github.forest611.mcclientmcp.mixin;

import io.github.forest611.mcclientmcp.event.EventHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet handlers first run on the network thread, where they bail out with an exception after rescheduling
 * themselves; injecting at TAIL therefore only fires for the real run on the client thread.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void mcclientmcp$recordEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        EventHooks.entityDataUpdated(packet.id());
    }

    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void mcclientmcp$recordSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        EventHooks.sound(packet.getSound().value().location().toString(), packet.getSource().getName(),
                packet.getX(), packet.getY(), packet.getZ(), packet.getVolume(), packet.getPitch());
    }

    @Inject(method = "handleSoundEntityEvent", at = @At("TAIL"))
    private void mcclientmcp$recordEntitySound(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        EventHooks.entitySound(packet.getSound().value().location().toString(), packet.getSource().getName(),
                packet.getId(), packet.getVolume(), packet.getPitch());
    }

    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void mcclientmcp$recordParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        EventHooks.particle(packet.getParticle().getType(), packet.getX(), packet.getY(), packet.getZ(), packet.getCount());
    }
}
