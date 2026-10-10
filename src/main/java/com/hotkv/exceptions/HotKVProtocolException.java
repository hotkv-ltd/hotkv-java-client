// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Thrown when a reply from the server could not be parsed as valid RESP2 or
 * RESP3, or did not have the shape a command's decoder expected. Indicates
 * a wire level problem, not a rejected command.
 */
public class HotKVProtocolException extends HotKVException {

    private static final long serialVersionUID = 1L;

    public HotKVProtocolException(String message) {
        super(message);
    }

    public HotKVProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
