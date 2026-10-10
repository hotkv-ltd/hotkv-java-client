// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads RESP2 and RESP3 values from a connection. Not thread safe: each
 * connection owns exactly one reader, used by a single caller at a time.
 */
public final class RespReader {

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    static final long MAX_BULK_LENGTH = 512L * 1024 * 1024;
    static final long MAX_ARRAY_ELEMENTS = 1_048_576;
    private static final int MAX_NESTING_DEPTH = 64;

    private final InputStream in;
    private final byte[] buf;
    private int pos;
    private int limit;
    private boolean eof;

    public RespReader(InputStream in) {
        this(in, DEFAULT_BUFFER_SIZE);
    }

    public RespReader(InputStream in, int bufferSize) {
        this.in = in;
        this.buf = new byte[Math.max(bufferSize, 4096)];
    }

    /** Reads the next complete RESP value from the stream, blocking until it arrives. */
    public RespValue readValue() throws IOException {
        return readValue(0);
    }

    private RespValue readValue(int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("hotkv: RESP nesting depth exceeded " + MAX_NESTING_DEPTH);
        }
        int type = readByteOrEOF();
        return switch (type) {
            case '+' -> new RespValue.RespSimpleString(readLine());
            case '-' -> new RespValue.RespError(readLine());
            case ':' -> new RespValue.RespInteger(parseLong(readLine()));
            case '$' -> readBulkString();
            case '*' -> readArray(depth);
            case '_' -> {
                readLine();
                yield new RespValue.RespNull();
            }
            case ',' -> new RespValue.RespDouble(parseDouble(readLine()));
            case '#' -> readBoolean();
            case '!' -> readBlobError();
            case '=' -> readVerbatimString();
            case '(' -> readBigNumber();
            case '%' -> readMap(depth);
            case '~' -> readSet(depth);
            case '|' -> readAttribute(depth);
            case '>' -> readPush(depth);
            default -> throw new IOException("hotkv: unexpected RESP type byte 0x" + Integer.toHexString(type));
        };
    }

    private RespValue readBulkString() throws IOException {
        long length = parseLong(readLine());
        if (length == -1) {
            return new RespValue.RespNull();
        }
        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new IOException("hotkv: bulk string length " + length + " out of range");
        }
        byte[] data = readNBytes(length);
        expectCRLF();
        return new RespValue.RespBulkString(data);
    }

    private RespValue readBlobError() throws IOException {
        long length = parseLong(readLine());
        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new IOException("hotkv: blob error length " + length + " out of range");
        }
        byte[] data = readNBytes(length);
        expectCRLF();
        return new RespValue.RespBlobError(new String(data, StandardCharsets.UTF_8));
    }

    private RespValue readVerbatimString() throws IOException {
        long length = parseLong(readLine());
        if (length < 4 || length > MAX_BULK_LENGTH) {
            throw new IOException("hotkv: verbatim string length " + length + " out of range");
        }
        byte[] data = readNBytes(length);
        expectCRLF();
        if (data[3] != ':') {
            throw new IOException("hotkv: malformed verbatim string header");
        }
        String format = new String(data, 0, 3, StandardCharsets.US_ASCII);
        String text = new String(data, 4, data.length - 4, StandardCharsets.UTF_8);
        return new RespValue.RespVerbatimString(format, text);
    }

    private RespValue readBigNumber() throws IOException {
        String line = readLine();
        try {
            new java.math.BigInteger(line);
        } catch (NumberFormatException e) {
            throw new IOException("hotkv: invalid big number '" + line + "'", e);
        }
        return new RespValue.RespBigNumber(line);
    }

    private RespValue readBoolean() throws IOException {
        String line = readLine();
        if ("t".equals(line)) {
            return new RespValue.RespBoolean(true);
        }
        if ("f".equals(line)) {
            return new RespValue.RespBoolean(false);
        }
        throw new IOException("hotkv: invalid boolean '" + line + "'");
    }

    private RespValue readArray(int depth) throws IOException {
        long count = parseLong(readLine());
        if (count == -1) {
            return new RespValue.RespNull();
        }
        return new RespValue.RespArray(readElements(count, depth));
    }

    private RespValue readSet(int depth) throws IOException {
        long count = parseLong(readLine());
        return new RespValue.RespSet(readElements(count, depth));
    }

    private RespValue readPush(int depth) throws IOException {
        long count = parseLong(readLine());
        return new RespValue.RespPush(readElements(count, depth));
    }

    private List<RespValue> readElements(long count, int depth) throws IOException {
        if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
            throw new IOException("hotkv: element count " + count + " out of range");
        }
        List<RespValue> items = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            items.add(readValue(depth + 1));
        }
        return items;
    }

    private RespValue readMap(int depth) throws IOException {
        long count = parseLong(readLine());
        return new RespValue.RespMap(readPairs(count, depth));
    }

    private RespValue readAttribute(int depth) throws IOException {
        long count = parseLong(readLine());
        List<Map.Entry<RespValue, RespValue>> attrs = readPairs(count, depth);
        RespValue value = readValue(depth);
        return new RespValue.RespAttribute(attrs, value);
    }

    private List<Map.Entry<RespValue, RespValue>> readPairs(long count, int depth) throws IOException {
        if (count < 0 || count > MAX_ARRAY_ELEMENTS / 2) {
            throw new IOException("hotkv: pair count " + count + " out of range");
        }
        List<Map.Entry<RespValue, RespValue>> pairs = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            RespValue k = readValue(depth + 1);
            RespValue v = readValue(depth + 1);
            pairs.add(new AbstractMap.SimpleImmutableEntry<>(k, v));
        }
        return pairs;
    }

    // --- low level byte/line reading over an internally buffered stream ---

    private int readByteOrEOF() throws IOException {
        if (pos >= limit) {
            fill();
            if (pos >= limit) {
                throw new EOFException("hotkv: connection closed while reading a reply");
            }
        }
        return buf[pos++] & 0xFF;
    }

    private void fill() throws IOException {
        if (eof) {
            return;
        }
        limit = in.read(buf);
        pos = 0;
        if (limit < 0) {
            eof = true;
            limit = 0;
        }
    }

    /** Reads a line up to, but not including, the trailing CRLF. */
    private String readLine() throws IOException {
        int startPos = pos;
        ByteArrayOutputStream spilled = null;
        while (true) {
            if (pos >= limit) {
                spilled = spill(spilled, startPos, limit);
                fill();
                if (pos >= limit) {
                    throw new EOFException("hotkv: connection closed while reading a line");
                }
                startPos = pos;
                continue;
            }
            if (buf[pos] != '\r') {
                pos++;
                continue;
            }
            if (pos + 1 < limit) {
                if (buf[pos + 1] != '\n') {
                    throw new IOException("hotkv: invalid RESP line terminator");
                }
                String line = spilled == null
                        ? new String(buf, startPos, pos - startPos, StandardCharsets.UTF_8)
                        : finish(spilled, startPos, pos);
                pos += 2;
                return line;
            }
            // The buffered '\r' is the last byte available; spill it and refill to see '\n'.
            spilled = spill(spilled, startPos, pos + 1);
            pos++;
            fill();
            if (pos >= limit || buf[pos] != '\n') {
                throw new IOException("hotkv: invalid RESP line terminator");
            }
            pos++;
            byte[] bytes = spilled.toByteArray();
            return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
        }
    }

    private ByteArrayOutputStream spill(ByteArrayOutputStream out, int from, int to) {
        if (out == null) {
            out = new ByteArrayOutputStream(Math.max(32, to - from));
        }
        if (to > from) {
            out.write(buf, from, to - from);
        }
        return out;
    }

    private String finish(ByteArrayOutputStream out, int from, int to) {
        if (to > from) {
            out.write(buf, from, to - from);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Reads exactly {@code length} bytes of binary payload (no line semantics). */
    private byte[] readNBytes(long length) throws IOException {
        byte[] dst = new byte[(int) length];
        int filled = 0;
        if (pos < limit) {
            int avail = (int) Math.min(limit - pos, dst.length);
            System.arraycopy(buf, pos, dst, 0, avail);
            pos += avail;
            filled = avail;
        }
        while (filled < dst.length) {
            int n = in.read(dst, filled, dst.length - filled);
            if (n < 0) {
                throw new EOFException("hotkv: connection closed while reading a " + dst.length + " byte payload");
            }
            filled += n;
        }
        return dst;
    }

    private void expectCRLF() throws IOException {
        int cr = readByteOrEOF();
        int lf = readByteOrEOF();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("hotkv: missing CRLF after payload");
        }
    }

    private static long parseLong(String s) throws IOException {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IOException("hotkv: invalid integer '" + s + "'", e);
        }
    }

    private static double parseDouble(String s) throws IOException {
        switch (s) {
            case "inf":
            case "+inf":
                return Double.POSITIVE_INFINITY;
            case "-inf":
                return Double.NEGATIVE_INFINITY;
            case "nan":
                return Double.NaN;
            default:
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new IOException("hotkv: invalid double '" + s + "'", e);
                }
        }
    }
}
