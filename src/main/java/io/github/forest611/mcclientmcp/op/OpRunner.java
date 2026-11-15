package io.github.forest611.mcclientmcp.op;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Runs submitted calls on the client thread, once per client tick. */
public final class OpRunner {
    private final Queue<Sequence> incoming = new ConcurrentLinkedQueue<>();
    private final List<Sequence> active = new ArrayList<>();

    public void submit(Sequence sequence) {
        incoming.add(sequence);
    }

    public void tick(long tick) {
        Sequence next;
        while ((next = incoming.poll()) != null) {
            active.add(next);
        }
        for (Iterator<Sequence> it = active.iterator(); it.hasNext(); ) {
            Sequence sequence = it.next();
            if (sequence.isCancelled()) {
                sequence.abort();
                it.remove();
            } else if (sequence.tick(tick)) {
                it.remove();
            }
        }
    }
}
