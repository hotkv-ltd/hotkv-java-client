// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Thrown when the server replies with a RESP error (RESP2 {@code -...} or
 * RESP3 blob error {@code !...}). {@link #errorPrefix()} is the first
 * whitespace delimited token of the reply, e.g. {@code ERR}, {@code
 * WRONGTYPE}, {@code MOVED}, {@code NOAUTH}, {@code NOLICENSE}; several
 * well known prefixes are surfaced as the more specific subclasses in this
 * package instead of a plain {@code HotKVServerException}.
 */
public class HotKVServerException extends HotKVException {

    private static final long serialVersionUID = 1L;

    private final String errorPrefix;
    private final String rawMessage;

    public HotKVServerException(String rawMessage) {
        super("hotkv: " + rawMessage);
        this.rawMessage = rawMessage;
        int sp = rawMessage.indexOf(' ');
        this.errorPrefix = sp < 0 ? rawMessage : rawMessage.substring(0, sp);
    }

    /** The error's leading token, e.g. {@code ERR}, {@code WRONGTYPE}, {@code NOLICENSE}. */
    public String errorPrefix() {
        return errorPrefix;
    }

    /** The full, unmodified error text the server sent (without the leading '-' or '!'). */
    public String rawMessage() {
        return rawMessage;
    }
}
