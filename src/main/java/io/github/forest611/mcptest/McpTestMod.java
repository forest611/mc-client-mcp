package io.github.forest611.mcptest;

import com.google.gson.JsonObject;
import io.github.forest611.mcptest.event.EventHooks;
import io.github.forest611.mcptest.event.EventLog;
import io.github.forest611.mcptest.event.StateWatcher;
import io.github.forest611.mcptest.input.InputLock;
import io.github.forest611.mcptest.mcp.CallLog;
import io.github.forest611.mcptest.mcp.McpHttpServer;
import io.github.forest611.mcptest.mcp.ToolRegistry;
import io.github.forest611.mcptest.op.OpRunner;
import io.github.forest611.mcptest.tools.Tools;
import io.github.forest611.mcptest.util.HostAllowlist;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.TutorialSteps;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class McpTestMod implements ClientModInitializer {
    public static final String MOD_ID = "mcptest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ModConfig config;
    private static HostAllowlist allowlist;
    private static EventLog events;
    private static OpRunner runner;
    private static Path dataDir;
    private static String version;
    private static int port;
    private static volatile boolean ready;
    private static boolean unattendedOptionsApplied;

    public static ModConfig config() {
        return config;
    }

    public static HostAllowlist allowlist() {
        return allowlist;
    }

    public static EventLog events() {
        return events;
    }

    public static OpRunner runner() {
        return runner;
    }

    /** Directory under the game directory holding logs, screenshots and the ready file. */
    public static Path dataDir() {
        return dataDir;
    }

    public static String version() {
        return version;
    }

    /** True once the client has finished its startup resource load and can accept a connect call. */
    public static boolean isReady() {
        return ready;
    }

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        version = loader.getModContainer(MOD_ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        config = ModConfig.load(loader.getConfigDir().resolve("mcptest.json"));
        allowlist = new HostAllowlist(config.allowedHosts);
        InputLock.setEnabled(config.lockInput);
        dataDir = loader.getGameDir().resolve("mcptest").toAbsolutePath();
        Path logDir = dataDir.resolve("logs").resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        events = new EventLog(config.eventBufferSize, logDir.resolve("events.jsonl"));
        runner = new OpRunner();

        ToolRegistry registry = new ToolRegistry();
        Tools.registerAll(registry);
        EventHooks.register();

        Path readyFile = dataDir.resolve("ready.json");
        McpHttpServer server = new McpHttpServer(registry, config.callTimeoutMs, version, new CallLog(logDir.resolve("calls.jsonl")));
        try {
            Files.deleteIfExists(readyFile);
            port = server.start(config.port);
        } catch (IOException e) {
            throw new IllegalStateException("MCPTest cannot listen on 127.0.0.1:" + config.port
                    + "; set another port with -Dmcptest.port or config/mcptest.json", e);
        }
        LOGGER.info("MCP endpoint listening on http://127.0.0.1:{}/mcp", port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try {
                Files.deleteIfExists(readyFile);
            } catch (IOException ignored) {
                // Nothing useful can be done while the JVM is exiting.
            }
        }, "mcptest-shutdown"));

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            ClientClock.advanceTick();
            applyUnattendedOptions(mc);
            runner.tick(ClientClock.tick());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            StateWatcher.endTick(mc);
            events.flush();
            if (!ready && mc.getOverlay() == null && (mc.screen != null || mc.level != null)) {
                ready = true;
                onReady(mc, readyFile);
            }
        });
    }

    /** Settings a human would otherwise have to click through or that stop an unfocused client. */
    private static void applyUnattendedOptions(Minecraft mc) {
        mc.options.pauseOnLostFocus = false;
        if (unattendedOptionsApplied) {
            return;
        }
        unattendedOptionsApplied = true;
        // With vsync on, macOS stalls buffer swaps of an occluded window, which stalls the whole client loop.
        mc.options.enableVsync().set(false);
        mc.options.onboardAccessibility = false;
        mc.options.joinedFirstServer = true;
        mc.getTutorial().setStep(TutorialSteps.NONE);
    }

    private static void onReady(Minecraft mc, Path readyFile) {
        if (config.startMinimized) {
            GLFW.glfwIconifyWindow(mc.getWindow().getWindow());
        }
        JsonObject json = new JsonObject();
        json.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
        json.addProperty("port", port);
        json.addProperty("pid", ProcessHandle.current().pid());
        json.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
        json.addProperty("version", version);
        json.addProperty("started", Instant.now().toString());
        try {
            Files.createDirectories(readyFile.getParent());
            Files.writeString(readyFile, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Cannot write ready file {}", readyFile, e);
        }
        LOGGER.info("MCPTest ready");
    }
}
