// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.Map;

/**
 * Typed wrappers for {@code OBSERVE}: a single command with a
 * subcommand argument (not a dotted family), admin. Needs a server license
 * that includes the observability feature.
 */
public final class ObserveCommands {

    private final HotKVEngine engine;

    ObserveCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    public Info info() {
        Map<String, String> f = Reply.flatStringMap(engine.execute("OBSERVE", "INFO"));
        return new Info(Long.parseLong(f.get("uptime_seconds")), Long.parseLong(f.get("connected_clients")),
                Long.parseLong(f.get("total_commands")), Long.parseLong(f.get("used_memory_bytes")),
                Long.parseLong(f.get("peak_memory_bytes")), Long.parseLong(f.get("total_connections")),
                f.get("enterprise_features"));
    }

    public Metrics metrics() {
        Map<String, String> f = Reply.flatStringMap(engine.execute("OBSERVE", "METRICS"));
        return new Metrics(Long.parseLong(f.get("uptime_seconds")), Long.parseLong(f.get("connected_clients")),
                Long.parseLong(f.get("total_commands")), Long.parseLong(f.get("total_connections")),
                Long.parseLong(f.get("used_memory_bytes")), Long.parseLong(f.get("peak_memory_bytes")),
                Long.parseLong(f.get("slowlog_length")), Long.parseLong(f.get("alerts_sent")),
                Long.parseLong(f.get("alerts_failed")), Long.parseLong(f.get("num_workers")));
    }

    public record Info(long uptimeSeconds, long connectedClients, long totalCommands, long usedMemoryBytes,
            long peakMemoryBytes, long totalConnections, String enterpriseFeatures) {
    }

    public record Metrics(long uptimeSeconds, long connectedClients, long totalCommands, long totalConnections,
            long usedMemoryBytes, long peakMemoryBytes, long slowlogLength, long alertsSent, long alertsFailed,
            long numWorkers) {
    }
}
