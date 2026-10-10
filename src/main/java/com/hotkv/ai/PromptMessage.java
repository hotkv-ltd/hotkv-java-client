// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.ai;

/** One chat style message, used only to build a deterministic {@link PromptCache} key. Provider neutral. */
public record PromptMessage(String role, String content) {
}
