// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builds the canonical, cross-language identical cache key for {@link
 * PromptCache} (the same form in every official HotKV SDK):
 *
 * <ol>
 *   <li>A JSON object with exactly the top-level keys {@code provider},
 *       {@code model}, {@code system}, {@code messages}, {@code params},
 *       in that order.
 *   <li>{@code messages} is a JSON array in the caller's given order; each
 *       element has keys {@code role}, {@code content}, in that order.
 *   <li>{@code params} is a JSON object of string-valued parameters, keys
 *       sorted ascending by byte value (a plain ASCII sort).
 *   <li>Compact encoding: no insignificant whitespace; standard JSON
 *       escaping for {@code "}, {@code \} and control characters below
 *       0x20 (as {@code \\uXXXX}); non-ASCII characters are left as
 *       literal UTF-8, never {@code \\uXXXX} escaped.
 *   <li>{@code system} empty and {@code params} empty are still present,
 *       as {@code ""} and {@code {}}, never omitted.
 * </ol>
 *
 * <p>The key itself is the lowercase hex SHA-256 digest (64 characters) of
 * that JSON string's UTF-8 bytes.
 */
public final class PromptCacheKeys {

    private PromptCacheKeys() {
    }

    public static String key(PromptCacheKeyInput input) {
        return key(input.provider(), input.model(), input.system(), input.messages(), input.params());
    }

    public static String key(String provider, String model, String system, List<PromptMessage> messages,
            Map<String, String> params) {
        return sha256Hex(canonicalJson(provider, model, system, messages, params));
    }

    /** The canonical JSON string a key is hashed from; exposed for cross-SDK testing and debugging. */
    public static String canonicalJson(String provider, String model, String system, List<PromptMessage> messages,
            Map<String, String> params) {
        StringBuilder b = new StringBuilder(256);
        b.append('{');

        b.append("\"provider\":");
        appendJsonString(b, nullToEmpty(provider));
        b.append(',');

        b.append("\"model\":");
        appendJsonString(b, nullToEmpty(model));
        b.append(',');

        b.append("\"system\":");
        appendJsonString(b, nullToEmpty(system));
        b.append(',');

        b.append("\"messages\":[");
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                PromptMessage m = messages.get(i);
                b.append('{');
                b.append("\"role\":");
                appendJsonString(b, nullToEmpty(m.role()));
                b.append(",\"content\":");
                appendJsonString(b, nullToEmpty(m.content()));
                b.append('}');
            }
        }
        b.append("],");

        b.append("\"params\":{");
        if (params != null && !params.isEmpty()) {
            List<String> keys = new ArrayList<>(params.keySet());
            Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                appendJsonString(b, keys.get(i));
                b.append(':');
                appendJsonString(b, nullToEmpty(params.get(keys.get(i))));
            }
        }
        b.append('}');

        b.append('}');
        return b.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void appendJsonString(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    private static String sha256Hex(String s) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("hotkv: SHA-256 is not available on this JVM", e);
        }
        byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
