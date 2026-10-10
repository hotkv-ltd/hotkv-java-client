// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** Callback invoked for every event on a {@link PubSub} session. Implementations must not block for long. */
@FunctionalInterface
public interface PubSubListener {
    void onMessage(PubSubMessage message);
}
