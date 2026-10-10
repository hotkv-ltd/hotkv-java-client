// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import com.hotkv.HotKV;
import com.hotkv.LlmStatsRecordOptions;
import com.hotkv.PcacheCommands;
import com.hotkv.exceptions.HotKVServerException;
import com.hotkv.exceptions.NotLicensedException;

import java.util.function.Supplier;

/**
 * An idiomatic get-or-call cache in front of {@code PCACHE.*}: on a cache
 * hit it returns the stored response without invoking the LLM; on a miss
 * it calls the caller's function, stores the result, and returns it.
 * Needs a server license that includes the promptcache feature; if it
 * is unavailable, {@link #getOrCall} degrades to always calling
 * the function (the cache is treated as unavailable, not an error).
 *
 * <pre>{@code
 * PromptCache cache = new PromptCache(client, "chat", 3600L).withStatsBucket("chat-llm");
 * cache.createNamespace(null, null);
 *
 * PromptCache.Result r = cache.getOrCall(
 *     new PromptCacheKeyInput("openai", "gpt-6-sol", messages),
 *     () -> {
 *         String answer = callTheLlm(messages); // only runs on a miss
 *         return new LlmCallResult(answer, new LlmUsage(120, 40, 0.002, 850));
 *     });
 * }</pre>
 */
public final class PromptCache {

    private final HotKV client;
    private final String namespace;
    private final Long ttlSeconds;
    private String statsBucket;

    public PromptCache(HotKV client, String namespace, Long ttlSeconds) {
        this.client = client;
        this.namespace = namespace;
        this.ttlSeconds = ttlSeconds;
    }

    /** Enables {@code LLMSTATS.RECORD} accounting on every {@link #getOrCall} (hit or miss) against {@code bucket}. */
    public PromptCache withStatsBucket(String bucket) {
        this.statsBucket = bucket;
        if (bucket != null) {
            client.llmstats().bucket(bucket);
        }
        return this;
    }

    /** Ensures the backing PCACHE namespace exists; safe to call again. */
    public void createNamespace(Long maxEntries, Double costPerHit) {
        try {
            client.pcache().create(namespace, maxEntries, ttlSeconds, costPerHit);
        } catch (HotKVServerException e) {
            if (!e.rawMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    /** Returns the cached response for {@code input} if present, otherwise calls {@code fn}, caches it, and returns it. */
    public Result getOrCall(PromptCacheKeyInput input, Supplier<LlmCallResult> fn) {
        String key = input.cacheKey();
        long start = System.nanoTime();

        try {
            String cached = client.pcache().get(namespace, key);
            if (cached != null) {
                record(LlmUsage.none(), true, elapsedMillis(start));
                return new Result(cached, true);
            }
        } catch (NotLicensedException notLicensed) {
            // Fall through to calling fn: the cache is unavailable, not the LLM.
        }

        LlmCallResult result = fn.get();
        try {
            client.pcache().set(namespace, key, result.response(), ttlSeconds, null, result.usage().asUsage());
        } catch (NotLicensedException ignored) {
            // Cache unavailable; the fresh response is still returned below.
        }
        record(result.usage(), false, result.usage().latencyMillis());
        return new Result(result.response(), false);
    }

    /** The current {@code PCACHE.STATS} for this cache's namespace, including how many tokens and how much cost recorded usage has saved on hits. */
    public PcacheCommands.Stats stats() {
        return client.pcache().stats(namespace);
    }

    private void record(LlmUsage usage, boolean hit, long latencyMillis) {
        if (statsBucket == null) {
            return;
        }
        long input = hit ? 0 : usage.inputTokens();
        long output = hit ? 0 : usage.outputTokens();
        long cached = hit ? usage.inputTokens() + usage.outputTokens() : 0;
        double cost = hit ? 0.0 : usage.costUsd();
        LlmStatsRecordOptions opts = LlmStatsRecordOptions.builder();
        if (!hit && usage.model() != null && !usage.model().isEmpty()) {
            opts.model(usage.model()).cacheWrite5m(usage.cacheWrite5mTokens()).cacheWrite1h(usage.cacheWrite1hTokens());
        }
        try {
            client.llmstats().record(statsBucket, input, output, cached, cost, latencyMillis, opts);
        } catch (NotLicensedException ignored) {
            // LLMSTATS needs its own license feature; accounting is best effort.
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public record Result(String response, boolean hit) {
    }
}
