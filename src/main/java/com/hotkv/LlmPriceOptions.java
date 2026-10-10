// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional cache price overrides for {@link LlmStatsCommands#priceSet}.
 * Null fields default (server side) to 0.1x (cache read), 1.25x
 * (5-minute cache write) and 2x (1-hour cache write) the input price.
 * Prices are USD per 1,000,000 tokens.
 */
public final class LlmPriceOptions {

    private Double cacheReadUsdPerMtok;
    private Double cacheWrite5mUsdPerMtok;
    private Double cacheWrite1hUsdPerMtok;

    private LlmPriceOptions() {
    }

    public static LlmPriceOptions builder() {
        return new LlmPriceOptions();
    }

    public LlmPriceOptions cacheReadUsdPerMtok(double usdPerMtok) {
        this.cacheReadUsdPerMtok = usdPerMtok;
        return this;
    }

    public LlmPriceOptions cacheWrite5mUsdPerMtok(double usdPerMtok) {
        this.cacheWrite5mUsdPerMtok = usdPerMtok;
        return this;
    }

    public LlmPriceOptions cacheWrite1hUsdPerMtok(double usdPerMtok) {
        this.cacheWrite1hUsdPerMtok = usdPerMtok;
        return this;
    }

    List<Object> toArgs() {
        List<Object> args = new ArrayList<>(6);
        if (cacheReadUsdPerMtok != null) {
            args.add("CACHEREAD");
            args.add(LlmStatsCommands.formatUsdPerMtok(cacheReadUsdPerMtok));
        }
        if (cacheWrite5mUsdPerMtok != null) {
            args.add("CACHEWRITE5M");
            args.add(LlmStatsCommands.formatUsdPerMtok(cacheWrite5mUsdPerMtok));
        }
        if (cacheWrite1hUsdPerMtok != null) {
            args.add("CACHEWRITE1H");
            args.add(LlmStatsCommands.formatUsdPerMtok(cacheWrite1hUsdPerMtok));
        }
        return args;
    }
}
