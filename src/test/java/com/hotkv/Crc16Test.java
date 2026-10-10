// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Crc16}, verified against known {@code CLUSTER KEYSLOT} vectors; no server needed. */
class Crc16Test {

    @Test
    void rawCrc16Xmodem() {
        assertEquals(0x31C3, Crc16.crc16("123456789"));
    }

    @Test
    void keySlotKnownVectors() {
        assertEquals(12182, Crc16.keySlot("foo"));
        assertEquals(5061, Crc16.keySlot("bar"));
        assertEquals(866, Crc16.keySlot("hello"));
    }

    @Test
    void hashTagsRouteToTheSameSlot() {
        assertEquals(3443, Crc16.keySlot("{user1000}.following"));
        assertEquals(3443, Crc16.keySlot("{user1000}.followers"));
    }

    @Test
    void hashTagEdgeCases() {
        // Empty {} tag: nothing between the braces, so the whole key is hashed instead.
        assertEquals(9500, Crc16.keySlot("{}foo"));
        // First '{' with no matching non-empty '}' before another '{': uses "" between the first pair? Per the
        // documented algorithm, only the first '{' and the first '}' after it count.
        assertEquals(8363, Crc16.keySlot("foo{}{bar}"));
        assertEquals(4015, Crc16.keySlot("foo{{bar}}zap"));
        assertEquals(5061, Crc16.keySlot("foo{bar}{zap}"));
    }

    @Test
    void slotsAreWithinRange() {
        for (String key : new String[] {"", "a", "some-long-key-name-for-testing", "{tag}rest"}) {
            int slot = Crc16.keySlot(key);
            assertTrue(slot >= 0 && slot < 16384);
        }
    }
}
