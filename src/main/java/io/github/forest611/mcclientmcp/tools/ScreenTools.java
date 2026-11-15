package io.github.forest611.mcclientmcp.tools;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.event.EventFilter;
import io.github.forest611.mcclientmcp.mcp.Args;
import io.github.forest611.mcclientmcp.mcp.Schema;
import io.github.forest611.mcclientmcp.mcp.ToolException;
import io.github.forest611.mcclientmcp.mcp.ToolRegistry;
import io.github.forest611.mcclientmcp.op.Op;
import io.github.forest611.mcclientmcp.util.Client;
import io.github.forest611.mcclientmcp.util.Describe;
import io.github.forest611.mcclientmcp.util.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import java.util.List;
import java.util.regex.Pattern;

final class ScreenTools {
    private ScreenTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("screen",
                "The screen currently open, or open=false. For container GUIs: kind, title, menu_type, container_id, slot counts, "
                        + "the item in every non-empty slot (id, count, name, lore, components) and the item on the cursor. "
                        + "Container slots come first; the player's inventory follows (inventory=\"player\"). "
                        + "Other screens list their buttons.",
                Schema.of().bool("include_empty", "Also list empty slots (default false)"),
                args -> {
                    Screen screen = Client.mc().screen;
                    JsonObject result = screen == null ? new JsonObject() : Screens.full(screen, args.bool("include_empty", false));
                    result.addProperty("open", screen != null);
                    return result;
                });

        registry.registerInstant("click_slot",
                "Clicks a slot of the open container GUI, the way the mouse would. Slot numbers are those reported by screen; "
                        + "-999 is outside the window. With no container open it acts on the player's own inventory menu "
                        + "(5-8 armor, 9-35 main, 36-44 hotbar, 45 offhand). Returns the cursor and the slot after the click, "
                        + "as predicted by the client; a server-driven GUI may answer with further changes (see screen or events).",
                Schema.of()
                        .reqInteger("slot", "Slot index, or -999 for outside the window")
                        .enumStr("click", "Kind of click (default left). number_key swaps with the hotbar slot given in 'hotbar'; "
                                        + "drop is Q over the slot, drop_stack is Ctrl+Q; double is a double left click.",
                                "left", "right", "shift_left", "shift_right", "middle", "number_key", "drop", "drop_stack", "double")
                        .integer("hotbar", "Hotbar slot 0-8 for number_key (40 = offhand)")
                        .integer("container_id", "Fail with wrong_screen unless this container is the one open (optional guard)"),
                ScreenTools::clickSlot);

        registry.registerInstant("close_screen",
                "Closes the open screen the way Escape does (for a container this tells the server).",
                Schema.of(),
                args -> {
                    Minecraft mc = Client.mc();
                    JsonObject result = new JsonObject();
                    result.addProperty("closed", mc.screen != null);
                    if (mc.screen != null) {
                        result.add("screen", Screens.summary(mc.screen));
                        mc.screen.onClose();
                    }
                    return result;
                });

        registry.register("wait_screen",
                "Waits until a screen is open (optionally with a matching title or kind) or until no screen is open, "
                        + "then returns the same data as screen. Returns at once if that is already the case.",
                Schema.of()
                        .enumStr("state", "Wait for a screen to be open or for none to be open (default open)", "open", "closed")
                        .str("title", "Java regex searched in the screen title (state=open only)")
                        .str("kind", "Required screen kind, e.g. container, inventory, death, disconnected (state=open only)")
                        .integer("timeout_ticks", "Give up after this many ticks (default 100 = 5 s)"),
                WaitScreenOp::new);

