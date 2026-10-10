// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

/** What the caller supplied function passed to {@link PromptCache#getOrCall} must return on a cache miss. */
public record LlmCallResult(String response, LlmUsage usage) {

    public LlmCallResult(String response) {
        this(response, LlmUsage.none());
    }
}
