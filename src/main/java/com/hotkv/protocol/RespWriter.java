// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes RESP commands (arrays of bulk strings) to a connection. The caller
 * supplies an already buffered {@link OutputStream} (see
 * {@link java.io.BufferedOutputStream}) and calls {@link #flush()} after one
 * or more commands to send them in a single write, which is what makes
 * pipelining a single round trip. Not thread safe: each connection owns
 * exactly one writer, used by a single caller at a time.
 */
public final class RespWriter {

    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out;
    }

    /**
     * Writes one command as a RESP array of bulk strings. Each argument may
     * be a {@code String}, {@code byte[]}, {@code Long}/{@code Integer},
     * {@code Double}/{@code Float} or {@code Boolean}; anything else is
     * converted with {@code String.valueOf}. Does not flush.
     */
    public void writeCommand(Object... args) throws IOException {
        byte[][] encoded = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            encoded[i] = toBytes(args[i]);
        }
        writeEncoded(encoded);
    }

    /** Writes one command from a pre-built argument list. Does not flush. */
    public void writeCommand(java.util.List<?> args) throws IOException {
        byte[][] encoded = new byte[args.size()][];
        int i = 0;
        for (Object arg : args) {
            encoded[i++] = toBytes(arg);
        }
        writeEncoded(encoded);
    }

    /**
     * Every argument is converted before the first byte is written, so an argument that cannot be
     * encoded (null, NaN) throws without leaving half a command in the connection's buffer.
     */
    private void writeEncoded(byte[][] encoded) throws IOException {
        writeArrayHeader(encoded.length);
        for (byte[] arg : encoded) {
            writeBulk(arg);
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    private void writeArrayHeader(int n) throws IOException {
        out.write('*');
        out.write(Integer.toString(n).getBytes(StandardCharsets.US_ASCII));
        writeCRLF();
    }

    private void writeBulk(byte[] data) throws IOException {
        out.write('$');
        out.write(Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
        writeCRLF();
        out.write(data);
        writeCRLF();
    }

    private void writeCRLF() throws IOException {
        out.write('\r');
        out.write('\n');
    }

    /** Converts one command argument to its wire bytes. Package visible for reuse by decoders/tests. */
    public static byte[] toBytes(Object arg) {
        if (arg == null) {
            throw new IllegalArgumentException("hotkv: command argument must not be null; pass an explicit sentinel instead");
        }
        if (arg instanceof byte[] b) {
            return b;
        }
        if (arg instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        if (arg instanceof Long || arg instanceof Integer || arg instanceof Short || arg instanceof Byte) {
            return arg.toString().getBytes(StandardCharsets.US_ASCII);
        }
        if (arg instanceof Double d) {
            return formatDouble(d).getBytes(StandardCharsets.US_ASCII);
        }
        if (arg instanceof Float f) {
            String text = f.isNaN() || f.isInfinite()
                    ? Numbers.score(f.doubleValue())
                    : Numbers.float32(f.doubleValue());
            return text.getBytes(StandardCharsets.US_ASCII);
        }
        if (arg instanceof Boolean b) {
            return (b ? "1" : "0").getBytes(StandardCharsets.US_ASCII);
        }
        return arg.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Formats a double the way HotKV expects numeric command arguments: plain positional decimal
     * digits with the shortest round tripping form (no exponent, no leading '+', no grouping
     * separators, negative zero as "0"). Infinities are written as "inf"/"-inf", which the
     * server accepts for sorted set scores and bounds; NaN throws
     * {@link IllegalArgumentException}. See {@link Numbers} for the stricter formatters.
     */
    public static String formatDouble(double d) {
        return Numbers.score(d);
    }
}
