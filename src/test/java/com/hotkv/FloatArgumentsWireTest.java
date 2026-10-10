// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exact wire arguments for every typed method that formats a floating point value, checked
 * against a loopback fake, so the enterprise families are verified without a licensed server.
 * Floating point values are always written as plain decimals (no exponent).
 */
class FloatArgumentsWireTest {

    private static final String OK = "+OK\r\n";
    private static final String ZERO = ":0\r\n";

    private List<String> run(String reply, Consumer<HotKV> body) throws Exception {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY); // a decimal comma locale must never reach the wire
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange(reply, client -> {
                body.accept(client);
                return null;
            });
            return fake.lastArgs();
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void llmBudgetSetUsesFixedPointUsd() throws Exception {
        assertEquals(List.of("LLMBUDGET.SET", "b", "1000", "0.00001", "60"),
                run(OK, c -> c.llmBudget().set("b", 1000, 1e-5, 60)));
        assertEquals(List.of("LLMBUDGET.SET", "b", "1000", "10000000", "60"),
                run(OK, c -> c.llmBudget().set("b", 1000, 1e7, 60)));
        assertEquals(List.of("LLMBUDGET.SET", "b", "0", "0", "60"),
                run(OK, c -> c.llmBudget().set("b", 0, 0.0, 60)));
        assertEquals(List.of("LLMBUDGET.SET", "b", "5", "12.5", "60"),
                run(OK, c -> c.llmBudget().set("b", 5, 12.5, 60)));
    }

