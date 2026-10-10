// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import java.util.List;

/**
 * One original (uncached) LLM call's token usage: the shared
 * token usage group, accepted by {@link PcacheCommands#set}, {@link
 * ScacheCommands#set}, every keyed cache {@code set} ({@link
 * KeyedCacheCommands#set}) and {@link LlmBudgetCommands#consume}. {@code
 * model} is required; the token counts default to 0 and are omitted from
 * the wire when zero, but {@code MODEL} is always sent once a {@code
 * Usage} is built, so the server can price the call and, on a later cache
 * hit, attribute the tokens (and cost) that hit did not spend.
 *
 * <p>{@code input}/{@code output} are uncached prompt/output tokens,
 * {@code cacheRead} is provider prompt-cache hit tokens, and {@code
 * cacheWrite5m}/{@code cacheWrite1h} are Anthropic cache-write tokens by
 * TTL tier (leave both 0 for providers without a separate cache-write
 * cost, e.g. OpenAI).
 */
public final class Usage {

    private final String model;
    private long input;
    private long output;
    private long cacheRead;
    private long cacheWrite5m;
    private long cacheWrite1h;

    private Usage(String model) {
        this.model = model;
    }

    public static Usage of(String model) {
        return new Usage(model);
    }

    public Usage input(long tokens) {
        this.input = tokens;
        return this;
    }

    public Usage output(long tokens) {
        this.output = tokens;
        return this;
    }

    public Usage cacheRead(long tokens) {
        this.cacheRead = tokens;
        return this;
    }

    public Usage cacheWrite5m(long tokens) {
        this.cacheWrite5m = tokens;
        return this;
    }

    public Usage cacheWrite1h(long tokens) {
        this.cacheWrite1h = tokens;
        return this;
    }

    public String model() {
        return model;
    }

    /** Appends this usage's wire options (MODEL always first) to args. */
    void appendTo(List<Object> args) {
        args.add("MODEL");
        args.add(model);
        if (input > 0) {
            args.add("INPUT");
            args.add(input);
        }
        if (output > 0) {
            args.add("OUTPUT");
            args.add(output);
        }
        if (cacheRead > 0) {
            args.add("CACHEREAD");
            args.add(cacheRead);
        }
        if (cacheWrite5m > 0) {
            args.add("CACHEWRITE5M");
            args.add(cacheWrite5m);
        }
        if (cacheWrite1h > 0) {
            args.add("CACHEWRITE1H");
            args.add(cacheWrite1h);
        }
    }

    /** Appends usage's wire options to args when usage is non-null; a no-op otherwise. */
    static void appendIfPresent(List<Object> args, Usage usage) {
        if (usage != null) {
            usage.appendTo(args);
        }
    }
}
