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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commands that have no typed method are reachable through
 * {@link HotKV#execute}. These tests call them live and check the decoded reply values.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class GenericCommandLiveTest {

    private static HotKV client;

    @BeforeAll
    static void connect() {
        client = IntegrationTestSupport.client();
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    private static String key(String label) {
        return "hkjgen:" + label + ":" + UUID.randomUUID();
    }

    /** Under RESP3 (the default) the bloom commands reply with booleans, under RESP2 with 0/1. */
    private static long flag(Object o) {
        return o instanceof Boolean b ? (b ? 1 : 0) : (Long) o;
    }

    @Test
    void bloomFilter() {
        String k = key("bf");
        try {
            assertEquals("OK", client.execute("BF.RESERVE", k, "0.01", 100));
            assertEquals(1L, flag(client.execute("BF.ADD", k, "a")));
            assertEquals(1L, flag(client.execute("BF.EXISTS", k, "a")));
            assertEquals(0L, flag(client.execute("BF.EXISTS", k, "b")));
            List<?> madd = (List<?>) client.execute("BF.MADD", k, "x", "y");
            assertEquals(List.of(1L, 1L), madd.stream().map(GenericCommandLiveTest::flag).toList());
            assertEquals(3L, client.execute("BF.CARD", k));
        } finally {
            client.del(k);
        }
    }

    @Test
    void countMinSketchAndTopK() {
        String cms = key("cms");
        String topk = key("topk");
        try {
            assertEquals("OK", client.execute("CMS.INITBYDIM", cms, 100, 5));
            assertEquals(List.of(3L), client.execute("CMS.INCRBY", cms, "a", 3));
            assertEquals(List.of(3L, 0L), client.execute("CMS.QUERY", cms, "a", "z"));

            assertEquals("OK", client.execute("TOPK.RESERVE", topk, 3));
            client.execute("TOPK.ADD", topk, "a", "b", "a");
            assertEquals(List.of(2L), client.execute("TOPK.COUNT", topk, "a"));
            assertEquals("a", ((List<?>) client.execute("TOPK.LIST", topk)).get(0));
        } finally {
            client.del(cms, topk);
        }
    }

    @Test
    void newerStyleKeyCommands() {
        String a = key("msetex-a");
        String b = key("msetex-b");
        String d = key("delex");
        try {
            assertEquals(1L, client.execute("MSETEX", 2, a, "v1", b, "v2", "EX", 100));
            assertEquals("v1", client.get(a));
            long ttl = client.ttl(b);
            assertTrue(ttl > 90 && ttl <= 100, "ttl " + ttl);

            client.set(d, "v");
            assertEquals(0L, client.execute("DELEX", d, "IFEQ", "nope"));
            assertEquals(1L, client.exists(d));
            assertEquals(1L, client.execute("DELEX", d, "IFEQ", "v"));
            assertEquals(0L, client.exists(d));
        } finally {
            client.del(a, b, d);
        }
    }

    @Test
    void streamConfigAndClientTracking() {
        String s = key("xcfg");
        try {
            client.xadd(s, "*", Map.of("a", "1"));
            assertEquals("OK", client.execute("XCFGSET", s, "IDMP-DURATION", 100));
            Object info = client.execute("CLIENT", "TRACKINGINFO");
            assertNotNull(info);
            assertTrue(info instanceof Map<?, ?> || info instanceof List<?>, "tracking info = " + info);
            assertEquals(-1L, client.execute("CLIENT", "GETREDIR"));
        } finally {
            client.del(s);
        }
    }

    @Test
    void enterpriseAdminFamilies() {
        IntegrationTestSupport.assumeLicensed(client);
        Object audit = client.execute("AUDIT.INFO");
        assertNotNull(audit);
        Object enc = client.execute("ENCRYPTION.INFO");
        assertTrue(String.valueOf(enc).contains("AES-256-GCM"), String.valueOf(enc));
        assertTrue(String.valueOf(client.execute("REPLICATION.INFO")).contains("primary"));
        assertTrue(client.execute("TENANT.LIST") instanceof List<?>);
        assertTrue(client.execute("IAM.ROLE.LIST") instanceof List<?>);
    }

    @Test
    void featureSetAtStampsTheEntityTime() {
        IntegrationTestSupport.assumeLicensed(client);
        String group = "hkjgen-fg-" + UUID.randomUUID();
        long at = System.currentTimeMillis() - 5_000;
        try {
            client.featureStore().create(group, "generic test", Map.of("score", "double"));
            assertEquals("OK", client.execute("FEATURE.SETAT", group, "e1", at, "score", "0.5"));
            FeatureStoreCommands.FeatureValues v = client.featureStore().get(group, "e1", null);
            assertEquals(0.5, Double.parseDouble(v.values().get("score")), 1e-9);
            assertFalse(client.execute("FEATURE.GET", group, "e1").toString().isEmpty());
            assertTrue(String.valueOf(client.execute("FEATURE.GET", group, "e1")).contains(Long.toString(at)));
        } finally {
            client.featureStore().drop(group);
        }
    }

    @Test
    void commonCommandsWithoutTypedMethods() {
        String l = key("list");
        String l2 = key("list2");
        String g = key("geo");
        String h = key("hash");
        String z = key("zset");
        String sa = key("lcs-a");
        String sb = key("lcs-b");
        try {
            client.rpush(l, "3", "1", "2");
            assertEquals(List.of("1", "2", "3"), client.execute("SORT", l));
            assertEquals(List.of("3", "2", "1"), client.execute("SORT", l, "DESC"));
            assertEquals(List.of(l, "3"), client.execute("BLPOP", l, 1));
            assertEquals(List.of(l, "2"), client.execute("BRPOP", l, 1));
            assertEquals(null, client.execute("BLPOP", l2, 1)); // blocks about 1 s, then replies nil
            client.rpush(l2, "a", "b", "c");
            Object lmpop = client.execute("LMPOP", 1, l2, "LEFT", "COUNT", 2);
            assertEquals(List.of(l2, List.of("a", "b")), lmpop);

            client.geoAdd(g, 13.361389, 38.115556, "Palermo");
            assertEquals(List.of("sqc8b49rny0"), client.execute("GEOHASH", g, "Palermo"));

            assertEquals("OK", client.execute("HMSET", h, "a", "1", "b", "2"));
            assertEquals("1", client.hget(h, "a"));

            client.zadd(z, Map.of("a", 1.0, "b", 2.0, "c", 3.0));
            assertEquals(List.of("b", "c"), client.execute("ZRANGEBYSCORE", z, "(1", "+inf"));
            assertEquals(List.of("c", "b"), client.execute("ZREVRANGEBYSCORE", z, "+inf", "(1"));

            client.set(sa, "ohmytext");
            client.set(sb, "mynewtext");
            assertEquals("mytext", client.execute("LCS", sa, sb));
            assertTrue((Long) client.execute("MEMORY", "USAGE", sa) > 0);
            assertEquals("string", client.type(sa));
            assertEquals("embstr", client.execute("OBJECT", "ENCODING", sa));
        } finally {
            client.del(l, l2, g, h, z, sa, sb);
        }
    }
}
