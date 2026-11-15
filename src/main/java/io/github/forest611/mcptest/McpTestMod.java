package io.github.forest611.mcptest;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McpTestMod implements ClientModInitializer {
    public static final String MOD_ID = "mcptest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("MCPTest initializing");
    }
}
