// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** A sorted set member's rank and score, as returned by {@code ZRANK}/{@code ZREVRANK} with {@code WITHSCORE}. */
public record RankScore(long rank, double score) {
}
