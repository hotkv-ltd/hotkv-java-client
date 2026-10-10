// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import com.hotkv.HotKV;
import com.hotkv.ScacheCommands;
import com.hotkv.exceptions.HotKVServerException;
import com.hotkv.exceptions.NotLicensedException;

import java.util.function.Supplier;

/**
 * An idiomatic get-or-call cache in front of {@code SCACHE.*}: looks up
 * the nearest cached answer to a caller supplied embedding vector above
 * the namespace's similarity threshold, calling the caller's function
 * only on a miss. Never computes embeddings itself: callers supply
 * vectors from whatever embedding provider they use. Needs a
 * server license that includes the semanticcache feature; if it
 * is unavailable, {@link #getOrCall} degrades to always calling the
 * function.
 */
public final class SemanticCache {

    private final HotKV client;
    private final String namespace;
    private final Long ttlSeconds;

    public SemanticCache(HotKV client, String namespace, Long ttlSeconds) {
        this.client = client;
        this.namespace = namespace;
        this.ttlSeconds = ttlSeconds;
    }

    /** {@code metric} is one of "cosine"/"l2"/"ip", or null for the server default (cosine); {@code threshold} null for 0.85. */
    public void createNamespace(int dims, String metric, Double threshold) {
        try {
            client.scache().create(namespace, dims, metric, threshold, ttlSeconds);
        } catch (HotKVServerException e) {
            if (!e.rawMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    /**
     * Returns the nearest cached answer for {@code vector} above the namespace threshold, if
     * any, otherwise calls {@code fn} and caches its response together with any usage it
     * reports (model required for it to be attached; see {@link LlmUsage}).
     */
    public Result getOrCall(String query, double[] vector, Supplier<LlmCallResult> fn) {
        ScacheCommands.ScacheResult hit;
        try {
            hit = client.scache().get(namespace, vector, null);
        } catch (NotLicensedException notLicensed) {
            hit = null;
        }
        if (hit != null) {
            return new Result(hit.response(), true, hit.similarity());
        }

        LlmCallResult result = fn.get();
        try {
            client.scache().set(namespace, query, result.response(), vector, ttlSeconds, result.usage().asUsage());
        } catch (NotLicensedException ignored) {
            // Cache unavailable; the fresh response is still returned below.
        }
        return new Result(result.response(), false, null);
    }

    /** The current {@code SCACHE.STATS} for this cache's namespace, including how many tokens and how much cost recorded usage has saved on hits. */
    public ScacheCommands.ScacheStats stats() {
        return client.scache().stats(namespace);
    }

    public record Result(String response, boolean hit, Double similarity) {
    }
}
