// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** An immutable slot range to node address mapping, parsed from {@code CLUSTER SLOTS} or {@code CLUSTER SHARDS}. */
final class SlotMap {

    record Range(int start, int end, String master, List<String> replicas) {
    }

    private final List<Range> ranges;

    private SlotMap(List<Range> ranges) {
        List<Range> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(Range::start));
        this.ranges = List.copyOf(sorted);
    }

    static SlotMap empty() {
        return new SlotMap(List.of());
    }

    boolean isEmpty() {
        return ranges.isEmpty();
    }

    List<Range> ranges() {
        return ranges;
    }

    String ownerOf(int slot) {
        Range r = rangeOf(slot);
        return r == null ? null : r.master();
    }

    List<String> replicasOf(int slot) {
        Range r = rangeOf(slot);
        return r == null ? List.of() : r.replicas();
    }

    private Range rangeOf(int slot) {
        int lo = 0;
        int hi = ranges.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Range r = ranges.get(mid);
            if (slot < r.start()) {
                hi = mid - 1;
            } else if (slot > r.end()) {
                lo = mid + 1;
            } else {
                return r;
            }
        }
        return null;
    }

    /** Parses the standard {@code CLUSTER SLOTS} reply: an array of {@code [start, end, [ip, port, ...], [ip, port, ...]*]}. */
    static SlotMap parseClusterSlots(RespValue v) {
        List<Range> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(v)) {
            List<RespValue> f = Reply.array(item);
            if (f == null || f.size() < 3) {
                continue;
            }
            int start = (int) Reply.integer(f.get(0));
            int end = (int) Reply.integer(f.get(1));
            List<RespValue> masterInfo = Reply.array(f.get(2));
            String master = Reply.string(masterInfo.get(0)) + ":" + Reply.integer(masterInfo.get(1));
            List<String> replicas = new ArrayList<>();
            for (int i = 3; i < f.size(); i++) {
                List<RespValue> rep = Reply.array(f.get(i));
                if (rep != null && rep.size() >= 2) {
                    replicas.add(Reply.string(rep.get(0)) + ":" + Reply.integer(rep.get(1)));
                }
            }
            out.add(new Range(start, end, master, List.copyOf(replicas)));
        }
        return new SlotMap(out);
    }

    /** Best effort parse of {@code CLUSTER SHARDS}; returns an empty map (never throws) if the shape is unrecognized. */
    @SuppressWarnings("unchecked")
    static SlotMap parseClusterShards(RespValue v) {
        List<Range> out = new ArrayList<>();
        try {
            for (Object shardObj : (List<Object>) Reply.toJava(v)) {
                java.util.Map<String, Object> shard = (java.util.Map<String, Object>) shardObj;
                List<Object> slots = (List<Object>) shard.get("slots");
                List<Object> nodes = (List<Object>) shard.get("nodes");
                if (slots == null || nodes == null) {
                    continue;
                }
                String master = null;
                List<String> replicas = new ArrayList<>();
                for (Object nodeObj : nodes) {
                    java.util.Map<String, Object> node = (java.util.Map<String, Object>) nodeObj;
                    String role = String.valueOf(node.get("role"));
                    String addr = node.get("ip") + ":" + node.get("port");
                    if ("master".equalsIgnoreCase(role)) {
                        master = addr;
                    } else {
                        replicas.add(addr);
                    }
                }
                if (master == null) {
                    continue;
                }
                for (int i = 0; i + 1 < slots.size(); i += 2) {
                    int start = ((Number) slots.get(i)).intValue();
                    int end = ((Number) slots.get(i + 1)).intValue();
                    out.add(new Range(start, end, master, List.copyOf(replicas)));
                }
            }
        } catch (RuntimeException malformed) {
            return empty();
        }
        return new SlotMap(out);
    }
}
