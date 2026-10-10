// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.util.List;

/** Typed wrappers for {@code GCRA.*}: the core rate limiter primitive. Core, no license needed. */
public final class GcraCommands {

    private final HotKVEngine engine;

    GcraCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    public GcraResult limit(String key, long periodMillis, long burst) {
        return decode(engine.execute("GCRA.LIMIT", key, periodMillis, burst));
    }

    public GcraResult limit(String key, long periodMillis, long burst, long count) {
        return decode(engine.execute("GCRA.LIMIT", key, periodMillis, burst, count));
    }

    private static GcraResult decode(RespValue v) {
        List<RespValue> items = Reply.array(v);
        return new GcraResult(
                Reply.integer(items.get(0)) != 0,
                Reply.integer(items.get(1)),
                Reply.integer(items.get(2)),
                Reply.integer(items.get(3)),
                Reply.integer(items.get(4)));
    }

    public long reset(String... keys) {
        return Reply.integer(engine.execute(HotKV.prepend("GCRA.RESET", keys)));
    }

    /** {@code retryAfterMillis} is -1 when {@code denied} is false. */
    public record GcraResult(boolean denied, long limit, long remaining, long retryAfterMillis, long resetAfterMillis) {
    }
}
