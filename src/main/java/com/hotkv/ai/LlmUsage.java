// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import com.hotkv.Usage;

/**
 * Token/cost/latency accounting for one LLM call, returned by the caller's
 * model function on a {@link PromptCache}/{@link SemanticCache} miss.
 * {@code model}, when non-empty, does two things: it enables {@code
 * LLMSTATS.RECORD} based accounting ({@link PromptCache#withStatsBucket})
 * to price the call from the server's price table, and it is attached to
 * the stored cache entry (the {@code PCACHE.SET}/{@code SCACHE.SET} usage
 * group, see {@link Usage}) so a later hit attributes the tokens and cost
 * that hit did not have to spend.
 */
public record LlmUsage(String model, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWrite5mTokens, long cacheWrite1hTokens, double costUsd, long latencyMillis) {

    public static LlmUsage none() {
        return new LlmUsage(null, 0, 0, 0, 0, 0, 0.0, 0);
    }

    /** This usage as a cache-entry {@link Usage} group, or null when {@link #model} is empty (Usage requires one). */
    public Usage asUsage() {
        if (model == null || model.isEmpty()) {
            return null;
        }
        return Usage.of(model)
                .input(inputTokens)
                .output(outputTokens)
                .cacheRead(cacheReadTokens)
                .cacheWrite5m(cacheWrite5mTokens)
                .cacheWrite1h(cacheWrite1hTokens);
    }
}
