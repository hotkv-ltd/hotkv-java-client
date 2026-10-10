// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/**
 * One event delivered on a {@link PubSub} session: a published message, or
 * a subscribe/unsubscribe acknowledgement. Unused fields for a given
 * {@link Kind} are null.
 */
public record PubSubMessage(Kind kind, String channel, String pattern, String payload, Long subscriptionCount) {

    public enum Kind {
        /** A message published on a subscribed channel; {@code channel} and {@code payload} are set. */
        MESSAGE,
        /** A message matching a subscribed pattern; {@code pattern}, {@code channel} and {@code payload} are set. */
        PMESSAGE,
        /** A message published on a subscribed shard channel; {@code channel} and {@code payload} are set. */
        SMESSAGE,
        SUBSCRIBE,
        UNSUBSCRIBE,
        PSUBSCRIBE,
        PUNSUBSCRIBE,
        SSUBSCRIBE,
        SUNSUBSCRIBE
    }
}
