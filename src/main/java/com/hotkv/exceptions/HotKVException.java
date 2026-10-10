// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Base type for every exception this SDK throws. Unchecked, so callers opt
 * in to handling specific failure modes (see the subclasses in this
 * package) instead of being forced to catch a checked exception on every
 * call.
 */
public class HotKVException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HotKVException(String message) {
        super(message);
    }

    public HotKVException(String message, Throwable cause) {
        super(message, cause);
    }
}
