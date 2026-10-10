// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed wrappers for {@code LLMSTATS.*}: usage accounting buckets. Needs a server license that includes the llmstats feature. */
public final class LlmStatsCommands {

    private final HotKVEngine engine;

    LlmStatsCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** Idempotent create. */
    public void bucket(String name) {
        Reply.ok(engine.execute("LLMSTATS.BUCKET", name));
    }

    /** {@code opts} may be null. */
    public void record(String bucket, long inputTokens, long outputTokens, long cachedTokens, double costUsd,
            long latencyMillis, LlmStatsRecordOptions opts) {
        List<Object> args = new ArrayList<>(List.of("LLMSTATS.RECORD", bucket, inputTokens, outputTokens,
                cachedTokens, Numbers.decimal(costUsd), latencyMillis));
        if (opts != null) {
            args.addAll(opts.toArgs());
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    public Snapshot get(String bucket) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("LLMSTATS.GET", bucket));
        return new Snapshot(
                Reply.longField(f, "requests"),
                Reply.longField(f, "errors"),
                Reply.longField(f, "input_tokens"),
                Reply.longField(f, "output_tokens"),
                Reply.longField(f, "cached_tokens"),
                Reply.doubleField(f, "cost_usd"),
                Reply.doubleField(f, "avg_latency_ms"),
                Reply.longField(f, "requests_1m"),
                Reply.longField(f, "tokens_1m"),
                Reply.doubleField(f, "cost_usd_1m"),
                Reply.longField(f, "cache_write_5m_tokens"),
                Reply.longField(f, "cache_write_1h_tokens"),
                Reply.longField(f, "unpriced_requests"));
    }

    public boolean reset(String bucket) {
        return Reply.bool(engine.execute("LLMSTATS.RESET", bucket));
    }

    public boolean drop(String bucket) {
        return Reply.bool(engine.execute("LLMSTATS.DROP", bucket));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("LLMSTATS.LIST"));
    }

    /**
     * Sets (or replaces) a custom price override for {@code model}, shared with {@code
     * LLMSTATS.RECORD}'s and {@link LlmBudgetCommands#consume}'s {@code MODEL} option.
     * Prices are USD per 1,000,000 tokens. {@code opts} may be null.
     */
    public void priceSet(String model, double inputUsdPerMtok, double outputUsdPerMtok, LlmPriceOptions opts) {
        List<Object> args = new ArrayList<>(List.of("LLMSTATS.PRICE", "SET", model,
                "INPUT", formatUsdPerMtok(inputUsdPerMtok), "OUTPUT", formatUsdPerMtok(outputUsdPerMtok)));
        if (opts != null) {
            args.addAll(opts.toArgs());
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    /** Unknown models reply with source "unpriced" and priced false rather than an error. */
    public LlmPrice priceGet(String model) {
        return LlmPrice.parse(Reply.flatStringMap(engine.execute("LLMSTATS.PRICE", "GET", model)));
    }

    /** Every known model's price sheet (built-in and custom). */
    public List<LlmPrice> priceList() {
        List<RespValue> items = Reply.arrayOrEmpty(engine.execute("LLMSTATS.PRICE", "LIST"));
        List<LlmPrice> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            out.add(LlmPrice.parse(Reply.flatStringMap(item)));
        }
        return out;
    }

    /** Returns true iff a custom override existed and was removed (the model then falls back to its built-in price, if any). */
    public boolean priceDel(String model) {
        return Reply.bool(engine.execute("LLMSTATS.PRICE", "DEL", model));
    }

    /**
     * Formats a USD-per-1,000,000-token price as the plain decimal the server's fixed point parser
     * accepts: never an exponent, at most 6 fractional digits (see {@link Numbers#usd}).
     */
    static String formatUsdPerMtok(double usdPerMtok) {
        return Numbers.usd(usdPerMtok);
    }

    public record Snapshot(long requests, long errors, long inputTokens, long outputTokens, long cachedTokens,
            double costUsd, double avgLatencyMs, long requests1m, long tokens1m, double costUsd1m,
            long cacheWrite5mTokens, long cacheWrite1hTokens, long unpricedRequests) {
    }

    /** One model's decoded price sheet, as returned by {@link #priceGet}/{@link #priceList}. {@code source} is "default", "custom" or "unpriced". */
    public record LlmPrice(String model, String source, boolean priced, boolean retired, double inputUsdPerMtok,
            double outputUsdPerMtok, double cacheReadUsdPerMtok, double cacheWrite5mUsdPerMtok,
            double cacheWrite1hUsdPerMtok) {

        static LlmPrice parse(Map<String, String> f) {
            return new LlmPrice(
                    Reply.stringField(f, "model"),
                    Reply.stringField(f, "source"),
                    Reply.boolField(f, "priced"),
                    Reply.boolField(f, "retired"),
                    Reply.doubleField(f, "input_usd_per_mtok"),
                    Reply.doubleField(f, "output_usd_per_mtok"),
                    Reply.doubleField(f, "cache_read_usd_per_mtok"),
                    Reply.doubleField(f, "cache_write_5m_usd_per_mtok"),
                    Reply.doubleField(f, "cache_write_1h_usd_per_mtok"));
        }
    }
}
