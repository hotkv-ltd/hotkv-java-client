// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed wrappers for {@code VSIM.*}: a vector similarity index.
 * Indexing and search are core (no license needed); {@link #save},
 * {@link #load}, {@link #list} and {@link #drop} (persistence) need
 * a server license that includes the vsimpersist feature.
 */
public final class VsimCommands {

    private final HotKVEngine engine;

    VsimCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    public void createIndex(String name, int dims) {
        Reply.ok(engine.execute("VSIM.CREATEINDEX", name, dims));
    }

    /** {@code metric} is one of "cosine"/"l2"/"ip"; any of the tuning params may be null to use the server default. */
    public void createIndex(String name, int dims, String metric, Integer m, Integer efConstruction, Integer efSearch) {
        List<Object> args = new ArrayList<>(List.of("VSIM.CREATEINDEX", name, dims));
        if (metric != null) {
            args.add("METRIC");
            args.add(metric);
        }
        if (m != null) {
            args.add("M");
            args.add(m);
        }
        if (efConstruction != null) {
            args.add("EF_CONSTRUCTION");
            args.add(efConstruction);
        }
        if (efSearch != null) {
            args.add("EF_SEARCH");
            args.add(efSearch);
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    public void dropIndex(String name) {
        Reply.ok(engine.execute("VSIM.DROPINDEX", name));
    }

    /** Returns true if {@code key} was newly inserted, false if it replaced an existing vector. */
    public boolean set(String index, String key, double[] vector) {
        return Reply.bool(engine.execute(HotKV.concat(new Object[] {"VSIM.SET", index, key}, box(vector))));
    }

    public double[] get(String index, String key) {
        List<RespValue> items = Reply.array(engine.execute("VSIM.GET", index, key));
        return items == null ? null : toDoubles(items);
    }

    public boolean del(String index, String key) {
        return Reply.bool(engine.execute("VSIM.DEL", index, key));
    }

    public List<VsimMatch> search(String index, int k, double[] vector, List<String> filterTags, Integer ef) {
        List<Object> args = new ArrayList<>();
        args.add("VSIM.SEARCH");
        args.add(index);
        args.add(k);
        for (double v : vector) {
            args.add(Numbers.float32(v));
        }
        if (filterTags != null && !filterTags.isEmpty()) {
            args.add("FILTER");
            args.addAll(filterTags);
        }
        if (ef != null) {
            args.add("EF");
            args.add(ef);
        }
        List<RespValue> flat = Reply.arrayOrEmpty(engine.execute(args.toArray()));
        List<VsimMatch> out = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            out.add(new VsimMatch(Reply.string(flat.get(i)), Reply.doubleValue(flat.get(i + 1))));
        }
        return out;
    }

    public List<String> info() {
        return Reply.stringList(engine.execute("VSIM.INFO"));
    }

    public VsimIndexInfo info(String index) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("VSIM.INFO", index));
        if (f.isEmpty()) {
            return null;
        }
        return new VsimIndexInfo(
                f.get("name"),
                Long.parseLong(f.get("dimensions")),
                f.get("metric"),
                Long.parseLong(f.get("vectors")),
                Long.parseLong(f.get("M")),
                Long.parseLong(f.get("ef_construction")),
                Long.parseLong(f.get("ef_search")));
    }

    public void save(String snapshotName, byte[] blob) {
        Reply.integer(engine.execute("VSIM.SAVE", snapshotName, blob));
    }

    public byte[] load(String snapshotName) {
        return Reply.bytes(engine.execute("VSIM.LOAD", snapshotName));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("VSIM.LIST"));
    }

    public boolean drop(String snapshotName) {
        return Reply.bool(engine.execute("VSIM.DROP", snapshotName));
    }

    private static Object[] box(double[] vector) {
        Object[] out = new Object[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = Numbers.float32(vector[i]);
        }
        return out;
    }

    private static double[] toDoubles(List<RespValue> items) {
        double[] out = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            out[i] = Double.parseDouble(Reply.string(items.get(i)));
        }
        return out;
    }

    public record VsimMatch(String key, double distance) {
    }

    public record VsimIndexInfo(String name, long dimensions, String metric, long vectors, long m,
            long efConstruction, long efSearch) {
    }
}
