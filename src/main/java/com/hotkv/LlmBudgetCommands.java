// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed wrappers for {@code LLMBUDGET.*}: token/cost budget caps. Needs a server license that includes the llmbudget feature. */
public final class LlmBudgetCommands {

    private final HotKVEngine engine;

    LlmBudgetCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    public void set(String name, long tokenLimit, double costLimitUsd, long windowSeconds) {
        Reply.ok(engine.execute("LLMBUDGET.SET", name, tokenLimit, Numbers.usd(costLimitUsd), windowSeconds));
    }

    /**
     * Returns true if allowed, false if this would exceed the token or cost cap for the
     * current window. {@code usage} is optional (null omits it): when given, the server
     * ignores {@code tokens}/{@code costUsd} (pass 0 and 0 for them) and instead sums
     * usage's token counts and prices the call from its model, exactly as {@link
     * LlmStatsCommands#record}'s {@code MODEL} option does.
     */
    public boolean consume(String name, long tokens, double costUsd, Usage usage) {
        List<Object> args = new ArrayList<>(List.of("LLMBUDGET.CONSUME", name, tokens, Numbers.decimal(costUsd)));
        Usage.appendIfPresent(args, usage);
        return Reply.bool(engine.execute(args.toArray()));
    }

    public Status status(String name) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("LLMBUDGET.STATUS", name));
        return new Status(
                Reply.longField(f, "tokens_used"),
                Reply.longField(f, "token_limit"),
                Reply.doubleField(f, "cost_used_usd"),
                Reply.doubleField(f, "cost_limit_usd"),
                Reply.longField(f, "window_seconds"),
                Reply.longField(f, "window_remaining"),
                Reply.longField(f, "requests_allowed"),
                Reply.longField(f, "requests_rejected"),
                Reply.longField(f, "unpriced_requests"));
    }

    public boolean reset(String name) {
        return Reply.bool(engine.execute("LLMBUDGET.RESET", name));
    }

    public boolean drop(String name) {
        return Reply.bool(engine.execute("LLMBUDGET.DROP", name));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("LLMBUDGET.LIST"));
    }

    public record Status(long tokensUsed, long tokenLimit, double costUsedUsd, double costLimitUsd,
            long windowSeconds, long windowRemaining, long requestsAllowed, long requestsRejected,
            long unpricedRequests) {
    }
}
