package io.github.forest611.mcclientmcp.op;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.forest611.mcclientmcp.McClientMcpMod;
import io.github.forest611.mcclientmcp.mcp.Args;
import io.github.forest611.mcclientmcp.mcp.Tool;
import io.github.forest611.mcclientmcp.mcp.ToolException;
import io.github.forest611.mcclientmcp.mcp.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One MCP call: either a single tool or a {@code run_sequence} batch. Steps start on exact client ticks; a step starts
 * {@code delay} ticks after the previous step finished its action phase (0 = the same tick).
 */
public final class Sequence {
    public static final String TOOL_NAME = "run_sequence";
    private static final int MAX_TOTAL_STEPS = 5000;

    public record Outcome(JsonObject result, JsonObject image, ToolException error) {
    }

    private record StepSpec(Tool tool, JsonObject args, int delay) {
    }

    private static final class StepRun {
        final int index;
        final int iteration;
        final StepSpec spec;
        Op op;
        long startTick = -1;
        long actionTick = -1;
        long endTick = -1;
        long eventSeq;
        ToolException error;
        boolean finished;

        StepRun(int index, int iteration, StepSpec spec) {
            this.index = index;
            this.iteration = iteration;
            this.spec = spec;
        }
    }

    private final List<StepSpec> steps;
    private final int repeat;
    private final int interval;
    private final boolean continueOnError;
    private final boolean single;
    private final CompletableFuture<Outcome> future = new CompletableFuture<>();
    private final List<StepRun> runs = new ArrayList<>();
    private final List<StepRun> active = new ArrayList<>();
    private volatile boolean cancelled;
    private long startTick = -1;
    private int nextIndex;
    private boolean aborted;

    private Sequence(List<StepSpec> steps, int repeat, int interval, boolean continueOnError, boolean single) {
        this.steps = steps;
        this.repeat = repeat;
        this.interval = interval;
        this.continueOnError = continueOnError;
        this.single = single;
    }

    public static Sequence single(Tool tool, JsonObject args) {
        return new Sequence(List.of(new StepSpec(tool, args, 0)), 1, 0, false, true);
    }

    public static Sequence fromArgs(ToolRegistry registry, Args args) throws ToolException {
        JsonArray stepsJson = args.array("steps");
        if (stepsJson == null || stepsJson.isEmpty()) {
            throw ToolException.invalidArgs("'steps' must be a non-empty array");
        }
        List<StepSpec> steps = new ArrayList<>();
        for (JsonElement element : stepsJson) {
            if (!element.isJsonObject()) {
                throw ToolException.invalidArgs("each step must be an object {tool, args, delay}");
            }
            Args step = new Args(element.getAsJsonObject());
            String name = step.requireString("tool");
            Tool tool = registry.get(name);
            if (tool == null) {
                throw ToolException.invalidArgs("unknown tool in step " + steps.size() + ": " + name);
            }
            int delay = step.integer("delay", 0);
            if (delay < 0) {
                throw ToolException.invalidArgs("'delay' must be >= 0");
            }
            JsonObject stepArgs = step.object("args");
            steps.add(new StepSpec(tool, stepArgs == null ? new JsonObject() : stepArgs, delay));
        }
        int repeat = args.integer("repeat", 1);
        int interval = args.integer("interval", 0);
        if (repeat < 1 || interval < 0) {
            throw ToolException.invalidArgs("'repeat' must be >= 1 and 'interval' must be >= 0");
        }
        if ((long) steps.size() * repeat > MAX_TOTAL_STEPS) {
            throw ToolException.invalidArgs("too many steps: steps x repeat must be <= " + MAX_TOTAL_STEPS);
        }
        boolean continueOnError = "continue".equals(args.string("on_error", "abort"));
        return new Sequence(steps, repeat, interval, continueOnError, false);
    }

    public CompletableFuture<Outcome> future() {
        return future;
    }

    public void cancel() {
        cancelled = true;
    }

    boolean isCancelled() {
        return cancelled;
    }

    /** Stops every op that is still running; called on the client thread. */
    void abort() {
        for (StepRun run : active) {
            run.op.cancel();
        }
        active.clear();
    }

