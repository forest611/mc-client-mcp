package io.github.forest611.mcptest.op;

import com.google.gson.JsonObject;
import io.github.forest611.mcptest.mcp.ToolException;

/**
 * A tool invocation driven by client ticks. {@link #tick} is called once per tick on the client thread, starting in
 * the tick the op is scheduled, until it returns true.
 *
 * <p>An op has two phases. The action phase is what a sequence waits for before starting the next step; the optional
 * settle phase (e.g. waiting for the server to acknowledge a block prediction) runs in the background of later steps.
 */
public abstract class Op {
    protected JsonObject result = new JsonObject();
    /** Optional MCP image content block returned alongside the JSON result. */
    protected JsonObject image;
    private boolean actionDone;

    public abstract boolean tick(long tick) throws ToolException;

    /** Releases anything the op holds (pressed inputs, a block being mined) when it is abandoned. */
    public void cancel() {
    }

    protected final void actionDone() {
        actionDone = true;
    }

    public final boolean isActionDone() {
        return actionDone;
    }

    public final JsonObject result() {
        return result;
    }

    public final JsonObject image() {
        return image;
    }

    public static Op instant(Instant body) {
        return new Op() {
            @Override
            public boolean tick(long tick) throws ToolException {
                result = body.run();
                return true;
            }
        };
    }

    @FunctionalInterface
    public interface Instant {
        JsonObject run() throws ToolException;
    }
}
