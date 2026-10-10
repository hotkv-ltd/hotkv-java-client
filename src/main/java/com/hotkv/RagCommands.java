// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed wrappers for {@code RAG.*}: a retrieval-augmented-generation pipeline. Needs a server license that includes the ragpipeline feature. */
public final class RagCommands {

    private final HotKVEngine engine;

    RagCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    /** {@code strategy} is one of "fixed" (or "fixed_size"), "sentence", "paragraph"; null fields use server defaults. */
    public void create(String name, int dimensions, String strategy, Integer chunkSize, Integer chunkOverlap) {
        List<Object> args = new ArrayList<>(List.of("RAG.CREATE", name, dimensions));
        if (strategy != null) {
            args.add("STRATEGY");
            args.add(strategy);
        }
        if (chunkSize != null) {
            args.add("CHUNK_SIZE");
            args.add(chunkSize);
        }
        if (chunkOverlap != null) {
            args.add("CHUNK_OVERLAP");
            args.add(chunkOverlap);
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    /**
     * Ingests {@code text}, chunked server side; {@code vectors}, if given,
     * must have one entry per chunk produced from {@code text}.
     */
    public long ingest(String pipeline, String docId, String text, Map<String, String> meta, List<double[]> vectors) {
        List<Object> args = new ArrayList<>(List.of("RAG.INGEST", pipeline, docId, text));
        if (meta != null && !meta.isEmpty()) {
            args.add("META");
            meta.forEach((k, v) -> {
                args.add(k);
                args.add(v);
            });
        }
        if (vectors != null && !vectors.isEmpty()) {
            args.add("VECTORS");
            for (double[] vec : vectors) {
                args.add(joinCsv(vec));
            }
        }
        return Reply.integer(engine.execute(args.toArray()));
    }

    private static String joinCsv(double[] vec) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Numbers.float32(vec[i]));
        }
        return sb.toString();
    }

    public List<RagResult> search(String pipeline, String queryText, Integer k, Double alpha, double[] vector, Map<String, String> filter) {
        List<Object> args = new ArrayList<>(List.of("RAG.SEARCH", pipeline, queryText));
        if (k != null) {
            args.add("K");
            args.add(k);
        }
        if (alpha != null) {
            args.add("ALPHA");
            args.add(Numbers.float32(alpha));
        }
        if (vector != null) {
            args.add("VECTOR");
            args.add(joinCsv(vector));
        }
        if (filter != null && !filter.isEmpty()) {
            args.add("FILTER");
            filter.forEach((fk, fv) -> {
                args.add(fk);
                args.add(fv);
            });
        }
        List<RagResult> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(engine.execute(args.toArray()))) {
            List<RespValue> f = Reply.array(item);
            Map<String, String> metaOut = new LinkedHashMap<>();
            for (int i = 6; i + 1 < f.size(); i += 2) {
                metaOut.put(Reply.string(f.get(i)), Reply.string(f.get(i + 1)));
            }
            out.add(new RagResult(Reply.string(f.get(0)), Reply.string(f.get(1)), Reply.string(f.get(2)),
                    Reply.doubleValue(f.get(3)), Reply.doubleValue(f.get(4)), Reply.doubleValue(f.get(5)), metaOut));
        }
        return out;
    }

    public Stats stats(String pipeline) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("RAG.STATS", pipeline));
        return new Stats(f.get("name"), Long.parseLong(f.get("dimensions")), Long.parseLong(f.get("totalDocs")),
                Long.parseLong(f.get("totalChunks")), Long.parseLong(f.get("totalIngested")),
                Long.parseLong(f.get("totalQueries")), Long.parseLong(f.get("chunkSize")), f.get("chunkStrategy"),
                Long.parseLong(f.get("chunkOverlap")));
    }

    public void drop(String pipeline) {
        Reply.ok(engine.execute("RAG.DROP", pipeline));
    }

    public List<String> list() {
        return Reply.stringList(engine.execute("RAG.LIST"));
    }

    public record RagResult(String chunkId, String docId, String text, double score, double vectorScore,
            double bm25Score, Map<String, String> metadata) {
    }

    public record Stats(String name, long dimensions, long totalDocs, long totalChunks, long totalIngested,
            long totalQueries, long chunkSize, String chunkStrategy, long chunkOverlap) {
    }
}
