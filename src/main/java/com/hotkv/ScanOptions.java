// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.ArrayList;
import java.util.List;

/** Optional {@code MATCH}/{@code COUNT}/{@code TYPE} arguments shared by the {@code SCAN} command family. */
public final class ScanOptions {

    private String match;
    private Long count;
    private String type;

    private ScanOptions() {
    }

    public static ScanOptions builder() {
        return new ScanOptions();
    }

    public ScanOptions match(String pattern) {
        this.match = pattern;
        return this;
    }

    public ScanOptions count(long count) {
        this.count = count;
        return this;
    }

    /** Restricts {@code SCAN} to keys of one type (e.g. "string", "hash"). Ignored by HSCAN/SSCAN/ZSCAN. */
    public ScanOptions type(String type) {
        this.type = type;
        return this;
    }

    List<Object> toArgs() {
        List<Object> args = new ArrayList<>(6);
        if (match != null) {
            args.add("MATCH");
            args.add(match);
        }
        if (count != null) {
            args.add("COUNT");
            args.add(count);
        }
        if (type != null) {
            args.add("TYPE");
            args.add(type);
        }
        return args;
    }
}
