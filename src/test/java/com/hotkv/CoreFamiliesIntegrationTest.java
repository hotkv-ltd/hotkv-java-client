// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Set;

import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for the command families that need no license: TAG.*, GCRA.*, VSIM.* (core). */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class CoreFamiliesIntegrationTest {

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
    void tagCommands() {
        String k = key("tag");
        client.set(k, "v");
        assertEquals(2, client.tag().set(k, "red", "blue"));
        assertEquals(Set.of("red", "blue"), Set.copyOf(client.tag().get(k)));
        assertTrue(client.tag().exists(k, "red"));
        assertEquals(2, client.tag().count(k));
        assertTrue(client.tag().members("red").contains(k));

        String k2 = key("tag2");
        client.set(k2, "v2");
        client.tag().set(k2, "red");
        assertTrue(client.tag().inter("red").containsAll(List.of(k, k2)));

        assertEquals(1, client.tag().rem(k, "red"));
    }

    @Test
    void gcraLimiter() {
        String k = key("gcra");
        GcraCommands.GcraResult r1 = client.gcra().limit(k, 60_000, 2);
        assertFalse(r1.denied());
        assertEquals(2, r1.limit());
        client.gcra().limit(k, 60_000, 2);
        GcraCommands.GcraResult r3 = client.gcra().limit(k, 60_000, 2);
        assertTrue(r3.denied());
        assertTrue(client.gcra().reset(k) >= 0);
    }

    @Test
    void vsimIndexAndSearch() {
        String indexName = "hkjtest-vsim-" + java.util.UUID.randomUUID();
        client.vsim().createIndex(indexName, 3);
        try {
            assertTrue(client.vsim().set(indexName, "a", new double[] {1.0, 0.0, 0.0}));
            assertTrue(client.vsim().set(indexName, "b", new double[] {0.0, 1.0, 0.0}));

            double[] got = client.vsim().get(indexName, "a");
            assertEquals(3, got.length);

            List<VsimCommands.VsimMatch> results = client.vsim().search(indexName, 2, new double[] {1.0, 0.0, 0.0}, null, null);
            assertEquals("a", results.get(0).key());

            VsimCommands.VsimIndexInfo info = client.vsim().info(indexName);
            assertEquals(3, info.dimensions());

            assertTrue(client.vsim().del(indexName, "b"));
        } finally {
            client.vsim().dropIndex(indexName);
        }
    }
}
