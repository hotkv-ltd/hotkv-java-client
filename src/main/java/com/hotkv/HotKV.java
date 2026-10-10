// Copyright (c) 2026 HotKV Ltd
// Licensed under the Apache License, Version 2.0. See LICENSE in the project root.

package com.hotkv;

import com.hotkv.protocol.Numbers;
import com.hotkv.protocol.RespValue;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * A thread safe, connection pooled client for a single HotKV server.
 *
 * <pre>{@code
 * try (HotKV client = HotKV.create("hotkv://localhost:6399")) {
 *     client.set("greeting", "hello");
 *     String value = client.get("greeting");
 * }
 * }</pre>
 *
 * <p>Every method here issues one command and waits for its reply on a
 * connection borrowed from the internal pool, which is safe to call from
 * many threads at once. For multiple commands in one round trip use
 * {@link #pipeline()}; for MULTI/EXEC semantics use {@link #transaction()};
 * for SUBSCRIBE/PSUBSCRIBE use {@link #pubSub()}. HotKV
 * exclusive and AI/enterprise commands are grouped under the accessor
 * methods near the end of this class, e.g. {@link #scache()}, {@link
 * #pcache()}, {@link #amem()}, {@link #rag()}.
 */
public final class HotKV implements Closeable {

    private final HotKVEngine engine;
    private final ExecutorService asyncExecutor;

    private final TagCommands tag;
    private final GcraCommands gcra;
    private final VsimCommands vsim;
    private final ScacheCommands scache;
    private final PcacheCommands pcache;
    private final KeyedCacheCommands toolCache;
    private final KeyedCacheCommands ncache;
    private final KeyedCacheCommands guard;
    private final KeyedCacheCommands rerank;
    private final LlmStatsCommands llmStats;
    private final LlmBudgetCommands llmBudget;
    private final PromptRegistryCommands promptRegistry;
    private final EmbedDedupCommands embedDedup;
    private final AmemCommands amem;
    private final AmemSummaryCommands amemSummary;
    private final RagCommands rag;
    private final RateLimitCommands rateLimit;
    private final TimeSeriesCommands timeSeries;
    private final FeatureStoreCommands featureStore;
    private final ObserveCommands observe;
    private final AlertsCommands alerts;

    public HotKV(HotKVOptions options) {
        this.engine = new HotKVEngine(Objects.requireNonNull(options, "options"));
        this.asyncExecutor = Executors.newCachedThreadPool(HotKV::newDaemonThread);

        this.tag = new TagCommands(engine);
        this.gcra = new GcraCommands(engine);
        this.vsim = new VsimCommands(engine);
        this.scache = new ScacheCommands(engine);
        this.pcache = new PcacheCommands(engine);
        this.toolCache = new KeyedCacheCommands(engine, "TOOL.CACHE");
        this.ncache = new KeyedCacheCommands(engine, "NCACHE");
        this.guard = new KeyedCacheCommands(engine, "GUARD");
        this.rerank = new KeyedCacheCommands(engine, "RERANK");
        this.llmStats = new LlmStatsCommands(engine);
        this.llmBudget = new LlmBudgetCommands(engine);
        this.promptRegistry = new PromptRegistryCommands(engine);
        this.embedDedup = new EmbedDedupCommands(engine);
        this.amem = new AmemCommands(engine);
        this.amemSummary = new AmemSummaryCommands(engine);
        this.rag = new RagCommands(engine);
        this.rateLimit = new RateLimitCommands(engine);
        this.timeSeries = new TimeSeriesCommands(engine);
        this.featureStore = new FeatureStoreCommands(engine);
        this.observe = new ObserveCommands(engine);
        this.alerts = new AlertsCommands(engine);
    }

    private static Thread newDaemonThread(Runnable r) {
        Thread t = new Thread(r, "hotkv-async");
        t.setDaemon(true);
        return t;
    }

    /** Parses a {@code hotkv://}/{@code hotkvs://} URL with default options; see {@link HotKVOptions#parse}. */
    public static HotKV create(String url) {
        return new HotKV(HotKVOptions.parse(url).build());
    }

    public static HotKV create(HotKVOptions options) {
        return new HotKV(options);
    }

    public HotKVOptions options() {
        return engine.options();
    }

    public PoolStats poolStats() {
        return engine.poolStats();
    }

    @Override
    public void close() {
        asyncExecutor.shutdown();
        engine.close();
    }

    HotKVEngine engine() {
        return engine;
    }

    <T> CompletableFuture<T> async(Supplier<T> op) {
        return CompletableFuture.supplyAsync(op, asyncExecutor);
    }

    // =====================================================================
    // Generic escape hatch: send any command, typed or not.
    // =====================================================================

    /** Sends any command and decodes the reply into plain Java types (String/Long/Double/Boolean/List/Map/null). */
    public Object execute(Object... args) {
        return Reply.toJava(engine.execute(args));
    }

    /** Alias for {@link #execute(Object...)}. */
    public Object command(Object... args) {
        return execute(args);
    }

    /** Like {@link #execute(Object...)} but returns the raw {@link RespValue}, preserving full RESP3 fidelity. A server error reply still throws the mapped exception. */
    public RespValue executeRaw(Object... args) {
        return engine.execute(args);
    }

    public CompletableFuture<Object> executeAsync(Object... args) {
        return async(() -> execute(args));
    }

    // =====================================================================
    // Pipelines, transactions, pub/sub, scripting helpers
    // =====================================================================

    /** A batch of commands sent in one round trip; see {@link Pipeline}. */
    public Pipeline pipeline() {
        return new Pipeline(engine);
    }

    /** A MULTI/EXEC transaction, optionally guarded by WATCH; see {@link Transaction}. */
    public Transaction transaction() {
        return new Transaction(engine);
    }

    /** A Pub/Sub session on its own dedicated connection; see {@link PubSub}. */
    public PubSub pubSub() {
        return new PubSub(engine);
    }

    public Object eval(String script, List<String> keys, List<String> args) {
        return execute(evalArgs("EVAL", script, keys, args));
    }

    public Object evalReadOnly(String script, List<String> keys, List<String> args) {
        return execute(evalArgs("EVAL_RO", script, keys, args));
    }

    /** Runs a cached script by SHA1, automatically falling back to {@code EVAL} on {@code NOSCRIPT}. */
    public Object evalSha(String sha1, List<String> keys, List<String> args, String scriptSourceForFallback) {
        try {
            return execute(evalArgs("EVALSHA", sha1, keys, args));
        } catch (com.hotkv.exceptions.HotKVServerException e) {
            if ("NOSCRIPT".equals(e.errorPrefix()) && scriptSourceForFallback != null) {
                return eval(scriptSourceForFallback, keys, args);
            }
            throw e;
        }
    }

    public Object evalSha(String sha1, List<String> keys, List<String> args) {
        return execute(evalArgs("EVALSHA", sha1, keys, args));
    }

    private static Object[] evalArgs(String cmd, String script, List<String> keys, List<String> args) {
        List<Object> out = new ArrayList<>(3 + keys.size() + args.size());
        out.add(cmd);
        out.add(script);
        out.add((long) keys.size());
        out.addAll(keys);
        out.addAll(args);
        return out.toArray();
    }

    /** Loads a script into the script cache and returns its SHA1, for later {@link #evalSha}. */
    public String scriptLoad(String script) {
        return Reply.string(engine.execute("SCRIPT", "LOAD", script));
    }

    public List<Boolean> scriptExists(String... sha1) {
        Object[] args = new Object[sha1.length + 2];
        args[0] = "SCRIPT";
        args[1] = "EXISTS";
        System.arraycopy(sha1, 0, args, 2, sha1.length);
        return Reply.list(engine.execute(args), Reply::bool);
    }

    public void scriptFlush() {
        Reply.ok(engine.execute("SCRIPT", "FLUSH"));
    }

    // =====================================================================
    // Keys / generic
    // =====================================================================

    public long del(String... keys) {
        return Reply.integer(engine.execute(prepend("DEL", keys)));
    }

    public long exists(String... keys) {
        return Reply.integer(engine.execute(prepend("EXISTS", keys)));
    }

    public boolean expire(String key, long seconds) {
        return Reply.bool(engine.execute("EXPIRE", key, seconds));
    }

    public boolean expire(String key, long seconds, ExpireCondition condition) {
        return Reply.bool(engine.execute("EXPIRE", key, seconds, condition.name()));
    }

    public boolean pexpire(String key, long millis) {
        return Reply.bool(engine.execute("PEXPIRE", key, millis));
    }

    public boolean pexpire(String key, long millis, ExpireCondition condition) {
        return Reply.bool(engine.execute("PEXPIRE", key, millis, condition.name()));
    }

    public boolean expireAt(String key, long unixSeconds) {
        return Reply.bool(engine.execute("EXPIREAT", key, unixSeconds));
    }

    public boolean expireAt(String key, long unixSeconds, ExpireCondition condition) {
        return Reply.bool(engine.execute("EXPIREAT", key, unixSeconds, condition.name()));
    }

    public boolean pexpireAt(String key, long unixMillis) {
        return Reply.bool(engine.execute("PEXPIREAT", key, unixMillis));
    }

    public boolean pexpireAt(String key, long unixMillis, ExpireCondition condition) {
        return Reply.bool(engine.execute("PEXPIREAT", key, unixMillis, condition.name()));
    }

    public boolean persist(String key) {
        return Reply.bool(engine.execute("PERSIST", key));
    }

    /** Remaining TTL in seconds; -2 if the key does not exist, -1 if it has no expiry. */
    public long ttl(String key) {
        return Reply.integer(engine.execute("TTL", key));
    }

    public long pttl(String key) {
        return Reply.integer(engine.execute("PTTL", key));
    }

    public long expireTime(String key) {
        return Reply.integer(engine.execute("EXPIRETIME", key));
    }

    public long pExpireTime(String key) {
        return Reply.integer(engine.execute("PEXPIRETIME", key));
    }

    public String type(String key) {
        return Reply.string(engine.execute("TYPE", key));
    }

    public List<String> keys(String pattern) {
        return Reply.stringList(engine.execute("KEYS", pattern));
    }

    public String randomKey() {
        return Reply.string(engine.execute("RANDOMKEY"));
    }

    public void rename(String key, String newKey) {
        Reply.ok(engine.execute("RENAME", key, newKey));
    }

    public boolean renameNx(String key, String newKey) {
        return Reply.bool(engine.execute("RENAMENX", key, newKey));
    }

    public boolean copy(String source, String destination) {
        return Reply.bool(engine.execute("COPY", source, destination));
    }

    public boolean copy(String source, String destination, boolean replace) {
        return replace
                ? Reply.bool(engine.execute("COPY", source, destination, "REPLACE"))
                : copy(source, destination);
    }

    public long touch(String... keys) {
        return Reply.integer(engine.execute(prepend("TOUCH", keys)));
    }

    public long unlink(String... keys) {
        return Reply.integer(engine.execute(prepend("UNLINK", keys)));
    }

    public byte[] dump(String key) {
        return Reply.bytes(engine.execute("DUMP", key));
    }

    public void restore(String key, long ttlMillis, byte[] serialized) {
        Reply.ok(engine.execute("RESTORE", key, ttlMillis, serialized));
    }

    public void restore(String key, long ttlMillis, byte[] serialized, boolean replace) {
        if (replace) {
            Reply.ok(engine.execute("RESTORE", key, ttlMillis, serialized, "REPLACE"));
        } else {
            restore(key, ttlMillis, serialized);
        }
    }

    public boolean move(String key, int database) {
        return Reply.bool(engine.execute("MOVE", key, database));
    }

    public ScanResult<String> scan(String cursor, ScanOptions options) {
        Object[] args = withOptions(new Object[] {"SCAN", cursor}, options.toArgs());
        return decodeScan(engine.execute(args));
    }

    public ScanResult<String> scan(String cursor) {
        return scan(cursor, ScanOptions.builder());
    }

    private static ScanResult<String> decodeScan(RespValue v) {
        List<RespValue> top = Reply.array(v);
        String next = Reply.string(top.get(0));
        return new ScanResult<>(next, Reply.stringList(top.get(1)));
    }

    // --- HotKV core extras that sit naturally alongside the generic key/expiry family ---

    /** Nanosecond resolution relative TTL; true if set, false if {@code key} does not exist. */
    public boolean nExpire(String key, long nanoseconds) {
        return Reply.bool(engine.execute("NEXPIRE", key, nanoseconds));
    }

    /** Remaining TTL in nanoseconds; -2 if the key does not exist, -1 if it has no expiry. */
    public long nTtl(String key) {
        return Reply.integer(engine.execute("NTTL", key));
    }

    public boolean nExpireAt(String key, long unixNanoseconds) {
        return Reply.bool(engine.execute("NEXPIREAT", key, unixNanoseconds));
    }

    /** Most frequently accessed keys, most frequent first. */
    public List<KeyCount> hotKeysTop() {
        return decodeHotKeys(engine.execute("HOTKEYS", "TOP"));
    }

    public List<KeyCount> hotKeysTop(long count) {
        return decodeHotKeys(engine.execute("HOTKEYS", "TOP", count));
    }

    private static List<KeyCount> decodeHotKeys(RespValue v) {
        List<RespValue> items = Reply.arrayOrEmpty(v);
        List<KeyCount> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            List<RespValue> pair = Reply.array(item);
            out.add(new KeyCount(Reply.string(pair.get(0)), Reply.integer(pair.get(1))));
        }
        return out;
    }

    public void hotKeysReset() {
        Reply.ok(engine.execute("HOTKEYS", "RESET"));
    }

    // =====================================================================
    // internal helpers
    // =====================================================================

    static Object[] prepend(String command, String... rest) {
        Object[] args = new Object[rest.length + 1];
        args[0] = command;
        System.arraycopy(rest, 0, args, 1, rest.length);
        return args;
    }

    static Object[] withOptions(Object[] head, List<Object> tail) {
        Object[] args = new Object[head.length + tail.size()];
        System.arraycopy(head, 0, args, 0, head.length);
        for (int i = 0; i < tail.size(); i++) {
            args[head.length + i] = tail.get(i);
        }
        return args;
    }

    static Object[] concat(Object[] head, Object[] tail) {
        Object[] args = new Object[head.length + tail.length];
        System.arraycopy(head, 0, args, 0, head.length);
        System.arraycopy(tail, 0, args, head.length, tail.length);
        return args;
    }

    // =====================================================================
    // Strings
    // =====================================================================

    public String get(String key) {
        return Reply.string(engine.execute("GET", key));
    }

    /** Sets {@code key} to {@code value} with no options; always succeeds. Use the overload for NX/XX/EX/etc. */
    public void set(String key, String value) {
        Reply.ok(engine.execute("SET", key, value));
    }

    /**
     * Sets {@code key} with options. Returns the previous value when
     * {@link SetOptions#get()} was requested; otherwise returns null on
     * success. When {@code NX}/{@code XX} rules out the set, returns null
     * (or the untouched previous value if {@code GET} was also requested).
     */
    public String set(String key, String value, SetOptions options) {
        RespValue v = engine.execute(concat(new Object[] {"SET", key, value}, options.toArgs().toArray()));
        return options.wantsGet() ? Reply.string(v) : null;
    }

    public String getDel(String key) {
        return Reply.string(engine.execute("GETDEL", key));
    }

    public String getEx(String key, GetExOptions options) {
        return Reply.string(engine.execute(concat(new Object[] {"GETEX", key}, options.toArgs().toArray())));
    }

    public String getEx(String key) {
        return Reply.string(engine.execute("GETEX", key));
    }

    public String getRange(String key, long start, long end) {
        return Reply.string(engine.execute("GETRANGE", key, start, end));
    }

    public String getSet(String key, String value) {
        return Reply.string(engine.execute("GETSET", key, value));
    }

    public long append(String key, String value) {
        return Reply.integer(engine.execute("APPEND", key, value));
    }

    public long strlen(String key) {
        return Reply.integer(engine.execute("STRLEN", key));
    }

    public long setRange(String key, long offset, String value) {
        return Reply.integer(engine.execute("SETRANGE", key, offset, value));
    }

    public long incr(String key) {
        return Reply.integer(engine.execute("INCR", key));
    }

    public long incrBy(String key, long amount) {
        return Reply.integer(engine.execute("INCRBY", key, amount));
    }

    public double incrByFloat(String key, double amount) {
        return Reply.doubleValue(engine.execute("INCRBYFLOAT", key, amount));
    }

    public long decr(String key) {
        return Reply.integer(engine.execute("DECR", key));
    }

    public long decrBy(String key, long amount) {
        return Reply.integer(engine.execute("DECRBY", key, amount));
    }

    public List<String> mget(String... keys) {
        return Reply.stringList(engine.execute(prepend("MGET", keys)));
    }

    public void mset(Map<String, String> keyValues) {
        Reply.ok(engine.execute(flattenKv("MSET", keyValues)));
    }

    public boolean msetNx(Map<String, String> keyValues) {
        return Reply.bool(engine.execute(flattenKv("MSETNX", keyValues)));
    }

    public void setEx(String key, long seconds, String value) {
        Reply.ok(engine.execute("SETEX", key, seconds, value));
    }

    public void pSetEx(String key, long millis, String value) {
        Reply.ok(engine.execute("PSETEX", key, millis, value));
    }

    public boolean setNx(String key, String value) {
        return Reply.bool(engine.execute("SETNX", key, value));
    }

    private static Object[] flattenKv(String command, Map<String, String> keyValues) {
        List<Object> out = new ArrayList<>(keyValues.size() * 2 + 1);
        out.add(command);
        keyValues.forEach((k, v) -> {
            out.add(k);
            out.add(v);
        });
        return out.toArray();
    }

    // =====================================================================
    // Lists
    // =====================================================================

    public long lpush(String key, String... values) {
        return Reply.integer(engine.execute(concat(new Object[] {"LPUSH", key}, values)));
    }

    public long rpush(String key, String... values) {
        return Reply.integer(engine.execute(concat(new Object[] {"RPUSH", key}, values)));
    }

    public long lpushX(String key, String... values) {
        return Reply.integer(engine.execute(concat(new Object[] {"LPUSHX", key}, values)));
    }

    public long rpushX(String key, String... values) {
        return Reply.integer(engine.execute(concat(new Object[] {"RPUSHX", key}, values)));
    }

    public String lpop(String key) {
        return Reply.string(engine.execute("LPOP", key));
    }

    public List<String> lpop(String key, long count) {
        return Reply.stringList(engine.execute("LPOP", key, count));
    }

    public String rpop(String key) {
        return Reply.string(engine.execute("RPOP", key));
    }

    public List<String> rpop(String key, long count) {
        return Reply.stringList(engine.execute("RPOP", key, count));
    }

    public List<String> lrange(String key, long start, long stop) {
        return Reply.stringList(engine.execute("LRANGE", key, start, stop));
    }

    public long llen(String key) {
        return Reply.integer(engine.execute("LLEN", key));
    }

    public String lindex(String key, long index) {
        return Reply.string(engine.execute("LINDEX", key, index));
    }

    public long linsertBefore(String key, String pivot, String value) {
        return Reply.integer(engine.execute("LINSERT", key, "BEFORE", pivot, value));
    }

    public long linsertAfter(String key, String pivot, String value) {
        return Reply.integer(engine.execute("LINSERT", key, "AFTER", pivot, value));
    }

    public long lrem(String key, long count, String value) {
        return Reply.integer(engine.execute("LREM", key, count, value));
    }

    public void lset(String key, long index, String value) {
        Reply.ok(engine.execute("LSET", key, index, value));
    }

    public void ltrim(String key, long start, long stop) {
        Reply.ok(engine.execute("LTRIM", key, start, stop));
    }

    public String rpopLPush(String source, String destination) {
        return Reply.string(engine.execute("RPOPLPUSH", source, destination));
    }

    public String lmove(String source, String destination, String fromSide, String toSide) {
        return Reply.string(engine.execute("LMOVE", source, destination, fromSide, toSide));
    }

    public Long lpos(String key, String element) {
        return Reply.integerOrNull(engine.execute("LPOS", key, element));
    }

    // =====================================================================
    // Sets
    // =====================================================================

    public long sadd(String key, String... members) {
        return Reply.integer(engine.execute(concat(new Object[] {"SADD", key}, members)));
    }

    public long srem(String key, String... members) {
        return Reply.integer(engine.execute(concat(new Object[] {"SREM", key}, members)));
    }

    public List<String> smembers(String key) {
        return Reply.stringList(engine.execute("SMEMBERS", key));
    }

    public boolean sisMember(String key, String member) {
        return Reply.bool(engine.execute("SISMEMBER", key, member));
    }

    public List<Boolean> smisMember(String key, String... members) {
        return Reply.list(engine.execute(concat(new Object[] {"SMISMEMBER", key}, members)), Reply::bool);
    }

    public long scard(String key) {
        return Reply.integer(engine.execute("SCARD", key));
    }

    public String spop(String key) {
        return Reply.string(engine.execute("SPOP", key));
    }

    public List<String> spop(String key, long count) {
        return Reply.stringList(engine.execute("SPOP", key, count));
    }

    public String srandMember(String key) {
        return Reply.string(engine.execute("SRANDMEMBER", key));
    }

    public List<String> srandMember(String key, long count) {
        return Reply.stringList(engine.execute("SRANDMEMBER", key, count));
    }

    public boolean smove(String source, String destination, String member) {
        return Reply.bool(engine.execute("SMOVE", source, destination, member));
    }

    public List<String> sdiff(String... keys) {
        return Reply.stringList(engine.execute(prepend("SDIFF", keys)));
    }

    public long sdiffStore(String destination, String... keys) {
        return Reply.integer(engine.execute(concat(new Object[] {"SDIFFSTORE", destination}, keys)));
    }

    public List<String> sinter(String... keys) {
        return Reply.stringList(engine.execute(prepend("SINTER", keys)));
    }

    public long sinterStore(String destination, String... keys) {
        return Reply.integer(engine.execute(concat(new Object[] {"SINTERSTORE", destination}, keys)));
    }

    public long sinterCard(long limit, String... keys) {
        Object[] args = concat(new Object[] {"SINTERCARD", (long) keys.length}, keys);
        return Reply.integer(engine.execute(concat(args, new Object[] {"LIMIT", limit})));
    }

    public List<String> sunion(String... keys) {
        return Reply.stringList(engine.execute(prepend("SUNION", keys)));
    }

    public long sunionStore(String destination, String... keys) {
        return Reply.integer(engine.execute(concat(new Object[] {"SUNIONSTORE", destination}, keys)));
    }

    public ScanResult<String> sscan(String key, String cursor, ScanOptions options) {
        return decodeScan(engine.execute(withOptions(new Object[] {"SSCAN", key, cursor}, options.toArgs())));
    }

    // =====================================================================
    // Hashes, including the HEXPIRE/HTTL field TTL family
    // =====================================================================

    public long hset(String key, String field, String value) {
        return Reply.integer(engine.execute("HSET", key, field, value));
    }

    public long hset(String key, Map<String, String> fields) {
        return Reply.integer(engine.execute(flattenKv("HSET", key, fields)));
    }

    public boolean hsetNx(String key, String field, String value) {
        return Reply.bool(engine.execute("HSETNX", key, field, value));
    }

    public String hget(String key, String field) {
        return Reply.string(engine.execute("HGET", key, field));
    }

    public List<String> hmget(String key, String... fields) {
        return Reply.stringList(engine.execute(concat(new Object[] {"HMGET", key}, fields)));
    }

    public Map<String, String> hgetAll(String key) {
        return Reply.stringMap(engine.execute("HGETALL", key));
    }

    public long hdel(String key, String... fields) {
        return Reply.integer(engine.execute(concat(new Object[] {"HDEL", key}, fields)));
    }

    public boolean hexists(String key, String field) {
        return Reply.bool(engine.execute("HEXISTS", key, field));
    }

    public long hincrBy(String key, String field, long amount) {
        return Reply.integer(engine.execute("HINCRBY", key, field, amount));
    }

    public double hincrByFloat(String key, String field, double amount) {
        return Reply.doubleValue(engine.execute("HINCRBYFLOAT", key, field, amount));
    }

    public List<String> hkeys(String key) {
        return Reply.stringList(engine.execute("HKEYS", key));
    }

    public List<String> hvals(String key) {
        return Reply.stringList(engine.execute("HVALS", key));
    }

    public long hlen(String key) {
        return Reply.integer(engine.execute("HLEN", key));
    }

    public long hstrlen(String key, String field) {
        return Reply.integer(engine.execute("HSTRLEN", key, field));
    }

    public String hrandField(String key) {
        return Reply.string(engine.execute("HRANDFIELD", key));
    }

    public List<String> hrandField(String key, long count) {
        return Reply.stringList(engine.execute("HRANDFIELD", key, count));
    }

    public List<FieldValue> hrandFieldWithValues(String key, long count) {
        List<Map.Entry<RespValue, RespValue>> pairs = Reply.pairs(engine.execute("HRANDFIELD", key, count, "WITHVALUES"));
        List<FieldValue> out = new ArrayList<>(pairs.size());
        for (Map.Entry<RespValue, RespValue> e : pairs) {
            out.add(new FieldValue(Reply.string(e.getKey()), Reply.string(e.getValue())));
        }
        return out;
    }

    public ScanResult<FieldValue> hscan(String key, String cursor, ScanOptions options) {
        RespValue v = engine.execute(withOptions(new Object[] {"HSCAN", key, cursor}, options.toArgs()));
        List<RespValue> top = Reply.array(v);
        String next = Reply.string(top.get(0));
        List<RespValue> flat = Reply.arrayOrEmpty(top.get(1));
        List<FieldValue> pairs = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            pairs.add(new FieldValue(Reply.string(flat.get(i)), Reply.string(flat.get(i + 1))));
        }
        return new ScanResult<>(next, pairs);
    }

    public String hgetDel(String key, String field) {
        List<RespValue> items = Reply.array(engine.execute("HGETDEL", key, "FIELDS", 1L, field));
        return items == null || items.isEmpty() ? null : Reply.string(items.get(0));
    }

    public String hgetEx(String key, String field) {
        List<RespValue> items = Reply.array(engine.execute("HGETEX", key, "FIELDS", 1L, field));
        return items == null || items.isEmpty() ? null : Reply.string(items.get(0));
    }

    public void hsetEx(String key, long ttlSeconds, String field, String value) {
        Reply.ok(engine.execute("HSETEX", key, "EX", ttlSeconds, "FIELDS", 1L, field, value));
    }

    /** Sets a relative TTL, in seconds, on one or more hash fields; one status code per field (see HEXPIRE). */
    public List<Long> hExpire(String key, long seconds, String... fields) {
        return hFieldTtlCommand("HEXPIRE", key, seconds, fields);
    }

    public List<Long> hpExpire(String key, long millis, String... fields) {
        return hFieldTtlCommand("HPEXPIRE", key, millis, fields);
    }

    public List<Long> hExpireAt(String key, long unixSeconds, String... fields) {
        return hFieldTtlCommand("HEXPIREAT", key, unixSeconds, fields);
    }

    public List<Long> hpExpireAt(String key, long unixMillis, String... fields) {
        return hFieldTtlCommand("HPEXPIREAT", key, unixMillis, fields);
    }

    private List<Long> hFieldTtlCommand(String command, String key, long amount, String... fields) {
        Object[] args = concat(new Object[] {command, key, amount, "FIELDS", (long) fields.length}, fields);
        return Reply.longList(engine.execute(args));
    }

    public List<Long> hPersist(String key, String... fields) {
        return hFieldsCommand("HPERSIST", key, fields);
    }

    public List<Long> hTtl(String key, String... fields) {
        return hFieldsCommand("HTTL", key, fields);
    }

    public List<Long> hpTtl(String key, String... fields) {
        return hFieldsCommand("HPTTL", key, fields);
    }

    public List<Long> hExpireTime(String key, String... fields) {
        return hFieldsCommand("HEXPIRETIME", key, fields);
    }

    public List<Long> hpExpireTime(String key, String... fields) {
        return hFieldsCommand("HPEXPIRETIME", key, fields);
    }

    private List<Long> hFieldsCommand(String command, String key, String... fields) {
        Object[] args = concat(new Object[] {command, key, "FIELDS", (long) fields.length}, fields);
        return Reply.longList(engine.execute(args));
    }

    private static Object[] flattenKv(String command, String key, Map<String, String> fields) {
        List<Object> out = new ArrayList<>(fields.size() * 2 + 2);
        out.add(command);
        out.add(key);
        fields.forEach((k, v) -> {
            out.add(k);
            out.add(v);
        });
        return out.toArray();
    }

    // =====================================================================
    // Sorted sets
    // =====================================================================

    public long zadd(String key, double score, String member) {
        return Reply.integer(engine.execute("ZADD", key, score, member));
    }

    public long zadd(String key, Map<String, Double> scoresByMember) {
        return Reply.integer(engine.execute(flattenScores("ZADD", key, List.of(), scoresByMember)));
    }

    public long zadd(String key, ZAddOptions options, Map<String, Double> scoresByMember) {
        return Reply.integer(engine.execute(flattenScores("ZADD", key, options.toArgs(), scoresByMember)));
    }

    /** ZADD in INCR mode for a single member: adds to its current score, returns the new score, or null if NX/XX blocked it. */
    public Double zaddIncr(String key, ZAddOptions options, double score, String member) {
        List<Object> opts = new ArrayList<>(options.toArgs());
        if (!options.isIncr()) {
            opts.add("INCR");
        }
        Object[] args = concat(new Object[] {"ZADD", key}, opts.toArray());
        args = concat(args, new Object[] {score, member});
        return Reply.doubleOrNull(engine.execute(args));
    }

    private static Object[] flattenScores(String command, String key, List<Object> options, Map<String, Double> scoresByMember) {
        List<Object> out = new ArrayList<>(options.size() + scoresByMember.size() * 2 + 2);
        out.add(command);
        out.add(key);
        out.addAll(options);
        scoresByMember.forEach((member, score) -> {
            out.add(score);
            out.add(member);
        });
        return out.toArray();
    }

    public double zincrBy(String key, double increment, String member) {
        return Reply.doubleValue(engine.execute("ZINCRBY", key, increment, member));
    }

    public Double zscore(String key, String member) {
        return Reply.doubleOrNull(engine.execute("ZSCORE", key, member));
    }

    public List<Double> zmScore(String key, String... members) {
        return Reply.list(engine.execute(concat(new Object[] {"ZMSCORE", key}, members)), Reply::doubleOrNull);
    }

    public long zcard(String key) {
        return Reply.integer(engine.execute("ZCARD", key));
    }

    public long zcount(String key, String min, String max) {
        return Reply.integer(engine.execute("ZCOUNT", key, min, max));
    }

    public long zlexCount(String key, String min, String max) {
        return Reply.integer(engine.execute("ZLEXCOUNT", key, min, max));
    }

    public List<String> zrange(String key, long start, long stop) {
        return Reply.stringList(engine.execute("ZRANGE", key, start, stop));
    }

    public List<ScoredMember> zrangeWithScores(String key, long start, long stop) {
        return decodeScoredMembers(engine.execute("ZRANGE", key, start, stop, "WITHSCORES"));
    }

    /** Unified ZRANGE with BYSCORE and optional LIMIT/REV, for score range queries. */
    public List<String> zrangeByScore(String key, String min, String max, boolean rev, Long limitOffset, Long limitCount) {
        return Reply.stringList(engine.execute(zrangeArgs(key, min, max, "BYSCORE", rev, limitOffset, limitCount, false)));
    }

    public List<String> zrangeByScore(String key, String min, String max) {
        return zrangeByScore(key, min, max, false, null, null);
    }

    public List<ScoredMember> zrangeByScoreWithScores(String key, String min, String max, boolean rev, Long limitOffset, Long limitCount) {
        return decodeScoredMembers(engine.execute(zrangeArgs(key, min, max, "BYSCORE", rev, limitOffset, limitCount, true)));
    }

    /** Unified ZRANGE with BYLEX and optional LIMIT/REV, for members with equal scores ordered lexically. */
    public List<String> zrangeByLex(String key, String min, String max, boolean rev, Long limitOffset, Long limitCount) {
        return Reply.stringList(engine.execute(zrangeArgs(key, min, max, "BYLEX", rev, limitOffset, limitCount, false)));
    }

    public List<String> zrangeByLex(String key, String min, String max) {
        return zrangeByLex(key, min, max, false, null, null);
    }

    private static Object[] zrangeArgs(String key, String min, String max, String by, boolean rev,
            Long limitOffset, Long limitCount, boolean withScores) {
        List<Object> args = new ArrayList<>(10);
        args.add("ZRANGE");
        args.add(key);
        // ZRANGE takes its bounds as "start stop", not "min max": in REV order the higher
        // bound comes first on the wire, regardless of the order callers pass min/max in.
        args.add(rev ? max : min);
        args.add(rev ? min : max);
        args.add(by);
        if (rev) {
            args.add("REV");
        }
        if (limitOffset != null) {
            args.add("LIMIT");
            args.add(limitOffset);
            args.add(limitCount);
        }
        if (withScores) {
            args.add("WITHSCORES");
        }
        return args.toArray();
    }

    /** Store the result of a unified ZRANGE query (BYSCORE/BYLEX, REV, LIMIT) into {@code destination}. */
    public long zrangeStore(String destination, String key, String min, String max, String by, boolean rev,
            Long limitOffset, Long limitCount) {
        List<Object> args = new ArrayList<>(12);
        args.add("ZRANGESTORE");
        args.add(destination);
        args.add(key);
        args.add(min);
        args.add(max);
        if (by != null) {
            args.add(by);
        }
        if (rev) {
            args.add("REV");
        }
        if (limitOffset != null) {
            args.add("LIMIT");
            args.add(limitOffset);
            args.add(limitCount);
        }
        return Reply.integer(engine.execute(args.toArray()));
    }

    public List<String> zrevRange(String key, long start, long stop) {
        return Reply.stringList(engine.execute("ZREVRANGE", key, start, stop));
    }

    public List<ScoredMember> zrevRangeWithScores(String key, long start, long stop) {
        return decodeScoredMembers(engine.execute("ZREVRANGE", key, start, stop, "WITHSCORES"));
    }

    public List<String> zrevRangeByScore(String key, String max, String min) {
        return Reply.stringList(engine.execute("ZREVRANGEBYSCORE", key, max, min));
    }

    public List<String> zrevRangeByLex(String key, String max, String min) {
        return Reply.stringList(engine.execute("ZREVRANGEBYLEX", key, max, min));
    }

    private static List<ScoredMember> decodeScoredMembers(RespValue v) {
        List<Map.Entry<RespValue, RespValue>> pairs = Reply.pairs(v);
        List<ScoredMember> out = new ArrayList<>(pairs.size());
        for (Map.Entry<RespValue, RespValue> e : pairs) {
            out.add(new ScoredMember(Reply.string(e.getKey()), Reply.doubleValue(e.getValue())));
        }
        return out;
    }

    public Long zrank(String key, String member) {
        return Reply.integerOrNull(engine.execute("ZRANK", key, member));
    }

    public RankScore zrankWithScore(String key, String member) {
        return decodeRankScore(engine.execute("ZRANK", key, member, "WITHSCORE"));
    }

    public Long zrevRank(String key, String member) {
        return Reply.integerOrNull(engine.execute("ZREVRANK", key, member));
    }

    public RankScore zrevRankWithScore(String key, String member) {
        return decodeRankScore(engine.execute("ZREVRANK", key, member, "WITHSCORE"));
    }

    private static RankScore decodeRankScore(RespValue v) {
        List<RespValue> items = Reply.array(v);
        return items == null ? null : new RankScore(Reply.integer(items.get(0)), Reply.doubleValue(items.get(1)));
    }

    public long zrem(String key, String... members) {
        return Reply.integer(engine.execute(concat(new Object[] {"ZREM", key}, members)));
    }

    public long zremRangeByRank(String key, long start, long stop) {
        return Reply.integer(engine.execute("ZREMRANGEBYRANK", key, start, stop));
    }

    public long zremRangeByScore(String key, String min, String max) {
        return Reply.integer(engine.execute("ZREMRANGEBYSCORE", key, min, max));
    }

    public long zremRangeByLex(String key, String min, String max) {
        return Reply.integer(engine.execute("ZREMRANGEBYLEX", key, min, max));
    }

    public ScoredMember zpopMin(String key) {
        List<ScoredMember> members = decodeScoredMembers(engine.execute("ZPOPMIN", key));
        return members.isEmpty() ? null : members.get(0);
    }

    public List<ScoredMember> zpopMin(String key, long count) {
        return decodeScoredMembers(engine.execute("ZPOPMIN", key, count));
    }

    public ScoredMember zpopMax(String key) {
        List<ScoredMember> members = decodeScoredMembers(engine.execute("ZPOPMAX", key));
        return members.isEmpty() ? null : members.get(0);
    }

    public List<ScoredMember> zpopMax(String key, long count) {
        return decodeScoredMembers(engine.execute("ZPOPMAX", key, count));
    }

    public ZPopResult zmPop(boolean max, long count, String... keys) {
        Object[] head = concat(new Object[] {"ZMPOP", (long) keys.length}, keys);
        Object[] args = concat(head, new Object[] {max ? "MAX" : "MIN", "COUNT", count});
        List<RespValue> top = Reply.array(engine.execute(args));
        if (top == null) {
            return null;
        }
        return new ZPopResult(Reply.string(top.get(0)), decodeScoredMembers(top.get(1)));
    }

    public String zrandMember(String key) {
        return Reply.string(engine.execute("ZRANDMEMBER", key));
    }

    public List<String> zrandMember(String key, long count) {
        return Reply.stringList(engine.execute("ZRANDMEMBER", key, count));
    }

    public List<ScoredMember> zrandMemberWithScores(String key, long count) {
        return decodeScoredMembers(engine.execute("ZRANDMEMBER", key, count, "WITHSCORES"));
    }

    public List<String> zdiff(String... keys) {
        Object[] args = concat(new Object[] {"ZDIFF", (long) keys.length}, keys);
        return Reply.stringList(engine.execute(args));
    }

    public List<ScoredMember> zdiffWithScores(String... keys) {
        Object[] args = concat(new Object[] {"ZDIFF", (long) keys.length}, keys);
        return decodeScoredMembers(engine.execute(concat(args, new Object[] {"WITHSCORES"})));
    }

    public long zdiffStore(String destination, String... keys) {
        Object[] args = concat(new Object[] {"ZDIFFSTORE", destination, (long) keys.length}, keys);
        return Reply.integer(engine.execute(args));
    }

    public List<String> zinter(String... keys) {
        Object[] args = concat(new Object[] {"ZINTER", (long) keys.length}, keys);
        return Reply.stringList(engine.execute(args));
    }

    public long zinterCard(long limit, String... keys) {
        Object[] args = concat(new Object[] {"ZINTERCARD", (long) keys.length}, keys);
        return Reply.integer(engine.execute(concat(args, new Object[] {"LIMIT", limit})));
    }

    public long zinterStore(String destination, List<String> keys, double[] weights, String aggregate) {
        return zSetOpStore("ZINTERSTORE", destination, keys, weights, aggregate);
    }

    public long zinterStore(String destination, String... keys) {
        return zinterStore(destination, List.of(keys), null, null);
    }

    public List<String> zunion(String... keys) {
        Object[] args = concat(new Object[] {"ZUNION", (long) keys.length}, keys);
        return Reply.stringList(engine.execute(args));
    }

    public long zunionStore(String destination, List<String> keys, double[] weights, String aggregate) {
        return zSetOpStore("ZUNIONSTORE", destination, keys, weights, aggregate);
    }

    public long zunionStore(String destination, String... keys) {
        return zunionStore(destination, List.of(keys), null, null);
    }

    private long zSetOpStore(String command, String destination, List<String> keys, double[] weights, String aggregate) {
        List<Object> args = new ArrayList<>(keys.size() + 8);
        args.add(command);
        args.add(destination);
        args.add((long) keys.size());
        args.addAll(keys);
        if (weights != null) {
            args.add("WEIGHTS");
            for (double w : weights) {
                args.add(w);
            }
        }
        if (aggregate != null) {
            args.add("AGGREGATE");
            args.add(aggregate);
        }
        return Reply.integer(engine.execute(args.toArray()));
    }

    public ScanResult<ScoredMember> zscan(String key, String cursor, ScanOptions options) {
        RespValue v = engine.execute(withOptions(new Object[] {"ZSCAN", key, cursor}, options.toArgs()));
        List<RespValue> top = Reply.array(v);
        return new ScanResult<>(Reply.string(top.get(0)), decodeScoredMembers(top.get(1)));
    }

    // =====================================================================
    // Streams
    // =====================================================================

    public String xadd(String key, String id, Map<String, String> fields) {
        return Reply.string(engine.execute(flattenKv2("XADD", key, id, fields)));
    }

    public String xadd(String key, Map<String, String> fields) {
        return xadd(key, "*", fields);
    }

    private static Object[] flattenKv2(String command, String key, String extra, Map<String, String> fields) {
        List<Object> out = new ArrayList<>(fields.size() * 2 + 3);
        out.add(command);
        out.add(key);
        out.add(extra);
        fields.forEach((k, v) -> {
            out.add(k);
            out.add(v);
        });
        return out.toArray();
    }

    public long xlen(String key) {
        return Reply.integer(engine.execute("XLEN", key));
    }

    public long xdel(String key, String... ids) {
        return Reply.integer(engine.execute(concat(new Object[] {"XDEL", key}, ids)));
    }

    public List<StreamEntry> xrange(String key, String start, String end) {
        return decodeStreamEntries(engine.execute("XRANGE", key, start, end));
    }

    public List<StreamEntry> xrange(String key, String start, String end, long count) {
        return decodeStreamEntries(engine.execute("XRANGE", key, start, end, "COUNT", count));
    }

    public List<StreamEntry> xrevRange(String key, String end, String start) {
        return decodeStreamEntries(engine.execute("XREVRANGE", key, end, start));
    }

    private static List<StreamEntry> decodeStreamEntries(RespValue v) {
        List<RespValue> items = Reply.arrayOrEmpty(v);
        List<StreamEntry> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            List<RespValue> pair = Reply.array(item);
            out.add(new StreamEntry(Reply.string(pair.get(0)), Reply.flatStringMap(pair.get(1))));
        }
        return out;
    }

    /** {@code XREAD [COUNT n] [BLOCK ms] STREAMS key [key ...] id [id ...]}; empty map on a BLOCK timeout. */
    public Map<String, List<StreamEntry>> xread(Map<String, String> streamsToIds, Long count, Long blockMillis) {
        return decodeXread(engine.execute(xreadArgs("XREAD", null, null, streamsToIds, count, blockMillis, false)));
    }

    public Map<String, List<StreamEntry>> xreadGroup(String group, String consumer, Map<String, String> streamsToIds,
            Long count, Long blockMillis, boolean noAck) {
        return decodeXread(engine.execute(xreadArgs("XREADGROUP", group, consumer, streamsToIds, count, blockMillis, noAck)));
    }

    private static Object[] xreadArgs(String command, String group, String consumer, Map<String, String> streamsToIds,
            Long count, Long blockMillis, boolean noAck) {
        List<Object> args = new ArrayList<>(streamsToIds.size() * 2 + 8);
        args.add(command);
        if (group != null) {
            args.add("GROUP");
            args.add(group);
            args.add(consumer);
        }
        if (count != null) {
            args.add("COUNT");
            args.add(count);
        }
        if (blockMillis != null) {
            args.add("BLOCK");
            args.add(blockMillis);
        }
        if (noAck) {
            args.add("NOACK");
        }
        args.add("STREAMS");
        args.addAll(streamsToIds.keySet());
        args.addAll(streamsToIds.values());
        return args.toArray();
    }

    private static Map<String, List<StreamEntry>> decodeXread(RespValue v) {
        RespValue u = Reply.unwrap(v);
        if (u.isNull()) {
            return Map.of();
        }
        Map<String, List<StreamEntry>> out = new LinkedHashMap<>();
        if (u instanceof RespValue.RespMap m) {
            for (Map.Entry<RespValue, RespValue> e : m.entries()) {
                out.put(Reply.string(e.getKey()), decodeStreamEntries(e.getValue()));
            }
            return out;
        }
        for (RespValue s : Reply.arrayOrEmpty(u)) {
            List<RespValue> pair = Reply.array(s);
            out.put(Reply.string(pair.get(0)), decodeStreamEntries(pair.get(1)));
        }
        return out;
    }

    public long xack(String key, String group, String... ids) {
        return Reply.integer(engine.execute(concat(new Object[] {"XACK", key, group}, ids)));
    }

    public void xgroupCreate(String key, String group, String id, boolean mkStream) {
        Object[] args = mkStream
                ? new Object[] {"XGROUP", "CREATE", key, group, id, "MKSTREAM"}
                : new Object[] {"XGROUP", "CREATE", key, group, id};
        Reply.ok(engine.execute(args));
    }

    public void xgroupSetId(String key, String group, String id) {
        Reply.ok(engine.execute("XGROUP", "SETID", key, group, id));
    }

    public boolean xgroupDestroy(String key, String group) {
        return Reply.bool(engine.execute("XGROUP", "DESTROY", key, group));
    }

    public void xgroupCreateConsumer(String key, String group, String consumer) {
        Reply.integer(engine.execute("XGROUP", "CREATECONSUMER", key, group, consumer));
    }

    public long xgroupDelConsumer(String key, String group, String consumer) {
        return Reply.integer(engine.execute("XGROUP", "DELCONSUMER", key, group, consumer));
    }

    public PendingSummary xpending(String key, String group) {
        List<RespValue> items = Reply.array(engine.execute("XPENDING", key, group));
        long count = Reply.integer(items.get(0));
        String minId = Reply.string(items.get(1));
        String maxId = Reply.string(items.get(2));
        Map<String, Long> byConsumer = new LinkedHashMap<>();
        for (RespValue c : Reply.arrayOrEmpty(items.get(3))) {
            List<RespValue> pair = Reply.array(c);
            byConsumer.put(Reply.string(pair.get(0)), Long.parseLong(Reply.string(pair.get(1))));
        }
        return new PendingSummary(count, minId, maxId, byConsumer);
    }

    public List<PendingEntry> xpending(String key, String group, String start, String end, long count, String consumer) {
        Object[] args = consumer != null
                ? new Object[] {"XPENDING", key, group, start, end, count, consumer}
                : new Object[] {"XPENDING", key, group, start, end, count};
        List<RespValue> items = Reply.arrayOrEmpty(engine.execute(args));
        List<PendingEntry> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            List<RespValue> f = Reply.array(item);
            out.add(new PendingEntry(Reply.string(f.get(0)), Reply.string(f.get(1)), Reply.integer(f.get(2)), Reply.integer(f.get(3))));
        }
        return out;
    }

    public List<StreamEntry> xclaim(String key, String group, String consumer, long minIdleMillis, String... ids) {
        Object[] args = concat(new Object[] {"XCLAIM", key, group, consumer, minIdleMillis}, ids);
        return decodeStreamEntries(engine.execute(args));
    }

    public List<String> xclaimJustId(String key, String group, String consumer, long minIdleMillis, String... ids) {
        Object[] args = concat(new Object[] {"XCLAIM", key, group, consumer, minIdleMillis}, ids);
        args = concat(args, new Object[] {"JUSTID"});
        return Reply.stringList(engine.execute(args));
    }

    public XAutoClaimResult xautoclaim(String key, String group, String consumer, long minIdleMillis, String start, Long count) {
        List<Object> args = new ArrayList<>(List.of("XAUTOCLAIM", key, group, consumer, minIdleMillis, start));
        if (count != null) {
            args.add("COUNT");
            args.add(count);
        }
        List<RespValue> top = Reply.array(engine.execute(args.toArray()));
        String next = Reply.string(top.get(0));
        List<StreamEntry> entries = decodeStreamEntries(top.get(1));
        List<String> deleted = top.size() > 2 ? Reply.stringList(top.get(2)) : List.of();
        return new XAutoClaimResult(next, entries, deleted);
    }

    public void xsetId(String key, String id) {
        Reply.ok(engine.execute("XSETID", key, id));
    }

    public long xtrim(String key, String strategy, boolean approximate, String threshold) {
        return Reply.integer(engine.execute("XTRIM", key, strategy, approximate ? "~" : "=", threshold));
    }

    public Map<String, Object> xinfoStream(String key) {
        return Reply.flatObjectMap(engine.execute("XINFO", "STREAM", key));
    }

    public List<Map<String, Object>> xinfoGroups(String key) {
        return decodeInfoList(engine.execute("XINFO", "GROUPS", key));
    }

    public List<Map<String, Object>> xinfoConsumers(String key, String group) {
        return decodeInfoList(engine.execute("XINFO", "CONSUMERS", key, group));
    }

    private static List<Map<String, Object>> decodeInfoList(RespValue v) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RespValue item : Reply.arrayOrEmpty(v)) {
            out.add(Reply.flatObjectMap(item));
        }
        return out;
    }

    // =====================================================================
    // Geospatial
    // =====================================================================

    public long geoAdd(String key, double longitude, double latitude, String member) {
        return Reply.integer(engine.execute("GEOADD", key, Numbers.decimal(longitude), Numbers.decimal(latitude), member));
    }

    public long geoAdd(String key, Map<String, GeoPoint> membersToPoints) {
        List<Object> args = new ArrayList<>(membersToPoints.size() * 3 + 2);
        args.add("GEOADD");
        args.add(key);
        membersToPoints.forEach((member, point) -> {
            args.add(Numbers.decimal(point.longitude()));
            args.add(Numbers.decimal(point.latitude()));
            args.add(member);
        });
        return Reply.integer(engine.execute(args.toArray()));
    }

    public Double geoDist(String key, String member1, String member2) {
        return Reply.doubleOrNull(engine.execute("GEODIST", key, member1, member2));
    }

    public Double geoDist(String key, String member1, String member2, String unit) {
        return Reply.doubleOrNull(engine.execute("GEODIST", key, member1, member2, unit));
    }

    public List<GeoPoint> geoPos(String key, String... members) {
        List<RespValue> items = Reply.arrayOrEmpty(engine.execute(concat(new Object[] {"GEOPOS", key}, members)));
        List<GeoPoint> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            List<RespValue> pair = Reply.array(item);
            out.add(pair == null ? null : new GeoPoint(Reply.doubleValue(pair.get(0)), Reply.doubleValue(pair.get(1))));
        }
        return out;
    }

    public List<GeoSearchResult> geoSearch(String key, GeoSearchOptions options) {
        Object[] args = withOptions(new Object[] {"GEOSEARCH", key}, options.toArgs());
        List<RespValue> items = Reply.arrayOrEmpty(engine.execute(args));
        List<GeoSearchResult> out = new ArrayList<>(items.size());
        for (RespValue item : items) {
            out.add(decodeGeoSearchResult(item, options));
        }
        return out;
    }

    private static GeoSearchResult decodeGeoSearchResult(RespValue item, GeoSearchOptions options) {
        boolean plain = !(options.wantsCoord() || options.wantsDist() || options.wantsHash());
        if (plain) {
            return new GeoSearchResult(Reply.string(item), null, null, null);
        }
        List<RespValue> fields = Reply.array(item);
        String member = Reply.string(fields.get(0));
        int idx = 1;
        Double distance = null;
        Long hash = null;
        GeoPoint coord = null;
        if (options.wantsDist()) {
            distance = Reply.doubleValue(fields.get(idx++));
        }
        if (options.wantsHash()) {
            hash = Reply.integer(fields.get(idx++));
        }
        if (options.wantsCoord()) {
            List<RespValue> pair = Reply.array(fields.get(idx));
            coord = new GeoPoint(Reply.doubleValue(pair.get(0)), Reply.doubleValue(pair.get(1)));
        }
        return new GeoSearchResult(member, distance, coord, hash);
    }

    // =====================================================================
    // Bitmaps and HyperLogLog
    // =====================================================================

    public boolean setBit(String key, long offset, int value) {
        return Reply.integer(engine.execute("SETBIT", key, offset, value)) != 0;
    }

    public boolean getBit(String key, long offset) {
        return Reply.integer(engine.execute("GETBIT", key, offset)) != 0;
    }

    public long bitCount(String key) {
        return Reply.integer(engine.execute("BITCOUNT", key));
    }

    public long bitCount(String key, long start, long end) {
        return Reply.integer(engine.execute("BITCOUNT", key, start, end));
    }

    public long bitCount(String key, long start, long end, String unit) {
        return Reply.integer(engine.execute("BITCOUNT", key, start, end, unit));
    }

    public Long bitPos(String key, int bit) {
        return Reply.integerOrNull(engine.execute("BITPOS", key, bit));
    }

    public Long bitPos(String key, int bit, long start, long end) {
        return Reply.integerOrNull(engine.execute("BITPOS", key, bit, start, end));
    }

    public long bitOp(String operation, String destination, String... keys) {
        return Reply.integer(engine.execute(concat(new Object[] {"BITOP", operation, destination}, keys)));
    }

    public Object bitField(String key, Object... operations) {
        return execute(concat(new Object[] {"BITFIELD", key}, operations));
    }

    public long pfAdd(String key, String... elements) {
        return Reply.integer(engine.execute(concat(new Object[] {"PFADD", key}, elements)));
    }

    public long pfCount(String... keys) {
        return Reply.integer(engine.execute(prepend("PFCOUNT", keys)));
    }

    public void pfMerge(String destination, String... sourceKeys) {
        Reply.ok(engine.execute(concat(new Object[] {"PFMERGE", destination}, sourceKeys)));
    }

    // =====================================================================
    // Server
    // =====================================================================

    public String ping() {
        return Reply.string(engine.execute("PING"));
    }

    public String ping(String message) {
        return Reply.string(engine.execute("PING", message));
    }

    public String echo(String message) {
        return Reply.string(engine.execute("ECHO", message));
    }

    public String info() {
        return Reply.string(engine.execute("INFO"));
    }

    public String info(String section) {
        return Reply.string(engine.execute("INFO", section));
    }

    public long dbSize() {
        return Reply.integer(engine.execute("DBSIZE"));
    }

    public void flushDb() {
        Reply.ok(engine.execute("FLUSHDB"));
    }

    /** Server time as [unix seconds, microseconds]. */
    public long[] time() {
        List<RespValue> items = Reply.array(engine.execute("TIME"));
        return new long[] {Reply.integer(items.get(0)), Reply.integer(items.get(1))};
    }

    public Map<String, String> configGet(String parameter) {
        return Reply.stringMap(engine.execute("CONFIG", "GET", parameter));
    }

    public void configSet(String parameter, String value) {
        Reply.ok(engine.execute("CONFIG", "SET", parameter, value));
    }

    public void clientSetName(String name) {
        Reply.ok(engine.execute("CLIENT", "SETNAME", name));
    }

    public long clientId() {
        return Reply.integer(engine.execute("CLIENT", "ID"));
    }

    public long publish(String channel, String message) {
        return Reply.integer(engine.execute("PUBLISH", channel, message));
    }

    public long sPublish(String channel, String message) {
        return Reply.integer(engine.execute("SPUBLISH", channel, message));
    }

    // =====================================================================
    // CompletableFuture async variants of the most common commands
    // =====================================================================

    public CompletableFuture<String> getAsync(String key) {
        return async(() -> get(key));
    }

    public CompletableFuture<Void> setAsync(String key, String value) {
        return async(() -> {
            set(key, value);
            return null;
        });
    }

    public CompletableFuture<Long> delAsync(String... keys) {
        return async(() -> del(keys));
    }

    public CompletableFuture<Long> existsAsync(String... keys) {
        return async(() -> exists(keys));
    }

    public CompletableFuture<Boolean> expireAsync(String key, long seconds) {
        return async(() -> expire(key, seconds));
    }

    public CompletableFuture<Long> incrAsync(String key) {
        return async(() -> incr(key));
    }

    public CompletableFuture<String> pingAsync() {
        return async(this::ping);
    }

    public CompletableFuture<Long> hsetAsync(String key, String field, String value) {
        return async(() -> hset(key, field, value));
    }

    public CompletableFuture<String> hgetAsync(String key, String field) {
        return async(() -> hget(key, field));
    }

    // =====================================================================
    // Sub-APIs: HotKV exclusive and AI/enterprise commands
    // =====================================================================

    /** {@code TAG.*}: tagging keys and querying by tag (core). */
    public TagCommands tag() {
        return tag;
    }

    /** {@code GCRA.*}: the core rate limiter primitive (core). */
    public GcraCommands gcra() {
        return gcra;
    }

    /** {@code VSIM.*}: the vector similarity index (core except persistence). */
    public VsimCommands vsim() {
        return vsim;
    }

    /** {@code SCACHE.*}: the semantic cache engine. */
    public ScacheCommands scache() {
        return scache;
    }

    /** {@code PCACHE.*}: the prompt/LLM response cache. */
    public PcacheCommands pcache() {
        return pcache;
    }

    /** {@code TOOL.CACHE.*}: tool call result cache. */
    public KeyedCacheCommands toolCache() {
        return toolCache;
    }

    /** {@code NCACHE.*}: negative/"don't retry" cache. */
    public KeyedCacheCommands ncache() {
        return ncache;
    }

    /** {@code GUARD.*}: guardrail/moderation decision cache. */
    public KeyedCacheCommands guard() {
        return guard;
    }

    /** {@code RERANK.*}: reranker result cache. */
    public KeyedCacheCommands rerank() {
        return rerank;
    }

    /** {@code LLMSTATS.*}: LLM usage accounting buckets. */
    public LlmStatsCommands llmstats() {
        return llmStats;
    }

    /** {@code LLMBUDGET.*}: token/cost budget caps. */
    public LlmBudgetCommands llmBudget() {
        return llmBudget;
    }

    /** {@code PROMPT.*}: the versioned prompt/template registry. */
    public PromptRegistryCommands promptRegistry() {
        return promptRegistry;
    }

    /** {@code EMBED.DEDUP.*}: reference counted embedding dedup. */
    public EmbedDedupCommands embedDedup() {
        return embedDedup;
    }

    /** {@code AMEM.*}: AI agent memory: conversation, semantic, working, episodic, cross-memory search. */
    public AmemCommands amem() {
        return amem;
    }

    /** {@code AMEM.SUMMARY.*}: rolling per-agent summaries. */
    public AmemSummaryCommands amemSummary() {
        return amemSummary;
    }

    /** {@code RAG.*}: the retrieval-augmented-generation pipeline. */
    public RagCommands rag() {
        return rag;
    }

    /** {@code RATELIMIT.*}: named application level rate limiters. */
    public RateLimitCommands rateLimit() {
        return rateLimit;
    }

    /** {@code TS.*}: time series storage. */
    public TimeSeriesCommands timeSeries() {
        return timeSeries;
    }

    /** {@code FEATURE.*}: the ML feature store. */
    public FeatureStoreCommands featureStore() {
        return featureStore;
    }

    /** {@code OBSERVE}: server observability metrics. */
    public ObserveCommands observe() {
        return observe;
    }

    /** {@code ALERTS}: alert dispatcher configuration. */
    public AlertsCommands alerts() {
        return alerts;
    }

}
