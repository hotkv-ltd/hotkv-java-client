// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/** Thrown when a command is applied to a key holding a value of the wrong type ({@code WRONGTYPE}). */
public class WrongTypeException extends HotKVServerException {

    private static final long serialVersionUID = 1L;

    public WrongTypeException(String rawMessage) {
        super(rawMessage);
    }
}
