// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional trailing keywords of {@code LLMSTATS.RECORD}: {@code [ERROR
 * 0|1] [MODEL <model>] [CACHEWRITE5M <n>] [CACHEWRITE1H <n>]}. When {@code
 * model} is non-empty, the server computes cost from its price table (see
 * {@link LlmStatsCommands#priceSet}) and ignores the positional {@code
 * costUsd} argument (pass {@code 0}); {@code cacheWrite5m}/{@code
 * cacheWrite1h} (Anthropic cache-write tokens by TTL tier) are only
 * meaningful together with {@code model}.
 */
public final class LlmStatsRecordOptions {

    private Boolean error;
    private String model;
    private Long cacheWrite5m;
    private Long cacheWrite1h;

    private LlmStatsRecordOptions() {
    }

    public static LlmStatsRecordOptions builder() {
        return new LlmStatsRecordOptions();
    }

    public LlmStatsRecordOptions error(boolean error) {
        this.error = error;
        return this;
    }

    public LlmStatsRecordOptions model(String model) {
        this.model = model;
        return this;
    }

    public LlmStatsRecordOptions cacheWrite5m(long tokens) {
        this.cacheWrite5m = tokens;
        return this;
    }

    public LlmStatsRecordOptions cacheWrite1h(long tokens) {
        this.cacheWrite1h = tokens;
        return this;
    }

    List<Object> toArgs() {
        List<Object> args = new ArrayList<>(8);
        if (error != null) {
            args.add("ERROR");
            args.add(error ? 1 : 0);
        }
        if (model != null) {
            args.add("MODEL");
            args.add(model);
        }
        if (cacheWrite5m != null) {
            args.add("CACHEWRITE5M");
            args.add(cacheWrite5m);
        }
        if (cacheWrite1h != null) {
            args.add("CACHEWRITE1H");
            args.add(cacheWrite1h);
        }
        return args;
    }
}
