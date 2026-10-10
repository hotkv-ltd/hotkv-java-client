// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.Map;

/** The summary form of {@code XPENDING key group} (no range arguments). */
public record PendingSummary(long count, String minId, String maxId, Map<String, Long> consumers) {
}
