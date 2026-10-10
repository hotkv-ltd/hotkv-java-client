// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.NotLicensedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests (no HotKV server) for the usage and pricing APIs: exact argument lists for every
 * new usage/price option and combination (built directly, and via a loopback fake server that
 * captures the real wire bytes {@link PcacheCommands#set} and friends send), plus parsing of
 * canned RESP2 flat arrays and RESP3 maps for LLMSTATS.PRICE GET/LIST and the
 * other changed STATS/GET/STATUS replies.
 */
class EnterpriseCommandsTest {

    // --- Usage / LlmStatsRecordOptions / LlmPriceOptions: every option and combination ---

    @Test
    void usageAppendsModelOnlyWhenNoCountsGiven() {
        List<Object> args = new ArrayList<>();
        Usage.of("gpt-4").appendTo(args);
        assertEquals(List.of("MODEL", "gpt-4"), args);
    }

    @Test
    void usageAppendsAllFieldsInOrder() {
        List<Object> args = new ArrayList<>();
        Usage.of("claude-sonnet-5").input(100).output(50).cacheRead(10).cacheWrite5m(20).cacheWrite1h(30)
                .appendTo(args);
        assertEquals(List.of("MODEL", "claude-sonnet-5", "INPUT", 100L, "OUTPUT", 50L,
                "CACHEREAD", 10L, "CACHEWRITE5M", 20L, "CACHEWRITE1H", 30L), args);
    }

    @Test
    void usageOmitsZeroCounts() {
        List<Object> args = new ArrayList<>();
        Usage.of("gpt-4").input(5).appendTo(args);
        assertEquals(List.of("MODEL", "gpt-4", "INPUT", 5L), args);
    }

    @Test
    void usageAppendIfPresentNullIsNoop() {
        List<Object> args = new ArrayList<>(List.of("BASE"));
        Usage.appendIfPresent(args, null);
        assertEquals(List.of("BASE"), args);
    }

    @Test
    void llmStatsRecordOptionsEmpty() {
        assertEquals(List.of(), LlmStatsRecordOptions.builder().toArgs());
    }

    @Test
    void llmStatsRecordOptionsErrorOnly() {
        assertEquals(List.of("ERROR", 1), LlmStatsRecordOptions.builder().error(true).toArgs());
    }

    @Test
    void llmStatsRecordOptionsModelAndCacheWrites() {
        List<Object> args = LlmStatsRecordOptions.builder()
                .error(false).model("claude-sonnet-5").cacheWrite5m(20).cacheWrite1h(30).toArgs();
        assertEquals(List.of("ERROR", 0, "MODEL", "claude-sonnet-5", "CACHEWRITE5M", 20L, "CACHEWRITE1H", 30L), args);
    }

    @Test
    void llmPriceOptionsAllOverrides() {
        List<Object> args = LlmPriceOptions.builder()
                .cacheReadUsdPerMtok(0.5).cacheWrite5mUsdPerMtok(4).cacheWrite1hUsdPerMtok(6).toArgs();
        assertEquals(List.of("CACHEREAD", "0.5", "CACHEWRITE5M", "4", "CACHEWRITE1H", "6"), args);
    }

    @Test
    void formatUsdPerMtokNeverUsesExponentNotation() {
        assertEquals("3", LlmStatsCommands.formatUsdPerMtok(3));
        assertEquals("0.3", LlmStatsCommands.formatUsdPerMtok(0.3));
        assertEquals("0.000001", LlmStatsCommands.formatUsdPerMtok(0.000001));
        assertEquals("15.000001", LlmStatsCommands.formatUsdPerMtok(15.000001));
        assertEquals("0", LlmStatsCommands.formatUsdPerMtok(0));
        assertEquals("2.5", LlmStatsCommands.formatUsdPerMtok(2.5));
    }

    // --- exact argument lists over the wire: PCACHE.SET / SCACHE.SET / keyed cache SET ---

    @Test
    void pcacheSetArgsNoUsage() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange("+OK\r\n", client -> {
                client.pcache().set("ns", "p", "r", null, null, null);
                return null;
            });
            assertEquals(List.of("PCACHE.SET", "ns", "p", "r"), fake.lastArgs());
        }
    }

    @Test
    void pcacheSetArgsTtlMetaAndUsage() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            Usage usage = Usage.of("gpt-4").input(12).output(8).cacheRead(4);
            fake.exchange("+OK\r\n", client -> {
                client.pcache().set("ns", "p", "r", 30L, "{\"k\":\"v\"}", usage);
                return null;
            });
            assertEquals(List.of("PCACHE.SET", "ns", "p", "r", "TTL", "30", "META", "{\"k\":\"v\"}",
                    "MODEL", "gpt-4", "INPUT", "12", "OUTPUT", "8", "CACHEREAD", "4"), fake.lastArgs());
        }
    }

    @Test
    void scacheSetArgsWithUsage() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            Usage usage = Usage.of("gpt-4").cacheWrite5m(7);
            fake.exchange("+OK\r\n", client -> {
                client.scache().set("ns", "q", "r", new double[] {0.1, 0.2}, null, usage);
                return null;
            });
            assertEquals(List.of("SCACHE.SET", "ns", "q", "r", "0.1", "0.2", "MODEL", "gpt-4", "CACHEWRITE5M", "7"),
                    fake.lastArgs());
        }
    }

    @Test
    void keyedCacheSetArgsWithUsage() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            Usage usage = Usage.of("gpt-4").cacheWrite1h(9);
            fake.exchange("+OK\r\n", client -> {
                client.toolCache().set("ns", "k", "v", 60L, "meta", usage);
                return null;
            });
            assertEquals(List.of("TOOL.CACHE.SET", "ns", "k", "v", "TTL", "60", "META", "meta",
                    "MODEL", "gpt-4", "CACHEWRITE1H", "9"), fake.lastArgs());
        }
    }

    // --- exact argument lists over the wire: LLMSTATS.RECORD / LLMSTATS.PRICE / LLMBUDGET.CONSUME ---

    @Test
    void llmStatsRecordArgsNilOptions() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange("+OK\r\n", client -> {
                client.llmstats().record("bucket", 100, 50, 0, 0.002, 120, null);
                return null;
            });
            assertEquals(List.of("LLMSTATS.RECORD", "bucket", "100", "50", "0", "0.002", "120"), fake.lastArgs());
        }
    }

    @Test
    void llmStatsRecordArgsModelAndCacheWrites() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            LlmStatsRecordOptions opts = LlmStatsRecordOptions.builder()
                    .error(false).model("claude-sonnet-5").cacheWrite5m(20).cacheWrite1h(30);
            fake.exchange("+OK\r\n", client -> {
                client.llmstats().record("bucket", 100, 50, 10, 0, 80, opts);
                return null;
            });
            assertEquals(List.of("LLMSTATS.RECORD", "bucket", "100", "50", "10", "0", "80",
                    "ERROR", "0", "MODEL", "claude-sonnet-5", "CACHEWRITE5M", "20", "CACHEWRITE1H", "30"),
                    fake.lastArgs());
        }
    }

    @Test
    void llmStatsPriceSetArgsRequiredOnly() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange("+OK\r\n", client -> {
                client.llmstats().priceSet("my-model", 3, 15, null);
                return null;
            });
            assertEquals(List.of("LLMSTATS.PRICE", "SET", "my-model", "INPUT", "3", "OUTPUT", "15"), fake.lastArgs());
        }
    }

    @Test
    void llmStatsPriceSetArgsAllOverrides() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            LlmPriceOptions opts = LlmPriceOptions.builder()
                    .cacheReadUsdPerMtok(0.5).cacheWrite5mUsdPerMtok(4).cacheWrite1hUsdPerMtok(6);
            fake.exchange("+OK\r\n", client -> {
                client.llmstats().priceSet("my-model", 3, 15, opts);
                return null;
            });
            assertEquals(List.of("LLMSTATS.PRICE", "SET", "my-model", "INPUT", "3", "OUTPUT", "15",
                    "CACHEREAD", "0.5", "CACHEWRITE5M", "4", "CACHEWRITE1H", "6"), fake.lastArgs());
        }
    }

    @Test
    void llmStatsPriceGetArgs() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange(pairsMap(priceEntryKV("gpt-4", "unpriced", false, false), Map.of("priced", true, "retired", true)), client -> {
                client.llmstats().priceGet("gpt-4");
                return null;
            });
            assertEquals(List.of("LLMSTATS.PRICE", "GET", "gpt-4"), fake.lastArgs());
        }
    }

    @Test
    void llmStatsPriceListArgs() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange(respArrayHeader(0), client -> {
                client.llmstats().priceList();
                return null;
            });
            assertEquals(List.of("LLMSTATS.PRICE", "LIST"), fake.lastArgs());
        }
    }

    @Test
    void llmStatsPriceDelArgs() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange(respInt(1), client -> {
                client.llmstats().priceDel("gpt-4");
                return null;
            });
            assertEquals(List.of("LLMSTATS.PRICE", "DEL", "gpt-4"), fake.lastArgs());
        }
    }

    @Test
    void llmBudgetConsumeArgsLegacy() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            fake.exchange(respInt(1), client -> client.llmBudget().consume("name", 10, 0.01, null));
            assertEquals(List.of("LLMBUDGET.CONSUME", "name", "10", "0.01"), fake.lastArgs());
        }
    }

    @Test
    void llmBudgetConsumeArgsWithUsage() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            Usage usage = Usage.of("gpt-4").input(10).output(5);
            fake.exchange(respInt(1), client -> client.llmBudget().consume("name", 0, 0, usage));
            assertEquals(List.of("LLMBUDGET.CONSUME", "name", "0", "0", "MODEL", "gpt-4", "INPUT", "10", "OUTPUT", "5"),
                    fake.lastArgs());
        }
    }

    // --- canned reply parsing: RESP2 flat arrays and RESP3 maps ---

    private static Map<String, String> priceEntryKV(String model, String source, boolean priced, boolean retired) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("model", model);
        f.put("source", source);
        f.put("priced", priced ? "1" : "0");
        f.put("retired", retired ? "1" : "0");
        f.put("input_usd_per_mtok", "3.000000");
        f.put("output_usd_per_mtok", "15.000000");
        f.put("cache_read_usd_per_mtok", "0.300000");
        f.put("cache_write_5m_usd_per_mtok", "3.750000");
        f.put("cache_write_1h_usd_per_mtok", "6.000000");
        return f;
    }

    private static LlmStatsCommands.LlmPrice wantPrice(String model, String source, boolean priced, boolean retired) {
        return new LlmStatsCommands.LlmPrice(model, source, priced, retired, 3, 15, 0.3, 3.75, 6);
    }

    @Test
    void llmStatsPriceGetParsesResp2FlatArray() throws Exception {
        Map<String, String> f = priceEntryKV("claude-sonnet-5", "custom", true, false);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            LlmStatsCommands.LlmPrice got = fake.exchange(
                    pairsArray(f, Map.of("priced", true, "retired", true)),
                    client -> client.llmstats().priceGet("claude-sonnet-5"));
            assertEquals(wantPrice("claude-sonnet-5", "custom", true, false), got);
        }
    }

    @Test
    void llmStatsPriceGetParsesResp3Map() throws Exception {
        Map<String, String> f = priceEntryKV("gpt-6-sol", "default", true, false);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            LlmStatsCommands.LlmPrice got = fake.exchange(
                    pairsMap(f, Map.of("priced", true, "retired", true)),
                    client -> client.llmstats().priceGet("gpt-6-sol"));
            assertEquals(wantPrice("gpt-6-sol", "default", true, false), got);
        }
    }

    @Test
    void llmStatsPriceGetUnknownModel() throws Exception {
        Map<String, String> f = priceEntryKV("unknown-model", "unpriced", false, false);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            LlmStatsCommands.LlmPrice got = fake.exchange(
                    pairsMap(f, Map.of("priced", true, "retired", true)),
                    client -> client.llmstats().priceGet("unknown-model"));
            assertEquals("unpriced", got.source());
            assertFalse(got.priced());
        }
    }

    @Test
    void llmStatsPriceListParsesMultipleEntries() throws Exception {
        Map<String, Boolean> ints = Map.of("priced", true, "retired", true);
        String raw = respArrayHeader(2)
                + pairsMap(priceEntryKV("model-a", "default", true, false), ints)
                + pairsMap(priceEntryKV("model-b", "custom", true, true), ints);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            List<LlmStatsCommands.LlmPrice> got = fake.exchange(raw, client -> client.llmstats().priceList());
            assertEquals(2, got.size());
            assertEquals("model-a", got.get(0).model());
            assertEquals("model-b", got.get(1).model());
            assertTrue(got.get(1).retired());
        }
    }

    @Test
    void scacheStatsParsesResp3Map() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("entries", "3");
        f.put("hits", "10");
        f.put("misses", "2");
        f.put("hit_rate", "0.833333");
        f.put("p50_us", "150");
        f.put("usage_saved_requests", "10");
        f.put("usage_saved_input_tokens", "120");
        f.put("usage_saved_output_tokens", "80");
        f.put("usage_saved_cache_read_tokens", "0");
        f.put("usage_saved_cache_write_tokens", "0");
        f.put("usage_saved_cost_usd", "0.000160");
        Map<String, Boolean> ints = Map.of("entries", true, "hits", true, "misses", true, "p50_us", true,
                "usage_saved_requests", true, "usage_saved_input_tokens", true, "usage_saved_output_tokens", true,
                "usage_saved_cache_read_tokens", true, "usage_saved_cache_write_tokens", true);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            ScacheCommands.ScacheStats got = fake.exchange(pairsMap(f, ints), client -> client.scache().stats("ns"));
            assertEquals(new ScacheCommands.ScacheStats(3, 10, 2, 0.833333, 150,
                    new UsageSavings(10, 120, 80, 0, 0, 0.00016)), got);
        }
    }

    @Test
    void pcacheStatsParsesResp2FlatArrayMissingFieldsDefaultZero() throws Exception {
        // Only a subset of fields present (as if talking to an older/newer server): missing
        // fields must default to zero, not throw.
        Map<String, String> f = new LinkedHashMap<>();
        f.put("entries", "1");
        f.put("hits", "4");
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            PcacheCommands.Stats got = fake.exchange(
                    pairsArray(f, Map.of("entries", true, "hits", true)), client -> client.pcache().stats("ns"));
            assertEquals(1, got.entries());
            assertEquals(4, got.hits());
            assertEquals(0, got.misses());
            assertEquals(0.0, got.costSavedUsd());
            assertEquals(new UsageSavings(0, 0, 0, 0, 0, 0.0), got.usageSavings());
        }
    }

    @Test
    void keyedCacheStatsParsesResp3Map() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("entries", "7");
        f.put("hits", "5");
        f.put("misses", "1");
        f.put("evictions", "0");
        f.put("memory_bytes", "2048");
        f.put("usage_saved_requests", "5");
        f.put("usage_saved_input_tokens", "50");
        f.put("usage_saved_output_tokens", "10");
        f.put("usage_saved_cache_read_tokens", "0");
        f.put("usage_saved_cache_write_tokens", "0");
        f.put("usage_saved_cost_usd", "0.000050");
        Map<String, Boolean> ints = Map.of("entries", true, "hits", true, "misses", true, "evictions", true,
                "memory_bytes", true, "usage_saved_requests", true, "usage_saved_input_tokens", true,
                "usage_saved_output_tokens", true, "usage_saved_cache_read_tokens", true,
                "usage_saved_cache_write_tokens", true);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            KeyedCacheCommands.Stats got = fake.exchange(pairsMap(f, ints), client -> client.toolCache().stats("ns"));
            assertEquals(7, got.entries());
            assertEquals(2048, got.memoryBytes());
            assertEquals(0.00005, got.usageSavings().costUsd());
        }
    }

    @Test
    void llmStatsGetParsesResp2FlatArray() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("requests", "10");
        f.put("errors", "1");
        f.put("input_tokens", "500");
        f.put("output_tokens", "200");
        f.put("cached_tokens", "50");
        f.put("cost_usd", "0.0123");
        f.put("avg_latency_ms", "42.5");
        f.put("requests_1m", "3");
        f.put("tokens_1m", "150");
        f.put("cost_usd_1m", "0.004");
        f.put("cache_write_5m_tokens", "20");
        f.put("cache_write_1h_tokens", "0");
        f.put("unpriced_requests", "2");
        Map<String, Boolean> ints = Map.of("requests", true, "errors", true, "input_tokens", true,
                "output_tokens", true, "cached_tokens", true, "requests_1m", true, "tokens_1m", true,
                "cache_write_5m_tokens", true, "cache_write_1h_tokens", true, "unpriced_requests", true);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            LlmStatsCommands.Snapshot got = fake.exchange(pairsArray(f, ints), client -> client.llmstats().get("bucket"));
            assertEquals(new LlmStatsCommands.Snapshot(10, 1, 500, 200, 50, 0.0123, 42.5, 3, 150, 0.004, 20, 0, 2), got);
        }
    }

    @Test
    void llmBudgetStatusParsesResp3Map() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("tokens_used", "100");
        f.put("token_limit", "1000");
        f.put("cost_used_usd", "0.01");
        f.put("cost_limit_usd", "5.00");
        f.put("window_seconds", "3600");
        f.put("window_remaining", "1200");
        f.put("requests_allowed", "9");
        f.put("requests_rejected", "1");
        f.put("unpriced_requests", "0");
        Map<String, Boolean> ints = Map.of("tokens_used", true, "token_limit", true, "window_seconds", true,
                "window_remaining", true, "requests_allowed", true, "requests_rejected", true, "unpriced_requests", true);
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            LlmBudgetCommands.Status got = fake.exchange(pairsMap(f, ints), client -> client.llmBudget().status("name"));
            assertEquals(new LlmBudgetCommands.Status(100, 1000, 0.01, 5.00, 3600, 1200, 9, 1, 0), got);
        }
    }

    @Test
    void noLicenseErrorDetectedOnEnterpriseCommands() throws Exception {
        try (FakeHotKVServer fake = FakeHotKVServer.start()) {
            Exception thrown = assertThrows(Exception.class, () -> fake.exchange(
                    "-NOLICENSE promptcache requires a HotKV license. Contact sales@hotkv.com\r\n",
                    client -> {
                        client.pcache().set("ns", "p", "r", null, null, null);
                        return null;
                    }));
            Throwable cause = thrown.getCause() != null ? thrown.getCause() : thrown;
            assertTrue(cause instanceof NotLicensedException, "expected NotLicensedException, got " + cause);
        }
    }

    // --- RESP wire builders for canned replies ---

    private static String respBulk(String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return "$" + b.length + "\r\n" + s + "\r\n";
    }

    private static String respInt(long n) {
        return ":" + n + "\r\n";
    }

    private static String respArrayHeader(int n) {
        return "*" + n + "\r\n";
    }

    private static String respMapHeader(int n) {
        return "%" + n + "\r\n";
    }

    /** Renders a flat RESP2 array of alternating name/value bulk strings, integer-typed per intFields. */
    private static String pairsArray(Map<String, String> fields, Map<String, Boolean> intFields) {
        StringBuilder sb = new StringBuilder(respArrayHeader(fields.size() * 2));
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(respBulk(e.getKey()));
            sb.append(intFields.getOrDefault(e.getKey(), false) ? respInt(Long.parseLong(e.getValue())) : respBulk(e.getValue()));
        }
        return sb.toString();
    }

    /** Renders a RESP3 map of name/value bulk strings, integer-typed per intFields. */
    private static String pairsMap(Map<String, String> fields, Map<String, Boolean> intFields) {
        StringBuilder sb = new StringBuilder(respMapHeader(fields.size()));
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(respBulk(e.getKey()));
            sb.append(intFields.getOrDefault(e.getKey(), false) ? respInt(Long.parseLong(e.getValue())) : respBulk(e.getValue()));
        }
        return sb.toString();
    }
}
