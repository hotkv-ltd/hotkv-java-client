// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.HotKVProtocolException;
import com.hotkv.protocol.RespValue;
import com.hotkv.protocol.RespValue.RespArray;
import com.hotkv.protocol.RespValue.RespBigNumber;
import com.hotkv.protocol.RespValue.RespBoolean;
import com.hotkv.protocol.RespValue.RespBulkString;
import com.hotkv.protocol.RespValue.RespDouble;
import com.hotkv.protocol.RespValue.RespInteger;
import com.hotkv.protocol.RespValue.RespMap;
import com.hotkv.protocol.RespValue.RespNull;
import com.hotkv.protocol.RespValue.RespPush;
import com.hotkv.protocol.RespValue.RespSet;
import com.hotkv.protocol.RespValue.RespSimpleString;
import com.hotkv.protocol.RespValue.RespVerbatimString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Decodes successful {@link RespValue} replies into plain Java types. Every
 * method here assumes the RESP error case has already been handled
 * upstream (see {@code HotKVEngine.execute}); none of them look for
 * {@code RespError}/{@code RespBlobError}.
 */
final class Reply {

    private Reply() {
    }

    /** Unwraps a RESP3 attribute if present, returning the value it decorates. */
    static RespValue unwrap(RespValue v) {
        return v instanceof RespValue.RespAttribute a ? a.value() : v;
    }

