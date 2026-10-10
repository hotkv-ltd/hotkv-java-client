// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed wrappers for {@code TS.*}: time series storage. Needs a server license that includes the timeseries feature. */
public final class TimeSeriesCommands {

    private final HotKVEngine engine;

    TimeSeriesCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** {@code RETENTION} is emitted before {@code LABELS}, as the server requires when both are given. */
    public void create(String key, Long retentionMillis, Map<String, String> labels) {
        List<Object> args = new ArrayList<>(List.of("TS.CREATE", key));
        if (retentionMillis != null) {
            args.add("RETENTION");
            args.add(retentionMillis);
        }
        if (labels != null && !labels.isEmpty()) {
            args.add("LABELS");
            labels.forEach((k, v) -> {
                args.add(k);
                args.add(v);
            });
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    /** Pass {@code timestampMillis} null for {@code *} (server assigned current time). Returns the timestamp used. */
    public long add(String key, Long timestampMillis, double value) {
        return Reply.integer(engine.execute("TS.ADD", key, timestampMillis == null ? "*" : timestampMillis, Numbers.decimal(value)));
    }

    public List<TsSample> range(String key, String fromMs, String toMs, Long count) {
        Object[] args = count != null
                ? new Object[] {"TS.RANGE", key, fromMs, toMs, "COUNT", count}
                : new Object[] {"TS.RANGE", key, fromMs, toMs};
        return decodeSamples(engine.execute(args));
    }

    /** {@code aggType} (case insensitive) is one of avg/min/max/sum/count. */
    public List<TsSample> agg(String key, String fromMs, String toMs, long bucketMillis, String aggType) {
        return decodeSamples(engine.execute("TS.AGG", key, fromMs, toMs, bucketMillis, aggType));
    }

    private static List<TsSample> decodeSamples(RespValue v) {
        List<TsSample> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(v)) {
            List<RespValue> pair = Reply.array(item);
            out.add(new TsSample(Reply.integer(pair.get(0)), Reply.doubleValue(pair.get(1))));
        }
        return out;
    }

    public TsInfo info(String key) {
        Map<String, Object> f = Reply.flatObjectMap(engine.execute("TS.INFO", key));
        Map<String, String> labels = new LinkedHashMap<>();
        Object rawLabels = f.get("labels");
        if (rawLabels instanceof List<?> pairs) {
            for (Object pair : pairs) {
                List<?> p = (List<?>) pair;
                labels.put(String.valueOf(p.get(0)), String.valueOf(p.get(1)));
            }
        }
        return new TsInfo((String) f.get("key"), (Long) f.get("totalSamples"), (Long) f.get("firstTimestamp"),
                (Long) f.get("lastTimestamp"), String.valueOf(f.get("lastValue")), (Long) f.get("retentionMs"),
                (Long) f.get("memoryBytes"), labels, (Long) f.get("rules"));
    }

    public void delete(String key) {
        Reply.ok(engine.execute("TS.DELETE", key));
    }

    public void retention(String key, long retentionMillis) {
        Reply.ok(engine.execute("TS.RETENTION", key, retentionMillis));
    }

    /** Adds a live downsample rule from {@code sourceKey} into {@code destKey} (both must already exist). */
    public void downsample(String sourceKey, String destKey, String aggType, long bucketMillis) {
        Reply.ok(engine.execute("TS.DOWNSAMPLE", sourceKey, destKey, aggType, bucketMillis));
    }

    public List<String> list(String labelKey, String labelValue) {
        Object[] args = labelKey != null
                ? new Object[] {"TS.LIST", "LABEL", labelKey, labelValue}
                : new Object[] {"TS.LIST"};
        return Reply.stringList(engine.execute(args));
    }

    public record TsSample(long timestampMillis, double value) {
    }

    public record TsInfo(String key, long totalSamples, long firstTimestamp, long lastTimestamp, String lastValue,
            long retentionMillis, long memoryBytes, Map<String, String> labels, long rules) {
    }
}
