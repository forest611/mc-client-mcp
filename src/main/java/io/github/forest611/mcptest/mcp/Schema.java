package io.github.forest611.mcptest.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Minimal builder for the JSON Schema of a tool's input object. */
public final class Schema {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public static Schema of() {
        return new Schema();
    }

    public Schema str(String name, String description) {
        return add(name, typed("string", description), false);
    }

    public Schema reqStr(String name, String description) {
        return add(name, typed("string", description), true);
    }

    public Schema integer(String name, String description) {
        return add(name, typed("integer", description), false);
    }

    public Schema reqInteger(String name, String description) {
        return add(name, typed("integer", description), true);
    }

    public Schema num(String name, String description) {
        return add(name, typed("number", description), false);
    }

    public Schema reqNum(String name, String description) {
        return add(name, typed("number", description), true);
    }

    public Schema bool(String name, String description) {
        return add(name, typed("boolean", description), false);
    }

    public Schema obj(String name, String description) {
        return add(name, typed("object", description), false);
    }

    public Schema enumStr(String name, String description, String... values) {
        JsonObject schema = typed("string", description);
        JsonArray options = new JsonArray();
        for (String value : values) {
            options.add(value);
        }
        schema.add("enum", options);
        return add(name, schema, false);
    }

    public Schema pos(String name, String description) {
        return add(name, array("integer", 3, description), false);
    }

    public Schema reqPos(String name, String description) {
        return add(name, array("integer", 3, description), true);
    }

    public Schema numbers(String name, int length, String description) {
        return add(name, array("number", length, description), false);
    }

    public Schema strings(String name, String description) {
        JsonObject schema = typed("array", description);
        schema.add("items", typed("string", null));
        return add(name, schema, false);
    }

    public Schema add(String name, JsonObject schema, boolean isRequired) {
        properties.add(name, schema);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    public JsonObject build() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        return schema;
    }

    public static JsonObject typed(String type, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        if (description != null) {
            schema.addProperty("description", description);
        }
        return schema;
    }

    private static JsonObject array(String itemType, int length, String description) {
        JsonObject schema = typed("array", description);
        schema.add("items", typed(itemType, null));
        schema.addProperty("minItems", length);
        schema.addProperty("maxItems", length);
        return schema;
    }
}
