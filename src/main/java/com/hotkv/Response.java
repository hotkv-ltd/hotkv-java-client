// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.util.function.Function;

/**
 * A placeholder for the result of one command queued on a {@link Pipeline}
 * or {@link Transaction}, populated once {@code sync()}/{@code exec()}
 * runs. Reading it before that throws {@link IllegalStateException};
 * reading it after a per-command server error throws the same mapped
 * exception {@link HotKV} would have thrown for a direct call, without
 * having failed the rest of the batch.
 */
public final class Response<T> {

    private final Function<RespValue, T> decoder;
    private RespValue raw;
    private boolean resolved;

    Response(Function<RespValue, T> decoder) {
        this.decoder = decoder;
    }

    void resolve(RespValue raw) {
        this.raw = raw;
        this.resolved = true;
    }

    public T get() {
        requireResolved();
        if (raw.isError()) {
            throw Errors.fromServerError(raw.errorMessage());
        }
        return decoder.apply(raw);
    }

    public boolean isError() {
        requireResolved();
        return raw.isError();
    }

    public boolean isAvailable() {
        return resolved;
    }

    private void requireResolved() {
        if (!resolved) {
            throw new IllegalStateException("hotkv: response is not available until sync()/exec() has run");
        }
    }
}
