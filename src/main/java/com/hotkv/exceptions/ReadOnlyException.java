// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/** Thrown when a write is rejected by a read only replica ({@code READONLY}). */
public class ReadOnlyException extends HotKVServerException {

    private static final long serialVersionUID = 1L;

    public ReadOnlyException(String rawMessage) {
        super(rawMessage);
    }
}
