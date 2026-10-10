// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Thrown when an enterprise or AI command is
 * rejected with a {@code NOLICENSE} error because the server's currently
 * loaded license does not cover that feature. Distinguished from other
 * server errors so callers, and integration tests, can detect it and skip
 * or degrade gracefully rather than fail.
 */
public class NotLicensedException extends HotKVServerException {

    private static final long serialVersionUID = 1L;

    public NotLicensedException(String rawMessage) {
        super(rawMessage);
    }
}
