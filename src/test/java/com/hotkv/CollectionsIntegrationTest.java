// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for lists, sets, hashes and sorted sets. */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollectionsIntegrationTest {

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

    // ---- lists ----

    @Test
    @Order(1)
    void listBasics() {
        String k = key("list");
        assertEquals(2, client.rpush(k, "a", "b"));
        assertEquals(3, client.lpush(k, "z"));
        assertEquals(List.of("z", "a", "b"), client.lrange(k, 0, -1));
        assertEquals(3, client.llen(k));
        assertEquals("z", client.lindex(k, 0));
        client.lset(k, 0, "z2");
        assertEquals("z2", client.lindex(k, 0));
        client.linsertAfter(k, "z2", "mid");
        assertEquals(List.of("z2", "mid", "a", "b"), client.lrange(k, 0, -1));
        assertEquals(1, client.lrem(k, 1, "mid"));
        assertEquals("z2", client.lpop(k));
        assertEquals("b", client.rpop(k));
        client.ltrim(k, 0, 0);
        assertEquals(List.of("a"), client.lrange(k, 0, -1));
    }

    @Test
    @Order(2)
    void listMoveAndPos() {
        String src = key("lmove-src");
        String dst = key("lmove-dst");
        client.rpush(src, "a", "b", "c"); // src = [a, b, c]
        assertEquals("c", client.rpopLPush(src, dst)); // pops the tail of src, pushes to the head of dst
        assertEquals(List.of("c"), client.lrange(dst, 0, -1)); // src = [a, b], dst = [c]
        assertEquals("a", client.lmove(src, dst, "LEFT", "RIGHT")); // pops the head of src, pushes to the tail of dst
        assertEquals(List.of("c", "a"), client.lrange(dst, 0, -1)); // src = [b], dst = [c, a]
        assertEquals(Long.valueOf(1), client.lpos(dst, "a"));
    }

    @Test
    @Order(3)
    void listPopCount() {
        String k = key("listcount");
        client.rpush(k, "a", "b", "c", "d");
        assertEquals(List.of("a", "b"), client.lpop(k, 2));
        assertEquals(List.of("d", "c"), client.rpop(k, 2));
    }

    // ---- sets ----

    @Test
    @Order(4)
    void setBasics() {
        String k = key("set");
        assertEquals(3, client.sadd(k, "a", "b", "c"));
        assertEquals(3, client.scard(k));
        assertTrue(client.sisMember(k, "a"));
        assertFalse(client.sisMember(k, "z"));
        assertEquals(List.of(true, false), client.smisMember(k, "a", "z"));
        assertEquals(1, client.srem(k, "a"));
        assertEquals(Set.of("b", "c"), Set.copyOf(client.smembers(k)));
    }

    @Test
    @Order(5)
    void setMoveDiffInterUnion() {
        String s1 = key("s1");
        String s2 = key("s2");
        client.sadd(s1, "a", "b", "c");
        client.sadd(s2, "b", "c", "d");

        assertTrue(client.smove(s1, s2, "a"));
        assertTrue(client.sisMember(s2, "a"));

        assertEquals(Set.of("b", "c"), Set.copyOf(client.sinter(s1, s2)));
        assertEquals(Set.of("a", "b", "c", "d"), Set.copyOf(client.sunion(s1, s2)));

        String diffDst = key("sdiffstore");
        client.sdiffStore(diffDst, s2, s1); // s2 {a,b,c,d} minus s1 {b,c} (note: "a" moved into s2 above)
        assertEquals(Set.of("a", "d"), Set.copyOf(client.smembers(diffDst)));

        assertTrue(client.sinterCard(10, s1, s2) >= 2);
    }

    @Test
    @Order(6)
    void setPopAndRandMemberCount() {
        String k = key("spopcount");
        client.sadd(k, "a", "b", "c", "d");
        List<String> popped = client.spop(k, 2);
        assertEquals(2, popped.size());

        List<String> sample = client.srandMember(k, 2);
        assertTrue(sample.size() <= 2);
    }

    @Test
    @Order(7)
    void setScan() {
        String k = key("sscan");
        client.sadd(k, "a", "b", "c");
        ScanResult<String> page = client.sscan(k, "0", ScanOptions.builder().count(100));
        assertEquals(Set.of("a", "b", "c"), Set.copyOf(page.items()));
    }

    // ---- hashes ----

    @Test
    @Order(8)
    void hashBasics() {
        String k = key("hash");
        assertEquals(1, client.hset(k, "f1", "v1"));
        assertEquals(2, client.hset(k, Map.of("f2", "v2", "f3", "v3")));
        assertEquals("v1", client.hget(k, "f1"));
        assertEquals(List.of("v1", "v2"), client.hmget(k, "f1", "f2"));
        assertEquals(3, client.hlen(k));
        assertTrue(client.hexists(k, "f1"));
        assertEquals(2, client.hstrlen(k, "f1"));
        assertEquals(Set.of("f1", "f2", "f3"), Set.copyOf(client.hkeys(k)));
        assertEquals(Set.of("v1", "v2", "v3"), Set.copyOf(client.hvals(k)));
        assertEquals(1, client.hdel(k, "f3"));
        assertEquals(Map.of("f1", "v1", "f2", "v2"), client.hgetAll(k));
        assertFalse(client.hsetNx(k, "f1", "other"));
        assertTrue(client.hsetNx(k, "f4", "v4"));
    }

    @Test
    @Order(9)
    void hashIncrAndScan() {
        String k = key("hincr");
        assertEquals(5, client.hincrBy(k, "n", 5));
        assertEquals(6.5, client.hincrByFloat(k, "n", 1.5), 1e-9);
        ScanResult<FieldValue> page = client.hscan(k, "0", ScanOptions.builder().count(100));
        assertEquals(1, page.items().size());
        assertEquals("n", page.items().get(0).field());
    }

    @Test
    @Order(10)
    void hashFieldTtlFamily() {
        String k = key("hfttl");
        client.hset(k, Map.of("f1", "v1", "f2", "v2"));
        List<Long> expireResult = client.hExpire(k, 100, "f1");
        assertEquals(1, expireResult.size());
        List<Long> ttls = client.hTtl(k, "f1", "f2");
        assertTrue(ttls.get(0) > 0);
        List<Long> persistResult = client.hPersist(k, "f1");
        assertEquals(1, persistResult.size());
    }

    @Test
    @Order(11)
    void hashRandField() {
        String k = key("hrand");
        client.hset(k, Map.of("f1", "v1", "f2", "v2"));
        String single = client.hrandField(k);
        assertTrue(single.equals("f1") || single.equals("f2"));
        List<String> many = client.hrandField(k, 2);
        assertEquals(2, many.size());
    }

    // ---- sorted sets ----

    @Test
    @Order(12)
    void sortedSetBasics() {
        String k = key("zset");
        assertEquals(3, client.zadd(k, Map.of("a", 1.0, "b", 2.0, "c", 3.0)));
        assertEquals(3, client.zcard(k));
        assertEquals(2.0, client.zscore(k, "b"), 1e-9);
        assertEquals(List.of(1.0, 2.0), client.zmScore(k, "a", "b"));
        assertEquals(List.of("a", "b", "c"), client.zrange(k, 0, -1));
        assertEquals(2, client.zcount(k, "1", "2"));
        assertEquals(1, client.zrem(k, "a"));
        assertEquals(4.0, client.zincrBy(k, 2.0, "b"), 1e-9);
    }

    @Test
    @Order(13)
    void sortedSetAddOptions() {
        String k = key("zaddopts");
        assertEquals(1, client.zadd(k, ZAddOptions.builder().nx(), Map.of("a", 1.0)));
        assertEquals(0, client.zadd(k, ZAddOptions.builder().nx(), Map.of("a", 5.0)));
        assertEquals(1.0, client.zscore(k, "a"), 1e-9); // NX must not have overwritten the score

        assertEquals(0, client.zadd(k, ZAddOptions.builder().ch().gt(), Map.of("a", 0.5))); // lower score: GT blocks it
        assertEquals(1, client.zadd(k, ZAddOptions.builder().ch().gt(), Map.of("a", 9.0)));
        assertEquals(9.0, client.zscore(k, "a"), 1e-9);

        Double newScore = client.zaddIncr(k, ZAddOptions.builder(), 1.5, "a");
        assertEquals(10.5, newScore, 1e-9);
    }

    @Test
    @Order(18)
    void sortedSetRangeByScoreAndLex() {
        String k = key("zrangeopts");
        client.zadd(k, Map.of("a", 1.0, "b", 2.0, "c", 3.0, "d", 4.0));
        assertEquals(List.of("b", "c"), client.zrangeByScore(k, "2", "3"));
        assertEquals(List.of("c", "b"), client.zrangeByScore(k, "1", "4", true, 1L, 2L));

        String lex = key("zlex");
        client.zadd(lex, Map.of("a", 0.0, "b", 0.0, "c", 0.0));
        assertEquals(List.of("a", "b", "c"), client.zrangeByLex(lex, "-", "+"));
    }

    @Test
    @Order(14)
    void sortedSetRankWithScore() {
        String k = key("zrank");
        client.zadd(k, Map.of("a", 1.0, "b", 2.0));
        assertEquals(Long.valueOf(0), client.zrank(k, "a"));
        RankScore rs = client.zrankWithScore(k, "b");
        assertEquals(1L, rs.rank());
        assertEquals(2.0, rs.score());
    }

    @Test
    @Order(15)
    void sortedSetPopCount() {
        String k = key("zpopcount");
        client.zadd(k, Map.of("a", 1.0, "b", 2.0, "c", 3.0));
        List<ScoredMember> mins = client.zpopMin(k, 2);
        assertEquals(2, mins.size());
        assertEquals("a", mins.get(0).member());
    }

    @Test
    @Order(16)
    void sortedSetUnionInterStoreWithWeights() {
        String z1 = key("zu1");
        String z2 = key("zu2");
        client.zadd(z1, Map.of("a", 1.0, "b", 1.0));
        client.zadd(z2, Map.of("a", 1.0, "b", 1.0));
        String dst = key("zustore");
        client.zunionStore(dst, List.of(z1, z2), new double[] {2.0, 3.0}, "SUM");
        assertEquals(5.0, client.zscore(dst, "a"), 1e-9);

        String idst = key("zistore");
        client.zinterStore(idst, List.of(z1, z2), null, "MAX");
        assertEquals(1.0, client.zscore(idst, "a"), 1e-9);
    }

    @Test
    @Order(17)
    void sortedSetDiffAndScan() {
        String z1 = key("zd1");
        String z2 = key("zd2");
        client.zadd(z1, Map.of("a", 1.0, "b", 2.0));
        client.zadd(z2, Map.of("a", 1.0));
        assertEquals(List.of("b"), client.zdiff(z1, z2));

        ScanResult<ScoredMember> page = client.zscan(z1, "0", ScanOptions.builder().count(100));
        assertEquals(2, page.items().size());
    }

    @Test
    @Order(19)
    void sortedSetRangeStoreByScoreAndLex() {
        String src = key("zrangestore-src");
        String dst = key("zrangestore-dst");
        client.zadd(src, Map.of("a", 1.0, "b", 2.0, "c", 3.0, "d", 4.0));

        assertEquals(2, client.zrangeStore(dst, src, "2", "3", "BYSCORE", false, null, null));
        assertEquals(List.of("b", "c"), client.zrange(dst, 0, -1));

        assertEquals(1, client.zrangeStore(dst, src, "3", "2", "BYSCORE", true, 0L, 1L));
        assertEquals(List.of("c"), client.zrange(dst, 0, -1));

        String lex = key("zrangestore-lex");
        client.zadd(lex, Map.of("a", 0.0, "b", 0.0, "c", 0.0));
        assertEquals(3, client.zrangeStore(dst, lex, "-", "+", "BYLEX", false, null, null));
        assertEquals(List.of("a", "b", "c"), client.zrange(dst, 0, -1));
    }
}
