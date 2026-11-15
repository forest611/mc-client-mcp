package io.github.forest611.mcclientmcp.mixin;

import io.github.forest611.mcclientmcp.McClientMcpMod;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;

/**
 * Enforces the server allowlist where every outgoing game connection is opened, so it also covers the multiplayer
 * screen and server-initiated transfers, not just the connect tool.
 */
@Mixin(Connection.class)
public class ConnectionMixin {
    @Inject(method = "connect", at = @At("HEAD"))
    private static void mcclientmcp$enforceAllowlist(InetSocketAddress address, boolean useEpoll, Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        if (!McClientMcpMod.allowlist().isAllowed(address.getAddress())) {
            McClientMcpMod.allowlist().noteBlocked(address.getHostString());
            throw new IllegalStateException("mc-client-mcp blocked the connection: " + address.getHostString() + " is not in the server allowlist");
        }
    }
}
