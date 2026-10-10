// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.HotKVException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the client the way a customer would, over the network against a TLS and password
 * protected HotKV server. Skipped unless {@code HOTKV_REMOTE_HOST} is set.
 *
 * <pre>
 * HOTKV_REMOTE_HOST=host HOTKV_REMOTE_PORT=6399 HOTKV_REMOTE_PASSWORD=... HOTKV_REMOTE_CA=ca.pem \
 *   mvn test -Dtest=RemoteInternetIntegrationTest
 * </pre>
 *
 * <p>The server is shared: every key and namespace carries a per run unique prefix and is removed
 * again, and nothing here issues FLUSHALL, FLUSHDB, CONFIG SET, ACL or SHUTDOWN. The password is
 * only ever read from the environment. To exercise the {@code hotkvs://} URL form with the CA
 * trusted through the JVM trust store, also set {@code HOTKV_REMOTE_TRUSTSTORE} to a JKS file
 * (password {@code changeit}) that contains the CA; that test is skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_REMOTE_HOST", matches = ".+")
class RemoteInternetIntegrationTest {

    private static final String RUN = "remote-java-" + UUID.randomUUID().toString().substring(0, 8);

    private static HotKV client;
    private static String host;
    private static int port;
    private static String password;
    private static String ca;

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    private static HotKVOptions.Builder options() {
        return HotKVOptions.builder().host(host).port(port).password(password)
                .tls(TlsOptions.builder().caCertPath(ca).build())
                .connectTimeoutMillis(10_000).commandTimeoutMillis(15_000);
    }

    @BeforeAll
    static void connect() {
        host = env("HOTKV_REMOTE_HOST");
        port = env("HOTKV_REMOTE_PORT").isEmpty() ? 6399 : Integer.parseInt(env("HOTKV_REMOTE_PORT"));
        password = env("HOTKV_REMOTE_PASSWORD");
        ca = env("HOTKV_REMOTE_CA");
        client = HotKV.create(options().build());
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    private static String k(String label) {
        return RUN + ":" + label;
    }

    private static void log(String what, long startNanos) {
        System.out.printf("TIMING %-28s %7.1f ms%n", what, (System.nanoTime() - startNanos) / 1e6);
    }

    // ---------------------------------------------------------------- connection

    @Test
    void pingAndRoundTripTiming() {
        assertEquals("PONG", client.ping());
        long t = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertEquals("PONG", client.ping());
        }
        double avg = (System.nanoTime() - t) / 1e6 / 20;
        System.out.printf("TIMING PING round trip average over 20: %.1f ms%n", avg);
        assertTrue(avg < 2000, "average PING " + avg + " ms");
    }

    @Test
    void wrongPasswordFailsCleanly() {
        long t = System.nanoTime();
        HotKV bad = HotKV.create(options().password("definitely-not-the-password").build());
        try {
            HotKVException e = assertThrows(HotKVException.class, bad::ping);
            assertNotNull(e.getMessage());
            assertFalse(e.getMessage().contains(password.isEmpty() ? "\u0000" : password));
        } finally {
            bad.close();
        }
        log("wrong password rejected", t);
    }

    @Test
    void missingPasswordFailsCleanly() {
        HotKV anon = HotKV.create(HotKVOptions.builder().host(host).port(port)
                .tls(TlsOptions.builder().caCertPath(ca).build()).connectTimeoutMillis(10_000)
                .commandTimeoutMillis(15_000).build());
        try {
            assertThrows(HotKVException.class, anon::ping);
        } finally {
            anon.close();
        }
    }

    @Test
    void wrongCaIsRejected() {
        // The server's own certificate is not signed by the JVM default CAs.
        HotKV noCa = HotKV.create(HotKVOptions.builder().host(host).port(port).password(password)
                .tls(TlsOptions.builder().build()).connectTimeoutMillis(10_000).commandTimeoutMillis(15_000)
                .build());
        try {
            assertThrows(HotKVException.class, noCa::ping);
        } finally {
            noCa.close();
        }
    }

    @Test
    void insecureSkipVerifyConnects() {
        HotKV insecure = HotKV.create(HotKVOptions.builder().host(host).port(port).password(password)
                .tls(TlsOptions.builder().insecureSkipVerify(true).build()).connectTimeoutMillis(10_000)
                .commandTimeoutMillis(15_000).build());
        try {
            assertEquals("PONG", insecure.ping());
        } finally {
            insecure.close();
        }
    }

    @Test
    void urlFormWithJvmTrustStore() throws Exception {
        String trustStore = env("HOTKV_REMOTE_TRUSTSTORE");
        org.junit.jupiter.api.Assumptions.assumeTrue(!trustStore.isEmpty(),
                "set HOTKV_REMOTE_TRUSTSTORE (JKS, password changeit) to test the hotkvs:// URL form");
        String url = "hotkvs://:" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8)
                + "@" + host + ":" + port;
        // The URL carries no CA path, so the CA must be in the JVM trust store (-Djavax.net.ssl.trustStore).
        assertEquals(trustStore, System.getProperty("javax.net.ssl.trustStore"));
        try (HotKV viaUrl = HotKV.create(url)) {
            assertEquals("PONG", viaUrl.ping());
            viaUrl.set(k("url"), "ok");
            assertEquals("ok", viaUrl.get(k("url")));
            viaUrl.del(k("url"));
        }
    }

    // ---------------------------------------------------------------- data types

    @Test
    void stringsAndExpiry() {
        String key = k("str");
        String counter = k("ctr");
        try {
            long t = System.nanoTime();
            client.set(key, "héllo wörld ☃");
            assertEquals("héllo wörld ☃", client.get(key));
            assertEquals(1, client.incr(counter));
            assertEquals(6, client.incrBy(counter, 5));
            assertEquals(2.5, client.incrByFloat(k("flt"), 2.5), 1e-9);
            assertTrue(client.expire(key, 100));
            long ttl = client.ttl(key);
            assertTrue(ttl > 90 && ttl <= 100, "ttl " + ttl);
            client.set(key, "short", SetOptions.builder().px(300));
            Thread.sleep(700);
            assertNull(client.get(key));
            log("strings + expiry", t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            client.del(key, counter, k("flt"));
        }
    }

    @Test
    void hashesListsSetsSortedSets() {
        String h = k("hash");
        String l = k("list");
        String s = k("set");
        String z = k("zset");
        try {
            client.hset(h, Map.of("name", "Ada", "role", "engineer"));
            assertEquals("Ada", client.hget(h, "name"));
            assertEquals(2, client.hgetAll(h).size());
            assertEquals(5, client.hincrBy(h, "n", 5));

            client.rpush(l, "a", "b", "c");
            client.lpush(l, "z");
            assertEquals(List.of("z", "a", "b", "c"), client.lrange(l, 0, -1));
            assertEquals("z", client.lpop(l));

            client.sadd(s, "x", "y", "z");
            assertEquals(3, client.scard(s));
            assertTrue(client.sisMember(s, "y"));

            client.zadd(z, Map.of("one", 1.0, "two", 2.0, "three", 3.0));
            assertEquals(List.of("one", "two", "three"), client.zrange(z, 0, -1));
            assertEquals(2.0, client.zscore(z, "two"));
            assertEquals(0L, client.zrank(z, "one"));
        } finally {
            client.del(h, l, s, z);
        }
    }

    @Test
    void streams() {
        String x = k("stream");
        try {
            String id1 = client.xadd(x, Map.of("temp", "21"));
            String id2 = client.xadd(x, Map.of("temp", "22"));
            assertNotNull(id1);
            assertEquals(2, client.xlen(x));
            List<StreamEntry> all = client.xrange(x, "-", "+");
            assertEquals(List.of(id1, id2), all.stream().map(StreamEntry::id).toList());
            assertEquals("22", all.get(1).fields().get("temp"));
        } finally {
            client.del(x);
        }
    }

    @Test
    void pipelineAndTransaction() {
        String a = k("pa");
        String b = k("pb");
        String c = k("tx");
        try {
            long t = System.nanoTime();
            Pipeline p = client.pipeline();
            p.set(a, "1");
            p.set(b, "2");
            Response<String> ga = p.get(a);
            Response<Long> inc = p.incr(k("pctr"));
            p.sync();
            assertEquals("1", ga.get());
            assertEquals(1L, inc.get());
            log("pipeline of 4", t);

            Transaction tx = client.transaction();
            tx.set(c, "10");
            Response<Long> incr = tx.incr(k("txctr"));
            List<Object> out = tx.exec();
            assertEquals(2, out.size());
            assertEquals(1L, incr.get());
            assertEquals("10", client.get(c));
        } finally {
            client.del(a, b, c, k("pctr"), k("txctr"));
        }
    }

    @Test
    void pubSubWithSeparatePublisherAndSubscriberConnections() throws Exception {
        String channel = k("chan");
        try (PubSub sub = client.pubSub()) {
            sub.subscribe(channel);
            HotKV publisher = HotKV.create(options().build());
            try {
                Thread.sleep(300);
                long t = System.nanoTime();
                assertEquals(1, publisher.publish(channel, "hello over TLS"));
                PubSubMessage m = sub.nextMessage();
                assertEquals(channel, m.channel());
                assertEquals("hello over TLS", m.payload());
                log("pubsub publish to receive", t);
            } finally {
                publisher.close();
            }
        }
    }

    @Test
    void oneMegabyteValueRoundTrip() {
        String key = k("big");
        byte[] blob = new byte[1 << 20];
        new java.util.Random(7).nextBytes(blob);
        try {
            long t = System.nanoTime();
            client.set(key, java.util.Base64.getEncoder().encodeToString(blob).substring(0, 1 << 20));
            String back = client.get(key);
            assertEquals(1 << 20, back.length());
            assertEquals(java.util.Base64.getEncoder().encodeToString(blob).substring(0, 1 << 20), back);
            log("1 MB set + get", t);
        } finally {
            client.del(key);
        }
    }

    @Test
    void reconnectsAfterTheServerClosesTheConnection() {
        HotKV one = HotKV.create(options().poolSize(1).build());
        try {
            assertEquals("PONG", one.ping());
            Object id = one.execute("CLIENT", "ID");
            // Close our own connection from the server side (the only connection the pool has).
            try {
                one.execute("CLIENT", "KILL", "ID", id);
            } catch (HotKVException ignored) {
                // the reply to the KILL itself may or may not arrive before the socket closes
            }
            // An idempotent read is retried on a fresh connection.
            assertEquals("PONG", one.ping());
            String key = k("after-kill");
            one.set(key, "v");
            assertEquals("v", one.get(key));
            one.del(key);
        } finally {
            one.close();
        }
    }

    @Test
    void concurrentUseFromManyThreads() throws Exception {
        int threads = 8;
        int perThread = 40;
        String counter = k("conc-counter");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            long t = System.nanoTime();
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    int ok = 0;
                    for (int j = 0; j < perThread; j++) {
                        String key = k("conc:" + id + ":" + j);
                        client.set(key, id + "-" + j);
                        if ((id + "-" + j).equals(client.get(key))) {
                            ok++;
                        }
                        client.incr(counter);
                        client.del(key);
                    }
                    return ok;
                }));
            }
            for (Future<Integer> f : futures) {
                assertEquals(perThread, f.get(120, TimeUnit.SECONDS));
            }
            assertEquals(String.valueOf(threads * perThread), client.get(counter));
            log("8 threads x 40 x 4 commands", t);
        } finally {
            pool.shutdownNow();
            client.del(counter);
        }
    }

    // ---------------------------------------------------------------- AI features

    @Test
    void semanticAndPromptCaches() {
        String sc = k("scache");
        String pc = k("pcache");
        try {
            client.scache().create(sc, 3, "cosine", 0.8, null);
            client.scache().set(sc, "what is HotKV", "a fast data store", new double[] {1, 0, 0}, null, null);
            ScacheCommands.ScacheResult hit = client.scache().get(sc, new double[] {0.98, 0.1, 0.0}, null);
            assertNotNull(hit);
            assertEquals("a fast data store", hit.response());
            assertNull(client.scache().get(sc, new double[] {0, 1, 0}, null));
            ScacheCommands.ScacheStats st = client.scache().stats(sc);
            assertEquals(1, st.entries());
            assertEquals(1, st.hits());
            assertEquals(1, st.misses());

            client.pcache().create(pc, null, null, 0.0002);
            client.pcache().set(pc, "prompt", "response", null, null, Usage.of("gpt-4").input(10).output(5));
            assertEquals("response", client.pcache().get(pc, "prompt"));
            assertNull(client.pcache().get(pc, "other"));
            assertEquals(1, client.pcache().stats(pc).hits());
        } finally {
            client.scache().drop(sc);
            client.pcache().drop(pc);
        }
    }

    @Test
    void keyedCaches() {
        List<KeyedCacheCommands> caches = List.of(client.toolCache(), client.ncache(), client.guard(), client.rerank());
        for (KeyedCacheCommands cache : caches) {
            String ns = k("keyed-" + cache.hashCode());
            try {
                cache.create(ns, null, null);
                cache.set(ns, "key", "value", null, null, null);
                assertEquals("value", cache.get(ns, "key"));
                assertNull(cache.get(ns, "absent"));
                assertEquals(1, cache.stats(ns).entries());
                assertTrue(cache.del(ns, "key"));
                assertNull(cache.get(ns, "key"));
            } finally {
                cache.drop(ns);
            }
        }
    }

    @Test
    void llmBudgetAndStatsWithFloatLimits() {
        String budget = k("budget");
        String bucket = k("llmstats");
        String model = k("model");
        try {
            client.llmBudget().set(budget, 10_000, 0.00001, 3600); // would be 1.0E-5 with Double.toString
            assertEquals(0.00001, client.llmBudget().status(budget).costLimitUsd(), 1e-12);
            assertTrue(client.llmBudget().consume(budget, 10, 0.000004, null));
            assertTrue(client.llmBudget().consume(budget, 10, 0.000004, null));
            assertFalse(client.llmBudget().consume(budget, 10, 0.000004, null));
            client.llmBudget().set(budget, 10_000, 12.5, 3600);
            assertEquals(12.5, client.llmBudget().status(budget).costLimitUsd(), 1e-9);

            client.llmstats().bucket(bucket);
            client.llmstats().record(bucket, 100, 50, 0, 0.002, 150, null);
            assertEquals(1, client.llmstats().get(bucket).requests());
            client.llmstats().priceSet(model, 0.00001, 12.5, null);
            assertEquals(12.5, client.llmstats().priceGet(model).outputUsdPerMtok(), 1e-9);
        } finally {
            client.llmBudget().drop(budget);
            client.llmstats().drop(bucket);
            client.llmstats().priceDel(model);
        }
    }

    @Test
    void promptRegistryAndEmbedDedup() {
        String prompt = k("prompt");
        String ns = k("embed");
        try {
            assertEquals(1, client.promptRegistry().put(prompt, "Hello {{name}}", null));
            assertEquals(2, client.promptRegistry().put(prompt, "Hi {{name}}", null));
            assertEquals(List.of(1L, 2L), client.promptRegistry().versions(prompt));
            assertEquals("Hi {{name}}", client.promptRegistry().latest(prompt).content());

            assertTrue(client.embedDedup().put(ns, "h1", "text-embedding-3", "0.1,0.2,0.3"));
            assertEquals("0.1,0.2,0.3", client.embedDedup().get(ns, "h1"));
        } finally {
            client.promptRegistry().drop(prompt);
            client.embedDedup().drop(ns);
        }
    }

    @Test
    void agentMemory() {
        String session = k("conv");
        String agent = k("agent");
        String work = k("work");
        try {
            client.amem().convCreate(session, null, null);
            client.amem().convAppend(session, "user", "hi");
            client.amem().convAppend(session, "assistant", "hello");
            assertEquals(2, client.amem().convGet(session).size());

            client.amem().semCreate(agent, 3);
            client.amem().semStore(agent, "fact", "the sky is blue", new double[] {1, 0, 0}, null);
            assertEquals("fact", client.amem().semRecall(agent, 1, new double[] {0.9, 0.1, 0}).get(0).key());

            client.amem().workSet(work, "k", "v", null);
            assertEquals("v", client.amem().workGet(work, "k"));
        } finally {
            client.amem().convDrop(session);
            client.amem().semForget(agent, "fact");
            client.amem().workClear(work);
        }
    }

    @Test
    void ragPipeline() {
        String name = k("rag");
        try {
            client.rag().create(name, 3, "fixed", 512, 64);
            assertTrue(client.rag().ingest(name, "doc1", "HotKV is a fast data store.", null,
                    List.of(new double[] {1, 0, 0})) >= 1);
            List<RagCommands.RagResult> r = client.rag().search(name, "fast", 3, 0.5, new double[] {1, 0, 0}, null);
            assertFalse(r.isEmpty());
            assertEquals("doc1", r.get(0).docId());
        } finally {
            client.rag().drop(name);
        }
    }

    @Test
    void rateLimitAndGcra() {
        String rl = k("ratelimit");
        String gcraKey = k("gcra");
        try {
            client.rateLimit().create(rl, 2, 60_000, null, null, null);
            assertTrue(client.rateLimit().check(rl, "u", null).allowed());
            assertTrue(client.rateLimit().check(rl, "u", null).allowed());
            assertFalse(client.rateLimit().check(rl, "u", null).allowed());

            GcraCommands.GcraResult g1 = client.gcra().limit(gcraKey, 60_000, 1);
            assertFalse(g1.denied());
            assertTrue(client.gcra().limit(gcraKey, 60_000, 1, 5).denied());
        } finally {
            client.rateLimit().drop(rl);
            client.gcra().reset(gcraKey);
        }
    }

    @Test
    void timeSeriesFeatureStoreAndVectors() {
        String ts = k("ts");
        String fg = k("features");
        String idx = k("vsim");
        try {
            client.timeSeries().create(ts, 0L, Map.of("host", "remote"));
            client.timeSeries().add(ts, 1000L, 0.5);
            client.timeSeries().add(ts, 2000L, 1.5);
            assertEquals(2, client.timeSeries().range(ts, "-", "+", null).size());
            assertEquals(1.0, client.timeSeries().agg(ts, "-", "+", 10_000, "avg").get(0).value(), 1e-6);

            Map<String, String> schema = new LinkedHashMap<>();
            schema.put("score", "double");
            client.featureStore().create(fg, "remote test", schema);
            client.featureStore().set(fg, "e1", Map.of("score", "0.75"));
            assertEquals(0.75, Double.parseDouble(client.featureStore().get(fg, "e1", null).values().get("score")),
                    1e-9);

            client.vsim().createIndex(idx, 3, "cosine", null, null, null);
            client.vsim().set(idx, "a", new double[] {1, 0, 0});
            client.vsim().set(idx, "b", new double[] {0, 1, 0});
            List<VsimCommands.VsimMatch> hits = client.vsim().search(idx, 1, new double[] {0.9, 0.1, 0}, null, null);
            assertEquals("a", hits.get(0).key());
        } finally {
            client.timeSeries().delete(ts);
            client.featureStore().drop(fg);
            client.vsim().dropIndex(idx);
        }
    }
}
