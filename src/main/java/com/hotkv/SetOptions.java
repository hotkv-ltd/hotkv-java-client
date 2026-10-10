// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional arguments for {@link HotKV#set(String, String, SetOptions)}:
 * {@code [NX | XX] [GET] [EX seconds | PX millis | EXAT unix-seconds |
 * PXAT unix-millis | KEEPTTL]}.
 */
public final class SetOptions {

    private Long exSeconds;
    private Long pxMillis;
    private Long exAtSeconds;
    private Long pxAtMillis;
    private boolean keepTtl;
    private boolean nx;
    private boolean xx;
    private boolean get;

    private SetOptions() {
    }

    public static SetOptions builder() {
        return new SetOptions();
    }

    public SetOptions ex(long seconds) {
        this.exSeconds = seconds;
        return this;
    }

    public SetOptions px(long millis) {
        this.pxMillis = millis;
        return this;
    }

    public SetOptions exAt(long unixSeconds) {
        this.exAtSeconds = unixSeconds;
        return this;
    }

    public SetOptions pxAt(long unixMillis) {
        this.pxAtMillis = unixMillis;
        return this;
    }

    /** Retains the key's current TTL instead of clearing it. Mutually exclusive with the EX/PX variants. */
    public SetOptions keepTtl() {
        this.keepTtl = true;
        return this;
    }

    /** Only set the key if it does not already exist. */
    public SetOptions nx() {
        this.nx = true;
        return this;
    }

    /** Only set the key if it already exists. */
    public SetOptions xx() {
        this.xx = true;
        return this;
    }

    /** Return the previous value (or null) instead of the usual OK reply. */
    public SetOptions get() {
        this.get = true;
        return this;
    }

    boolean wantsGet() {
        return get;
    }

    List<Object> toArgs() {
        List<Object> args = new ArrayList<>(6);
        if (nx) {
            args.add("NX");
        }
        if (xx) {
            args.add("XX");
        }
        if (get) {
            args.add("GET");
        }
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
        } else if (keepTtl) {
            args.add("KEEPTTL");
        }
        return args;
    }
}
