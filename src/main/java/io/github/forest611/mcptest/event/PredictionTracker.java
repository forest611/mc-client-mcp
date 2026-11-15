package io.github.forest611.mcptest.event;

import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.List;

/** Passes the server's block-prediction acknowledgements on to the actions waiting for them. */
public final class PredictionTracker {
    private static final List<AckListener> listeners = new ArrayList<>();

    @FunctionalInterface
    public interface AckListener {
        /** Called on the client thread right after the client applied the acknowledgement. */
        void onAck(ClientLevel level, int sequence);
    }

    private PredictionTracker() {
    }

    public static void onAck(ClientLevel level, int sequence) {
        for (AckListener listener : List.copyOf(listeners)) {
            listener.onAck(level, sequence);
        }
    }

    public static void addListener(AckListener listener) {
        listeners.add(listener);
    }

    public static void removeListener(AckListener listener) {
        listeners.remove(listener);
    }
}
