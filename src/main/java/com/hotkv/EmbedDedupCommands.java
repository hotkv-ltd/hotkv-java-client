// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.List;
import java.util.Map;

/** Typed wrappers for {@code EMBED.DEDUP.*}: reference counted embedding dedup. Needs a server license that includes the embeddedup feature. */
public final class EmbedDedupCommands {

    private final HotKVEngine engine;

    EmbedDedupCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** Returns true if newly stored, false if {@code hash} already existed (its refcount was incremented instead). */
    public boolean put(String namespace, String hash, String model, String embedding) {
        return Reply.bool(engine.execute("EMBED.DEDUP.PUT", namespace, hash, model, embedding));
    }

    public String get(String namespace, String hash) {
        return Reply.string(engine.execute("EMBED.DEDUP.GET", namespace, hash));
    }

    /** Decrements the refcount; the entry is freed once it reaches zero. */
    public boolean release(String namespace, String hash) {
        return Reply.bool(engine.execute("EMBED.DEDUP.RELEASE", namespace, hash));
    }

    public Stats stats(String namespace) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("EMBED.DEDUP.STATS", namespace));
        return new Stats(Long.parseLong(f.get("hits")), Long.parseLong(f.get("misses")), Long.parseLong(f.get("entries")));
    }

    public boolean drop(String namespace) {
        return Reply.bool(engine.execute("EMBED.DEDUP.DROP", namespace));
    }

    /** Purges expired/unreferenced entries in one namespace. */
    public boolean purge(String namespace) {
        return Reply.bool(engine.execute("EMBED.DEDUP.PURGE", namespace));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("EMBED.DEDUP.LIST"));
    }

    public record Stats(long hits, long misses, long entries) {
    }
}
