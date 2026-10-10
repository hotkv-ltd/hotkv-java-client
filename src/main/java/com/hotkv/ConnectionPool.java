// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.HotKVConnectionException;
import com.hotkv.exceptions.HotKVTimeoutException;
import com.hotkv.protocol.RespValue;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded pool of {@link Connection}s to one HotKV server. Safe for
 * concurrent use: many threads may {@link #borrow()}/{@link #release}
 * connections at once, up to {@code options.poolSize()} concurrently open.
 * Idle connections older than {@code healthCheckIntervalMillis} are
 * verified with a {@code PING} before being handed out, and connections
 * idle longer than {@code idleTimeoutMillis} are closed and replaced.
 */
final class ConnectionPool {

    private final HotKVOptions options;
    private final LinkedBlockingDeque<Connection> idle = new LinkedBlockingDeque<>();
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ConnectionPool(HotKVOptions options) {
        this.options = options;
    }

    Connection borrow() {
        if (closed.get()) {
            throw new HotKVConnectionException("hotkv: client is closed");
        }
        long deadline = System.nanoTime() + options.poolBorrowTimeoutMillis() * 1_000_000L;

        while (true) {
            Connection c = idle.pollFirst();
            if (c != null) {
                if (c.isBroken()) {
                    closeAndForget(c);
                    continue;
                }
                if (c.idleMillis() > options.idleTimeoutMillis()) {
                    closeAndForget(c);
                    continue;
                }
                if (c.idleMillis() > options.healthCheckIntervalMillis() && !healthCheck(c)) {
                    closeAndForget(c);
                    continue;
                }
                return c;
            }

            if (total.get() < options.poolSize()) {
                if (total.incrementAndGet() <= options.poolSize()) {
                    try {
                        return Connection.connect(options);
                    } catch (IOException e) {
                        total.decrementAndGet();
                        throw new HotKVConnectionException(
                                "hotkv: failed to connect to " + describeTarget() + ": " + e.getMessage(), e);
                    }
                }
                total.decrementAndGet(); // lost the race against another borrower; fall through to waiting
            }

            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                throw new HotKVTimeoutException(
                        "hotkv: timed out waiting for a pooled connection (pool size " + options.poolSize() + ")");
            }
            try {
                Connection waited = idle.pollFirst(remaining, TimeUnit.MILLISECONDS);
                if (waited == null) {
                    throw new HotKVTimeoutException(
                            "hotkv: timed out waiting for a pooled connection (pool size " + options.poolSize() + ")");
                }
                if (waited.isBroken() || waited.idleMillis() > options.idleTimeoutMillis()) {
                    closeAndForget(waited);
                    continue;
                }
                return waited;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HotKVConnectionException("hotkv: interrupted while waiting for a pooled connection", e);
            }
        }
    }

    void release(Connection c) {
        if (c == null) {
            return;
        }
        if (closed.get() || c.isBroken()) {
            closeAndForget(c);
            return;
        }
        c.touch();
        idle.addFirst(c);
    }

    /** For a connection that failed mid use: close it and free its pool slot. */
    void discard(Connection c) {
        if (c == null) {
            return;
        }
        c.markBroken();
        closeAndForget(c);
    }

    /** Removes a connection from pool accounting without closing it; the caller now owns its lifecycle. */
    void detach(Connection c) {
        if (c != null) {
            total.decrementAndGet();
        }
    }

    private void closeAndForget(Connection c) {
        c.close();
        total.decrementAndGet();
    }

    private boolean healthCheck(Connection c) {
        try {
            RespValue v = c.call("PING");
            return !v.isError();
        } catch (IOException e) {
            return false;
        }
    }

    private String describeTarget() {
        return options.unixSocketPath() != null
                ? options.unixSocketPath()
                : options.host() + ":" + options.port();
    }

    PoolStats stats() {
        int t = total.get();
        int i = idle.size();
        return new PoolStats(t, i, options.poolSize());
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            Connection c;
            while ((c = idle.pollFirst()) != null) {
                closeAndForget(c);
            }
        }
    }
}
