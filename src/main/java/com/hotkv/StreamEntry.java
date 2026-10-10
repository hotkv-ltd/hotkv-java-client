// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.Map;

/** One stream entry: its ID and its field/value payload, in the order the server returned them. */
public record StreamEntry(String id, Map<String, String> fields) {
}
