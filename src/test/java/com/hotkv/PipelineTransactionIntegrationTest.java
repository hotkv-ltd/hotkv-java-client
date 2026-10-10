// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for pipelines and transactions. */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class PipelineTransactionIntegrationTest {

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
    void pipelineRunsManyCommandsInOneRoundTrip() {
        String k1 = key("p1");
        String k2 = key("p2");
        Pipeline p = client.pipeline();
        Response<Void> set1 = p.set(k1, "a");
        Response<Void> set2 = p.set(k2, "b");
        Response<String> get1 = p.get(k1);
        Response<Long> incr = p.incr(key("pcounter"));
        List<Object> results = p.sync();

        assertEquals(4, results.size());
        assertNull(set1.get());
        assertNull(set2.get());
        assertEquals("a", get1.get());
        assertEquals(1L, incr.get());
    }

    @Test
    void pipelineSurfacesPerCommandErrorsWithoutFailingTheBatch() {
        String stringKey = key("pipelineerr");
        client.set(stringKey, "not-a-list");

        Pipeline p = client.pipeline();
        Response<String> ok = p.get(stringKey);
        Response<Long> badLpush = p.lpush(stringKey, "x"); // WRONGTYPE: stringKey holds a string
        Response<String> okAfter = p.get(stringKey);
        List<Object> results = p.sync();

        assertEquals("not-a-list", ok.get());
        assertTrue(badLpush.isError());
        assertInstanceOf(com.hotkv.exceptions.WrongTypeException.class, results.get(1));
        assertEquals("not-a-list", okAfter.get()); // the command after the error still ran and returned normally
    }

    @Test
    void pipelineGenericCommandEscapeHatch() {
        String k = key("pipelinegeneric");
        Pipeline p = client.pipeline();
        Response<Object> setResp = p.command("SET", k, "v");
        Response<Object> getResp = p.command("GET", k);
        p.sync();
        assertEquals("OK", setResp.get());
        assertEquals("v", getResp.get());
    }

    @Test
    void transactionCommitsAtomically() {
        String k = key("tx1");
        client.set(k, "0");
        Transaction tx = client.transaction();
        Response<Long> r1 = tx.incrBy(k, 5);
        Response<Long> r2 = tx.incrBy(k, 2);
        List<Object> results = tx.exec();

        assertEquals(2, results.size());
        assertEquals(5L, r1.get());
        assertEquals(7L, r2.get());
        assertEquals("7", client.get(k));
    }

    @Test
    void transactionAbortsWhenWatchedKeyChanges() {
        String k = key("tx2");
        client.set(k, "initial");

        Transaction tx = client.transaction().watch(k);
        tx.set(k, "from-tx");

        // A concurrent writer changes the watched key before EXEC.
        client.set(k, "changed-by-someone-else");

        List<Object> results = tx.exec();
        assertNull(results, "EXEC should return null when a watched key changed");
        assertEquals("changed-by-someone-else", client.get(k));
    }

    @Test
    void transactionDiscard() {
        Transaction tx = client.transaction();
        tx.set(key("txdiscard"), "v");
        tx.discard();
        assertEquals(0, tx.size());
    }
}
