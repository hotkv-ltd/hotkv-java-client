// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.List;

/** The key popped from and its members, as returned by {@code ZMPOP}/{@code BZMPOP}. */
public record ZPopResult(String key, List<ScoredMember> members) {
}
