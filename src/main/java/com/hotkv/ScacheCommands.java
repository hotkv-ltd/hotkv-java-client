// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed wrappers for {@code SCACHE.*}: the semantic cache
 * engine. Needs a server license that includes the semanticcache
 * feature. See {@link com.hotkv.ai.SemanticCache} for the higher level
 * get-or-call helper built on this.
 */
public final class ScacheCommands {

    private final HotKVEngine engine;

    ScacheCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** {@code metric} ("cosine"/"l2"/"ip"), {@code threshold} and {@code ttlSeconds} may be null for server defaults. */
    public void create(String namespace, int dims, String metric, Double threshold, Long ttlSeconds) {
        List<Object> args = new ArrayList<>(List.of("SCACHE.CREATE", namespace, dims));
        if (metric != null) {
            args.add("METRIC");
            args.add(metric);
        }
        if (threshold != null) {
            args.add("THRESHOLD");
            args.add(Numbers.float32(threshold));
        }
        if (ttlSeconds != null) {
            args.add("TTL");
            args.add(ttlSeconds);
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    /**
     * {@code usage} is optional (null omits it): when given, it records the original
     * (uncached) call's token usage so a later {@link #get} hit attributes the tokens and
     * cost that hit did not have to spend (see {@link #stats}).
     */
    public void set(String namespace, String queryText, String response, double[] vector, Long ttlSeconds,
            Usage usage) {
        List<Object> args = new ArrayList<>(List.of("SCACHE.SET", namespace, queryText, response));
        for (double v : vector) {
            args.add(Numbers.float32(v));
        }
        if (ttlSeconds != null) {
            args.add("TTL");
            args.add(ttlSeconds);
        }
        Usage.appendIfPresent(args, usage);
        Reply.ok(engine.execute(args.toArray()));
    }

    /** Returns the cached response and similarity above the namespace threshold, or null on a miss. */
    public ScacheResult get(String namespace, double[] vector, Double thresholdOverride) {
        List<Object> args = new ArrayList<>();
        args.add("SCACHE.GET");
        args.add(namespace);
        for (double v : vector) {
            args.add(Numbers.float32(v));
        }
        if (thresholdOverride != null) {
            args.add("THRESHOLD");
            args.add(Numbers.float32(thresholdOverride));
        }
        List<RespValue> items = Reply.array(engine.execute(args.toArray()));
        return items == null ? null : new ScacheResult(Reply.string(items.get(0)), Reply.doubleValue(items.get(1)));
    }

    public ScacheStats stats(String namespace) {
        java.util.Map<String, String> f = Reply.flatStringMap(engine.execute("SCACHE.STATS", namespace));
        return new ScacheStats(
                Reply.longField(f, "entries"),
                Reply.longField(f, "hits"),
                Reply.longField(f, "misses"),
                Reply.doubleField(f, "hit_rate"),
                Reply.longField(f, "p50_us"),
                UsageSavings.parse(f));
    }

    public boolean flush(String namespace) {
        return Reply.bool(engine.execute("SCACHE.FLUSH", namespace));
    }

    public boolean drop(String namespace) {
        return Reply.bool(engine.execute("SCACHE.DROP", namespace));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("SCACHE.LIST"));
    }

    public void threshold(String namespace, double value) {
        Reply.ok(engine.execute("SCACHE.THRESHOLD", namespace, value));
    }

    public record ScacheResult(String response, double similarity) {
    }

    public record ScacheStats(long entries, long hits, long misses, double hitRate, long p50Micros,
            UsageSavings usageSavings) {
    }
}
