package io.github.forest611.mcptest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings read from {@code config/mcptest.json}. Each can be overridden per launch with a JVM system property
 * {@code -Dmcptest.<name>=<value>}, which is how several clients run side by side.
 */
public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** TCP port of the MCP endpoint on 127.0.0.1; 0 picks a free port (see the ready file). */
    public int port = 25580;
    /** Servers allowed in addition to loopback and private addresses: IPs, CIDR blocks, or host names. */
    public List<String> allowedHosts = new ArrayList<>();
    /** Ignore the real keyboard and mouse while in a world so stray input cannot disturb a test. */
    public boolean lockInput = true;
    /** Minimize the window as soon as the client has started. */
    public boolean startMinimized = false;
    /** Accept server resource packs without showing the confirmation screen. */
    public boolean acceptResourcePacks = true;
    /** Number of events kept in memory. */
    public int eventBufferSize = 50000;
    /** Wall-clock limit for a single MCP call, as a last resort when the client thread stops ticking. */
    public long callTimeoutMs = 300000;

    public static ModConfig load(Path file) {
        ModConfig config = new ModConfig();
        if (Files.exists(file)) {
            try {
                ModConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ModConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | JsonParseException e) {
                McpTestMod.LOGGER.error("Cannot read {}; using defaults", file, e);
            }
        } else {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
            } catch (IOException e) {
                McpTestMod.LOGGER.warn("Cannot write default config {}", file, e);
            }
        }
        if (config.allowedHosts == null) {
            config.allowedHosts = new ArrayList<>();
        }
        config.applySystemProperties();
        return config;
    }

    private void applySystemProperties() {
        port = Integer.getInteger("mcptest.port", port);
        lockInput = booleanProperty("mcptest.lockInput", lockInput);
        startMinimized = booleanProperty("mcptest.startMinimized", startMinimized);
        acceptResourcePacks = booleanProperty("mcptest.acceptResourcePacks", acceptResourcePacks);
        String hosts = System.getProperty("mcptest.allowedHosts");
        if (hosts != null && !hosts.isBlank()) {
            allowedHosts = new ArrayList<>(Arrays.asList(hosts.trim().split("\\s*,\\s*")));
        }
    }

    private static boolean booleanProperty(String name, boolean fallback) {
        String value = System.getProperty(name);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
