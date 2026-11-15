package io.github.forest611.mcptest.event;

import com.google.gson.JsonObject;
import io.github.forest611.mcptest.McpTestMod;
import io.github.forest611.mcptest.mixin.DisconnectedScreenAccessor;
import io.github.forest611.mcptest.util.Describe;
import io.github.forest611.mcptest.util.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Finds changes by comparing state at the end of every tick: the open screen, the player's inventory and cursor, and
 * the contents of an open container. Comparing catches server packets and client-side predictions alike.
 */
public final class StateWatcher {
    private static Screen screen;
    private static LocalPlayer player;
    private static ItemStack[] inventory = new ItemStack[0];
    private static ItemStack cursor = ItemStack.EMPTY;
    private static AbstractContainerMenu menu;
    private static ItemStack[] container = new ItemStack[0];

    private StateWatcher() {
    }

    public static void endTick(Minecraft mc) {
        watchScreen(mc);
        watchInventory(mc);
        EventHooks.endTick();
    }

    private static void watchScreen(Minecraft mc) {
        Screen current = mc.screen;
        if (current == screen) {
            return;
        }
        if (screen != null) {
            JsonObject data = new JsonObject();
            data.add("screen", Screens.summary(screen));
            McpTestMod.events().add("screen_close", data);
        }
        if (current != null) {
            JsonObject data = new JsonObject();
            data.add("screen", Screens.summary(current));
            McpTestMod.events().add("screen_open", data);
            if (current instanceof DisconnectedScreen) {
                String reason = ((DisconnectedScreenAccessor) current).mcptest$getDetails().reason().getString();
                EventHooks.disconnected(current.getTitle().getString(), reason);
            }
        }
        screen = current;
    }

    private static void watchInventory(Minecraft mc) {
        LocalPlayer current = mc.player;
        if (current != player) {
            player = current;
            inventory = current == null ? new ItemStack[0] : emptyStacks(current.getInventory().getContainerSize());
            cursor = ItemStack.EMPTY;
            menu = null;
            container = new ItemStack[0];
        }
        if (current == null) {
            return;
        }

        Inventory inv = current.getInventory();
        for (int slot = 0; slot < inventory.length; slot++) {
            ItemStack now = inv.getItem(slot);
            if (!ItemStack.matches(inventory[slot], now)) {
                JsonObject data = new JsonObject();
                data.addProperty("slot", slot);
                data.addProperty("slot_type", Describe.inventorySlotType(slot));
                data.add("old", Describe.item(inventory[slot]));
                data.add("new", Describe.item(now));
                McpTestMod.events().add("inventory_change", data);
                inventory[slot] = now.copy();
            }
        }

        AbstractContainerMenu currentMenu = current.containerMenu;
        ItemStack carried = currentMenu.getCarried();
        if (!ItemStack.matches(cursor, carried)) {
            JsonObject data = new JsonObject();
            data.addProperty("slot_type", "cursor");
            data.add("old", Describe.item(cursor));
            data.add("new", Describe.item(carried));
            McpTestMod.events().add("inventory_change", data);
            cursor = carried.copy();
        }

        if (currentMenu != menu) {
            menu = currentMenu;
            container = currentMenu == current.inventoryMenu ? new ItemStack[0] : emptyStacks(currentMenu.slots.size());
        }
        for (int index = 0; index < container.length && index < currentMenu.slots.size(); index++) {
            Slot slot = currentMenu.slots.get(index);
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack now = slot.getItem();
            if (!ItemStack.matches(container[index], now)) {
                JsonObject data = new JsonObject();
                data.addProperty("container_id", currentMenu.containerId);
                data.addProperty("slot", index);
                data.add("old", Describe.item(container[index]));
                data.add("new", Describe.item(now));
                McpTestMod.events().add("container_change", data);
                container[index] = now.copy();
            }
        }
    }

    private static ItemStack[] emptyStacks(int size) {
        ItemStack[] stacks = new ItemStack[size];
        Arrays.fill(stacks, ItemStack.EMPTY);
        return stacks;
    }
}
