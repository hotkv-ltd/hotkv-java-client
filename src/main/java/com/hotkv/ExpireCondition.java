// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** Optional condition for {@code EXPIRE}/{@code PEXPIRE} family commands. */
public enum ExpireCondition {
    /** Set expiry only when the key has no existing expiry. */
    NX,
    /** Set expiry only when the key already has an expiry. */
    XX,
    /** Set expiry only when the new expiry is greater than the current one. */
    GT,
    /** Set expiry only when the new expiry is less than the current one. */
    LT
}
