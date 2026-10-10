// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** A key and an access count, as returned by {@code HOTKEYS TOP}. */
public record KeyCount(String key, long count) {
}