    @Test
    void invalidFloatsAreRejectedBeforeAnythingIsSent() {
        HotKV client = HotKV.create("hotkv://127.0.0.1:1"); // nothing listens: the check must come first
        assertThrows(IllegalArgumentException.class, () -> client.llmBudget().set("b", 1, -1.0, 60));
        assertThrows(IllegalArgumentException.class, () -> client.llmBudget().set("b", 1, Double.NaN, 60));
        assertThrows(IllegalArgumentException.class,
                () -> client.llmBudget().set("b", 1, Double.POSITIVE_INFINITY, 60));
        assertThrows(IllegalArgumentException.class, () -> client.llmBudget().set("b", 1, 1e-9, 60));
        assertThrows(IllegalArgumentException.class, () -> client.llmBudget().set("b", 1, 1e30, 60));
        assertThrows(IllegalArgumentException.class, () -> client.llmBudget().consume("b", 1, Double.NaN, null));
        assertThrows(IllegalArgumentException.class,
                () -> client.llmstats().record("b", 1, 1, 0, Double.POSITIVE_INFINITY, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> client.llmstats().priceSet("m", 1e-9, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> client.rateLimit().create("r", 1, 1000, "token_bucket", Double.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> client.timeSeries().add("k", 1L, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> client.scache().get("ns", new double[] {Double.NaN}, null));
        assertThrows(IllegalArgumentException.class, () -> client.geoAdd("k", Double.NaN, 1, "m"));
        client.close();
    }

    @Test
    void llmBudgetConsumeAndLlmStatsRecordUsePlainDecimalCost() throws Exception {
        assertEquals(List.of("LLMBUDGET.CONSUME", "b", "10", "0.00001"),
                run(":1\r\n", c -> c.llmBudget().consume("b", 10, 1e-5, null)));
        assertEquals(List.of("LLMBUDGET.CONSUME", "b", "10", "20000000"),
                run(":1\r\n", c -> c.llmBudget().consume("b", 10, 2e7, null)));
        assertEquals(List.of("LLMSTATS.RECORD", "bkt", "1", "2", "3", "0.0000015", "7"),
                run(OK, c -> c.llmstats().record("bkt", 1, 2, 3, 1.5e-6, 7, null)));
    }

    @Test
    void llmPricesUseFixedPointUsd() throws Exception {
        LlmPriceOptions opts = LlmPriceOptions.builder().cacheReadUsdPerMtok(1e-5).cacheWrite5mUsdPerMtok(3.75)
                .cacheWrite1hUsdPerMtok(1e7);
        assertEquals(List.of("LLMSTATS.PRICE", "SET", "m", "INPUT", "0.00001", "OUTPUT", "15",
                "CACHEREAD", "0.00001", "CACHEWRITE5M", "3.75", "CACHEWRITE1H", "10000000"),
                run(OK, c -> c.llmstats().priceSet("m", 1e-5, 15, opts)));
    }

    @Test
    void cacheThresholdsAndVectorsAreSinglePrecisionPlainDecimals() throws Exception {
        assertEquals(List.of("SCACHE.CREATE", "ns", "3", "METRIC", "cosine", "THRESHOLD", "0.85"),
                run(OK, c -> c.scache().create("ns", 3, "cosine", 0.85, null)));
        assertEquals(List.of("SCACHE.CREATE", "ns", "3", "THRESHOLD", "0.00001"),
                run(OK, c -> c.scache().create("ns", 3, null, 1e-5, null)));
        assertEquals(List.of("SCACHE.SET", "ns", "q", "r", "0.00001", "-1", "0", "0.1", "TTL", "60"),
                run(OK, c -> c.scache().set("ns", "q", "r", new double[] {1e-5, -1, 0, 0.1}, 60L, null)));
        assertEquals(List.of("SCACHE.GET", "ns", "0.5", "1000000", "THRESHOLD", "0.9"),
                run("*-1\r\n", c -> c.scache().get("ns", new double[] {0.5, 1e6}, 0.9)));
        assertEquals(List.of("PCACHE.CREATE", "ns", "COST_PER_HIT", "0.000002"),
                run(OK, c -> c.pcache().create("ns", null, null, 2e-6)));
    }

    @Test
    void vectorFamiliesUsePlainDecimals() throws Exception {
        assertEquals(List.of("VSIM.SET", "idx", "k", "0.00001", "1000000", "-0.5"),
                run(":1\r\n", c -> c.vsim().set("idx", "k", new double[] {1e-5, 1e6, -0.5})));
        assertEquals(List.of("VSIM.SEARCH", "idx", "5", "0.00001", "2"),
                run("*0\r\n", c -> c.vsim().search("idx", 5, new double[] {1e-5, 2}, null, null)));
        assertEquals(List.of("AMEM.SEM.STORE", "a", "k", "c", "0.00001", "3"),
                run(OK, c -> c.amem().semStore("a", "k", "c", new double[] {1e-5, 3}, null)));
        assertEquals(List.of("AMEM.SEM.RECALL", "a", "2", "0.00001", "3"),
                run("*0\r\n", c -> c.amem().semRecall("a", 2, new double[] {1e-5, 3})));
        assertEquals(List.of("RAG.INGEST", "p", "d", "t", "VECTORS", "0.00001,3,-0.5"),
                run(":1\r\n", c -> c.rag().ingest("p", "d", "t", null, List.of(new double[] {1e-5, 3, -0.5}))));
        assertEquals(List.of("RAG.SEARCH", "p", "q", "ALPHA", "0.00001", "VECTOR", "0.00001,3"),
                run("*0\r\n", c -> c.rag().search("p", "q", null, 1e-5, new double[] {1e-5, 3}, null)));
    }

    @Test
    void rateLimitAndTimeSeriesUsePlainDecimals() throws Exception {
        assertEquals(List.of("RATELIMIT.CREATE", "r", "10", "1000", "ALGO", "token_bucket", "REFILL", "0.00001",
                "DRAIN", "2.5"),
                run(OK, c -> c.rateLimit().create("r", 10, 1000, "token_bucket", 1e-5, 2.5)));
        assertEquals(List.of("TS.ADD", "k", "*", "0.00001"),
                run(":1\r\n", c -> c.timeSeries().add("k", null, 1e-5)));
        assertEquals(List.of("TS.ADD", "k", "5", "1000000000000000000000"),
                run(":5\r\n", c -> c.timeSeries().add("k", 5L, 1e21)));
    }

    @Test
    void coreFloatCommandsUsePlainDecimals() throws Exception {
        assertEquals(List.of("ZADD", "z", "0.00001", "m"), run(ZERO, c -> c.zadd("z", 1e-5, "m")));
        assertEquals(List.of("ZADD", "z", "-inf", "m"),
                run(ZERO, c -> c.zadd("z", Double.NEGATIVE_INFINITY, "m")));
        assertEquals(List.of("ZADD", "z", "1000000000000000000000", "m"), run(ZERO, c -> c.zadd("z", 1e21, "m")));
        assertEquals(List.of("ZINCRBY", "z", "-0.00005", "m"), run("$1\r\n1\r\n", c -> c.zincrBy("z", -5e-5, "m")));
        assertEquals(List.of("INCRBYFLOAT", "k", "0.0000001"), run("$1\r\n1\r\n", c -> c.incrByFloat("k", 1e-7)));
        assertEquals(List.of("HINCRBYFLOAT", "k", "f", "10000000"),
                run("$1\r\n1\r\n", c -> c.hincrByFloat("k", "f", 1e7)));
        assertEquals(List.of("GEOADD", "g", "13.361389", "38.115556", "Palermo"),
                run(":1\r\n", c -> c.geoAdd("g", 13.361389, 38.115556, "Palermo")));
        assertEquals(List.of("GEOADD", "g", "0.00001", "-0.00002", "m"),
                run(":1\r\n", c -> c.geoAdd("g", Map.of("m", new GeoPoint(1e-5, -2e-5)))));
        assertEquals(List.of("ZINTERSTORE", "d", "2", "a", "b", "WEIGHTS", "0.00001", "1000000"),
                run(ZERO, c -> c.zinterStore("d", List.of("a", "b"), new double[] {1e-5, 1e6}, null)));
    }
}
