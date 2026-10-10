// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed wrappers for {@code FEATURE.*}: an ML feature store. Needs a server license that includes the featurestore feature. */
public final class FeatureStoreCommands {

    private final HotKVEngine engine;

    FeatureStoreCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** {@code schema} maps feature name to type: one of int64/double(float)/string/int_array/double_array(float_array). */
    public void create(String group, String description, Map<String, String> schema) {
        List<Object> args = new ArrayList<>(List.of("FEATURE.CREATE", group, description, "SCHEMA"));
        schema.forEach((name, type) -> {
            args.add(name);
            args.add(type);
        });
        Reply.ok(engine.execute(args.toArray()));
    }

    public void set(String group, String entity, Map<String, String> featureValues) {
        List<Object> args = new ArrayList<>(List.of("FEATURE.SET", group, entity));
        featureValues.forEach((k, v) -> {
            args.add(k);
            args.add(v);
        });
        Reply.ok(engine.execute(args.toArray()));
    }

    /** Omit {@code features} (null or empty) for every stored feature on the entity. */
    public FeatureValues get(String group, String entity, List<String> features) {
        Object[] args = (features == null || features.isEmpty())
                ? new Object[] {"FEATURE.GET", group, entity}
                : HotKV.concat(new Object[] {"FEATURE.GET", group, entity}, features.toArray());
        return decodeFeatureValues(engine.execute(args));
    }

    public List<FeatureValues> batch(String group, List<String> entities, List<String> features) {
        List<Object> args = new ArrayList<>();
        args.add("FEATURE.BATCH");
        args.add(group);
        args.addAll(entities);
        if (features != null && !features.isEmpty()) {
            args.add("FEATURES");
            args.addAll(features);
        }
        List<FeatureValues> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(engine.execute(args.toArray()))) {
            out.add(decodeFeatureValues(item));
        }
        return out;
    }

    private static FeatureValues decodeFeatureValues(RespValue v) {
        List<RespValue> items = Reply.array(v);
        if (items == null) {
            return null;
        }
        return new FeatureValues(Reply.flatStringMap(items.get(0)), Reply.integer(items.get(1)), Reply.integer(items.get(2)));
    }

    public Map<String, String> schema(String group) {
        Map<String, String> out = new LinkedHashMap<>();
        for (RespValue item : Reply.arrayOrEmpty(engine.execute("FEATURE.SCHEMA", group))) {
            List<RespValue> pair = Reply.array(item);
            out.put(Reply.string(pair.get(0)), Reply.string(pair.get(1)));
        }
        return out;
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("FEATURE.LIST"));
    }

    public void drop(String group) {
        Reply.ok(engine.execute("FEATURE.DROP", group));
    }

    public Stats stats(String group) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("FEATURE.STATS", group));
        return new Stats(Long.parseLong(f.get("entities")), Long.parseLong(f.get("totalReads")),
                Long.parseLong(f.get("totalWrites")), Long.parseLong(f.get("memoryBytes")));
    }

    public record FeatureValues(Map<String, String> values, long updatedAtMillis, long freshnessMillis) {
    }

    public record Stats(long entities, long totalReads, long totalWrites, long memoryBytes) {
    }
}
