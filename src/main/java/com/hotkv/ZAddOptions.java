// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional arguments for {@code ZADD}: {@code [NX | XX] [GT | LT] [CH]
 * [INCR]}. NX/XX and GT/LT are mutually exclusive.
 */
public final class ZAddOptions {

    private boolean nx;
    private boolean xx;
    private boolean gt;
    private boolean lt;
    private boolean ch;
    private boolean incr;

    private ZAddOptions() {
    }

    public static ZAddOptions builder() {
        return new ZAddOptions();
    }

    public ZAddOptions nx() {
        this.nx = true;
        return this;
    }

    public ZAddOptions xx() {
        this.xx = true;
        return this;
    }

    public ZAddOptions gt() {
        this.gt = true;
        return this;
    }

    public ZAddOptions lt() {
        this.lt = true;
        return this;
    }

    /** Return the number of changed elements (added or updated) instead of just added. */
    public ZAddOptions ch() {
        this.ch = true;
        return this;
    }

    /** Increment mode: add to the existing score like {@code ZINCRBY}, for a single member. */
    public ZAddOptions incr() {
        this.incr = true;
        return this;
    }

    boolean isIncr() {
        return incr;
    }

    List<Object> toArgs() {
        List<Object> args = new ArrayList<>(4);
        if (nx) {
            args.add("NX");
        }
        if (xx) {
            args.add("XX");
        }
        if (gt) {
            args.add("GT");
        }
        if (lt) {
            args.add("LT");
        }
        if (ch) {
            args.add("CH");
        }
        if (incr) {
            args.add("INCR");
        }
        return args;
    }
}
