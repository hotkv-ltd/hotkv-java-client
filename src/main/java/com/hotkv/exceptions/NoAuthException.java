// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/** Thrown when the server requires authentication that was missing or invalid ({@code NOAUTH}). */
public class NoAuthException extends HotKVServerException {

    private static final long serialVersionUID = 1L;

    public NoAuthException(String rawMessage) {
        super(rawMessage);
    }
}
