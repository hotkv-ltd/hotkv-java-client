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
 * Integration tests for the platform command families that need a server
 * license with the matching features: SCACHE.*, AMEM.* (all
 * four memory kinds plus cross search), RATELIMIT.*, TS.*,
 * FEATURE.*, RAG.*, and OBSERVE/ALERTS. Every check skips with a
 * NOLICENSE reason when the server's license does not cover the feature,
 * and runs for real once it does.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class LicensedPlatformFamiliesIntegrationTest {

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
    void semanticCache() {
        String namespace = ns("scache");
        runOrSkipIfNotLicensed("semantic-cache", () -> {
            client.scache().create(namespace, 3, "cosine", 0.5, null);
            try {
                client.scache().set(namespace, "hello", "world", new double[] {1.0, 0.0, 0.0}, null,
                        Usage.of("gpt-4").input(10).output(4));
                ScacheCommands.ScacheResult hit = client.scache().get(namespace, new double[] {1.0, 0.0, 0.0}, null);
                assertNotNull(hit);
                assertEquals("world", hit.response());

                ScacheCommands.ScacheStats stats = client.scache().stats(namespace);
                assertTrue(stats.entries() >= 1);
                assertTrue(client.scache().list().contains(namespace));
            } finally {
                client.scache().drop(namespace);
            }
        });
    }

    @Test
    void agentMemoryConversation() {
        String session = ns("conv");
        runOrSkipIfNotLicensed("agent-memory", () -> {
            client.amem().convCreate(session, null, null);
            client.amem().convAppend(session, "user", "hi");
            client.amem().convAppend(session, "assistant", "hello!");
            List<AmemCommands.ConversationTurn> turns = client.amem().convGet(session);
            assertEquals(2, turns.size());
            assertEquals("user", turns.get(0).role());
            assertEquals(1, client.amem().convWindow(session, 1).size());
            assertTrue(client.amem().convClear(session));
            client.amem().convDrop(session);
        });
    }

    @Test
    void agentMemorySemanticAndSearch() {
        String agentId = ns("agent");
        runOrSkipIfNotLicensed("agent-memory", () -> {
            client.amem().semCreate(agentId, 3);
            client.amem().semStore(agentId, "fact1", "the sky is blue", new double[] {1.0, 0.0, 0.0}, null);
            List<AmemCommands.SemanticRecall> recalled = client.amem().semRecall(agentId, 1, new double[] {1.0, 0.0, 0.0});
            assertEquals(1, recalled.size());
            assertEquals("fact1", recalled.get(0).key());

            List<AmemCommands.MemorySearchResult> searched = client.amem().search(agentId, 5, new double[] {1.0, 0.0, 0.0});
            assertTrue(searched.size() >= 1);

            assertTrue(client.amem().semForget(agentId, "fact1"));
        });
    }

    @Test
    void agentMemoryWorkingAndEpisodic() {
        String session = ns("work");
        String agentId = ns("epis");
        runOrSkipIfNotLicensed("agent-memory", () -> {
            client.amem().workSet(session, "k", "v", null);
            assertEquals("v", client.amem().workGet(session, "k"));
            assertTrue(client.amem().workList(session).contains("k"));
            assertTrue(client.amem().workDel(session, "k"));

            client.amem().episLog(agentId, "tool_call", "did a thing", null);
            List<AmemCommands.EpisodicEvent> events = client.amem().episQuery(agentId, null, null, null, null);
            assertEquals(1, events.size());
            AmemCommands.EpisStats stats = client.amem().episStats(agentId);
            assertEquals(1, stats.totalEvents());
            assertTrue(client.amem().episClear(agentId));
        });
    }

    @Test
    void ragPipeline() {
        String pipeline = ns("rag");
        runOrSkipIfNotLicensed("rag-pipeline", () -> {
            client.rag().create(pipeline, 3, "fixed", 512, 64);
            try {
                long chunks = client.rag().ingest(pipeline, "doc1", "hello world", null, List.of(new double[] {1.0, 0.0, 0.0}));
                assertTrue(chunks >= 1);

                List<RagCommands.RagResult> results = client.rag().search(pipeline, "hello", 5, 0.5, new double[] {1.0, 0.0, 0.0}, null);
                assertTrue(results.size() >= 1);

                RagCommands.Stats stats = client.rag().stats(pipeline);
                assertEquals(pipeline, stats.name());
                assertTrue(client.rag().list().contains(pipeline));
            } finally {
                client.rag().drop(pipeline);
            }
        });
    }

    @Test
    void rateLimiting() {
        String name = ns("ratelimit");
        runOrSkipIfNotLicensed("rate-limiting", () -> {
            client.rateLimit().create(name, 2, 60_000, null, null, null);
            try {
                RateLimitCommands.Check c1 = client.rateLimit().check(name, "userA", null);
                assertTrue(c1.allowed());
                client.rateLimit().check(name, "userA", null);
                RateLimitCommands.Check c3 = client.rateLimit().check(name, "userA", null);
                assertTrue(!c3.allowed());

                RateLimitCommands.Stats stats = client.rateLimit().stats(name);
                assertEquals(name, stats.name());
                client.rateLimit().reset(name, "userA");
            } finally {
                client.rateLimit().drop(name);
            }
        });
    }

    @Test
    void timeSeries() {
        String key = ns("ts");
        runOrSkipIfNotLicensed("time-series", () -> {
            client.timeSeries().create(key, 0L, Map.of("region", "eu"));
            try {
                client.timeSeries().add(key, 1000L, 10.0);
                client.timeSeries().add(key, 2000L, 20.0);

                List<TimeSeriesCommands.TsSample> range = client.timeSeries().range(key, "-", "+", null);
                assertEquals(2, range.size());

                List<TimeSeriesCommands.TsSample> agg = client.timeSeries().agg(key, "-", "+", 10_000, "avg");
                assertTrue(agg.size() >= 1);

                TimeSeriesCommands.TsInfo info = client.timeSeries().info(key);
                assertEquals(2, info.totalSamples());
                assertEquals("eu", info.labels().get("region"));

                assertTrue(client.timeSeries().list(null, null).contains(key));
            } finally {
                client.timeSeries().delete(key);
            }
        });
    }

    @Test
    void featureStore() {
        String group = ns("features");
        runOrSkipIfNotLicensed("feature-store", () -> {
            client.featureStore().create(group, "test group", Map.of("score", "double"));
            try {
                client.featureStore().set(group, "entity1", Map.of("score", "0.9"));
                FeatureStoreCommands.FeatureValues values = client.featureStore().get(group, "entity1", null);
                // The server reformats numeric feature values by schema type (e.g. fixed decimals for "double"),
                // so compare numerically rather than expecting an exact string round trip.
                assertEquals(0.9, Double.parseDouble(values.values().get("score")), 1e-9);

                List<FeatureStoreCommands.FeatureValues> batch = client.featureStore().batch(group, List.of("entity1", "missing"), null);
                assertEquals(2, batch.size());

                Map<String, String> schema = client.featureStore().schema(group);
                assertEquals("double", schema.get("score"));

                FeatureStoreCommands.Stats stats = client.featureStore().stats(group);
                assertTrue(stats.entities() >= 1);
            } finally {
                client.featureStore().drop(group);
            }
        });
    }

    @Test
    void observeAndAlerts() {
        runOrSkipIfNotLicensed("enterprise-observability", () -> {
            ObserveCommands.Info info = client.observe().info();
            assertTrue(info.uptimeSeconds() >= 0);
            ObserveCommands.Metrics metrics = client.observe().metrics();
            assertTrue(metrics.totalCommands() >= 0);

            AlertsCommands.Info alerts = client.alerts().info();
            assertNotNull(alerts.supportedProviders());
        });
    }
}
