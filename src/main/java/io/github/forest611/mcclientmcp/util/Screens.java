package io.github.forest611.mcclientmcp.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

public final class Screens {
    private Screens() {
    }

    /** A name for the screen that does not depend on (possibly obfuscated) class names. */
    public static String kind(Screen screen) {
        if (screen instanceof CreativeModeInventoryScreen) {
            return "creative_inventory";
        } else if (screen instanceof InventoryScreen) {
            return "inventory";
        } else if (screen instanceof AbstractContainerScreen<?>) {
            return "container";
        } else if (screen instanceof ChatScreen) {
            return "chat";
        } else if (screen instanceof PauseScreen) {
            return "pause";
        } else if (screen instanceof DeathScreen) {
            return "death";
        } else if (screen instanceof DisconnectedScreen) {
            return "disconnected";
        } else if (screen instanceof ConnectScreen) {
            return "connecting";
        } else if (isLoading(screen)) {
            return "loading";
        } else if (screen instanceof TitleScreen) {
            return "title";
        } else if (screen instanceof JoinMultiplayerScreen) {
            return "multiplayer";
        } else if (screen instanceof AbstractSignEditScreen) {
            return "sign_edit";
        }
        return "other";
    }

    public static boolean isLoading(Screen screen) {
        return screen instanceof ReceivingLevelScreen || screen instanceof ProgressScreen
                || screen instanceof GenericMessageScreen || screen instanceof LevelLoadingScreen;
    }

    public static String menuType(AbstractContainerMenu menu) {
        try {
            return String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType()));
        } catch (UnsupportedOperationException e) {
            // The player's own inventory menu has no registered type.
            return null;
        }
    }

    public static List<AbstractButton> buttons(Screen screen) {
        List<AbstractButton> buttons = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractButton button && button.visible) {
                buttons.add(button);
            }
        }
        return buttons;
    }

    /** Type, title and container identity; no slot contents. */
    public static JsonObject summary(Screen screen) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", kind(screen));
        json.addProperty("class", screen.getClass().getSimpleName());
        json.addProperty("title", screen.getTitle().getString());
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            String menuType = menuType(menu);
            if (menuType != null) {
                json.addProperty("menu_type", menuType);
            }
            json.addProperty("container_id", menu.containerId);
            json.addProperty("slot_count", menu.slots.size());
        }
        return json;
    }

    public static JsonObject full(Screen screen, boolean includeEmpty) {
        JsonObject json = summary(screen);
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            JsonArray slots = new JsonArray();
            int containerSlots = 0;
            for (Slot slot : menu.slots) {
                boolean playerSlot = slot.container instanceof Inventory;
                if (!playerSlot) {
                    containerSlots++;
                }
                JsonElement item = Describe.item(slot.getItem());
                if (item.isJsonNull() && !includeEmpty) {
                    continue;
                }
                JsonObject entry = item.isJsonNull() ? new JsonObject() : item.getAsJsonObject();
                JsonObject ordered = new JsonObject();
                ordered.addProperty("slot", slot.index);
                ordered.addProperty("inventory", playerSlot ? "player" : "container");
                if (item.isJsonNull()) {
                    ordered.addProperty("empty", true);
                }
                entry.entrySet().forEach(e -> ordered.add(e.getKey(), e.getValue()));
                slots.add(ordered);
            }
            json.addProperty("container_slot_count", containerSlots);
            json.add("slots", slots);
            json.add("cursor", Describe.item(menu.getCarried()));
        }
        List<AbstractButton> buttons = buttons(screen);
        if (!buttons.isEmpty()) {
            JsonArray array = new JsonArray();
            for (int i = 0; i < buttons.size(); i++) {
                JsonObject button = new JsonObject();
                button.addProperty("index", i);
                button.addProperty("text", buttons.get(i).getMessage().getString());
                button.addProperty("active", buttons.get(i).active);
                array.add(button);
            }
            json.add("buttons", array);
        }
        return json;
    }
}
