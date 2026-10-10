// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * A MULTI/EXEC transaction, optionally guarded by WATCH: queued commands
 * run atomically when {@link #exec()} is called, or not at all if a
 * watched key changed first.
 *
 * <pre>{@code
 * Transaction tx = client.transaction();
 * tx.watch("balance"); // sent immediately, so it truly guards what happens next
 * long current = Long.parseLong(client.get("balance"));
 * Response<Long> newBalance = tx.incrBy("balance", 10);
 * List<Object> results = tx.exec(); // null if "balance" changed since watch()
 * }</pre>
 *
 * <p>{@link #watch} sends {@code WATCH} immediately and holds a dedicated
 * connection until {@link #exec()} or {@link #discard()} runs (WATCH is
 * per connection state, so it must be observed before the caller goes on
 * to read values and decide what to queue). {@link #exec()} then sends
 * {@code MULTI}, every queued command and {@code EXEC} together as one
 * write. If {@link #watch} was never called, {@link #exec()} borrows its
 * connection at that point instead, same as before. Implements {@link
 * Closeable} as a safety net: closing releases a connection left open by
 * {@link #watch} if neither {@link #exec()} nor {@link #discard()} ran.
 *
 * <p>Not thread safe; build and run one transaction from a single thread.
 */
public final class Transaction extends CommandBatch implements Closeable {

    private Connection conn;
    private boolean watching;

    Transaction(HotKVEngine engine) {
        super(engine);
    }

    /**
     * Sends {@code WATCH} for these keys immediately, on a connection held
     * until {@link #exec()}/{@link #discard()}/{@link #close()}. May be
     * called more than once to watch additional keys.
     */
    public Transaction watch(String... keys) {
        if (keys.length == 0) {
            return this;
        }
        Connection c = connection();
        try {
            RespValue reply = c.call(HotKV.prepend("WATCH", keys));
            if (reply.isError()) {
                throw Errors.fromServerError(reply.errorMessage());
            }
            watching = true;
            return this;
        } catch (IOException e) {
            engine.discardDedicated(c);
            conn = null;
            throw HotKVEngine.translate(e);
        }
    }

    private Connection connection() {
        if (conn == null) {
            conn = engine.borrowDedicated();
        }
        return conn;
    }

    /**
     * Sends MULTI, every queued command and EXEC as one write (on the
     * connection {@link #watch} already holds, if any), then reads all
     * replies back in order. Returns null if EXEC was aborted (a watched
     * key changed), matching the server's own null reply; otherwise one
     * decoded result per queued command, in order, with a per-command
     * error surfaced as the mapped exception instance (not thrown) rather
     * than failing the whole transaction. Clears the queue and releases
     * the connection either way.
     */
    public List<Object> exec() {
        Connection c = connection();
        try {
            c.send("MULTI");
            for (Object[] cmd : commands) {
                c.send(cmd);
            }
            c.send("EXEC");
            c.flush();

            RespValue multiReply = c.receive();
            if (multiReply.isError()) {
                throw Errors.fromServerError(multiReply.errorMessage());
            }
            for (int i = 0; i < commands.size(); i++) {
                c.receive(); // per queued command: "+QUEUED", or an error that will surface as EXECABORT below
            }
            RespValue execReply = c.receive();
            engine.releaseDedicated(c);
            conn = null;
            watching = false;

            if (execReply.isNull()) {
                return null;
            }
            if (execReply.isError()) {
                throw Errors.fromServerError(execReply.errorMessage());
            }
            return resolveResults(Reply.array(execReply));
        } catch (IOException e) {
            engine.discardDedicated(c);
            conn = null;
            watching = false;
            throw HotKVEngine.translate(e);
        } catch (RuntimeException e) {
            if (conn != null) { // not yet released: do not leak a connection with a half sent batch
                engine.discardDedicated(conn);
                conn = null;
                watching = false;
            }
            throw e;
        } finally {
            clearQueue();
        }
    }

    /** Drops all locally queued commands; sends UNWATCH if {@link #watch} had opened a connection. */
    public void discard() {
        if (conn != null) {
            try {
                if (watching) {
                    conn.call("UNWATCH");
                }
                engine.releaseDedicated(conn);
            } catch (IOException e) {
                engine.discardDedicated(conn);
            } finally {
                conn = null;
                watching = false;
            }
        }
        clearQueue();
    }

    /** Releases a connection left open by {@link #watch} if {@link #exec()}/{@link #discard()} never ran. Idempotent. */
    @Override
    public void close() {
        if (conn != null) {
            discard();
        }
    }
}
