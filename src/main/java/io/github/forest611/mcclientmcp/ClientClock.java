package io.github.forest611.mcclientmcp;

/** Counters advanced by the client: ticks (20 per second) and rendered frames. */
public final class ClientClock {
    private static volatile long tick;
    private static volatile long frame;

    private ClientClock() {
    }

    public static long tick() {
        return tick;
    }

    public static long frame() {
        return frame;
    }

    static void advanceTick() {
        tick++;
    }

    public static void advanceFrame() {
        frame++;
    }
}
