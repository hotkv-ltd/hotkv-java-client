// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed wrappers shared by the four identically shaped keyed cache
 * families: {@code TOOL.CACHE.*} (tool call results),
 * {@code NCACHE.*} (negative/"don't retry" cache), {@code
 * GUARD.*} (guardrail decisions) and {@code RERANK.*} (reranker
 * results); each needs a server license that includes its feature
 * (toolcache, negativecache, guardcache, rerankcache). Reached via {@link HotKV#toolCache()}, {@link
 * HotKV#ncache()}, {@link HotKV#guard()} and {@link HotKV#rerank()}.
 */
public final class KeyedCacheCommands {

    private final HotKVEngine engine;
    private final String prefix;

    KeyedCacheCommands(HotKVEngine engine, String prefix) {
        this.engine = engine;
        this.prefix = prefix;
    }

    public void create(String namespace, Long maxEntries, Long ttlSeconds) {
        List<Object> args = new ArrayList<>(List.of(prefix + ".CREATE", namespace));
        if (maxEntries != null) {
            args.add("MAX_ENTRIES");
            args.add(maxEntries);
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
    public void set(String namespace, String key, String value, Long ttlSeconds, String metaJson, Usage usage) {
        List<Object> args = new ArrayList<>(List.of(prefix + ".SET", namespace, key, value));
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

    public String get(String namespace, String key) {
        return Reply.string(engine.execute(prefix + ".GET", namespace, key));
    }

    public boolean del(String namespace, String key) {
        return Reply.bool(engine.execute(prefix + ".DEL", namespace, key));
    }

    public Stats stats(String namespace) {
        Map<String, String> f = Reply.flatStringMap(engine.execute(prefix + ".STATS", namespace));
        return new Stats(
                Reply.longField(f, "entries"),
                Reply.longField(f, "hits"),
                Reply.longField(f, "misses"),
                Reply.longField(f, "evictions"),
                Reply.longField(f, "memory_bytes"),
                UsageSavings.parse(f));
    }

    public long flush(String namespace) {
        return Reply.integer(engine.execute(prefix + ".FLUSH", namespace));
    }

    public boolean drop(String namespace) {
        return Reply.bool(engine.execute(prefix + ".DROP", namespace));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute(prefix + ".LIST"));
    }

    public record Stats(long entries, long hits, long misses, long evictions, long memoryBytes,
            UsageSavings usageSavings) {
    }
}
