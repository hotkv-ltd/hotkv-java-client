// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

import java.util.List;
import java.util.Map;

/**
 * The normalized shape a {@link PromptCache} key is derived from. {@code
 * params} must be pre-formatted to strings by the caller (e.g. {@code
 * "0.7"}, not {@code 0.7}) so the same logical request always hashes to
 * the same key regardless of language or float formatting. Every official HotKV SDK derives
 * the same canonical form.
 */
public record PromptCacheKeyInput(String provider, String model, String system, List<PromptMessage> messages,
        Map<String, String> params) {

    public PromptCacheKeyInput {
        if (system == null) {
            system = "";
        }
        if (messages == null) {
            messages = List.of();
        }
        if (params == null) {
            params = Map.of();
        }
    }

    public PromptCacheKeyInput(String provider, String model, List<PromptMessage> messages) {
        this(provider, model, "", messages, Map.of());
    }

    /** The deterministic cache key: the lowercase hex SHA-256 digest of the canonical JSON form. */
    public String cacheKey() {
        return PromptCacheKeys.key(this);
    }
}
