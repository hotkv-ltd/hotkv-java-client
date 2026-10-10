// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.io.IOException;
import java.util.List;

/**
 * A batch of commands queued client side and sent to the server as one
 * write, with all replies read back in order on {@link #sync()}: one
 * round trip for the whole batch. A failing command does not fail the
 * others; its {@link Response#get()} throws instead.
 *
 * <pre>{@code
 * Pipeline p = client.pipeline();
 * Response<Void> a = p.set("k1", "v1");
 * Response<String> b = p.get("k2");
 * p.sync();
 * String value = b.get();
 * }</pre>
 *
 * <p>Not thread safe; build and run one batch from a single thread.
 */
public final class Pipeline extends CommandBatch {

    Pipeline(HotKVEngine engine) {
        super(engine);
    }

    /**
     * Sends every queued command in one write and reads all replies back in
     * order, resolving each {@link Response}. Returns each command's
     * decoded result in order, or the mapped exception instance (not
     * thrown) for a command that errored. Clears the queue afterwards, so
     * the pipeline can be reused for another batch.
     */
    public List<Object> sync() {
        if (commands.isEmpty()) {
            return List.of();
        }
        Connection c = engine.borrowDedicated();
        boolean released = false;
        try {
            for (Object[] cmd : commands) {
                c.send(cmd);
            }
            c.flush();
            List<Object> results = collectResults(c);
            engine.releaseDedicated(c);
            released = true;
            return results;
        } catch (IOException e) {
            engine.discardDedicated(c);
            throw HotKVEngine.translate(e);
        } catch (RuntimeException e) {
            // e.g. a command argument that cannot be encoded: part of the batch may already be buffered
            if (!released) {
                engine.discardDedicated(c);
            }
            throw e;
        } finally {
            clearQueue();
        }
    }

    /** Drops all queued commands without sending them. */
    public void discard() {
        clearQueue();
    }
}
