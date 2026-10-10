// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed wrappers for {@code PCACHE.*}: the prompt/LLM response
 * cache. Needs a server license that includes the promptcache feature.
 * See {@link com.hotkv.ai.PromptCache} for the higher level
 * get-or-call helper built on this.
 */
public final class PcacheCommands {

    private final HotKVEngine engine;

    PcacheCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    public void create(String namespace, Long maxEntries, Long ttlSeconds, Double costPerHit) {
        List<Object> args = new ArrayList<>(List.of("PCACHE.CREATE", namespace));
        if (maxEntries != null) {
            args.add("MAX_ENTRIES");
            args.add(maxEntries);
        }
        if (ttlSeconds != null) {
            args.add("TTL");
            args.add(ttlSeconds);
        }
        if (costPerHit != null) {
            args.add("COST_PER_HIT");
            args.add(Numbers.decimal(costPerHit));
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    /**
     * {@code usage} is optional (null omits it): when given, it records the original
     * (uncached) call's token usage so a later {@link #get}/{@link #getEx} hit attributes
     * the tokens and cost that hit did not have to spend (see {@link #stats}).
     */
    public void set(String namespace, String prompt, String response, Long ttlSeconds, String metaJson,
            Usage usage) {
        List<Object> args = new ArrayList<>(List.of("PCACHE.SET", namespace, prompt, response));
        if (ttlSeconds != null) {
            args.add("TTL");
            args.add(ttlSeconds);
        }
        if (metaJson != null) {
            args.add("META");
            args.add(metaJson);
        }
        Usage.appendIfPresent(args, usage);
        Reply.ok(engine.execute(args.toArray()));
    }

    public String get(String namespace, String prompt) {
        return Reply.string(engine.execute("PCACHE.GET", namespace, prompt));
    }

    public GetExResult getEx(String namespace, String prompt) {
        List<RespValue> items = Reply.array(engine.execute("PCACHE.GETEX", namespace, prompt));
        if (items == null) {
            return null;
        }
        return new GetExResult(Reply.string(items.get(0)), Reply.string(items.get(1)), Reply.integer(items.get(2)));
    }

    public boolean del(String namespace, String prompt) {
        return Reply.bool(engine.execute("PCACHE.DEL", namespace, prompt));
    }

    public Stats stats(String namespace) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("PCACHE.STATS", namespace));
        return new Stats(
                Reply.longField(f, "entries"),
                Reply.longField(f, "hits"),
                Reply.longField(f, "misses"),
                Reply.longField(f, "evictions"),
                Reply.longField(f, "memory_bytes"),
                Reply.doubleField(f, "hit_rate"),
                Reply.longField(f, "p50_ns"),
                Reply.longField(f, "p95_ns"),
                Reply.longField(f, "p99_ns"),
                Reply.doubleField(f, "cost_saved_usd"),
                UsageSavings.parse(f));
    }

    public long flush(String namespace) {
        return Reply.integer(engine.execute("PCACHE.FLUSH", namespace));
    }

    public boolean drop(String namespace) {
        return Reply.bool(engine.execute("PCACHE.DROP", namespace));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("PCACHE.LIST"));
    }

    public void configSet(String namespace, String key, String value) {
        Reply.ok(engine.execute("PCACHE.CONFIG", namespace, "SET", key, value));
    }

    public record GetExResult(String response, String metadata, long hitCount) {
    }

    public record Stats(long entries, long hits, long misses, long evictions, long memoryBytes,
            double hitRate, long p50Nanos, long p95Nanos, long p99Nanos, double costSavedUsd,
            UsageSavings usageSavings) {
    }
}
