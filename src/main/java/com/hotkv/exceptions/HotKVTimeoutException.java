// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Thrown when connecting, reading a reply or borrowing a pooled connection
 * exceeds its configured timeout. A subtype of {@link HotKVConnectionException}
 * because callers that already treat connection failures as retryable
 * transport errors handle timeouts the same way without extra code.
 */
public class HotKVTimeoutException extends HotKVConnectionException {

    private static final long serialVersionUID = 1L;

    public HotKVTimeoutException(String message) {
        super(message);
    }

    public HotKVTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