    /** @return true once every step has finished and the future has been completed */
    boolean tick(long tick) {
        if (startTick < 0) {
            startTick = tick;
        }
        for (StepRun run : List.copyOf(active)) {
            advance(run, tick);
        }
        int total = steps.size() * repeat;
        while (nextIndex < total && !aborted) {
            StepRun previous = nextIndex == 0 ? null : runs.get(nextIndex - 1);
            if (previous != null && previous.actionTick < 0) {
                break;
            }
            int stepIndex = nextIndex % steps.size();
            int iteration = nextIndex / steps.size();
            StepSpec spec = steps.get(stepIndex);
            long delay = spec.delay + (stepIndex == 0 && iteration > 0 ? interval : 0);
            long earliest = (previous == null ? startTick : previous.actionTick) + delay;
            if (tick < earliest) {
                break;
            }
            StepRun run = new StepRun(stepIndex, iteration, spec);
            runs.add(run);
            nextIndex++;
            start(run, tick);
        }
        if (!active.isEmpty() || (nextIndex < total && !aborted)) {
            return false;
        }
        future.complete(single ? singleOutcome() : sequenceOutcome(tick, total));
        return true;
    }

    private void start(StepRun run, long tick) {
        run.startTick = tick;
        run.eventSeq = McClientMcpMod.events().lastSeq();
        try {
            run.op = run.spec.tool.factory().create(new Args(run.spec.args));
        } catch (ToolException e) {
            fail(run, e, tick);
            return;
        } catch (RuntimeException e) {
            McClientMcpMod.LOGGER.error("Tool {} failed to start", run.spec.tool.name(), e);
            fail(run, new ToolException("internal_error", e.toString()), tick);
            return;
        }
        active.add(run);
        advance(run, tick);
    }

    private void advance(StepRun run, long tick) {
        try {
            boolean done = run.op.tick(tick);
            if ((done || run.op.isActionDone()) && run.actionTick < 0) {
                run.actionTick = tick;
            }
            if (done) {
                run.endTick = tick;
                run.finished = true;
                active.remove(run);
            }
        } catch (ToolException e) {
            cancelQuietly(run);
            fail(run, e, tick);
        } catch (RuntimeException e) {
            McClientMcpMod.LOGGER.error("Tool {} threw", run.spec.tool.name(), e);
            cancelQuietly(run);
            fail(run, new ToolException("internal_error", e.toString()), tick);
        }
    }

    private void cancelQuietly(StepRun run) {
        try {
            run.op.cancel();
        } catch (RuntimeException e) {
            McClientMcpMod.LOGGER.warn("Tool {} failed to cancel", run.spec.tool.name(), e);
        }
    }

    private void fail(StepRun run, ToolException error, long tick) {
        run.error = error;
        run.finished = true;
        if (run.actionTick < 0) {
            run.actionTick = tick;
        }
        run.endTick = tick;
        active.remove(run);
        if (!continueOnError) {
            aborted = true;
        }
    }

    private Outcome singleOutcome() {
        StepRun run = runs.get(0);
        if (run.error != null) {
            return new Outcome(null, null, run.error);
        }
        JsonObject result = run.op.result();
        result.addProperty("tick", run.startTick);
        result.addProperty("event_seq", run.eventSeq);
        return new Outcome(result, run.op.image(), null);
    }

    private Outcome sequenceOutcome(long tick, int total) {
        JsonObject result = new JsonObject();
        result.addProperty("start_tick", startTick);
        result.addProperty("end_tick", tick);
        result.addProperty("completed", !aborted && runs.size() == total);
        result.addProperty("steps_total", total);
        JsonArray stepResults = new JsonArray();
        for (StepRun run : runs) {
            JsonObject step = new JsonObject();
            step.addProperty("index", run.index);
            if (repeat > 1) {
                step.addProperty("iteration", run.iteration);
            }
            step.addProperty("tool", run.spec.tool.name());
            step.addProperty("tick", run.startTick);
            step.addProperty("action_tick", run.actionTick);
            step.addProperty("end_tick", run.endTick);
            step.addProperty("event_seq", run.eventSeq);
            step.addProperty("ok", run.error == null);
            if (run.error == null) {
                step.add("result", run.op.result());
            } else {
                step.add("error", run.error.toJson());
            }
            stepResults.add(step);
        }
        result.add("steps", stepResults);
        return new Outcome(result, null, null);
    }
}
