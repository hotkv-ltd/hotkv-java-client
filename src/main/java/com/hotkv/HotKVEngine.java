// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.HotKVConnectionException;
import com.hotkv.exceptions.HotKVException;
import com.hotkv.exceptions.HotKVTimeoutException;
import com.hotkv.protocol.RespValue;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Set;

/**
 * Ties a {@link ConnectionPool} to the retry policy and error mapping every
 * command path (the synchronous client, pipelines, transactions, the
 * cluster client) shares.
 */
final class HotKVEngine implements Closeable {

    /**
     * Commands safe to retry once on a fresh connection after a transport
     * failure: read only, so repeating them has no side effect. Everything
     * else is retried only when {@link HotKVOptions#retryNonIdempotentWrites()}
     * is set, per the SDK contract of never blindly retrying writes.
     */
    private static final Set<String> IDEMPOTENT_READS = Set.of(
            "GET", "MGET", "GETRANGE", "SUBSTR", "STRLEN",
            "EXISTS", "TYPE", "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME", "NTTL", "RANDOMKEY", "KEYS", "SCAN", "DUMP",
            "LLEN", "LRANGE", "LINDEX", "LPOS",
            "SMEMBERS", "SCARD", "SISMEMBER", "SMISMEMBER", "SRANDMEMBER", "SSCAN", "SDIFF", "SINTER", "SINTERCARD", "SUNION",
            "HGET", "HMGET", "HGETALL", "HKEYS", "HVALS", "HLEN", "HSTRLEN", "HEXISTS", "HRANDFIELD", "HSCAN",
            "HTTL", "HPTTL", "HEXPIRETIME", "HPEXPIRETIME",
            "ZSCORE", "ZMSCORE", "ZCARD", "ZCOUNT", "ZRANGE", "ZRANGEBYSCORE", "ZRANGEBYLEX",
            "ZREVRANGE", "ZREVRANGEBYSCORE", "ZREVRANGEBYLEX", "ZRANK", "ZREVRANK", "ZRANDMEMBER", "ZSCAN",
            "ZLEXCOUNT", "ZDIFF", "ZINTER", "ZINTERCARD", "ZUNION",
            "XRANGE", "XREVRANGE", "XLEN", "XREAD", "XPENDING", "XINFO",
            "GEOPOS", "GEODIST", "GEOSEARCH", "GEOHASH",
            "GETBIT", "BITCOUNT", "BITPOS",
            "PFCOUNT",
            "PING", "ECHO", "INFO", "DBSIZE", "TIME", "CONFIG",
            "TAG.GET", "TAG.MEMBERS", "TAG.INTER", "TAG.COUNT", "TAG.EXISTS");

    private final HotKVOptions options;
    private final ConnectionPool pool;

    HotKVEngine(HotKVOptions options) {
        this.options = options;
        this.pool = new ConnectionPool(options);
    }

    HotKVOptions options() {
        return options;
    }

    /** Runs a command and throws the mapped {@link HotKVException} if the server replied with an error. */
    RespValue execute(Object... args) {
        RespValue v = executeRaw(args);
        if (v.isError()) {
            throw Errors.fromServerError(v.errorMessage());
        }
        return v;
    }

    /** Runs a command, returning the raw reply, error or not, after retrying transport failures per policy. */
    RespValue executeRaw(Object... args) {
        boolean retryable = isIdempotentRead(args) || options.retryNonIdempotentWrites();
        int attempts = retryable ? Math.max(1, options.maxRetries() + 1) : 1;
        HotKVException lastFailure = null;

        for (int attempt = 0; attempt < attempts; attempt++) {
            Connection c = pool.borrow();
            try {
                RespValue v = c.call(args);
                pool.release(c);
                return v;
            } catch (IOException e) {
                pool.discard(c);
                lastFailure = translate(e);
                if (attempt + 1 >= attempts) {
                    throw lastFailure;
                }
            } catch (RuntimeException e) {
                // An argument that cannot be encoded (null, NaN) or an unreadable reply: do not
                // leak the borrowed connection or hand a possibly desynchronised one back.
                pool.discard(c);
                throw e;
            }
        }
        throw lastFailure != null ? lastFailure : new HotKVConnectionException("hotkv: command could not be sent");
    }

    static boolean isIdempotentRead(Object[] args) {
        if (args.length == 0) {
            return false;
        }
        return IDEMPOTENT_READS.contains(String.valueOf(args[0]).toUpperCase(Locale.ROOT));
    }

    /** Borrows a connection for exclusive use (pipeline/transaction batches); pair with release/discard. */
    Connection borrowDedicated() {
        return pool.borrow();
    }

    void releaseDedicated(Connection c) {
        pool.release(c);
    }

    void discardDedicated(Connection c) {
        pool.discard(c);
    }

    /**
     * Runs {@code setupCmd} then {@code mainCmd} on the same borrowed
     * connection and returns {@code mainCmd}'s reply, for protocols that
     * require per-connection state such as {@code ASKING} or {@code
     * READONLY} immediately before the real command. Never retried:
     * callers of this path are already handling a cluster redirect.
     */
    RespValue executeAfter(Object[] setupCmd, Object[] mainCmd) {
        Connection c = pool.borrow();
        try {
            RespValue setupReply = c.call(setupCmd);
            if (setupReply.isError()) {
                pool.release(c);
                c = null;
                throw Errors.fromServerError(setupReply.errorMessage());
            }
            RespValue v = c.call(mainCmd);
            pool.release(c);
            return v;
        } catch (IOException e) {
            pool.discard(c);
            throw translate(e);
        } catch (RuntimeException e) {
            pool.discard(c); // null when the connection was already released
            throw e;
        }
    }

    /** Borrows a connection and removes it from pool accounting entirely, for the lifetime of a PubSub session. */
    Connection detachForPubSub() {
        Connection c = pool.borrow();
        pool.detach(c);
        return c;
    }

    PoolStats poolStats() {
        return pool.stats();
    }

    static HotKVException translate(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return new HotKVTimeoutException("hotkv: timed out waiting for a reply: " + e.getMessage(), e);
        }
        return new HotKVConnectionException("hotkv: connection error: " + e.getMessage(), e);
    }

    @Override
    public void close() {
        pool.close();
    }
}
