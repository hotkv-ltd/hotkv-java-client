// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the canonical, cross-SDK identical {@link PromptCache} key; no server needed. */
class PromptCacheKeysTest {

    @Test
    void canonicalJsonExactShape() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("top_p", "1");
        params.put("temperature", "0.7"); // inserted out of order; must still sort ascending by byte value

        String json = PromptCacheKeys.canonicalJson("openai", "gpt-6-sol", "",
                List.of(new PromptMessage("user", "hi")), params);

        assertEquals(
                "{\"provider\":\"openai\",\"model\":\"gpt-6-sol\",\"system\":\"\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"params\":{\"temperature\":\"0.7\",\"top_p\":\"1\"}}",
                json);
    }

    @Test
    void missingSystemAndParamsAreStillPresent() {
        String json = PromptCacheKeys.canonicalJson("openai", "gpt-6-sol", null, List.of(), null);
        assertTrue(json.contains("\"system\":\"\""));
        assertTrue(json.contains("\"params\":{}"));
        assertTrue(json.contains("\"messages\":[]"));
    }

    @Test
    void controlAndSpecialCharactersAreEscaped() {
        String json = PromptCacheKeys.canonicalJson("p", "m", "", List.of(new PromptMessage("user", "a\"b\\c\nd\te")), Map.of());
        assertTrue(json.contains("\"content\":\"a\\\"b\\\\c\\nd\\te\""));
    }

    @Test
    void nonAsciiIsLeftAsLiteralUtf8NotEscaped() {
        String json = PromptCacheKeys.canonicalJson("p", "m", "", List.of(new PromptMessage("user", "café 你好")), Map.of());
        assertTrue(json.contains("café 你好"));
        assertTrue(json.indexOf("\\u00e9") < 0, "non-ASCII must not be \\u escaped");
    }

    @Test
    void keyIsLowercaseHex64CharSha256() {
        String key = PromptCacheKeys.key("openai", "gpt-6-sol", "", List.of(new PromptMessage("user", "hi")), Map.of());
        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"));
    }

    @Test
    void keyMatchesIndependentlyComputedSha256() throws Exception {
        String json = PromptCacheKeys.canonicalJson("openai", "gpt-6-sol", "", List.of(new PromptMessage("user", "hi")), Map.of());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        String key = PromptCacheKeys.key("openai", "gpt-6-sol", "", List.of(new PromptMessage("user", "hi")), Map.of());
        assertEquals(hex.toString(), key);
    }

    @Test
    void keyIsDeterministicRegardlessOfParamMapInsertionOrder() {
        Map<String, String> p1 = new LinkedHashMap<>();
        p1.put("a", "1");
        p1.put("b", "2");
        Map<String, String> p2 = new LinkedHashMap<>();
        p2.put("b", "2");
        p2.put("a", "1");

        String k1 = PromptCacheKeys.key("p", "m", "", List.of(), p1);
        String k2 = PromptCacheKeys.key("p", "m", "", List.of(), p2);
        assertEquals(k1, k2);
    }

    @Test
    void messageOrderMattersForTheKey() {
        List<PromptMessage> a = List.of(new PromptMessage("user", "x"), new PromptMessage("assistant", "y"));
        List<PromptMessage> b = List.of(new PromptMessage("assistant", "y"), new PromptMessage("user", "x"));
        assertTrue(!PromptCacheKeys.key("p", "m", "", a, Map.of()).equals(PromptCacheKeys.key("p", "m", "", b, Map.of())));
    }

    @Test
    void recordConvenienceMatchesStaticBuilder() {
        PromptCacheKeyInput input = new PromptCacheKeyInput("openai", "gpt-6-sol", "", List.of(new PromptMessage("user", "hi")), Map.of());
        assertEquals(PromptCacheKeys.key("openai", "gpt-6-sol", "", List.of(new PromptMessage("user", "hi")), Map.of()), input.cacheKey());
    }
}
