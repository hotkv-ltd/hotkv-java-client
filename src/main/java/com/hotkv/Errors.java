// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.AskRedirectException;
import com.hotkv.exceptions.HotKVServerException;
import com.hotkv.exceptions.NoAuthException;
import com.hotkv.exceptions.NotLicensedException;
import com.hotkv.exceptions.ReadOnlyException;
import com.hotkv.exceptions.WrongTypeException;
import com.hotkv.exceptions.MovedRedirectException;

/** Maps a raw RESP error message to the most specific {@link HotKVServerException} subtype. */
final class Errors {

    private Errors() {
    }

    static HotKVServerException fromServerError(String rawMessage) {
        String prefix = prefixOf(rawMessage);
        return switch (prefix) {
            case "NOLICENSE" -> new NotLicensedException(rawMessage);
            case "WRONGTYPE" -> new WrongTypeException(rawMessage);
            case "NOAUTH" -> new NoAuthException(rawMessage);
            case "READONLY" -> new ReadOnlyException(rawMessage);
            case "MOVED" -> new MovedRedirectException(rawMessage);
            case "ASK" -> new AskRedirectException(rawMessage);
            default -> new HotKVServerException(rawMessage);
        };
    }

    private static String prefixOf(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }
}