        registry.registerInstant("click_button",
                "Presses a button of the open (non-container) screen, chosen by its index or its text as listed by screen.",
                Schema.of()
                        .integer("index", "Button index from screen")
                        .str("text", "Exact button text"),
                ScreenTools::clickButton);
    }

    private static JsonObject clickSlot(Args args) throws ToolException {
        LocalPlayer player = Client.player();
        AbstractContainerMenu menu = player.containerMenu;
        int slot = args.requireInteger("slot");
        if (args.has("container_id") && args.integer("container_id", 0) != menu.containerId) {
            JsonObject details = new JsonObject();
            details.addProperty("container_id", menu.containerId);
            throw new ToolException("wrong_screen", "a different container is open", details);
        }
        if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) {
            throw ToolException.invalidArgs("'slot' must be 0-" + (menu.slots.size() - 1) + " or -999");
        }
        int button;
        ClickType type;
        switch (args.string("click", "left")) {
            case "left" -> {
                button = 0;
                type = ClickType.PICKUP;
            }
            case "right" -> {
                button = 1;
                type = ClickType.PICKUP;
            }
            case "shift_left" -> {
                button = 0;
                type = ClickType.QUICK_MOVE;
            }
            case "shift_right" -> {
                button = 1;
                type = ClickType.QUICK_MOVE;
            }
            case "middle" -> {
                button = 2;
                type = ClickType.CLONE;
            }
            case "number_key" -> {
                button = args.requireInteger("hotbar");
                if ((button < 0 || button > 8) && button != 40) {
                    throw ToolException.invalidArgs("'hotbar' must be 0-8 or 40");
                }
                type = ClickType.SWAP;
            }
            case "drop" -> {
                button = 0;
                type = ClickType.THROW;
            }
            case "drop_stack" -> {
                button = 1;
                type = ClickType.THROW;
            }
            case "double" -> {
                button = 0;
                type = ClickType.PICKUP_ALL;
            }
            default -> throw ToolException.invalidArgs("unknown 'click' value");
        }
        Client.gameMode().handleInventoryMouseClick(menu.containerId, slot, button, type, player);

        JsonObject result = new JsonObject();
        result.addProperty("container_id", menu.containerId);
        result.addProperty("slot", slot);
        result.add("slot_item", slot == -999 ? JsonNull.INSTANCE : Describe.item(menu.getSlot(slot).getItem()));
        result.add("cursor", Describe.item(menu.getCarried()));
        return result;
    }

    private static JsonObject clickButton(Args args) throws ToolException {
        Screen screen = Client.mc().screen;
        if (screen == null) {
            throw new ToolException("no_screen", "no screen is open");
        }
        List<AbstractButton> buttons = Screens.buttons(screen);
        AbstractButton target = null;
        if (args.has("index")) {
            int index = args.integer("index", -1);
            if (index >= 0 && index < buttons.size()) {
                target = buttons.get(index);
            }
        } else if (args.has("text")) {
            String text = args.string("text", "");
            target = buttons.stream().filter(button -> button.getMessage().getString().equals(text)).findFirst().orElse(null);
        } else {
            throw ToolException.invalidArgs("give 'index' or 'text'");
        }
        if (target == null || !target.active) {
            JsonObject details = Screens.full(screen, false);
            throw new ToolException("no_target", target == null ? "no such button on this screen" : "the button is disabled", details);
        }
        JsonObject result = new JsonObject();
        result.addProperty("pressed", target.getMessage().getString());
        target.onPress();
        return result;
    }

    private static final class WaitScreenOp extends Op {
        private final boolean open;
        private final Pattern title;
        private final String kind;
        private final int timeoutTicks;
        private long startTick = -1;

        WaitScreenOp(Args args) throws ToolException {
            open = !"closed".equals(args.string("state", "open"));
            title = EventFilter.compile(args.string("title", null));
            kind = args.string("kind", null);
            timeoutTicks = args.integer("timeout_ticks", 100);
        }

        @Override
        public boolean tick(long tick) throws ToolException {
            if (startTick < 0) {
                startTick = tick;
            }
            Screen screen = Client.mc().screen;
            if (open ? matches(screen) : screen == null) {
                result = screen == null ? new JsonObject() : Screens.full(screen, false);
                result.addProperty("open", screen != null);
                result.addProperty("waited_ticks", tick - startTick);
                return true;
            }
            if (tick - startTick >= timeoutTicks) {
                JsonObject details = new JsonObject();
                details.add("screen", screen == null ? JsonNull.INSTANCE : Screens.summary(screen));
                throw new ToolException("timeout", "the screen did not reach the requested state within " + timeoutTicks + " ticks", details);
            }
            return false;
        }

        private boolean matches(Screen screen) {
            return screen != null
                    && (kind == null || kind.equals(Screens.kind(screen)))
                    && (title == null || title.matcher(screen.getTitle().getString()).find());
        }
    }
}
