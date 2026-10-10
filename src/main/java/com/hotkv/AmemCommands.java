// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed wrappers for {@code AMEM.*}: AI agent memory, four
 * kinds in one namespace family plus a cross-memory search. Needs a server
 * license that includes the agent-memory feature. Summaries
 * ({@code AMEM.SUMMARY.*}) are a separate family, see {@link AmemSummaryCommands}.
 */
public final class AmemCommands {

    private final HotKVEngine engine;

    AmemCommands(HotKVEngine engine) {
        this.engine = engine;
    }

    // --- conversation memory ---

    public void convCreate(String sessionId, Integer windowSize, Long ttlSeconds) {
        List<Object> args = new ArrayList<>(List.of("AMEM.CONV.CREATE", sessionId));
        if (windowSize != null) {
            args.add("WINDOW");
            args.add(windowSize);
        }
        if (ttlSeconds != null) {
            args.add("TTL");
            args.add(ttlSeconds);
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    public void convAppend(String sessionId, String role, String content) {
        Reply.ok(engine.execute("AMEM.CONV.APPEND", sessionId, role, content));
    }

    public List<ConversationTurn> convGet(String sessionId) {
        return decodeTurns(engine.execute("AMEM.CONV.GET", sessionId));
    }

    public List<ConversationTurn> convWindow(String sessionId, long n) {
        return decodeTurns(engine.execute("AMEM.CONV.WINDOW", sessionId, n));
    }

    private static List<ConversationTurn> decodeTurns(RespValue v) {
        List<ConversationTurn> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(v)) {
            Map<String, String> f = Reply.flatStringMap(item);
            out.add(new ConversationTurn(f.get("role"), f.get("content")));
        }
        return out;
    }

    public boolean convClear(String sessionId) {
        return Reply.bool(engine.execute("AMEM.CONV.CLEAR", sessionId));
    }

    public boolean convDrop(String sessionId) {
        return Reply.bool(engine.execute("AMEM.CONV.DROP", sessionId));
    }

    // --- semantic memory (per agent vector store) ---

    public void semCreate(String agentId, int dimensions) {
        Reply.ok(engine.execute("AMEM.SEM.CREATE", agentId, dimensions));
    }

    public void semStore(String agentId, String key, String content, double[] vector, Long ttlSeconds) {
        List<Object> args = new ArrayList<>(List.of("AMEM.SEM.STORE", agentId, key, content));
        for (double v : vector) {
            args.add(Numbers.float32(v));
        }
        if (ttlSeconds != null) {
            args.add("TTL");
            args.add(ttlSeconds);
        }
        Reply.ok(engine.execute(args.toArray()));
    }

    public List<SemanticRecall> semRecall(String agentId, int k, double[] vector) {
        Object[] args = concatVector(new Object[] {"AMEM.SEM.RECALL", agentId, k}, vector);
        List<SemanticRecall> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(engine.execute(args))) {
            Map<String, String> f = Reply.flatStringMap(item);
            out.add(new SemanticRecall(f.get("key"), f.get("content"), Double.parseDouble(f.get("similarity"))));
        }
        return out;
    }

    public boolean semForget(String agentId, String key) {
        return Reply.bool(engine.execute("AMEM.SEM.FORGET", agentId, key));
    }

    public List<String> semList(String agentId) {
        return Reply.stringList(engine.execute("AMEM.SEM.LIST", agentId));
    }

    // --- working memory (per session scratch key/value) ---

    public void workSet(String sessionId, String key, String value, Long ttlSeconds) {
        Object[] args = ttlSeconds != null
                ? new Object[] {"AMEM.WORK.SET", sessionId, key, value, "TTL", ttlSeconds}
                : new Object[] {"AMEM.WORK.SET", sessionId, key, value};
        Reply.ok(engine.execute(args));
    }

    public String workGet(String sessionId, String key) {
        return Reply.string(engine.execute("AMEM.WORK.GET", sessionId, key));
    }

    public boolean workDel(String sessionId, String key) {
        return Reply.bool(engine.execute("AMEM.WORK.DEL", sessionId, key));
    }

    public boolean workClear(String sessionId) {
        return Reply.bool(engine.execute("AMEM.WORK.CLEAR", sessionId));
    }

    public List<String> workList(String sessionId) {
        return Reply.stringList(engine.execute("AMEM.WORK.LIST", sessionId));
    }

    // --- episodic memory (append only event log) ---

    public void episLog(String agentId, String eventType, String content, String metadataJson) {
        Object[] args = metadataJson != null
                ? new Object[] {"AMEM.EPIS.LOG", agentId, eventType, content, "METADATA", metadataJson}
                : new Object[] {"AMEM.EPIS.LOG", agentId, eventType, content};
        Reply.ok(engine.execute(args));
    }

    public List<EpisodicEvent> episQuery(String agentId, String type, Long from, Long to, Long limit) {
        List<Object> args = new ArrayList<>(List.of("AMEM.EPIS.QUERY", agentId));
        if (type != null) {
            args.add("TYPE");
            args.add(type);
        }
        if (from != null) {
            args.add("FROM");
            args.add(from);
        }
        if (to != null) {
            args.add("TO");
            args.add(to);
        }
        if (limit != null) {
            args.add("LIMIT");
            args.add(limit);
        }
        List<EpisodicEvent> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(engine.execute(args.toArray()))) {
            Map<String, String> f = Reply.flatStringMap(item);
            out.add(new EpisodicEvent(f.get("type"), f.get("content"), f.get("metadata"),
                    Long.parseLong(f.get("timestamp"))));
        }
        return out;
    }

    public boolean episClear(String agentId) {
        return Reply.bool(engine.execute("AMEM.EPIS.CLEAR", agentId));
    }

    public EpisStats episStats(String agentId) {
        Map<String, String> f = Reply.flatStringMap(engine.execute("AMEM.EPIS.STATS", agentId));
        return new EpisStats(Long.parseLong(f.get("total_events")), Long.parseLong(f.get("oldest_ts")),
                Long.parseLong(f.get("newest_ts")));
    }

    // --- cross memory search ---

    public List<MemorySearchResult> search(String agentId, int k, double[] vector) {
        Object[] args = concatVector(new Object[] {"AMEM.SEARCH", agentId, k}, vector);
        List<MemorySearchResult> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(engine.execute(args))) {
            Map<String, String> f = Reply.flatStringMap(item);
            out.add(new MemorySearchResult(f.get("source"), f.get("key"), f.get("content"),
                    Double.parseDouble(f.get("similarity"))));
        }
        return out;
    }

    private static Object[] concatVector(Object[] head, double[] vector) {
        Object[] out = new Object[head.length + vector.length];
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = 0; i < vector.length; i++) {
            out[head.length + i] = Numbers.float32(vector[i]);
        }
        return out;
    }

    public record ConversationTurn(String role, String content) {
    }

    public record SemanticRecall(String key, String content, double similarity) {
    }

    public record EpisodicEvent(String type, String content, String metadata, long timestampNanos) {
    }

    public record EpisStats(long totalEvents, long oldestTimestamp, long newestTimestamp) {
    }

    public record MemorySearchResult(String source, String key, String content, double similarity) {
    }
}
