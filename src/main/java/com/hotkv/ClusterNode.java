// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

/** One slot range and the node address that currently owns it, as reported by {@code CLUSTER SLOTS}. */
public record ClusterNode(int startSlot, int endSlot, String address, boolean master) {
}
