// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.HotKVServerException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live checks of the enterprise/AI typed methods against a server with a valid license. The
 * whole class is skipped (with a reason) when the server is unlicensed, in which case the
 * wire-level unit tests are the only verification of these methods. Every test asserts reply
 * values.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class EnterpriseLiveTest {

    private static HotKV client;

    @BeforeAll
    static void connect() {
        client = IntegrationTestSupport.client();
        IntegrationTestSupport.assumeLicensed(client);
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    private static String ns(String label) {
        return "hkjlive-" + label + "-" + UUID.randomUUID();
    }

    // ---------------------------------------------------------------- LLMBUDGET float formatting, live

    @Test
    void llmBudgetSetAcceptsTinyHugeAndFractionalLimits() {
        String name = ns("budget");
        try {
            client.llmBudget().set(name, 1000, 1e-5, 60); // Double.toString would be "1.0E-5", which is never sent
            assertEquals(0.00001, client.llmBudget().status(name).costLimitUsd(), 1e-12);
            client.llmBudget().set(name, 1000, 0.000001, 60);
            assertEquals(0.000001, client.llmBudget().status(name).costLimitUsd(), 1e-12);
            client.llmBudget().set(name, 1000, 12.5, 60);
            assertEquals(12.5, client.llmBudget().status(name).costLimitUsd(), 1e-9);
            client.llmBudget().set(name, 1000, 1e7, 60);
            assertEquals(1e7, client.llmBudget().status(name).costLimitUsd(), 1e-3);
            client.llmBudget().set(name, 0, 3, 60);
            assertEquals(3.0, client.llmBudget().status(name).costLimitUsd(), 1e-9);
        } finally {
            client.llmBudget().drop(name);
        }
    }

    @Test
    void llmBudgetConsumeEnforcesTheCostCap() {
        String name = ns("budget-cap");
        try {
            client.llmBudget().set(name, 0, 0.00003, 3600);
            assertTrue(client.llmBudget().consume(name, 1, 1e-5, null));
            assertTrue(client.llmBudget().consume(name, 1, 1e-5, null));
            assertTrue(client.llmBudget().consume(name, 1, 1e-5, null));
            assertFalse(client.llmBudget().consume(name, 1, 1e-5, null)); // 4th would exceed 0.00003
            LlmBudgetCommands.Status st = client.llmBudget().status(name);
            assertEquals(0.00003, st.costUsedUsd(), 1e-9);
            assertEquals(3, st.requestsAllowed());
            assertEquals(1, st.requestsRejected());
            assertTrue(client.llmBudget().reset(name));
            assertEquals(0, client.llmBudget().status(name).tokensUsed());
            assertTrue(client.llmBudget().list().contains(name));
        } finally {
            client.llmBudget().drop(name);
        }
    }

    @Test
    void llmStatsRecordTinyCostAndPrices() {
        String bucket = ns("stats");
        String model = ns("model");
        try {
            client.llmstats().bucket(bucket);
            client.llmstats().record(bucket, 10, 5, 0, 1.5e-6, 100, null);
            LlmStatsCommands.Snapshot s = client.llmstats().get(bucket);
            assertEquals(1, s.requests());
            assertEquals(15, s.inputTokens() + s.outputTokens());
            assertEquals(0.000001, s.costUsd(), 1e-12); // the server truncates a cost to whole micro dollars

            client.llmstats().priceSet(model, 1e-5, 12.5,
                    LlmPriceOptions.builder().cacheReadUsdPerMtok(0.000001).cacheWrite5mUsdPerMtok(3.75));
            LlmStatsCommands.LlmPrice p = client.llmstats().priceGet(model);
            assertTrue(p.priced());
            assertEquals(0.00001, p.inputUsdPerMtok(), 1e-12);
            assertEquals(12.5, p.outputUsdPerMtok(), 1e-12);
            assertEquals(0.000001, p.cacheReadUsdPerMtok(), 1e-12);
            assertEquals(3.75, p.cacheWrite5mUsdPerMtok(), 1e-12);
            assertTrue(client.llmstats().priceDel(model));
            assertTrue(client.llmstats().list().contains(bucket));
            assertTrue(client.llmstats().reset(bucket));
            assertEquals(0, client.llmstats().get(bucket).requests());
        } finally {
            client.llmstats().drop(bucket);
            client.llmstats().priceDel(model);
        }
    }

    @Test
    void floatArgumentsInOtherFamiliesAreAcceptedInPlainForm() {
        String limiter = ns("rl-float");
        String ts = ns("ts-float");
        String pc = ns("pc-float");
        String sc = ns("sc-float");
        try {
            client.rateLimit().create(limiter, 10, 1000, "token_bucket", 1e-5, null);
            assertEquals("token_bucket", client.rateLimit().stats(limiter).algo());
            client.timeSeries().create(ts, 0L, null);
            client.timeSeries().add(ts, 1000L, 1e-7);
            client.timeSeries().add(ts, 2000L, 1e15);
            List<TimeSeriesCommands.TsSample> r = client.timeSeries().range(ts, "-", "+", null);
            assertEquals(2, r.size());
            assertEquals(1e-7, r.get(0).value(), 1e-6); // the server prints 6 decimals
            assertEquals(1e15, r.get(1).value(), 1.0);
            client.pcache().create(pc, null, null, 2e-6);
            client.scache().create(sc, 2, "cosine", 1e-5, null);
            client.scache().threshold(sc, 0.25);
        } finally {
            client.rateLimit().drop(limiter);
            client.timeSeries().delete(ts);
            client.pcache().drop(pc);
            client.scache().drop(sc);
        }
    }

    @Test
    void zaddAndGeoAcceptTinyAndHugeValues() {
        String z = ns("z-float");
        String g = ns("geo-float");
        try {
            client.zadd(z, Map.of("tiny", 1e-12, "huge", 1e21, "neg", -1e-5));
            assertEquals(List.of("neg", "tiny", "huge"), client.zrange(z, 0, -1));
            assertEquals(1e-12, client.zscore(z, "tiny"), 1e-20);
            assertEquals(1e21, client.zscore(z, "huge"), 1e6);
            assertEquals(1, client.zadd(z, Double.POSITIVE_INFINITY, "top"));
            assertEquals("top", client.zrange(z, -1, -1).get(0));
            client.geoAdd(g, 0.00001, -0.00002, "near-origin");
            assertEquals(1, client.geoPos(g, "near-origin").size());
            assertEquals(0.00001, client.geoPos(g, "near-origin").get(0).longitude(), 1e-4); // geohash precision
            assertEquals(5.0, client.incrByFloat(ns("inc"), 5), 1e-9);
        } finally {
            client.del(z, g);
        }
    }

    // ---------------------------------------------------------------- remaining untested typed methods

    @Test
    void agentMemorySemanticListAndWorkingClear() {
        String agent = ns("agent");
        String session = ns("sess");
        client.amem().semCreate(agent, 2);
        client.amem().semStore(agent, "k1", "first", new double[] {1, 0}, null);
        client.amem().semStore(agent, "k2", "second", new double[] {0, 1}, null);
        assertEquals(java.util.Set.of("k1", "k2"), new java.util.HashSet<>(client.amem().semList(agent)));
        List<AmemCommands.SemanticRecall> top = client.amem().semRecall(agent, 1, new double[] {0.9, 0.1});
        assertEquals("k1", top.get(0).key());
        assertEquals("first", top.get(0).content());
        assertTrue(client.amem().semForget(agent, "k1"));
        assertFalse(client.amem().semForget(agent, "k1"));

        client.amem().workSet(session, "a", "1", null);
        client.amem().workSet(session, "b", "2", 100L);
        assertEquals(java.util.Set.of("a", "b"), new java.util.HashSet<>(client.amem().workList(session)));
        assertTrue(client.amem().workClear(session));
        assertNull(client.amem().workGet(session, "a"));
    }

    @Test
    void embedDedupReleaseAndPurge() {
        String space = ns("embed");
        assertTrue(client.embedDedup().put(space, "h1", "m", "0.1,0.2"));
        assertFalse(client.embedDedup().put(space, "h1", "m", "0.1,0.2")); // second put is a dedup hit
        assertEquals("0.1,0.2", client.embedDedup().get(space, "h1"));
        assertTrue(client.embedDedup().release(space, "h1"));
        assertEquals(1, client.embedDedup().stats(space).entries()); // still referenced once
        assertTrue(client.embedDedup().release(space, "h1"));
        assertEquals(0, client.embedDedup().stats(space).entries());
        client.embedDedup().put(space, "h2", "m", "0.3");
        assertTrue(client.embedDedup().purge(space));
        assertEquals(0, client.embedDedup().stats(space).entries());
        client.embedDedup().drop(space);
    }

    @Test
    void promptCacheConfigAndFlush() {
        String space = ns("pc");
        try {
            client.pcache().create(space, null, null, null);
            client.pcache().configSet(space, "MAX_ENTRIES", "100");
            client.pcache().configSet(space, "TTL", "600");
            client.pcache().configSet(space, "COST_PER_HIT", "0.5");
            assertThrows(HotKVServerException.class, () -> client.pcache().configSet(space, "BOGUS", "1"));
            client.pcache().set(space, "p1", "r1", null, null, null);
            client.pcache().set(space, "p2", "r2", null, null, null);
            assertEquals(2, client.pcache().stats(space).entries());
            assertEquals(2, client.pcache().flush(space));
            assertEquals(0, client.pcache().stats(space).entries());
            assertNull(client.pcache().get(space, "p1"));
        } finally {
            client.pcache().drop(space);
        }
    }

    @Test
    void promptRegistrySetActive() {
        String name = ns("prompt");
        try {
            assertEquals(1, client.promptRegistry().put(name, "first", null));
            assertEquals(2, client.promptRegistry().put(name, "second", null));
            client.promptRegistry().setActive(name, 1);
            // LATEST and an unversioned GET return the active version, not the highest one
            assertEquals("first", client.promptRegistry().get(name, null).content());
            assertEquals("first", client.promptRegistry().latest(name).content());
            assertEquals("second", client.promptRegistry().get(name, 2L).content());
            assertEquals(List.of(1L, 2L), client.promptRegistry().versions(name));
            assertThrows(HotKVServerException.class, () -> client.promptRegistry().setActive(name, 99));
        } finally {
            client.promptRegistry().drop(name);
        }
    }

    @Test
    void rateLimitPeekDoesNotConsume() {
        String name = ns("rl");
        try {
            client.rateLimit().create(name, 2, 60_000, null, null, null);
            RateLimitCommands.Check peek0 = client.rateLimit().peek(name, "u");
            assertTrue(peek0.allowed());
            assertEquals(2, peek0.remaining());
            assertEquals(2, peek0.limit());
            RateLimitCommands.Check c1 = client.rateLimit().check(name, "u", null);
            assertTrue(c1.allowed());
            assertEquals(1, c1.remaining());
            assertEquals(1, client.rateLimit().peek(name, "u").remaining());
            assertEquals(1, client.rateLimit().peek(name, "u").remaining()); // peeking twice changes nothing
            client.rateLimit().check(name, "u", null);
            RateLimitCommands.Check denied = client.rateLimit().check(name, "u", null);
            assertFalse(denied.allowed());
            assertEquals(0, denied.remaining());
            assertTrue(denied.retryAfterMillis() > 0);
            assertTrue(client.rateLimit().list().contains(name));
        } finally {
            client.rateLimit().drop(name);
        }
    }

    @Test
    void semanticCacheFlushAndThreshold() {
        String space = ns("sc");
        try {
            client.scache().create(space, 2, "cosine", 0.99, null);
            client.scache().set(space, "q", "answer", new double[] {1, 0}, null, null);
            // cosine similarity of (1,0) and (0.9,0.436) is about 0.9: below 0.99 -> miss
            assertNull(client.scache().get(space, new double[] {0.9, 0.436}, null));
            client.scache().threshold(space, 0.5);
            ScacheCommands.ScacheResult hit = client.scache().get(space, new double[] {0.9, 0.436}, null);
            assertNotNull(hit);
            assertEquals("answer", hit.response());
            assertTrue(hit.similarity() > 0.85 && hit.similarity() < 0.95, "similarity " + hit.similarity());
            assertTrue(client.scache().flush(space));
            assertEquals(0, client.scache().stats(space).entries());
            assertNull(client.scache().get(space, new double[] {1, 0}, null));
        } finally {
            client.scache().drop(space);
        }
    }

    @Test
    void timeSeriesDownsampleAndRetention() {
        String src = ns("ts-src");
        String dst = ns("ts-dst");
        try {
            client.timeSeries().create(src, 0L, null);
            client.timeSeries().create(dst, 0L, null);
            client.timeSeries().downsample(src, dst, "avg", 10_000);
            client.timeSeries().add(src, 1000L, 10.0);
            client.timeSeries().add(src, 2000L, 20.0);
            client.timeSeries().add(src, 15_000L, 99.0); // closes the first bucket
            // The destination accumulates the raw samples; aggregation happens at query time (TS.AGG).
            List<TimeSeriesCommands.TsSample> out = client.timeSeries().range(dst, "-", "+", null);
            assertEquals(3, out.size());
            assertEquals(20.0, out.get(1).value(), 1e-6);
            List<TimeSeriesCommands.TsSample> agg = client.timeSeries().agg(dst, "-", "+", 10_000, "avg");
            assertEquals(2, agg.size());
            assertEquals(15.0, agg.get(0).value(), 1e-6);
            assertEquals(99.0, agg.get(1).value(), 1e-6);

            client.timeSeries().retention(src, 5000);
            assertEquals(5000, client.timeSeries().info(src).retentionMillis());
            assertThrows(HotKVServerException.class, () -> client.timeSeries().downsample(src, ns("missing"), "avg", 1000));
        } finally {
            client.timeSeries().delete(src);
            client.timeSeries().delete(dst);
        }
    }

    @Test
    void vectorSnapshotSaveAndLoadIsBinarySafe() {
        String snap = ns("snap");
        byte[] blob = new byte[256];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) i;
        }
        try {
            client.vsim().save(snap, blob);
            assertArrayEquals(blob, client.vsim().load(snap));
            assertTrue(client.vsim().list().contains(snap));
        } finally {
            assertTrue(client.vsim().drop(snap));
        }
    }
}
