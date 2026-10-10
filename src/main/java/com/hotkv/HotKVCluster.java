// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.exceptions.AskRedirectException;
import com.hotkv.exceptions.HotKVConnectionException;
import com.hotkv.exceptions.HotKVException;
import com.hotkv.protocol.RespValue;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A cluster aware client: discovers the slot map with {@code CLUSTER
 * SLOTS} (falling back to {@code CLUSTER SHARDS}), routes each command by
 * its key's CRC16 hash slot (honoring {@code {hash tag}} substrings, see
 * {@link Crc16}), and follows {@code MOVED} (refreshing the slot map) and
 * {@code ASK} (sending {@code ASKING} first) redirects transparently, up
 * to a bounded number of hops.
 *
 * <p>One {@link HotKV} client, each with its own connection pool, is kept
 * per discovered node and reused across calls. For the full typed command
 * surface against a specific key's owning node, use {@link #clientFor(String)}
 * directly; this class itself only provides the generic {@link #command}
 * escape hatch, routed automatically.
 */
public final class HotKVCluster implements Closeable {

    private final HotKVOptions template;
    private final List<String> seeds;
    private final int maxRedirects;
    private final Map<String, HotKV> nodes = new ConcurrentHashMap<>();
    private volatile SlotMap slotMap = SlotMap.empty();

    public HotKVCluster(HotKVOptions template, List<String> seedAddresses) {
        this(template, seedAddresses, 5);
    }

    public HotKVCluster(HotKVOptions template, List<String> seedAddresses, int maxRedirects) {
        this.template = template;
        this.seeds = List.copyOf(seedAddresses);
        this.maxRedirects = maxRedirects;
        refresh();
    }

    /** Parses each {@code hotkv://host:port} seed URL; the first seed's non-address options become the shared template. */
    public static HotKVCluster create(String... seedUrls) {
        if (seedUrls.length == 0) {
            throw new IllegalArgumentException("hotkv: at least one seed URL is required");
        }
        HotKVOptions template = HotKVOptions.parse(seedUrls[0]).build();
        List<String> addresses = new ArrayList<>(seedUrls.length);
        for (String url : seedUrls) {
            HotKVOptions o = HotKVOptions.parse(url).build();
            addresses.add(o.host() + ":" + o.port());
        }
        return new HotKVCluster(template, addresses);
    }

    /** Re-discovers the slot map from the first reachable seed (or already known node). */
    public synchronized void refresh() {
        List<String> candidates = new ArrayList<>(seeds);
        for (String known : nodes.keySet()) {
            if (!candidates.contains(known)) {
                candidates.add(known);
            }
        }
        HotKVException lastError = null;
        for (String address : candidates) {
            try {
                HotKV c = clientForAddress(address);
                SlotMap map = SlotMap.parseClusterSlots(c.executeRaw("CLUSTER", "SLOTS"));
                if (map.isEmpty()) {
                    map = SlotMap.parseClusterShards(c.executeRaw("CLUSTER", "SHARDS"));
                }
                this.slotMap = map;
                return;
            } catch (HotKVException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError
                : new HotKVConnectionException("hotkv: could not reach any cluster seed node");
    }

    /** The current topology, one entry per slot range. */
    public List<ClusterNode> topology() {
        List<ClusterNode> out = new ArrayList<>();
        for (SlotMap.Range r : slotMap.ranges()) {
            out.add(new ClusterNode(r.start(), r.end(), r.master(), true));
        }
        return out;
    }

    /** The fully typed client for the node currently owning {@code key}'s slot. Its connections are pooled and reused. */
    public HotKV clientFor(String key) {
        int slot = Crc16.keySlot(key);
        String addr = slotMap.ownerOf(slot);
        if (addr == null) {
            throw new HotKVConnectionException("hotkv: no cluster node owns slot " + slot + "; call refresh()?");
        }
        return clientForAddress(addr);
    }

    HotKV clientForAddress(String address) {
        return nodes.computeIfAbsent(address, addr -> {
            int colon = addr.lastIndexOf(':');
            HotKVOptions o = template.toBuilder()
                    .host(addr.substring(0, colon))
                    .port(Integer.parseInt(addr.substring(colon + 1)))
                    .build();
            return new HotKV(o);
        });
    }

    /** Sends any command, routed by {@code key}'s slot, following MOVED/ASK redirects automatically. */
    public Object command(String key, Object... args) {
        return Reply.toJava(executeRaw(key, args));
    }

    RespValue executeRaw(String key, Object... args) {
        int slot = Crc16.keySlot(key);
        String addr = slotMap.ownerOf(slot);
        if (addr == null) {
            refresh();
            addr = slotMap.ownerOf(slot);
        }
        if (addr == null) {
            throw new HotKVConnectionException("hotkv: no cluster node owns slot " + slot);
        }

        for (int hop = 0; hop <= maxRedirects; hop++) {
            HotKV client = clientForAddress(addr);
            RespValue v = client.engine().executeRaw(args);
            if (!v.isError()) {
                return v;
            }
            String prefix = prefixOf(v.errorMessage());
            if ("MOVED".equals(prefix)) {
                refresh();
                String next = slotMap.ownerOf(slot);
                if (next == null) {
                    throw Errors.fromServerError(v.errorMessage());
                }
                addr = next;
                continue;
            }
            if ("ASK".equals(prefix)) {
                AskRedirectException ask = (AskRedirectException) Errors.fromServerError(v.errorMessage());
                String askAddr = ask.host() + ":" + ask.port();
                RespValue v2 = clientForAddress(askAddr).engine().executeAfter(new Object[] {"ASKING"}, args);
                if (!v2.isError()) {
                    return v2;
                }
                addr = askAddr;
                continue;
            }
            throw Errors.fromServerError(v.errorMessage());
        }
        throw new HotKVConnectionException("hotkv: too many cluster redirects for key '" + key + "' (limit " + maxRedirects + ")");
    }

    /** Reads {@code key} from a replica of its slot's owner via {@code READONLY} (falls back to the master if there is none). */
    public Object commandReadOnly(String key, Object... args) {
        int slot = Crc16.keySlot(key);
        List<String> replicas = slotMap.replicasOf(slot);
        String addr = replicas.isEmpty() ? slotMap.ownerOf(slot) : replicas.get(ThreadLocalRandom.current().nextInt(replicas.size()));
        if (addr == null) {
            throw new HotKVConnectionException("hotkv: no cluster node owns slot " + slot);
        }
        RespValue v = clientForAddress(addr).engine().executeAfter(new Object[] {"READONLY"}, args);
        if (v.isError()) {
            throw Errors.fromServerError(v.errorMessage());
        }
        return Reply.toJava(v);
    }

    private static String prefixOf(String message) {
        int sp = message.indexOf(' ');
        return (sp < 0 ? message : message.substring(0, sp)).toUpperCase(Locale.ROOT);
    }

    @Override
    public void close() {
        for (HotKV node : nodes.values()) {
            node.close();
        }
    }
}
