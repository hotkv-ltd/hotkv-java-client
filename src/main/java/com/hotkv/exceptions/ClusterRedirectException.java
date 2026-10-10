// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Base type for cluster redirect replies ({@code MOVED}/{@code ASK}), of
 * the form {@code <PREFIX> <slot> <host>:<port>}. See {@link MovedRedirectException}
 * and {@link AskRedirectException}.
 */
public abstract class ClusterRedirectException extends HotKVServerException {

    private static final long serialVersionUID = 1L;

    private final int slot;
    private final String host;
    private final int port;

    protected ClusterRedirectException(String rawMessage) {
        super(rawMessage);
        String[] parts = rawMessage.split("\\s+");
        int parsedSlot = -1;
        String parsedHost = "";
        int parsedPort = -1;
        if (parts.length >= 3) {
            try {
                parsedSlot = Integer.parseInt(parts[1]);
                int colon = parts[2].lastIndexOf(':');
                if (colon > 0) {
                    parsedHost = parts[2].substring(0, colon);
                    parsedPort = Integer.parseInt(parts[2].substring(colon + 1));
                }
            } catch (NumberFormatException ignored) {
                // Leave defaults; callers can still use rawMessage().
            }
        }
        this.slot = parsedSlot;
        this.host = parsedHost;
        this.port = parsedPort;
    }

    /** The hash slot the key belongs to, or -1 if the reply could not be parsed. */
    public int slot() {
        return slot;
    }

    /** The target node's host, or empty if the reply could not be parsed. */
    public String host() {
        return host;
    }

    /** The target node's port, or -1 if the reply could not be parsed. */
    public int port() {
        return port;
    }
}
