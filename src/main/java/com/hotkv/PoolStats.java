// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/**
 * A snapshot of {@link HotKV#poolStats()}.
 *
 * @param totalConnections connections currently open (borrowed and idle)
 * @param idleConnections  connections currently sitting idle in the pool
 * @param maxConnections   the pool's configured maximum size
 */
public record PoolStats(int totalConnections, int idleConnections, int maxConnections) {
}
