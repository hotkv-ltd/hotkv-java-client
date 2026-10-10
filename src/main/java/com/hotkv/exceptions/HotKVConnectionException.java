// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Thrown when a connection to the server could not be established, was
 * lost, or the pool could not hand out a connection (exhausted or closed).
 */
public class HotKVConnectionException extends HotKVException {

    private static final long serialVersionUID = 1L;

    public HotKVConnectionException(String message) {
        super(message);
    }

    public HotKVConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
