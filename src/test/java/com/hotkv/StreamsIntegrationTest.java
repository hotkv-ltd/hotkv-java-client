// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for streams (XADD, XRANGE, XREAD, XREADGROUP, XACK, XGROUP, XPENDING, XCLAIM, XAUTOCLAIM, XTRIM, XLEN, XDEL, XINFO). */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class StreamsIntegrationTest {

    private static HotKV client;
    private final List<String> cleanupKeys = new java.util.ArrayList<>();

    @BeforeAll
    static void connect() {
        client = IntegrationTestSupport.client();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @AfterEach
    void cleanup() {
        if (!cleanupKeys.isEmpty()) {
            client.del(cleanupKeys.toArray(new String[0]));
            cleanupKeys.clear();
        }
    }

    private String key(String label) {
        String k = uniqueKey(label);
        cleanupKeys.add(k);
        return k;
    }

    @Test
    void addRangeLenDel() {
        String k = key("stream");
        String id1 = client.xadd(k, Map.of("f", "v1"));
        String id2 = client.xadd(k, Map.of("f", "v2"));
        assertEquals(2, client.xlen(k));

        List<StreamEntry> all = client.xrange(k, "-", "+");
        assertEquals(2, all.size());
        assertEquals(id1, all.get(0).id());
        assertEquals("v1", all.get(0).fields().get("f"));

        List<StreamEntry> rev = client.xrevRange(k, "+", "-");
        assertEquals(id2, rev.get(0).id());

        assertEquals(1, client.xdel(k, id1));
        assertEquals(1, client.xlen(k));
    }

    @Test
    void readAndTrim() {
        String k = key("streamread");
        client.xadd(k, Map.of("a", "1"));
        Map<String, List<StreamEntry>> result = client.xread(Map.of(k, "0"), null, null);
        assertEquals(1, result.get(k).size());

        for (int i = 0; i < 5; i++) {
            client.xadd(k, Map.of("n", Integer.toString(i)));
        }
        long trimmed = client.xtrim(k, "MAXLEN", false, "2");
        assertTrue(trimmed >= 0);
        assertTrue(client.xlen(k) <= 6);
    }

    @Test
    void consumerGroupsAckAndPending() {
        String k = key("streamgroup");
        String id = client.xadd(k, Map.of("job", "1"));
        client.xgroupCreate(k, "workers", "0", false);
        client.xgroupCreateConsumer(k, "workers", "c1");

        Map<String, List<StreamEntry>> read = client.xreadGroup("workers", "c1", Map.of(k, ">"), null, null, false);
        assertEquals(1, read.get(k).size());
        assertEquals(id, read.get(k).get(0).id());

        PendingSummary summary = client.xpending(k, "workers");
        assertEquals(1, summary.count());

        List<PendingEntry> extended = client.xpending(k, "workers", "-", "+", 10, null);
        assertEquals(1, extended.size());
        assertEquals("c1", extended.get(0).consumer());

        assertEquals(1, client.xack(k, "workers", id));
        assertEquals(0, client.xpending(k, "workers").count());

        // Returns the number of PENDING messages the consumer still had; we already ACKed the only one.
        assertEquals(0, client.xgroupDelConsumer(k, "workers", "c1"));
        assertTrue(client.xgroupDestroy(k, "workers"));
    }

    @Test
    void claimAndAutoclaim() {
        String k = key("streamclaim");
        String id = client.xadd(k, Map.of("job", "1"));
        client.xgroupCreate(k, "grp", "0", false);
        client.xreadGroup("grp", "c1", Map.of(k, ">"), null, null, false);

        List<StreamEntry> claimed = client.xclaim(k, "grp", "c2", 0, id);
        assertEquals(1, claimed.size());
        assertEquals(id, claimed.get(0).id());

        XAutoClaimResult result = client.xautoclaim(k, "grp", "c3", 0, "0-0", null);
        assertTrue(result.entries().size() >= 1);
    }

    @Test
    void groupSetIdAndXsetId() {
        String k = key("streamsetid");
        String id = client.xadd(k, Map.of("a", "1"));
        client.xgroupCreate(k, "g", "0", false);
        client.xgroupSetId(k, "g", id);
        client.xsetId(k, id);
    }

    @Test
    void xinfo() {
        String k = key("streaminfo");
        client.xadd(k, Map.of("a", "1"));
        client.xgroupCreate(k, "g1", "0", false);

        Map<String, Object> streamInfo = client.xinfoStream(k);
        assertTrue(!streamInfo.isEmpty(), "XINFO STREAM should return at least one field");

        List<Map<String, Object>> groups = client.xinfoGroups(k);
        assertEquals(1, groups.size());

        client.xreadGroup("g1", "consumerA", Map.of(k, ">"), null, null, false);
        List<Map<String, Object>> consumers = client.xinfoConsumers(k, "g1");
        assertEquals(1, consumers.size());
    }
}
