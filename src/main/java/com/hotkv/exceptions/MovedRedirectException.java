// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Thrown when the server replies {@code MOVED}: the key's slot is now
 * permanently owned by a different node. {@link com.hotkv.HotKVCluster} handles this
 * internally by refreshing its slot map and retrying.
 */
public class MovedRedirectException extends ClusterRedirectException {

    private static final long serialVersionUID = 1L;

    public MovedRedirectException(String rawMessage) {
        super(rawMessage);
    }
}
