// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** One hash field and its value, as returned by {@code HSCAN}. */
public record FieldValue(String field, String value) {
}
