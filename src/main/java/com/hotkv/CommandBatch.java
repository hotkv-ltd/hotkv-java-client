// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shared queuing machinery and common typed commands for {@link Pipeline}
 * and {@link Transaction}. Any command, typed here or not, can be queued
 * with the generic {@link #command(Object...)} escape hatch; enterprise
 * and AI commands are only available that way inside a
 * batch, not as dedicated methods, since {@link HotKV} itself is the fully
 * typed surface for those.
 */
public abstract sealed class CommandBatch permits Pipeline, Transaction {

    final HotKVEngine engine;
    final List<Object[]> commands = new ArrayList<>();
    final List<Response<?>> responses = new ArrayList<>();

    CommandBatch(HotKVEngine engine) {
        this.engine = engine;
    }

    /** Number of commands queued so far. */
    public final int size() {
        return commands.size();
    }

    public final <T> Response<T> queue(Function<RespValue, T> decoder, Object... args) {
        Response<T> response = new Response<>(decoder);
        commands.add(args);
        responses.add(response);
        return response;
    }

    /** Queues any command, decoding its reply into plain Java types. */
    public final Response<Object> command(Object... args) {
        return queue(Reply::toJava, args);
    }

    final List<Object> collectResults(Connection c) throws IOException {
        List<RespValue> raw = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            raw.add(c.receive());
        }
        return resolveResults(raw);
    }

    final List<Object> resolveResults(List<RespValue> raw) {
        List<Object> results = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Response<?> r = responses.get(i);
            r.resolve(raw.get(i));
            Object decoded;
            try {
                decoded = r.get();
            } catch (RuntimeException ex) {
                decoded = ex;
            }
            results.add(decoded);
        }
        return results;
    }

    final void clearQueue() {
        commands.clear();
        responses.clear();
    }

    private static Object[] args(Object... a) {
        return a;
    }

    // ---- common typed commands (the full family lives on HotKV itself) ----

    public Response<String> get(String key) {
        return queue(Reply::string, args("GET", key));
    }

    public Response<Void> set(String key, String value) {
        return queue(v -> null, args("SET", key, value));
    }

    public Response<Long> del(String... keys) {
        return queue(Reply::integer, HotKV.prepend("DEL", keys));
    }

    public Response<Long> exists(String... keys) {
        return queue(Reply::integer, HotKV.prepend("EXISTS", keys));
    }

    public Response<Boolean> expire(String key, long seconds) {
        return queue(Reply::bool, args("EXPIRE", key, seconds));
    }

    public Response<Long> ttl(String key) {
        return queue(Reply::integer, args("TTL", key));
    }

    public Response<Long> incr(String key) {
        return queue(Reply::integer, args("INCR", key));
    }

    public Response<Long> incrBy(String key, long amount) {
        return queue(Reply::integer, args("INCRBY", key, amount));
    }

    public Response<Long> decr(String key) {
        return queue(Reply::integer, args("DECR", key));
    }

    public Response<Long> append(String key, String value) {
        return queue(Reply::integer, args("APPEND", key, value));
    }

    public Response<Long> strlen(String key) {
        return queue(Reply::integer, args("STRLEN", key));
    }

    public Response<List<String>> mget(String... keys) {
        return queue(Reply::stringList, HotKV.prepend("MGET", keys));
    }

    public Response<Void> mset(java.util.Map<String, String> keyValues) {
        List<Object> flat = new ArrayList<>(keyValues.size() * 2 + 1);
        flat.add("MSET");
        keyValues.forEach((k, v) -> {
            flat.add(k);
            flat.add(v);
        });
        return queue(v -> null, flat.toArray());
    }

    public Response<Long> hset(String key, String field, String value) {
        return queue(Reply::integer, args("HSET", key, field, value));
    }

    public Response<String> hget(String key, String field) {
        return queue(Reply::string, args("HGET", key, field));
    }

    public Response<java.util.Map<String, String>> hgetAll(String key) {
        return queue(Reply::stringMap, args("HGETALL", key));
    }

    public Response<Long> hdel(String key, String... fields) {
        return queue(Reply::integer, HotKV.concat(new Object[] {"HDEL", key}, fields));
    }

    public Response<Boolean> hexists(String key, String field) {
        return queue(Reply::bool, args("HEXISTS", key, field));
    }

    public Response<Long> lpush(String key, String... values) {
        return queue(Reply::integer, HotKV.concat(new Object[] {"LPUSH", key}, values));
    }

    public Response<Long> rpush(String key, String... values) {
        return queue(Reply::integer, HotKV.concat(new Object[] {"RPUSH", key}, values));
    }

    public Response<String> lpop(String key) {
        return queue(Reply::string, args("LPOP", key));
    }

    public Response<String> rpop(String key) {
        return queue(Reply::string, args("RPOP", key));
    }

    public Response<List<String>> lrange(String key, long start, long stop) {
        return queue(Reply::stringList, args("LRANGE", key, start, stop));
    }

    public Response<Long> llen(String key) {
        return queue(Reply::integer, args("LLEN", key));
    }

    public Response<Long> sadd(String key, String... members) {
        return queue(Reply::integer, HotKV.concat(new Object[] {"SADD", key}, members));
    }

    public Response<Long> srem(String key, String... members) {
        return queue(Reply::integer, HotKV.concat(new Object[] {"SREM", key}, members));
    }

    public Response<List<String>> smembers(String key) {
        return queue(Reply::stringList, args("SMEMBERS", key));
    }

    public Response<Boolean> sismember(String key, String member) {
        return queue(Reply::bool, args("SISMEMBER", key, member));
    }

    public Response<Long> zadd(String key, double score, String member) {
        return queue(Reply::integer, args("ZADD", key, score, member));
    }

    public Response<Double> zscore(String key, String member) {
        return queue(Reply::doubleOrNull, args("ZSCORE", key, member));
    }

    public Response<List<String>> zrange(String key, long start, long stop) {
        return queue(Reply::stringList, args("ZRANGE", key, start, stop));
    }

    public Response<Long> zrem(String key, String... members) {
        return queue(Reply::integer, HotKV.concat(new Object[] {"ZREM", key}, members));
    }

    public Response<String> ping() {
        return queue(Reply::string, args("PING"));
    }
}
