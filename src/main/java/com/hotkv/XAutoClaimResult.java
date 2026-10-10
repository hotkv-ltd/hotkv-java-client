// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.List;

/**
 * The result of {@code XAUTOCLAIM}: the cursor to resume from, the claimed
 * entries, and the IDs of entries deleted from the stream that were
 * dropped from the pending list along the way.
 */
public record XAutoClaimResult(String nextCursor, List<StreamEntry> entries, List<String> deletedIds) {
}
