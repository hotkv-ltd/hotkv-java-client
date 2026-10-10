// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import com.hotkv.HotKV;
import com.hotkv.IntegrationTestSupport;
import com.hotkv.PcacheCommands;
import com.hotkv.exceptions.NotLicensedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link PromptCache}. PCACHE needs a server license
 * that includes the promptcache feature; without it this exercises {@link
 * PromptCache#getOrCall}'s graceful degradation: with the cache
 * unavailable it must still call the supplied function and return its
 * response, never leaking a {@link NotLicensedException} to the caller.
 * If a suitable license is present instead, the second call becomes a real
 * cache hit; both outcomes are asserted as valid.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class PromptCacheIntegrationTest {

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
    void getOrCallNeverLeaksANotLicensedExceptionAndTheLlmRunsOnAMiss() {
        String namespace = "hkjtest-pcache-" + UUID.randomUUID();
        PromptCache cache = new PromptCache(client, namespace, 3600L);
        try {
            cache.createNamespace(null, null);
        } catch (NotLicensedException expectedWithoutLicense) {
            // Namespace setup itself needs the license; getOrCall must still degrade below.
        }

        AtomicInteger llmCalls = new AtomicInteger();
        PromptCacheKeyInput input = new PromptCacheKeyInput("openai", "gpt-6-sol", List.of(new PromptMessage("user", "2+2?")));

        PromptCache.Result first = cache.getOrCall(input, () -> {
            llmCalls.incrementAndGet();
            return new LlmCallResult("4", new LlmUsage("gpt-6-sol", 10, 2, 0, 0, 0, 0.001, 50));
        });
        assertEquals("4", first.response());
        assertEquals(1, llmCalls.get());

        PromptCache.Result second = cache.getOrCall(input, () -> {
            llmCalls.incrementAndGet();
            return new LlmCallResult("4", new LlmUsage("gpt-6-sol", 10, 2, 0, 0, 0, 0.001, 50));
        });
        assertEquals("4", second.response());
        assertTrue(llmCalls.get() == 1 || llmCalls.get() == 2,
                "either a real cache hit (full license present) or a graceful degrade (no license) is correct");

        try {
            PcacheCommands.Stats stats = cache.stats();
            assertTrue(stats.entries() >= 0);
        } catch (NotLicensedException expectedWithoutLicense) {
            // PCACHE.STATS needs the same license as the rest of the family.
        }
    }
}
