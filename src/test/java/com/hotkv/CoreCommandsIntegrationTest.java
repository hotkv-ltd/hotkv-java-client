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
import java.util.concurrent.ExecutionException;

import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for strings, keys, expiry, server commands, scripting, scan and the core HotKV extras. */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class CoreCommandsIntegrationTest {

    private static HotKV client;
    private final java.util.List<String> cleanupKeys = new java.util.ArrayList<>();

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
    void pingEchoAndServerInfo() {
        assertEquals("PONG", client.ping());
        assertEquals("hi", client.ping("hi"));
        assertEquals("hi", client.echo("hi"));
        assertTrue(client.info().length() > 0);
        assertTrue(client.dbSize() >= 0);
        long[] t = client.time();
        assertEquals(2, t.length);
        assertTrue(t[0] > 1_700_000_000L);
    }

    @Test
    void configGetSet() {
        Map<String, String> before = client.configGet("maxmemory");
        assertTrue(before == null || before.size() <= 1);
        client.clientSetName("hotkv-java-client-test");
        assertTrue(client.clientId() > 0);
    }

    @Test
    void stringBasics() {
        String k = key("str");
        client.set(k, "v1");
        assertEquals("v1", client.get(k));
        assertEquals(3L, client.append(k, "x")); // APPEND returns the new total length
        assertEquals("v1x", client.get(k));
        assertEquals(3, client.strlen(k));
        assertEquals("v1x", client.getSet(k, "v2"));
        assertEquals("v2", client.get(k));
        assertEquals("v2", client.getDel(k));
        assertNull(client.get(k));
    }

    @Test
    void setOptionsNxXx() {
        String k = key("setnxxx");
        assertTrue(client.setNx(k, "a"));
        assertFalse(client.setNx(k, "b"));
        assertEquals("a", client.get(k));

        String result = client.set(k, "c", SetOptions.builder().xx().get());
        assertEquals("a", result);
        assertEquals("c", client.get(k));
    }

    @Test
    void setOptionsExAndGetAndKeepTtl() {
        String k = key("setopts");
        client.set(k, "v0");
        String previous = client.set(k, "v1", SetOptions.builder().ex(100).get());
        assertEquals("v0", previous);
        long ttl = client.ttl(k);
        assertTrue(ttl > 0 && ttl <= 100, "expected a positive TTL after SET EX, got " + ttl);

        client.set(k, "v2", SetOptions.builder().keepTtl());
        ttl = client.ttl(k);
        assertTrue(ttl > 0, "expected TTL to survive SET KEEPTTL, got " + ttl);
    }

    @Test
    void setOptionsExatPxat() {
        String k = key("setexat");
        long future = System.currentTimeMillis() / 1000 + 100;
        client.set(k, "v", SetOptions.builder().exAt(future));
        long ttl = client.ttl(k);
        assertTrue(ttl > 0 && ttl <= 100, "expected a positive TTL after SET EXAT, got " + ttl);
    }

    @Test
    void incrDecrAndFloat() {
        String k = key("counter");
        assertEquals(1, client.incr(k));
        assertEquals(4, client.incrBy(k, 3));
        assertEquals(3, client.decr(k));
        assertEquals(1, client.decrBy(k, 2));
        String f = key("floatcounter");
        assertEquals(2.5, client.incrByFloat(f, 2.5), 1e-9);
    }

    @Test
    void getRangeSetRange() {
        String k = key("range");
        client.set(k, "Hello World");
        assertEquals("Hello", client.getRange(k, 0, 4));
        client.setRange(k, 6, "HotKV");
        assertEquals("Hello HotKV", client.get(k));
    }

    @Test
    void mgetMsetMsetNx() {
        String k1 = key("m1");
        String k2 = key("m2");
        client.mset(Map.of(k1, "a", k2, "b"));
        assertEquals(List.of("a", "b"), client.mget(k1, k2));

        String k3 = key("m3");
        assertFalse(client.msetNx(Map.of(k1, "z", k3, "c")));
        assertNull(client.get(k3));
        assertTrue(client.msetNx(Map.of(k3, "c")));
        assertEquals("c", client.get(k3));
    }

    @Test
    void setexPsetex() {
        String k1 = key("setex");
        client.setEx(k1, 100, "v");
        assertTrue(client.ttl(k1) > 0);
        String k2 = key("psetex");
        client.pSetEx(k2, 100_000, "v");
        assertTrue(client.pttl(k2) > 0);
    }

    @Test
    void getExOptions() {
        String k = key("getex");
        client.set(k, "v");
        client.getEx(k, GetExOptions.builder().ex(100));
        assertTrue(client.ttl(k) > 0);
        client.getEx(k, GetExOptions.builder().persist());
        assertEquals(-1, client.ttl(k));
    }

    @Test
    void expiryFamily() {
        String k = key("exp");
        client.set(k, "v");
        assertEquals(-1, client.ttl(k));
        assertTrue(client.expire(k, 100));
        assertTrue(client.ttl(k) > 0);
        assertTrue(client.persist(k));
        assertEquals(-1, client.ttl(k));
        assertTrue(client.pexpire(k, 100_000));
        assertTrue(client.pttl(k) > 0);
        long futureSec = System.currentTimeMillis() / 1000 + 100;
        assertTrue(client.expireAt(k, futureSec));
        assertTrue(client.expireTime(k) > 0);
        assertTrue(client.pExpireTime(k) > 0);
        assertEquals(-2, client.ttl(key("doesnotexist")));
    }

    @Test
    void expireConditions() {
        String k = key("expcond");
        client.set(k, "v");
        assertTrue(client.expire(k, 1000));
        assertFalse(client.expire(k, 2000, ExpireCondition.NX)); // already has a TTL
        assertTrue(client.expire(k, 2000, ExpireCondition.GT));
        assertTrue(client.ttl(k) > 1000);
    }

    @Test
    void genericKeyCommands() {
        String k = key("gen");
        String k2 = key("gen2");
        client.set(k, "v");
        assertEquals(1, client.exists(k));
        assertEquals(0, client.exists(k2));
        assertEquals("string", client.type(k));
        assertTrue(client.touch(k) >= 1);

        assertTrue(client.copy(k, k2));
        assertEquals("v", client.get(k2));

        String k3 = key("gen3");
        client.rename(k2, k3);
        assertNull(client.get(k2));
        assertEquals("v", client.get(k3));
        assertFalse(client.renameNx(k, k3));

        byte[] dump = client.dump(k);
        assertTrue(dump != null && dump.length > 0);
        String k4 = key("gen4");
        client.restore(k4, 0, dump);
        assertEquals("v", client.get(k4));

        assertTrue(client.unlink(k, k3, k4) >= 1);
    }

    @Test
    void keysAndRandomKey() {
        String k = key("pattern-fixed-suffix");
        client.set(k, "v");
        List<String> found = client.keys(k);
        assertEquals(List.of(k), found);
        assertTrue(client.randomKey() != null || client.dbSize() == 0);
    }

    @Test
    void scanFindsSetKey() {
        String k = key("scanme");
        client.set(k, "v");
        String cursor = "0";
        boolean found = false;
        for (int i = 0; i < 1000 && !found; i++) {
            ScanResult<String> page = client.scan(cursor, ScanOptions.builder().match(k).count(100));
            found = page.items().contains(k);
            cursor = page.cursor();
            if (page.isFinished()) {
                break;
            }
        }
        assertTrue(found, "SCAN did not find the key we just set");
    }

    @Test
    void scripting() {
        Object result = client.eval("return ARGV[1]", List.of(), List.of("hello"));
        assertEquals("hello", result);

        String sha = client.scriptLoad("return 1");
        assertEquals(List.of(true), client.scriptExists(sha));
        assertEquals(1L, client.evalSha(sha, List.of(), List.of()));
    }

    @Test
    void nanosecondExpiryFamily() {
        String k = key("nexp");
        client.set(k, "v");
        assertEquals(-1, client.nTtl(k));
        assertTrue(client.nExpire(k, 60_000_000_000L));
        assertTrue(client.nTtl(k) > 0);
        long futureNanos = System.currentTimeMillis() * 1_000_000L + 60_000_000_000L;
        assertTrue(client.nExpireAt(k, futureNanos));
        assertEquals(-2, client.nTtl(key("doesnotexist-nexp")));
    }

    @Test
    void hotKeys() {
        String k = key("hot");
        client.set(k, "v");
        client.get(k);
        client.get(k);
        List<KeyCount> top = client.hotKeysTop(50);
        // HOTKEYS tracking is best effort server side; just confirm the shape decodes without error.
        assertTrue(top != null);
        client.hotKeysReset();
    }

    @Test
    void genericCommandEscapeHatch() {
        String k = key("generic");
        Object setResult = client.command("SET", k, "v");
        assertEquals("OK", setResult);
        Object getResult = client.execute("GET", k);
        assertEquals("v", getResult);
    }

    @Test
    void asyncVariants() throws ExecutionException, InterruptedException {
        String k = key("async");
        client.setAsync(k, "v").get();
        assertEquals("v", client.getAsync(k).get());
        assertEquals(1L, client.existsAsync(k).get());
        assertTrue(client.expireAsync(k, 100).get());
        assertEquals("PONG", client.pingAsync().get());
    }
}
