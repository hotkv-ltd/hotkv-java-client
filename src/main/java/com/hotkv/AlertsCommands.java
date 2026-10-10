// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.Map;

/**
 * Typed wrappers for {@code ALERTS}: a single command with a
 * subcommand argument (not a dotted family), admin. Needs a server license
 * that includes the observability feature.
 */
public final class AlertsCommands {

    private final HotKVEngine engine;

    AlertsCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    public Info info() {
        Map<String, String> f = Reply.flatStringMap(engine.execute("ALERTS", "INFO"));
        return new Info(
                "1".equals(f.get("enabled")),
                "1".equals(f.get("flag_enabled")),
                f.get("webhook_url"),
                f.get("provider"),
                f.get("supported_providers"),
                f.get("min_severity"),
                Long.parseLong(f.get("alerts_sent")),
                Long.parseLong(f.get("alerts_failed")),
                Long.parseLong(f.get("alerts_dropped")),
                Long.parseLong(f.get("alerts_rate_limited")),
                Long.parseLong(f.get("alerts_deduped")),
                Long.parseLong(f.get("alerts_filtered")),
                Long.parseLong(f.get("alerts_queued")));
    }

    /** {@code severity} is one of "info"/"warning"/"critical", or null for the dispatcher's configured minimum. */
    public String test(String severity, String message) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add("ALERTS");
        args.add("TEST");
        if (severity != null) {
            args.add(severity);
        }
        if (message != null) {
            args.add(message);
        }
        return Reply.string(engine.execute(args.toArray()));
    }

    /** Empty string disables alerting. */
    public String setUrl(String url) {
        return Reply.string(engine.execute("ALERTS", "SETURL", url));
    }

    public String setSeverity(String severity) {
        return Reply.string(engine.execute("ALERTS", "SETSEVERITY", severity));
    }

    /** Toggles the master flag without clearing the webhook URL. */
    public String on() {
        return Reply.string(engine.execute("ALERTS", "ON"));
    }

    public String off() {
        return Reply.string(engine.execute("ALERTS", "OFF"));
    }

    public record Info(boolean enabled, boolean flagEnabled, String webhookUrl, String provider,
            String supportedProviders, String minSeverity, long alertsSent, long alertsFailed, long alertsDropped,
            long alertsRateLimited, long alertsDeduped, long alertsFiltered, long alertsQueued) {
    }
}
