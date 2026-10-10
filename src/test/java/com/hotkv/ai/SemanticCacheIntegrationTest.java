// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import com.hotkv.HotKV;
import com.hotkv.IntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hotkv.IntegrationTestSupport.runOrSkipIfNotLicensed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link SemanticCache}: SCACHE needs a server license
 * that includes the semanticcache feature. Every check skips with a NOLICENSE
 * reason when it does not, and runs for real once it does.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class SemanticCacheIntegrationTest {

    private static HotKV client;

    @BeforeAll
    static void connect() {
        client = IntegrationTestSupport.client();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @Test
    void llmIsCalledOnlyOnACacheMiss() {
        String namespace = "hkjtest-semcache-" + java.util.UUID.randomUUID();
        SemanticCache cache = new SemanticCache(client, namespace, null);
        runOrSkipIfNotLicensed("semantic-cache", () -> {
            cache.createNamespace(3, "cosine", 0.8);

            AtomicInteger llmCalls = new AtomicInteger();
            double[] vector = {1.0, 0.0, 0.0};

            SemanticCache.Result first = cache.getOrCall("what color is the sky?", vector, () -> {
                llmCalls.incrementAndGet();
                return new LlmCallResult("The sky is blue.", new LlmUsage("gpt-6-sol", 5, 1, 0, 0, 0, 0.0002, 40));
            });
            assertFalse(first.hit());
            assertEquals("The sky is blue.", first.response());
            assertEquals(1, llmCalls.get());

            // A near identical vector should hit the cache: the LLM function must not run again.
            SemanticCache.Result second = cache.getOrCall("what color is the sky?", vector, () -> {
                llmCalls.incrementAndGet();
                return new LlmCallResult("SHOULD NOT BE CALLED", LlmUsage.none());
            });
            assertTrue(second.hit());
            assertEquals("The sky is blue.", second.response());
            assertEquals(1, llmCalls.get(), "the LLM function must not run again on a cache hit");

            assertTrue(cache.stats().entries() >= 1);
        });
    }

    @Test
    void cacheKeyDeterminismAcrossEquivalentInput() {
        List<PromptMessage> a = List.of(new PromptMessage("user", "hi"));
        List<PromptMessage> b = List.of(new PromptMessage("user", "hi"));
        String k1 = new PromptCacheKeyInput("p", "m", "", a, java.util.Map.of()).cacheKey();
        String k2 = new PromptCacheKeyInput("p", "m", "", b, java.util.Map.of()).cacheKey();
        assertEquals(k1, k2);
    }
}
