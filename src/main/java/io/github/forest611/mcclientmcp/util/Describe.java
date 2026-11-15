package io.github.forest611.mcclientmcp.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.math.Transformation;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

/** Converts game objects into the JSON shapes returned by tools and stored in events. */
public final class Describe {
    private Describe() {
    }

    public static double round(double value) {
        return Math.round(value * 1e5) / 1e5;
    }

    public static JsonArray pos(BlockPos pos) {
        JsonArray json = new JsonArray();
        json.add(pos.getX());
        json.add(pos.getY());
        json.add(pos.getZ());
        return json;
    }

    public static JsonArray vec(Vec3 vec) {
        JsonArray json = new JsonArray();
        json.add(round(vec.x));
        json.add(round(vec.y));
        json.add(round(vec.z));
        return json;
    }

    private static JsonArray vec(Vector3f vec) {
        JsonArray json = new JsonArray();
        json.add(round(vec.x()));
        json.add(round(vec.y()));
        json.add(round(vec.z()));
        return json;
    }

    private static JsonArray quaternion(Quaternionf q) {
        JsonArray json = new JsonArray();
        json.add(round(q.x()));
        json.add(round(q.y()));
        json.add(round(q.z()));
        json.add(round(q.w()));
        return json;
    }

    public static JsonObject block(BlockState state) {
        JsonObject json = new JsonObject();
        json.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getValues().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                Comparable<?> value = entry.getValue();
                String name = entry.getKey().getName();
                if (value instanceof Integer i) {
                    properties.addProperty(name, i);
                } else if (value instanceof Boolean b) {
                    properties.addProperty(name, b);
                } else {
                    properties.addProperty(name, propertyName(entry.getKey(), value));
                }
            }
            json.add("properties", properties);
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyName(Property<T> property, Comparable<?> value) {
        return property.getName((T) value);
    }

    /** @return the item as JSON, or JSON null for an empty stack */
    public static JsonElement item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        json.addProperty("count", stack.getCount());
        json.addProperty("name", stack.getHoverName().getString());
        Component customName = stack.getCustomName();
        if (customName != null) {
            json.addProperty("custom_name", customName.getString());
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            JsonArray lines = new JsonArray();
            for (Component line : lore.lines()) {
                lines.add(line.getString());
            }
            json.add("lore", lines);
        }
        DataComponentPatch patch = stack.getComponentsPatch();
        if (!patch.isEmpty()) {
            DataComponentPatch.CODEC.encodeStart(registries().createSerializationContext(JsonOps.INSTANCE), patch)
                    .resultOrPartial(error -> json.addProperty("components_error", error))
                    .ifPresent(components -> json.add("components", components));
        }
        return json;
    }

    /** Names the player inventory slot ranges: 0-8 hotbar, 9-35 main, 36-39 armor (feet to head), 40 offhand. */
    public static String inventorySlotType(int slot) {
        if (slot < 9) {
            return "hotbar";
        } else if (slot < 36) {
            return "main";
        }
        return switch (slot) {
            case 36 -> "feet";
            case 37 -> "legs";
            case 38 -> "chest";
            case 39 -> "head";
            case 40 -> "offhand";
            default -> "equipment";
        };
    }

    private static RegistryAccess registries() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.registryAccess() : RegistryAccess.EMPTY;
    }

    public static JsonObject entity(Entity entity) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entity.getId());
        json.addProperty("uuid", entity.getUUID().toString());
        json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        json.add("pos", vec(entity.position()));
        json.addProperty("yaw", round(entity.getYRot()));
        json.addProperty("pitch", round(entity.getXRot()));
        if (entity instanceof Player player) {
            json.addProperty("name", player.getScoreboardName());
        }
        if (entity.getCustomName() != null) {
            json.addProperty("custom_name", entity.getCustomName().getString());
        }
        if (entity instanceof LivingEntity living) {
            json.addProperty("health", round(living.getHealth()));
        }
        if (entity instanceof ItemEntity itemEntity) {
            json.add("item", item(itemEntity.getItem()));
        } else if (entity instanceof ItemFrame frame) {
            json.add("item", item(frame.getItem()));
        } else if (entity instanceof FallingBlockEntity falling) {
            json.add("block", block(falling.getBlockState()));
        } else if (entity instanceof Display display) {
            json.add("display", display(display));
        }
        if (entity.getVehicle() != null) {
            json.addProperty("vehicle", entity.getVehicle().getId());
        }
        if (!entity.getPassengers().isEmpty()) {
            JsonArray passengers = new JsonArray();
            for (Entity passenger : entity.getPassengers()) {
                passengers.add(passenger.getId());
            }
            json.add("passengers", passengers);
        }
        return json;
    }

    private static JsonObject display(Display display) {
        JsonObject json = new JsonObject();
        if (display instanceof Display.ItemDisplay itemDisplay) {
            json.addProperty("kind", "item");
            json.add("item", item(itemDisplay.getItemStack()));
            json.addProperty("item_display", itemDisplay.getItemTransform().getSerializedName());
        } else if (display instanceof Display.BlockDisplay blockDisplay) {
            json.addProperty("kind", "block");
            json.add("block", block(blockDisplay.getBlockState()));
        } else if (display instanceof Display.TextDisplay textDisplay) {
            json.addProperty("kind", "text");
            json.addProperty("text", textDisplay.getText().getString());
        }
        Transformation transformation = Display.createTransformation(display.getEntityData());
        JsonObject transform = new JsonObject();
        transform.add("translation", vec(transformation.getTranslation()));
        transform.add("scale", vec(transformation.getScale()));
        transform.add("left_rotation", quaternion(transformation.getLeftRotation()));
        transform.add("right_rotation", quaternion(transformation.getRightRotation()));
        json.add("transformation", transform);
        json.addProperty("billboard", display.getBillboardConstraints().getSerializedName());
        json.addProperty("view_range", round(display.getViewRange()));
        json.addProperty("width", round(display.getWidth()));
        json.addProperty("height", round(display.getHeight()));
        json.addProperty("interpolation_duration", display.getTransformationInterpolationDuration());
        json.addProperty("teleport_duration", display.getPosRotInterpolationDuration());
        return json;
    }

    public static JsonObject hit(HitResult hit) {
        JsonObject json = new JsonObject();
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            json.addProperty("type", "miss");
            return json;
        }
        Minecraft mc = Minecraft.getInstance();
        if (hit instanceof BlockHitResult blockHit) {
            json.addProperty("type", "block");
            json.add("pos", pos(blockHit.getBlockPos()));
            json.addProperty("face", blockHit.getDirection().getName());
            if (mc.level != null) {
                json.add("block", block(mc.level.getBlockState(blockHit.getBlockPos())));
            }
        } else if (hit instanceof EntityHitResult entityHit) {
            json.addProperty("type", "entity");
            json.add("entity", entity(entityHit.getEntity()));
        }
        json.add("location", vec(hit.getLocation()));
        if (mc.player != null) {
            json.addProperty("distance", round(hit.getLocation().distanceTo(mc.player.getEyePosition())));
        }
        return json;
    }
}
