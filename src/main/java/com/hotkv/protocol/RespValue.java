// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A single value read from, or written to, a HotKV connection using the
 * RESP2 or RESP3 wire protocol. Every reply the server can send is
 * represented by exactly one of the permitted record types below, so callers
 * of the low level {@code command(...)} escape hatch can use an exhaustive
 * {@code switch} pattern match to handle every case.
 *
 * <p>Instances are immutable and safe to share across threads.
 */
public sealed interface RespValue {

    /** {@code +...\r\n}: a short, trusted, non-binary status string such as {@code OK}. */
    record RespSimpleString(String value) implements RespValue {}

    /** {@code -...\r\n}: a RESP2 error reply. {@code message} excludes the leading '-'. */
    record RespError(String message) implements RespValue {}

    /** {@code :...\r\n}: a signed 64 bit integer reply. */
    record RespInteger(long value) implements RespValue {}

    /** {@code $len\r\n...\r\n}: a binary safe bulk string. Never null; see {@link RespNull}. */
    record RespBulkString(byte[] value) implements RespValue {
        public String asUtf8() {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    /** {@code *count\r\n...}: an ordered, possibly heterogeneous, list of values. */
    record RespArray(List<RespValue> items) implements RespValue {}

    /** RESP2 null bulk string/array ({@code $-1}, {@code *-1}) or RESP3 {@code _\r\n}. */
    record RespNull() implements RespValue {}

    /** RESP3 {@code ,...\r\n}: a double precision float, including {@code inf}/{@code -inf}/{@code nan}. */
    record RespDouble(double value) implements RespValue {}

    /** RESP3 {@code #t\r\n} / {@code #f\r\n}: a boolean. */
    record RespBoolean(boolean value) implements RespValue {}

    /** RESP3 {@code !len\r\n...\r\n}: an error reply with a binary safe body. */
    record RespBlobError(String message) implements RespValue {}

    /** RESP3 {@code =len\r\ntxt:...\r\n}: a bulk string tagged with a 3 byte format hint. */
    record RespVerbatimString(String format, String text) implements RespValue {}

    /** RESP3 {@code (...\r\n}: an arbitrary precision integer, kept as its decimal digits. */
    record RespBigNumber(String digits) implements RespValue {}

    /** RESP3 {@code %count\r\n}: an ordered key/value mapping. */
    record RespMap(List<Map.Entry<RespValue, RespValue>> entries) implements RespValue {}

    /** RESP3 {@code ~count\r\n}: an unordered collection of distinct values. */
    record RespSet(List<RespValue> items) implements RespValue {}

    /** RESP3 {@code >count\r\n}: an out of band push message (pub/sub, invalidation, ...). */
    record RespPush(List<RespValue> items) implements RespValue {}

    /** RESP3 {@code \|count\r\n}: attribute metadata that preceded {@code value} on the wire. */
    record RespAttribute(List<Map.Entry<RespValue, RespValue>> attributes, RespValue value) implements RespValue {}

    /** Returns true for {@link RespNull}. */
    default boolean isNull() {
        return this instanceof RespNull;
    }

    /** Returns true for {@link RespError} and {@link RespBlobError}. */
    default boolean isError() {
        return this instanceof RespError || this instanceof RespBlobError;
    }

    /** Returns the error message for {@link RespError}/{@link RespBlobError}, else null. */
    default String errorMessage() {
        if (this instanceof RespError e) {
            return e.message();
        }
        if (this instanceof RespBlobError e) {
            return e.message();
        }
        return null;
    }
}
