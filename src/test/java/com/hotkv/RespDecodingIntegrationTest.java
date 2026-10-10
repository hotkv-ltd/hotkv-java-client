// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static com.hotkv.IntegrationTestSupport.testUrl;
import static com.hotkv.IntegrationTestSupport.uniqueKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that HGETALL, HRANDFIELD WITHVALUES, SMEMBERS, ZSCORE, ZRANGE WITHSCORES, ZPOPMIN with
 * a count, ZRANDMEMBER WITHSCORES, XREAD, XINFO STREAM, CONFIG GET and INFO decode to the same
 * Java shape whether the connection negotiated RESP3 (the default) or RESP2.
 */
@EnabledIfEnvironmentVariable(named = "HOTKV_TEST_URL", matches = ".+")
class RespDecodingIntegrationTest {

    @Test
    void resp3() {
        try (HotKV client = HotKV.create(HotKVOptions.parse(testUrl()).resp3(true).build())) {
            assertTrue(client.options().resp3());
            runChecks(client, "resp3");
        }
    }

    @Test
    void resp2() {
        try (HotKV client = HotKV.create(HotKVOptions.parse(testUrl()).resp3(false).build())) {
            assertFalse(client.options().resp3());
            runChecks(client, "resp2");
        }
    }

    private void runChecks(HotKV client, String label) {
        String hkey = uniqueKey("decode-" + label + "-hash");
        String skey = uniqueKey("decode-" + label + "-set");
        String zkey = uniqueKey("decode-" + label + "-zset");
        String xkey = uniqueKey("decode-" + label + "-stream");
        try {
            client.hset(hkey, Map.of("f1", "v1", "f2", "v2"));
            assertEquals(Map.of("f1", "v1", "f2", "v2"), client.hgetAll(hkey));

            List<FieldValue> fvs = client.hrandFieldWithValues(hkey, 2);
            assertEquals(2, fvs.size());
            for (FieldValue fv : fvs) {
                assertEquals(fv.field().equals("f1") ? "v1" : "v2", fv.value());
            }

            client.sadd(skey, "a", "b", "c");
            assertEquals(java.util.Set.of("a", "b", "c"), java.util.Set.copyOf(client.smembers(skey)));

            client.zadd(zkey, Map.of("a", 1.0, "b", 2.0, "c", 3.0));
            assertEquals(2.0, client.zscore(zkey, "b"), 1e-9);

            List<ScoredMember> withScores = client.zrangeWithScores(zkey, 0, -1);
            assertEquals(
                    List.of(new ScoredMember("a", 1.0), new ScoredMember("b", 2.0), new ScoredMember("c", 3.0)),
                    withScores);

            List<ScoredMember> popped = client.zpopMin(zkey, 2);
            assertEquals(List.of(new ScoredMember("a", 1.0), new ScoredMember("b", 2.0)), popped);

            client.zadd(zkey, Map.of("a", 1.0, "b", 2.0));
            List<ScoredMember> randScored = client.zrandMemberWithScores(zkey, 3);
            assertEquals(3, randScored.size());

            String id1 = client.xadd(xkey, Map.of("field1", "v1"));
            String id2 = client.xadd(xkey, Map.of("field2", "v2"));
            Map<String, List<StreamEntry>> streams = client.xread(Map.of(xkey, "0"), null, null);
            assertEquals(1, streams.size());
            List<StreamEntry> entries = streams.get(xkey);
            assertEquals(2, entries.size());
            assertEquals(id1, entries.get(0).id());
            assertEquals(Map.of("field1", "v1"), entries.get(0).fields());
            assertEquals(id2, entries.get(1).id());

            Map<String, Object> info = client.xinfoStream(xkey);
            assertEquals(2L, info.get("length"));
            assertEquals(id2, info.get("last-generated-id"));

            Map<String, String> cfg = client.configGet("maxmemory");
            assertTrue(cfg.containsKey("maxmemory"));

            String infoStr = client.info();
            assertFalse(infoStr.startsWith("txt:") || infoStr.startsWith("mkd:"),
                    "INFO leaked a verbatim string encoding prefix");
            assertTrue(infoStr.contains("hotkv_version"));
        } finally {
            client.del(hkey, skey, zkey, xkey);
        }
    }
}
