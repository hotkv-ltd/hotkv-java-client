// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.hotkv.IntegrationTestSupport.runOrSkipIfNotLicensed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the LLM related command families that need a server
 * license with the matching features: PCACHE.*, the keyed caches
 * TOOL.CACHE/NCACHE/GUARD/RERANK, LLMSTATS.*, LLMBUDGET.*, PROMPT.*,
 * EMBED.DEDUP.*, AMEM.SUMMARY.* and VSIM persistence. Every check skips
 * with a NOLICENSE reason when the server's license does not cover the
 * feature, and runs for real once it does.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class LicensedLlmFamiliesIntegrationTest {

    private static HotKV client;

    @BeforeAll
    static void connect() {
        client = IntegrationTestSupport.client();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    private static String ns(String label) {
        return "hkjtest-" + label + "-" + UUID.randomUUID();
    }

    @Test
    void promptCache() {
        String namespace = ns("pcache");
        runOrSkipIfNotLicensed("prompt-cache", () -> {
            client.pcache().create(namespace, null, null, null);
            Usage usage = Usage.of("gpt-4").input(12).output(8);
            client.pcache().set(namespace, "prompt", "response", null, null, usage);
            assertEquals("response", client.pcache().get(namespace, "prompt"));
            PcacheCommands.GetExResult getEx = client.pcache().getEx(namespace, "prompt");
            assertEquals("response", getEx.response());
            PcacheCommands.Stats stats = client.pcache().stats(namespace);
            assertTrue(stats.entries() >= 1);
            client.pcache().drop(namespace);
        });
    }

    @Test
    void keyedCaches() {
        for (var accessor : List.<java.util.function.Supplier<KeyedCacheCommands>>of(
                () -> client.toolCache(), () -> client.ncache(), () -> client.guard(), () -> client.rerank())) {
            KeyedCacheCommands cache = accessor.get();
            String namespace = ns("keyed");
            runOrSkipIfNotLicensed("keyed-cache", () -> {
                cache.create(namespace, null, null);
                cache.set(namespace, "k", "v", null, null, Usage.of("gpt-4").input(5));
                assertEquals("v", cache.get(namespace, "k"));
                KeyedCacheCommands.Stats stats = cache.stats(namespace);
                assertTrue(stats.entries() >= 1);
                cache.drop(namespace);
            });
        }
    }

    @Test
    void llmStatsAndBudget() {
        String bucket = ns("llmstats");
        runOrSkipIfNotLicensed("llm-stats", () -> {
            client.llmstats().bucket(bucket);
            client.llmstats().record(bucket, 100, 50, 0, 0.01, 200, null);
            client.llmstats().record(bucket, 100, 50, 10, 0, 80,
                    LlmStatsRecordOptions.builder().model("gpt-4").cacheWrite5m(20));
            LlmStatsCommands.Snapshot snap = client.llmstats().get(bucket);
            assertEquals(2, snap.requests());
            client.llmstats().drop(bucket);
        });

        String model = ns("model");
        runOrSkipIfNotLicensed("llm-stats-price", () -> {
            client.llmstats().priceSet(model, 3, 15, null);
            LlmStatsCommands.LlmPrice price = client.llmstats().priceGet(model);
            assertEquals("custom", price.source());
            assertTrue(price.priced());
            assertTrue(client.llmstats().priceList().size() >= 1);
            assertTrue(client.llmstats().priceDel(model));
        });

        String budget = ns("llmbudget");
        runOrSkipIfNotLicensed("llm-budget", () -> {
            client.llmBudget().set(budget, 1000, 10.0, 3600);
            assertTrue(client.llmBudget().consume(budget, 100, 1.0, null));
            assertTrue(client.llmBudget().consume(budget, 0, 0, Usage.of("gpt-4").input(10).output(5)));
            LlmBudgetCommands.Status status = client.llmBudget().status(budget);
            assertEquals(115, status.tokensUsed());
            client.llmBudget().drop(budget);
        });
    }

    @Test
    void promptRegistry() {
        String name = ns("prompt");
        runOrSkipIfNotLicensed("prompt-registry", () -> {
            long v1 = client.promptRegistry().put(name, "Hello {{name}}", null);
            assertEquals(1, v1);
            PromptRegistryCommands.Version latest = client.promptRegistry().latest(name);
            assertEquals("Hello {{name}}", latest.content());
            assertEquals(List.of(1L), client.promptRegistry().versions(name));
            client.promptRegistry().drop(name);
        });
    }

    @Test
    void embedDedup() {
        String namespace = ns("embed");
        runOrSkipIfNotLicensed("embed-dedup", () -> {
            assertTrue(client.embedDedup().put(namespace, "hash1", "text-embedding-3", "0.1,0.2,0.3"));
            assertEquals("0.1,0.2,0.3", client.embedDedup().get(namespace, "hash1"));
            EmbedDedupCommands.Stats stats = client.embedDedup().stats(namespace);
            assertTrue(stats.entries() >= 1);
            client.embedDedup().drop(namespace);
        });
    }

    @Test
    void agentMemorySummary() {
        String agentId = ns("summary");
        runOrSkipIfNotLicensed("amem-summary", () -> {
            long v1 = client.amemSummary().set(agentId, "Initial summary.");
            assertEquals(1, v1);
            AmemSummaryCommands.AgentSummary summary = client.amemSummary().get(agentId);
            assertEquals("Initial summary.", summary.summary());
            client.amemSummary().drop(agentId);
        });
    }

    @Test
    void vsimPersistence() {
        runOrSkipIfNotLicensed("vsim-persist", () -> {
            client.vsim().save("hkjtest-snapshot", new byte[] {1, 2, 3});
            assertNotNull(client.vsim().list());
            client.vsim().drop("hkjtest-snapshot");
        });
    }
}
