// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** One member and its score, as returned by sorted set commands with {@code WITHSCORES}. */
public record ScoredMember(String member, double score) {
}
