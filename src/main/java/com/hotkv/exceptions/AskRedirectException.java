// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv.exceptions;

/**
 * Thrown when the server replies {@code ASK}: the slot is being migrated
 * and this single key has already moved. {@link com.hotkv.HotKVCluster} handles this
 * internally by sending {@code ASKING} to the target node and retrying,
 * without updating its slot map (the migration may not be complete yet).
 */
public class AskRedirectException extends ClusterRedirectException {

    private static final long serialVersionUID = 1L;

    public AskRedirectException(String rawMessage) {
        super(rawMessage);
    }
}
