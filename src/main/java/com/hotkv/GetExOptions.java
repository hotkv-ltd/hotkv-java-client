// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional arguments for {@link HotKV#getEx(String, GetExOptions)}:
 * {@code [EX seconds | PX millis | EXAT unix-seconds | PXAT unix-millis |
 * PERSIST]}.
 */
public final class GetExOptions {

    private Long exSeconds;
    private Long pxMillis;
    private Long exAtSeconds;
    private Long pxAtMillis;
    private boolean persist;

    private GetExOptions() {
    }

    public static GetExOptions builder() {
        return new GetExOptions();
    }

    public GetExOptions ex(long seconds) {
        this.exSeconds = seconds;
        return this;
    }

    public GetExOptions px(long millis) {
        this.pxMillis = millis;
        return this;
    }

    public GetExOptions exAt(long unixSeconds) {
        this.exAtSeconds = unixSeconds;
        return this;
    }

    public GetExOptions pxAt(long unixMillis) {
        this.pxAtMillis = unixMillis;
        return this;
    }

    /** Removes the key's TTL, making it persistent. */
    public GetExOptions persist() {
        this.persist = true;
        return this;
    }

    List<Object> toArgs() {
        List<Object> args = new ArrayList<>(2);
        if (exSeconds != null) {
            args.add("EX");
            args.add(exSeconds);
        } else if (pxMillis != null) {
            args.add("PX");
            args.add(pxMillis);
        } else if (exAtSeconds != null) {
            args.add("EXAT");
            args.add(exAtSeconds);
        } else if (pxAtMillis != null) {
            args.add("PXAT");
            args.add(pxAtMillis);
        } else if (persist) {
            args.add("PERSIST");
        }
        return args;
    }
}
