// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.AskRedirectException;
import com.hotkv.exceptions.HotKVServerException;
import com.hotkv.exceptions.MovedRedirectException;
import com.hotkv.exceptions.NoAuthException;
import com.hotkv.exceptions.NotLicensedException;
import com.hotkv.exceptions.ReadOnlyException;
import com.hotkv.exceptions.WrongTypeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Unit tests mapping raw RESP error text to typed exceptions; no server needed. */
class ErrorsTest {

    @Test
    void notLicensedMapping() {
        HotKVServerException e = Errors.fromServerError("NOLICENSE enterprise feature 'prompt-cache' requires a valid license");
        assertInstanceOf(NotLicensedException.class, e);
        assertEquals("NOLICENSE", e.errorPrefix());
    }

    @Test
    void wrongTypeMapping() {
        assertInstanceOf(WrongTypeException.class,
                Errors.fromServerError("WRONGTYPE Operation against a key holding the wrong kind of value"));
    }

    @Test
    void noAuthMapping() {
        assertInstanceOf(NoAuthException.class, Errors.fromServerError("NOAUTH Authentication required."));
    }

    @Test
    void readOnlyMapping() {
        assertInstanceOf(ReadOnlyException.class, Errors.fromServerError("READONLY You can't write against a read only replica."));
    }

    @Test
    void movedMapping() {
        MovedRedirectException e = (MovedRedirectException) Errors.fromServerError("MOVED 3999 127.0.0.1:6381");
        assertEquals(3999, e.slot());
        assertEquals("127.0.0.1", e.host());
        assertEquals(6381, e.port());
    }

    @Test
    void askMapping() {
        AskRedirectException e = (AskRedirectException) Errors.fromServerError("ASK 3999 127.0.0.1:6381");
        assertEquals(3999, e.slot());
        assertEquals(6381, e.port());
    }

    @Test
    void genericErrorFallsBackToPlainServerException() {
        HotKVServerException e = Errors.fromServerError("ERR wrong number of arguments for 'get' command");
        assertEquals(HotKVServerException.class, e.getClass());
        assertEquals("ERR", e.errorPrefix());
        assertEquals("ERR wrong number of arguments for 'get' command", e.rawMessage());
    }
}
