// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the RESP2/RESP3 reader and writer; no server needed. */
class RespCodecTest {

    private static RespValue read(String wire) throws IOException {
        return new RespReader(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))).readValue();
    }

    /** Feeds the reader one byte at a time to exercise the buffered reader's refill/spill paths. */
    private static RespValue readThrottled(byte[] wire) throws IOException {
        InputStream throttled = new InputStream() {
            int pos;

            @Override
            public int read() {
                return pos < wire.length ? wire[pos++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= wire.length) {
                    return -1;
                }
                b[off] = wire[pos++];
                return 1;
            }
        };
        return new RespReader(throttled, 4096).readValue();
    }

    @Test
    void simpleString() throws IOException {
        assertEquals(new RespValue.RespSimpleString("OK"), read("+OK\r\n"));
    }

    @Test
    void error() throws IOException {
        RespValue v = read("-ERR unknown command\r\n");
        assertEquals(new RespValue.RespError("ERR unknown command"), v);
        assertTrue(v.isError());
        assertEquals("ERR unknown command", v.errorMessage());
    }

    @Test
    void integer() throws IOException {
        assertEquals(new RespValue.RespInteger(1000), read(":1000\r\n"));
        assertEquals(new RespValue.RespInteger(-1), read(":-1\r\n"));
    }

    @Test
    void bulkString() throws IOException {
        RespValue v = read("$5\r\nhello\r\n");
        assertInstanceOf(RespValue.RespBulkString.class, v);
        assertEquals("hello", ((RespValue.RespBulkString) v).asUtf8());
    }

    @Test
    void emptyBulkString() throws IOException {
        RespValue v = read("$0\r\n\r\n");
        assertArrayEquals(new byte[0], ((RespValue.RespBulkString) v).value());
    }

    @Test
    void nullBulkStringResp2() throws IOException {
        assertTrue(read("$-1\r\n").isNull());
    }

    @Test
    void nullArrayResp2() throws IOException {
        assertTrue(read("*-1\r\n").isNull());
    }

    @Test
    void resp3Null() throws IOException {
        assertTrue(read("_\r\n").isNull());
    }

    @Test
    void array() throws IOException {
        RespValue v = read("*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
        RespValue.RespArray a = (RespValue.RespArray) v;
        assertEquals(2, a.items().size());
        assertEquals("foo", ((RespValue.RespBulkString) a.items().get(0)).asUtf8());
        assertEquals("bar", ((RespValue.RespBulkString) a.items().get(1)).asUtf8());
    }

    @Test
    void nestedArray() throws IOException {
        RespValue v = read("*2\r\n*1\r\n:1\r\n*1\r\n:2\r\n");
        RespValue.RespArray outer = (RespValue.RespArray) v;
        assertEquals(1L, ((RespValue.RespInteger) ((RespValue.RespArray) outer.items().get(0)).items().get(0)).value());
    }

    @Test
    void doubleValues() throws IOException {
        assertEquals(3.14, ((RespValue.RespDouble) read(",3.14\r\n")).value(), 1e-12);
        assertEquals(Double.POSITIVE_INFINITY, ((RespValue.RespDouble) read(",inf\r\n")).value());
        assertEquals(Double.NEGATIVE_INFINITY, ((RespValue.RespDouble) read(",-inf\r\n")).value());
        assertTrue(Double.isNaN(((RespValue.RespDouble) read(",nan\r\n")).value()));
    }

    @Test
    void booleans() throws IOException {
        assertTrue(((RespValue.RespBoolean) read("#t\r\n")).value());
        assertFalse(((RespValue.RespBoolean) read("#f\r\n")).value());
    }

    @Test
    void blobError() throws IOException {
        RespValue v = read("!21\r\nSYNTAX invalid syntax\r\n");
        assertEquals("SYNTAX invalid syntax", v.errorMessage());
        assertTrue(v.isError());
    }

    @Test
    void verbatimString() throws IOException {
        RespValue.RespVerbatimString v = (RespValue.RespVerbatimString) read("=15\r\ntxt:Some string\r\n");
        assertEquals("txt", v.format());
        assertEquals("Some string", v.text());
    }

    @Test
    void bigNumber() throws IOException {
        String digits = "3492890328409238509324850943850943825024385";
        RespValue.RespBigNumber v = (RespValue.RespBigNumber) read("(" + digits + "\r\n");
        assertEquals(digits, v.digits());
    }

    @Test
    void map() throws IOException {
        RespValue.RespMap m = (RespValue.RespMap) read("%2\r\n+key1\r\n:1\r\n+key2\r\n:2\r\n");
        assertEquals(2, m.entries().size());
        assertEquals("key1", ((RespValue.RespSimpleString) m.entries().get(0).getKey()).value());
        assertEquals(1L, ((RespValue.RespInteger) m.entries().get(0).getValue()).value());
    }

    @Test
    void set() throws IOException {
        RespValue.RespSet s = (RespValue.RespSet) read("~3\r\n:1\r\n:2\r\n:3\r\n");
        assertEquals(3, s.items().size());
    }

    @Test
    void push() throws IOException {
        RespValue.RespPush p = (RespValue.RespPush) read(">3\r\n$7\r\nmessage\r\n$2\r\nch\r\n$5\r\nhello\r\n");
        assertEquals(3, p.items().size());
        assertEquals("message", ((RespValue.RespBulkString) p.items().get(0)).asUtf8());
    }

    @Test
    void attributeWrapsFollowingValue() throws IOException {
        RespValue v = read("|1\r\n+key\r\n+val\r\n*1\r\n:1\r\n");
        RespValue.RespAttribute attr = (RespValue.RespAttribute) v;
        assertEquals(1, attr.attributes().size());
        RespValue.RespArray inner = (RespValue.RespArray) attr.value();
        assertEquals(1L, ((RespValue.RespInteger) inner.items().get(0)).value());
    }

    @Test
    void binarySafeBulkStringRoundTrips() throws IOException {
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i; // includes 0x00, \r, \n and every other byte value
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new RespWriter(out).writeCommand((Object) data);
        // writeCommand wraps in an array header; parse the array then the one bulk string inside it.
        RespValue v = new RespReader(new ByteArrayInputStream(out.toByteArray())).readValue();
        RespValue.RespArray arr = (RespValue.RespArray) v;
        byte[] roundTripped = ((RespValue.RespBulkString) arr.items().get(0)).value();
        assertArrayEquals(data, roundTripped);
    }

    @Test
    void oneByteAtATimeStreamStillParsesCorrectly() throws IOException {
        byte[] wire = "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8);
        RespValue v = readThrottled(wire);
        RespValue.RespArray a = (RespValue.RespArray) v;
        assertEquals(3, a.items().size());
        assertEquals("SET", ((RespValue.RespBulkString) a.items().get(0)).asUtf8());
        assertEquals("bar", ((RespValue.RespBulkString) a.items().get(2)).asUtf8());
    }

    @Test
    void writeCommandProducesStandardResp() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new RespWriter(out).writeCommand("SET", "key", 42L, 3.5);
        assertEquals("*4\r\n$3\r\nSET\r\n$3\r\nkey\r\n$2\r\n42\r\n$3\r\n3.5\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void formatDoubleUsesPlainDecimalForWholeNumbers() {
        assertEquals("42", RespWriter.formatDouble(42.0));
        assertEquals("inf", RespWriter.formatDouble(Double.POSITIVE_INFINITY));
        assertEquals("-inf", RespWriter.formatDouble(Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> RespWriter.formatDouble(Double.NaN));
    }

    @Test
    void malformedLineTerminatorThrows() {
        assertThrows(IOException.class, () -> read("+OK\n"));
    }

    @Test
    void unknownTypeByteThrows() {
        assertThrows(IOException.class, () -> read("?nope\r\n"));
    }
}
