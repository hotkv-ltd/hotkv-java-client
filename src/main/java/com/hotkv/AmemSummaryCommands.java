// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.util.List;

/** Typed wrappers for {@code AMEM.SUMMARY.*}: rolling per-agent summaries. Needs a server license that includes the amemsummary feature. */
public final class AmemSummaryCommands {

    private final HotKVEngine engine;

    AmemSummaryCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** Replaces the stored summary and returns the new version number. */
    public long set(String agentId, String summary) {
        return Reply.integer(engine.execute("AMEM.SUMMARY.SET", agentId, summary));
    }

    /** Appends to the stored summary text and returns the new version number. */
    public long append(String agentId, String extra) {
        return Reply.integer(engine.execute("AMEM.SUMMARY.APPEND", agentId, extra));
    }

    public AgentSummary get(String agentId) {
        List<RespValue> items = Reply.array(engine.execute("AMEM.SUMMARY.GET", agentId));
        if (items == null) {
            return null;
        }
        return new AgentSummary(Reply.string(items.get(0)), Reply.integer(items.get(1)), Reply.integer(items.get(2)));
    }

    public boolean drop(String agentId) {
        return Reply.bool(engine.execute("AMEM.SUMMARY.DROP", agentId));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("AMEM.SUMMARY.LIST"));
    }

    public record AgentSummary(String summary, long version, long updatedUnix) {
    }
}
