package io.github.forest611.mcptest.mcp;

import com.google.gson.JsonObject;

/** A tool failure with a machine-readable code, returned to the MCP client as a structured error. */
public final class ToolException extends Exception {
    private final String code;
    private final JsonObject details;

    public ToolException(String code, String message) {
        this(code, message, null);
    }

    public ToolException(String code, String message, JsonObject details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public static ToolException invalidArgs(String message) {
        return new ToolException("invalid_args", message);
    }

    public String code() {
        return code;
    }

    public JsonObject toJson() {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", getMessage());
        if (details != null) {
            error.add("details", details);
        }
        return error;
    }
}
