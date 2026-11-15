package io.github.forest611.mcclientmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.mcp.Schema;
import io.github.forest611.mcclientmcp.mcp.ToolException;
import io.github.forest611.mcclientmcp.mcp.ToolRegistry;
import io.github.forest611.mcclientmcp.util.Client;
import io.github.forest611.mcclientmcp.util.Describe;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

final class PlayerTools {
    private PlayerTools() {
    }

    static void register(ToolRegistry registry) {
        registry.registerInstant("player_state",
                "The player's position, view direction (yaw/pitch), on-ground and flying flags, game mode, health, "
                        + "selected hotbar slot and held items.",
                Schema.of(), args -> playerState());

        registry.registerInstant("inventory",
                "The player's inventory. Each entry has slot, id, count, name, lore and the item's non-default components "
                        + "(custom_data holds plugin tags; Bukkit persistent data is under PublicBukkitValues). "
                        + "Slots: 0-8 hotbar, 9-35 main, 36-39 armor (feet, legs, chest, head), 40 offhand. "
                        + "With a filter, only matching stacks are listed and total_count is their combined count.",
                Schema.of()
                        .str("item", "Only stacks of this item id, e.g. minecraft:wheat_seeds")
                        .str("custom_data_key", "Only stacks whose custom_data contains this key at any depth")
                        .bool("include_empty", "Also list empty slots (default false)"),
                args -> inventory(args.string("item", null), args.string("custom_data_key", null), args.bool("include_empty", false)));

        registry.registerInstant("select_slot",
                "Selects a hotbar slot (0-8) and returns the item now held in the main hand.",
                Schema.of().reqInteger("slot", "Hotbar slot 0-8"),
                args -> {
                    int slot = args.requireInteger("slot");
                    if (!Inventory.isHotbarSlot(slot)) {
                        throw ToolException.invalidArgs("'slot' must be 0-8");
                    }
                    LocalPlayer player = Client.player();
                    player.getInventory().setSelectedSlot(slot);
                    JsonObject result = new JsonObject();
                    result.addProperty("selected_slot", slot);
                    result.add("main_hand", Describe.item(player.getMainHandItem()));
                    return result;
                });
    }

    private static JsonObject playerState() throws ToolException {
        LocalPlayer player = Client.player();
        JsonObject json = new JsonObject();
        json.add("pos", Describe.vec(player.position()));
        json.add("block_pos", Describe.pos(player.blockPosition()));
        json.add("eye", Describe.vec(player.getEyePosition()));
        json.addProperty("yaw", Describe.round(player.getYRot()));
        json.addProperty("pitch", Describe.round(player.getXRot()));
        json.add("velocity", Describe.vec(player.getDeltaMovement()));
        json.addProperty("on_ground", player.onGround());
        json.addProperty("flying", player.getAbilities().flying);
        json.addProperty("may_fly", player.getAbilities().mayfly);
        json.addProperty("sneaking", player.isShiftKeyDown());
        json.addProperty("sprinting", player.isSprinting());
        json.addProperty("in_water", player.isInWater());
        json.addProperty("fall_distance", Describe.round(player.fallDistance));
        json.addProperty("gamemode", Client.gameMode().getPlayerMode().getName());
        json.addProperty("health", Describe.round(player.getHealth()));
        json.addProperty("food", player.getFoodData().getFoodLevel());
        json.addProperty("dead", player.isDeadOrDying());
        json.addProperty("permission_level", player.getPermissionLevel());
        json.addProperty("dimension", Client.level().dimension().location().toString());
        json.addProperty("block_reach", Describe.round(player.blockInteractionRange()));
        json.addProperty("entity_reach", Describe.round(player.entityInteractionRange()));
        json.addProperty("selected_slot", player.getInventory().getSelectedSlot());
        json.add("main_hand", Describe.item(player.getMainHandItem()));
        json.add("off_hand", Describe.item(player.getOffhandItem()));
        return json;
    }

    private static JsonObject inventory(String itemFilter, String customDataKey, boolean includeEmpty) throws ToolException {
        LocalPlayer player = Client.player();
        Inventory inv = player.getInventory();
        boolean filtered = itemFilter != null || customDataKey != null;
        JsonArray slots = new JsonArray();
        int total = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty() ? (filtered || !includeEmpty) : !matches(stack, itemFilter, customDataKey)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("slot_type", Describe.inventorySlotType(slot));
            JsonElement item = Describe.item(stack);
            if (item.isJsonObject()) {
                item.getAsJsonObject().entrySet().forEach(e -> entry.add(e.getKey(), e.getValue()));
            } else {
                entry.addProperty("empty", true);
            }
            slots.add(entry);
            total += stack.getCount();
        }
        JsonObject json = new JsonObject();
        json.addProperty("selected_slot", inv.getSelectedSlot());
        json.add("slots", slots);
        json.addProperty("total_count", total);
        if (!filtered) {
            json.add("cursor", Describe.item(player.containerMenu.getCarried()));
        }
        return json;
    }

    private static boolean matches(ItemStack stack, String itemFilter, String customDataKey) {
        if (itemFilter != null && !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemFilter)) {
            return false;
        }
        if (customDataKey != null) {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            return data != null && containsKey(data.copyTag(), customDataKey);
        }
        return true;
    }

    private static boolean containsKey(CompoundTag tag, String key) {
        for (String name : tag.keySet()) {
            if (name.equals(key)) {
                return true;
            }
            Tag child = tag.get(name);
            if (child instanceof CompoundTag compound && containsKey(compound, key)) {
                return true;
            }
        }
        return false;
    }
}
