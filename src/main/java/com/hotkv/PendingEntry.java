// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** One entry from the extended form of {@code XPENDING key group start end count [consumer]}. */
public record PendingEntry(String id, String consumer, long idleMillis, long deliveryCount) {
}
