// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.Map;

/**
 * The tokens (and, once the model is priced, the cost) a cache's hits did
 * not have to spend, accumulated from usage recorded on a SET call (see
 * e.g. {@link PcacheCommands#set}'s {@link Usage} parameter). Every field
 * is zero when no SET ever carried a usage value. Reported by {@link
 * ScacheCommands#stats}, {@link PcacheCommands#stats} and {@link
 * KeyedCacheCommands#stats}.
 */
public record UsageSavings(long requests, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWriteTokens, double costUsd) {

    static UsageSavings parse(Map<String, String> f) {
        return new UsageSavings(
                Reply.longField(f, "usage_saved_requests"),
                Reply.longField(f, "usage_saved_input_tokens"),
                Reply.longField(f, "usage_saved_output_tokens"),
                Reply.longField(f, "usage_saved_cache_read_tokens"),
                Reply.longField(f, "usage_saved_cache_write_tokens"),
                Reply.doubleField(f, "usage_saved_cost_usd"));
    }
}
