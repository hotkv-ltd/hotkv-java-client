// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.List;

/**
 * One page from a {@code SCAN}-family cursor. {@code cursor} is
 * {@code "0"} once iteration has completed; pass it back in to continue.
 */
public record ScanResult<T>(String cursor, List<T> items) {

    public boolean isFinished() {
        return "0".equals(cursor);
    }
}
