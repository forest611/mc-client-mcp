package io.github.forest611.mcclientmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/** Typed access to tool arguments; every accessor reports problems as {@code invalid_args}. */
public final class Args {
    private final JsonObject json;

    public Args(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    public JsonObject json() {
        return json;
    }

    public boolean has(String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }

    public String string(String key, String fallback) throws ToolException {
        if (!has(key)) {
            return fallback;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonPrimitive()) {
            throw ToolException.invalidArgs("'" + key + "' must be a string");
        }
        return e.getAsString();
    }

    public String requireString(String key) throws ToolException {
        require(key);
        return string(key, null);
    }

    public int integer(String key, int fallback) throws ToolException {
        if (!has(key)) {
            return fallback;
        }
        try {
            double d = json.get(key).getAsDouble();
            if (d != Math.rint(d)) {
                throw new NumberFormatException();
            }
            return (int) d;
        } catch (RuntimeException e) {
            throw ToolException.invalidArgs("'" + key + "' must be an integer");
        }
    }

    public int requireInteger(String key) throws ToolException {
        require(key);
        return integer(key, 0);
    }

    public long longValue(String key, long fallback) throws ToolException {
        if (!has(key)) {
            return fallback;
        }
        try {
            return json.get(key).getAsLong();
        } catch (RuntimeException e) {
            throw ToolException.invalidArgs("'" + key + "' must be an integer");
        }
    }

    public double number(String key, double fallback) throws ToolException {
        if (!has(key)) {
            return fallback;
        }
        try {
            return json.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw ToolException.invalidArgs("'" + key + "' must be a number");
        }
    }

    public double requireNumber(String key) throws ToolException {
        require(key);
        return number(key, 0);
    }

    public boolean bool(String key, boolean fallback) throws ToolException {
        if (!has(key)) {
            return fallback;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw ToolException.invalidArgs("'" + key + "' must be a boolean");
        }
        return e.getAsBoolean();
    }

    public JsonObject object(String key) throws ToolException {
        if (!has(key)) {
            return null;
        }
        if (!json.get(key).isJsonObject()) {
            throw ToolException.invalidArgs("'" + key + "' must be an object");
        }
        return json.getAsJsonObject(key);
    }

    public JsonArray array(String key) throws ToolException {
        if (!has(key)) {
            return null;
        }
        if (!json.get(key).isJsonArray()) {
            throw ToolException.invalidArgs("'" + key + "' must be an array");
        }
        return json.getAsJsonArray(key);
    }

    /** Accepts either a single string or an array of strings. */
    public List<String> stringList(String key) throws ToolException {
        List<String> out = new ArrayList<>();
        if (!has(key)) {
            return out;
        }
        JsonElement e = json.get(key);
        if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
            return out;
        }
        if (!e.isJsonArray()) {
            throw ToolException.invalidArgs("'" + key + "' must be a string or an array of strings");
        }
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                throw ToolException.invalidArgs("'" + key + "' must contain only strings");
            }
            out.add(item.getAsString());
        }
        return out;
    }

    public double[] numbers(String key, int length) throws ToolException {
        if (!has(key)) {
            return null;
        }
        JsonElement e = json.get(key);
        if (!e.isJsonArray() || e.getAsJsonArray().size() != length) {
            throw ToolException.invalidArgs("'" + key + "' must be an array of " + length + " numbers");
        }
        double[] out = new double[length];
        try {
            for (int i = 0; i < length; i++) {
                out[i] = e.getAsJsonArray().get(i).getAsDouble();
            }
        } catch (RuntimeException ex) {
            throw ToolException.invalidArgs("'" + key + "' must be an array of " + length + " numbers");
        }
        return out;
    }

    public BlockPos pos(String key) throws ToolException {
        double[] v = numbers(key, 3);
        if (v == null) {
            return null;
        }
        for (double d : v) {
            if (d != Math.rint(d)) {
                throw ToolException.invalidArgs("'" + key + "' must be integer block coordinates [x, y, z]");
            }
        }
        return new BlockPos((int) v[0], (int) v[1], (int) v[2]);
    }

    public BlockPos requirePos(String key) throws ToolException {
        require(key);
        return pos(key);
    }

    public Direction face(String key) throws ToolException {
        if (!has(key)) {
            return null;
        }
        Direction direction = Direction.byName(string(key, ""));
        if (direction == null) {
            throw ToolException.invalidArgs("'" + key + "' must be one of up, down, north, south, east, west");
        }
        return direction;
    }

    private void require(String key) throws ToolException {
        if (!has(key)) {
            throw ToolException.invalidArgs("missing required argument '" + key + "'");
        }
    }
}
