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

/** Integration tests for geospatial, bitmaps and HyperLogLog. */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class GeoBitmapHllIntegrationTest {

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
    void geoAddPosDist() {
        String k = key("geo");
        assertEquals(2, client.geoAdd(k, Map.of(
                "palermo", new GeoPoint(13.361389, 38.115556),
                "catania", new GeoPoint(15.087269, 37.502669))));

        List<GeoPoint> pos = client.geoPos(k, "palermo", "catania");
        assertEquals(2, pos.size());
        assertTrue(Math.abs(pos.get(0).longitude() - 13.361389) < 0.01);

        Double dist = client.geoDist(k, "palermo", "catania", "km");
        assertTrue(dist != null && dist > 150 && dist < 200);
    }

    @Test
    void geoSearchByRadius() {
        String k = key("geosearch");
        client.geoAdd(k, 13.361389, 38.115556, "palermo");
        client.geoAdd(k, 15.087269, 37.502669, "catania");

        List<GeoSearchResult> results = client.geoSearch(k, GeoSearchOptions.builder()
                .fromLonLat(15.0, 37.0)
                .byRadius(200, "km")
                .withDist()
                .asc());
        assertTrue(results.size() >= 1);
        assertEquals("catania", results.get(0).member());
        assertTrue(results.get(0).distance() != null);
    }

    @Test
    void bitmapBasics() {
        String k = key("bits");
        assertEquals(false, client.setBit(k, 7, 1));
        assertTrue(client.getBit(k, 7));
        assertEquals(1, client.bitCount(k));

        String k2 = key("bits2");
        client.setBit(k2, 0, 1);
        client.setBit(k2, 1, 1);
        String dest = key("bitsdest");
        client.bitOp("AND", dest, k, k2);
        assertTrue(client.bitCount(dest) <= 1);

        Long pos = client.bitPos(k, 1);
        assertEquals(7L, pos);
    }

    @Test
    void hyperLogLog() {
        String k1 = key("hll1");
        String k2 = key("hll2");
        client.pfAdd(k1, "a", "b", "c");
        client.pfAdd(k2, "c", "d", "e");
        assertTrue(client.pfCount(k1) >= 2);

        String merged = key("hllmerged");
        client.pfMerge(merged, k1, k2);
        long count = client.pfCount(merged);
        assertTrue(count >= 3 && count <= 6);
    }
}