    static String string(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespNull) {
            return null;
        }
        if (v instanceof RespBulkString b) {
            return b.asUtf8();
        }
        if (v instanceof RespSimpleString s) {
            return s.value();
        }
        if (v instanceof RespVerbatimString vs) {
            return vs.text();
        }
        if (v instanceof RespInteger i) {
            return Long.toString(i.value());
        }
        if (v instanceof RespBigNumber n) {
            return n.digits();
        }
        throw unexpected("a string", v);
    }

    static byte[] bytes(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespNull) {
            return null;
        }
        if (v instanceof RespBulkString b) {
            return b.value();
        }
        String s = string(v);
        return s == null ? null : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static long integer(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespInteger i) {
            return i.value();
        }
        if (v instanceof RespBulkString || v instanceof RespSimpleString) {
            String s = string(v);
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new HotKVProtocolException("hotkv: expected an integer reply, got '" + s + "'", e);
            }
        }
        if (v instanceof RespDouble d) {
            return (long) d.value();
        }
        throw unexpected("an integer", v);
    }

    static Long integerOrNull(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespNull) {
            return null;
        }
        return integer(v);
    }

    static boolean bool(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespBoolean b) {
            return b.value();
        }
        return integer(v) != 0;
    }

    static double doubleValue(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespDouble d) {
            return d.value();
        }
        String s = string(v);
        if (s == null) {
            throw unexpected("a double", v);
        }
        return switch (s) {
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            case "nan" -> Double.NaN;
            default -> Double.parseDouble(s);
        };
    }

    static Double doubleOrNull(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespNull) {
            return null;
        }
        return doubleValue(v);
    }

    static void ok(RespValue value) {
        string(value);
    }

    static List<RespValue> array(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespNull) {
            return null;
        }
        if (v instanceof RespArray a) {
            return a.items();
        }
        if (v instanceof RespSet s) {
            return s.items();
        }
        if (v instanceof RespPush p) {
            return p.items();
        }
        throw unexpected("an array", v);
    }

    static List<RespValue> arrayOrEmpty(RespValue value) {
        List<RespValue> items = array(value);
        return items == null ? List.of() : items;
    }

    static <T> List<T> list(RespValue value, Function<RespValue, T> mapper) {
        List<RespValue> items = array(value);
        if (items == null) {
            return null;
        }
        List<T> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            out.add(mapper.apply(item));
        }
        return out;
    }

    static List<String> stringList(RespValue value) {
        return list(value, Reply::string);
    }

    static List<Long> longList(RespValue value) {
        return list(value, Reply::integer);
    }

    /**
     * Decodes a flat array {@code [k1, v1, k2, v2, ...]} into an insertion ordered map of
     * strings; also accepts a native RESP3 map (some standard commands, e.g. {@code
     * XINFO}, upgrade to it under RESP3 while HotKV's own commands keep sending flat arrays).
     */
    static Map<String, String> flatStringMap(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespMap m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<RespValue, RespValue> e : m.entries()) {
                out.put(string(e.getKey()), string(e.getValue()));
            }
            return out;
        }
        List<RespValue> items = arrayOrEmpty(v);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < items.size(); i += 2) {
            out.put(string(items.get(i)), string(items.get(i + 1)));
        }
        return out;
    }

    /** Decodes a RESP3 map, or a RESP2 flat array fallback, into an insertion ordered map of strings. */
    static Map<String, String> stringMap(RespValue value) {
        return flatStringMap(value);
    }

    /**
     * Tolerant field accessors for STATS/GET/STATUS-like replies decoded by
     * {@link #flatStringMap}: every field is looked up BY NAME rather than
     * position, a missing field defaults to zero/empty/false rather than
     * throwing, and unknown extra fields are simply never looked up. This
     * lets the SDK keep working when the server adds fields to a reply.
     */
    static long longField(Map<String, String> fields, String name) {
        String v = fields.get(name);
        if (v == null || v.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(v);
    }

    static double doubleField(Map<String, String> fields, String name) {
        String v = fields.get(name);
        if (v == null || v.isEmpty()) {
            return 0.0;
        }
        return Double.parseDouble(v);
    }

    static String stringField(Map<String, String> fields, String name) {
        return fields.getOrDefault(name, "");
    }

    static boolean boolField(Map<String, String> fields, String name) {
        return "1".equals(fields.get(name));
    }

    /**
     * Decodes a "paired" reply into an ordered list of (member, value) entries, accepting
     * either RESP3's native array of 2 element pairs (e.g. {@code ZRANGE ... WITHSCORES},
     * {@code HRANDFIELD ... WITHVALUES}, {@code ZRANDMEMBER ... WITHSCORES} under {@code
     * HELLO 3}) or a flat alternating array {@code [a1, b1, a2, b2, ...]} (RESP2, or RESP3
     * without a native paired reply for that command). Callers never need to know which wire
     * shape the connection's negotiated protocol version produced.
     */
    static List<Map.Entry<RespValue, RespValue>> pairs(RespValue value) {
        List<RespValue> items = arrayOrEmpty(value);
        List<Map.Entry<RespValue, RespValue>> out = new ArrayList<>(items.size());
        if (!items.isEmpty() && unwrap(items.get(0)) instanceof RespArray first && first.items().size() == 2) {
            for (RespValue item : items) {
                List<RespValue> pair = ((RespArray) unwrap(item)).items();
                out.add(Map.entry(pair.get(0), pair.get(1)));
            }
            return out;
        }
        for (int i = 0; i + 1 < items.size(); i += 2) {
            out.add(Map.entry(items.get(i), items.get(i + 1)));
        }
        return out;
    }

    static RespValue at(List<RespValue> items, int index) {
        if (items == null || index < 0 || index >= items.size()) {
            throw new HotKVProtocolException("hotkv: reply array too short, expected index " + index);
        }
        return items.get(index);
    }

    /**
     * Converts an arbitrary reply into plain Java types: {@code String},
     * {@code Long}, {@code Double}, {@code Boolean}, {@code java.math.BigInteger},
     * {@code List<Object>}, {@code Map<String, Object>} or {@code null}. Used by
     * the generic {@code command(...)} escape hatch and by HotKV-specific replies
     * with server defined, free form shapes (e.g. {@code XINFO}).
     */
    static Object toJava(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespNull) {
            return null;
        }
        if (v instanceof RespBulkString b) {
            return b.asUtf8();
        }
        if (v instanceof RespSimpleString s) {
            return s.value();
        }
        if (v instanceof RespVerbatimString vs) {
            return vs.text();
        }
        if (v instanceof RespInteger i) {
            return i.value();
        }
        if (v instanceof RespDouble d) {
            return d.value();
        }
        if (v instanceof RespBoolean b) {
            return b.value();
        }
        if (v instanceof RespBigNumber n) {
            return new java.math.BigInteger(n.digits());
        }
        if (v instanceof RespArray a) {
            return toJavaList(a.items());
        }
        if (v instanceof RespSet s) {
            return toJavaList(s.items());
        }
        if (v instanceof RespPush p) {
            return toJavaList(p.items());
        }
        if (v instanceof RespMap m) {
            return toJavaMap(m.entries());
        }
        throw unexpected("a convertible value", v);
    }

    private static List<Object> toJavaList(List<RespValue> items) {
        List<Object> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            out.add(toJava(item));
        }
        return out;
    }

    private static Map<String, Object> toJavaMap(List<Map.Entry<RespValue, RespValue>> entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<RespValue, RespValue> e : entries) {
            out.put(String.valueOf(toJava(e.getKey())), toJava(e.getValue()));
        }
        return out;
    }

    /**
     * Decodes a flat array {@code [k1, v1, k2, v2, ...]} where values may be arbitrarily
     * structured; also accepts a native RESP3 map (some standard commands, e.g. {@code
     * XINFO}, upgrade to it under RESP3 while HotKV's own commands keep sending flat arrays).
     */
    static Map<String, Object> flatObjectMap(RespValue value) {
        RespValue v = unwrap(value);
        if (v instanceof RespMap m) {
            return toJavaMap(m.entries());
        }
        List<RespValue> items = arrayOrEmpty(v);
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < items.size(); i += 2) {
            out.put(string(items.get(i)), toJava(items.get(i + 1)));
        }
        return out;
    }

    private static HotKVProtocolException unexpected(String want, RespValue got) {
        return new HotKVProtocolException("hotkv: expected " + want + " reply, got " + got.getClass().getSimpleName());
    }
}
