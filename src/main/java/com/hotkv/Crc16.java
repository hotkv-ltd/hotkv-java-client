// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.nio.charset.StandardCharsets;

/**
 * CRC16/XMODEM (polynomial 0x1021, initial value 0, no reflection) and the
 * cluster hash slot function built on it, matching {@code CLUSTER KEYSLOT}.
 */
public final class Crc16 {

    private static final int CLUSTER_SLOTS = 16384;

    private Crc16() {
    }

    /** The raw CRC16/XMODEM checksum of {@code data}. {@code crc16("123456789") == 0x31C3}. */
    public static int crc16(byte[] data) {
        int crc = 0;
        for (byte value : data) {
            crc ^= (value & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    public static int crc16(String data) {
        return crc16(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The cluster hash slot (0..16383) for {@code key}, honoring {@code
     * {hash tag}} substrings exactly like {@code CLUSTER KEYSLOT}: if the
     * key contains '{' followed by a '}' with at least one byte between
     * them, only those bytes are hashed; otherwise the whole key is hashed.
     */
    public static int keySlot(String key) {
        String hashed = key;
        int open = key.indexOf('{');
        if (open >= 0) {
            int close = key.indexOf('}', open + 1);
            if (close > open + 1) {
                hashed = key.substring(open + 1, close);
            }
        }
        return crc16(hashed) % CLUSTER_SLOTS;
    }
}
