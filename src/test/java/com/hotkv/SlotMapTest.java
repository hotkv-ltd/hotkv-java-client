// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SlotMap} parsing against a synthetic {@code CLUSTER SLOTS} reply; no server needed. */
class SlotMapTest {

    private static RespValue bulk(String s) {
        return new RespValue.RespBulkString(s.getBytes());
    }

    private static RespValue integer(long n) {
        return new RespValue.RespInteger(n);
    }

    private static RespValue arr(RespValue... items) {
        return new RespValue.RespArray(List.of(items));
    }

    @Test
    void parsesRangesAndReplicas() {
        // [[0, 5460, [127.0.0.1, 30001], [127.0.0.1, 30004]], [5461, 10922, [127.0.0.1, 30002]]]
        RespValue reply = arr(
                arr(integer(0), integer(5460), arr(bulk("127.0.0.1"), integer(30001)), arr(bulk("127.0.0.1"), integer(30004))),
                arr(integer(5461), integer(10922), arr(bulk("127.0.0.1"), integer(30002))));

        SlotMap map = SlotMap.parseClusterSlots(reply);
        assertEquals("127.0.0.1:30001", map.ownerOf(0));
        assertEquals("127.0.0.1:30001", map.ownerOf(5460));
        assertEquals(List.of("127.0.0.1:30004"), map.replicasOf(100));
        assertEquals("127.0.0.1:30002", map.ownerOf(10922));
        assertTrue(map.replicasOf(10922).isEmpty());
        assertNull(map.ownerOf(10923));
    }

    @Test
    void emptyReplyYieldsEmptyMap() {
        SlotMap map = SlotMap.parseClusterSlots(arr());
        assertTrue(map.isEmpty());
        assertNull(map.ownerOf(0));
    }

    @Test
    void malformedShardsReplyDoesNotThrow() {
        SlotMap map = SlotMap.parseClusterShards(new RespValue.RespSimpleString("unexpected"));
        assertTrue(map.isEmpty());
    }
}
