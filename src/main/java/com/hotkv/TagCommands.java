// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.List;

/** Typed wrappers for the {@code TAG.*} family: tagging keys and querying by tag. Core, no license needed. */
public final class TagCommands {

    private final HotKVEngine engine;

    TagCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** Adds tags to an existing key. Throws a server error ("no such key") if {@code key} does not exist. */
    public long set(String key, String... tags) {
        return Reply.integer(engine.execute(HotKV.concat(new Object[] {"TAG.SET", key}, tags)));
    }

    public long rem(String key, String... tags) {
        return Reply.integer(engine.execute(HotKV.concat(new Object[] {"TAG.REM", key}, tags)));
    }

    public List<String> get(String key) {
        return Reply.stringList(engine.execute("TAG.GET", key));
    }

    public List<String> members(String... tags) {
        return Reply.stringList(engine.execute(HotKV.prepend("TAG.MEMBERS", tags)));
    }

    /** Keys that carry every one of the given tags. */
    public List<String> inter(String... tags) {
        return Reply.stringList(engine.execute(HotKV.prepend("TAG.INTER", tags)));
    }

    public long count(String key) {
        return Reply.integer(engine.execute("TAG.COUNT", key));
    }

    public boolean exists(String key, String tag) {
        return Reply.bool(engine.execute("TAG.EXISTS", key, tag));
    }
}
