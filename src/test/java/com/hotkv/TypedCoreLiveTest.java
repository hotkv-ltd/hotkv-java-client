// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live checks, against a real server, of typed core methods that no other integration class
 * calls. Every test asserts reply values, not just the absence of an exception.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class TypedCoreLiveTest {

    private static HotKV client;
    private final List<String> keys = new ArrayList<>();

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
        if (!keys.isEmpty()) {
            client.del(keys.toArray(new String[0]));
            keys.clear();
        }
    }

    private String key(String label) {
        String k = uniqueKey(label);
        keys.add(k);
        return k;
    }

    // ---------------------------------------------------------------- strings / keys / server

    @Test
    void bitFieldSetGetIncrbyAndOverflow() {
        String k = key("bf");
        assertEquals(List.of(0L), client.bitField(k, "SET", "u8", 0, 200));
        assertEquals(List.of(200L), client.bitField(k, "GET", "u8", 0));
        assertEquals(List.of(44L), client.bitField(k, "INCRBY", "u8", 0, 100)); // 300 wraps to 44
        assertEquals(List.of(255L), client.bitField(k, "OVERFLOW", "SAT", "INCRBY", "u8", 0, 1000));
    }

    @Test
    void configSetRoundTripsAParameter() {
        Map<String, String> before = client.configGet("hz");
        String hz = before.get("hz");
        assertTrue(hz != null && !hz.isEmpty(), "hz = " + hz);
        client.configSet("hz", hz);
        assertEquals(hz, client.configGet("hz").get("hz"));
    }

    @Test
    void evalReadOnlyReadsKeysAndArgs() {
        String k = key("evro");
        client.set(k, "payload");
        Object r = client.evalReadOnly("return {redis.call('GET', KEYS[1]), ARGV[1]}", List.of(k), List.of("arg"));
        assertEquals(List.of("payload", "arg"), r);
    }

    @Test
    void scriptFlushRemovesLoadedScripts() {
        String sha = client.scriptLoad("return 7");
        assertEquals(List.of(true), client.scriptExists(sha));
        client.scriptFlush();
        assertEquals(List.of(false), client.scriptExists(sha));
        assertEquals(7L, client.evalSha(client.scriptLoad("return 7"), List.of(), List.of()));
    }

    @Test
    void flushDbEmptiesOnlyTheSelectedDatabase() {
        try (HotKV db14 = HotKV.create(HotKVOptions.parse(IntegrationTestSupport.testUrl()).database(14).build())) {
            db14.set("flushdb-probe", "1");
            db14.set("flushdb-probe2", "2");
            assertEquals(2, db14.dbSize());
            db14.flushDb();
            assertEquals(0, db14.dbSize());
            assertNull(db14.get("flushdb-probe"));
        }
    }

    @Test
    void moveTransfersAKeyToAnotherDatabase() {
        String k = key("move");
        client.set(k, "v");
        try (HotKV db13 = HotKV.create(HotKVOptions.parse(IntegrationTestSupport.testUrl()).database(13).build())) {
            db13.del(k);
            assertTrue(client.move(k, 13));
            assertEquals(0, client.exists(k));
            assertEquals("v", db13.get(k));
            assertFalse(client.move(k, 13)); // no longer in the source database
            db13.del(k);
        }
    }

    @Test
    void pexpireAtSetsAnAbsoluteMillisecondDeadline() {
        String k = key("pexat");
        client.set(k, "v");
        long deadline = System.currentTimeMillis() + 100_000;
        assertTrue(client.pexpireAt(k, deadline));
        assertEquals(deadline, client.pExpireTime(k));
        long pttl = client.pttl(k);
        assertTrue(pttl > 90_000 && pttl <= 100_000, "pttl = " + pttl);
    }

    // ---------------------------------------------------------------- lists

    @Test
    void pushIfExistsOnlyAppliesToExistingLists() {
        String k = key("pushx");
        assertEquals(0, client.lpushX(k, "a"));
        assertEquals(0, client.rpushX(k, "a"));
        assertEquals(0, client.exists(k));
        client.rpush(k, "m");
        assertEquals(2, client.lpushX(k, "l"));
        assertEquals(3, client.rpushX(k, "r"));
        assertEquals(List.of("l", "m", "r"), client.lrange(k, 0, -1));
    }

    // ---------------------------------------------------------------- sets

    @Test
    void setAlgebraWithAndWithoutStore() {
        String a = key("sa");
        String b = key("sb");
        String dInter = key("sinter-dst");
        String dUnion = key("sunion-dst");
        client.sadd(a, "1", "2", "3");
        client.sadd(b, "2", "3", "4");
        assertEquals(List.of("1"), client.sdiff(a, b));
        assertEquals(2, client.sinterStore(dInter, a, b));
        assertEquals(java.util.Set.of("2", "3"), new java.util.HashSet<>(client.smembers(dInter)));
        assertEquals(4, client.sunionStore(dUnion, a, b));
        assertEquals(java.util.Set.of("1", "2", "3", "4"), new java.util.HashSet<>(client.smembers(dUnion)));
    }

    // ---------------------------------------------------------------- hashes

    @Test
    void hashFieldExpiryFamily() {
        String k = key("hfe");
        client.hset(k, Map.of("f", "v", "g", "w", "h", "x", "i", "y"));
        assertEquals(List.of(1L), client.hpExpire(k, 100_000, "f"));
        long hpttl = client.hpTtl(k, "f").get(0);
        assertTrue(hpttl > 90_000 && hpttl <= 100_000, "hpttl = " + hpttl);

        long unixSec = System.currentTimeMillis() / 1000 + 500;
        assertEquals(List.of(1L), client.hExpireAt(k, unixSec, "g"));
        assertEquals(List.of(unixSec), client.hExpireTime(k, "g"));

        long unixMs = System.currentTimeMillis() + 500_000;
        assertEquals(List.of(1L), client.hpExpireAt(k, unixMs, "h"));
        assertEquals(List.of(unixMs), client.hpExpireTime(k, "h"));

        assertEquals(List.of(-1L), client.hTtl(k, "i")); // exists, no expiry
        assertEquals(List.of(-2L), client.hTtl(k, "missing"));
    }

    @Test
    void hgetDelHgetExAndHsetEx() {
        String k = key("hget-ops");
        client.hset(k, "f", "v");
        assertEquals("v", client.hgetEx(k, "f"));
        assertEquals("v", client.hgetDel(k, "f"));
        assertFalse(client.hexists(k, "f"));
        assertNull(client.hgetDel(k, "f"));

        client.hsetEx(k, 100, "g", "w");
        assertEquals("w", client.hget(k, "g"));
        long ttl = client.hTtl(k, "g").get(0);
        assertTrue(ttl > 0 && ttl <= 100, "ttl = " + ttl);
    }

    // ---------------------------------------------------------------- sorted sets

    private String zset(String label) {
        String z = key(label);
        client.zadd(z, Map.of("a", 1.0, "b", 2.0, "c", 3.0, "d", 4.0));
        return z;
    }

    @Test
    void reverseRangesAndRanks() {
        String z = zset("zrev");
        assertEquals(List.of("d", "c", "b", "a"), client.zrevRange(z, 0, -1));
        List<ScoredMember> withScores = client.zrevRangeWithScores(z, 0, 1);
        assertEquals(List.of("d", "c"), withScores.stream().map(ScoredMember::member).toList());
        assertEquals(List.of(4.0, 3.0), withScores.stream().map(ScoredMember::score).toList());
        assertEquals(List.of("c", "b"), client.zrevRangeByScore(z, "3", "2"));
        assertEquals(0L, client.zrevRank(z, "d"));
        assertEquals(3L, client.zrevRank(z, "a"));
        assertNull(client.zrevRank(z, "nope"));
        RankScore rs = client.zrevRankWithScore(z, "c");
        assertEquals(1L, rs.rank());
        assertEquals(3.0, rs.score());
    }

    @Test
    void lexRangesCountAndRemoval() {
        String z = key("zlex");
        client.zadd(z, Map.of("a", 0.0, "b", 0.0, "c", 0.0, "d", 0.0, "e", 0.0));
        assertEquals(3, client.zlexCount(z, "[b", "[d"));
        assertEquals(List.of("d", "c", "b"), client.zrevRangeByLex(z, "[d", "[b"));
        assertEquals(2, client.zremRangeByLex(z, "[a", "(c"));
        assertEquals(List.of("c", "d", "e"), client.zrange(z, 0, -1));
    }

    @Test
    void removeRangesByRankAndScore() {
        String z = zset("zrem");
        assertEquals(1, client.zremRangeByRank(z, 0, 0)); // removes a
        assertEquals(2, client.zremRangeByScore(z, "2", "3")); // removes b, c
        assertEquals(List.of("d"), client.zrange(z, 0, -1));
    }

    @Test
    void popMaxAndMultiPop() {
        String z = zset("zpop");
        ScoredMember top = client.zpopMax(z);
        assertEquals("d", top.member());
        assertEquals(4.0, top.score());
        List<ScoredMember> two = client.zpopMax(z, 2);
        assertEquals(List.of("c", "b"), two.stream().map(ScoredMember::member).toList());
        ZPopResult r = client.zmPop(false, 5, key("empty-zset"), z);
        assertEquals(z, r.key());
        assertEquals(List.of("a"), r.members().stream().map(ScoredMember::member).toList());
        assertNull(client.zmPop(true, 1, z));
    }

    @Test
    void setAlgebraOnSortedSets() {
        String a = key("za");
        String b = key("zb");
        String dst = key("zdiff-dst");
        client.zadd(a, Map.of("x", 1.0, "y", 2.0, "z", 3.0));
        client.zadd(b, Map.of("y", 10.0, "z", 20.0, "w", 30.0));
        assertEquals(List.of("y", "z"), client.zinter(a, b));
        assertEquals(List.of("x", "y", "z", "w"), client.zunion(a, b));
        assertEquals(2, client.zinterCard(0, a, b));
        assertEquals(1, client.zinterCard(1, a, b)); // LIMIT stops counting early
        assertEquals(1, client.zdiffStore(dst, a, b));
        assertEquals(List.of("x"), client.zrange(dst, 0, -1));
    }

    // ---------------------------------------------------------------- pub/sub

    @Test
    void punsubscribeStopsPatternDelivery() throws InterruptedException {
        String pattern = uniqueKey("punsub") + ".*";
        String channel = pattern.substring(0, pattern.length() - 1) + "x";
        try (PubSub sub = client.pubSub()) {
            sub.psubscribe(pattern);
            Thread.sleep(150);
            assertEquals(1, client.publish(channel, "before"));
            PubSubMessage first = sub.nextMessage();
            assertEquals("before", first.payload());
            sub.punsubscribe(pattern);
            Thread.sleep(150);
            assertEquals(0, client.publish(channel, "after"));
        }
    }
}
