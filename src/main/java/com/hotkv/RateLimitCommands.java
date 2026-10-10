// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed wrappers for {@code RATELIMIT.*}: named rate limiters. Needs a server license that includes the ratelimiting feature. */
public final class RateLimitCommands {

    private final HotKVEngine engine;

    RateLimitCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** {@code algo} is one of "sliding_window" (default), "token_bucket", "leaky_bucket"; refill/drain tune the latter two. */
    public void create(String name, long limit, long windowMillis, String algo, Double refill, Double drain) {
        List<Object> args = new ArrayList<>(List.of("RATELIMIT.CREATE", name, limit, windowMillis));
        if (algo != null) {
            args.add("ALGO");
            args.add(algo);
        }
        if (refill != null) {
            args.add("REFILL");
            args.add(Numbers.decimal(refill));
        }
        if (drain != null) {
            args.add("DRAIN");
            args.add(Numbers.decimal(drain));
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    /** Mutating: advances limiter state. {@code cost} (tokens consumed) defaults to 1 when null. */
    public Check check(String name, String key, Long cost) {
        RespValue v = cost != null
                ? engine.execute("RATELIMIT.CHECK", name, key, "COST", cost)
                : engine.execute("RATELIMIT.CHECK", name, key);
        return decodeCheck(v);
    }

    /** Non-mutating: does not consume. */
    public Check peek(String name, String key) {
        return decodeCheck(engine.execute("RATELIMIT.PEEK", name, key));
    }

    private static Check decodeCheck(RespValue v) {
        List<RespValue> items = Reply.array(v);
        return new Check(Reply.integer(items.get(0)) != 0, Reply.integer(items.get(1)), Reply.integer(items.get(2)),
                Reply.integer(items.get(3)), Reply.integer(items.get(4)));
    }

    public void reset(String name, String key) {
        Reply.ok(engine.execute("RATELIMIT.RESET", name, key));
    }

    public Stats stats(String name) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("RATELIMIT.STATS", name));
        return new Stats(f.get("name"), f.get("algo"), Long.parseLong(f.get("limit")), Long.parseLong(f.get("window_ms")),
                Long.parseLong(f.get("unique_keys")), Long.parseLong(f.get("total_allowed")), Long.parseLong(f.get("total_denied")));
    }

    public void drop(String name) {
        Reply.ok(engine.execute("RATELIMIT.DROP", name));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("RATELIMIT.LIST"));
    }

    public record Check(boolean allowed, long remaining, long retryAfterMillis, long limit, long current) {
    }

    public record Stats(String name, String algo, long limit, long windowMillis, long uniqueKeys, long totalAllowed, long totalDenied) {
    }
}
