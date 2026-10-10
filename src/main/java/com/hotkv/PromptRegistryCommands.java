// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.util.List;

/**
 * Typed wrappers for {@code PROMPT.*}: a versioned prompt/template
 * registry. Not to be confused with {@link com.hotkv.ai.PromptCache}, the
 * LLM response cache. Needs a server license that includes the promptregistry feature.
 */
public final class PromptRegistryCommands {

    private final HotKVEngine engine;

    PromptRegistryCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** Stores a new version and returns its version number (versions start at 1). */
    public long put(String name, String content, String metaJson) {
        Object[] args = metaJson != null
                ? new Object[] {"PROMPT.PUT", name, content, "META", metaJson}
                : new Object[] {"PROMPT.PUT", name, content};
        return Reply.integer(engine.execute(args));
    }

    /** Omit {@code version} (pass null) for the latest version. */
    public Version get(String name, Long version) {
        RespValue v = version != null
                ? engine.execute("PROMPT.GET", name, version)
                : engine.execute("PROMPT.GET", name);
        return decode(v);
    }

    public Version latest(String name) {
        return decode(engine.execute("PROMPT.LATEST", name));
    }

    private static Version decode(RespValue v) {
        List<RespValue> items = Reply.array(v);
        if (items == null) {
            return null;
        }
        return new Version(Reply.integer(items.get(0)), Reply.string(items.get(1)), Reply.string(items.get(2)),
                Reply.integer(items.get(3)));
    }

    public void setActive(String name, long version) {
        Reply.ok(engine.execute("PROMPT.SETACTIVE", name, version));
    }

    public List<Long> versions(String name) {
        return Reply.longList(engine.execute("PROMPT.VERSIONS", name));
    }

    public boolean drop(String name) {
        return Reply.bool(engine.execute("PROMPT.DROP", name));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("PROMPT.LIST"));
    }

    public record Version(long version, String content, String metadata, long createdUnix) {
    }
}
