# HotKV Java Client

[![CI](https://github.com/hotkv-ltd/hotkv-java-client/actions/workflows/ci.yml/badge.svg)](https://github.com/hotkv-ltd/hotkv-java-client/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A high-performance Java client for [HotKV](https://hotkv.com), a RESP2/RESP3-compatible
in-memory data store with built-in AI and LLM-serving features (semantic and prompt
caching, agent memory, RAG, feature store, rate limiting, time series and more).

- Zero runtime dependencies: built only on the JDK (`java.net`, `javax.net.ssl`,
  `java.util.concurrent`, `java.security`).
- Thread safe, connection pooled client with automatic RESP3 negotiation and RESP2 fallback.
- Pipelines, MULTI/EXEC transactions with WATCH, Pub/Sub, and a cluster client with MOVED/ASK
  redirect handling.
- Typed methods for the common command families (strings, keys, hashes, lists, sets, sorted sets,
  streams, geo, bitmaps, HyperLogLog, scripting, pub/sub, transactions) and for most HotKV
  exclusive and AI commands (semantic cache, prompt cache, agent memory, RAG, rate limiting, time
  series, feature store...). Every other command is available through the generic `execute`
  method, see [Command coverage](#command-coverage).
- Java 17+, package `com.hotkv`.

## Install

```xml
<dependency>
  <groupId>com.hotkv</groupId>
  <artifactId>hotkv-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

Building from source (produces `target/hotkv-client-1.0.0.jar`):

```bash
mvn -B package
```

## Quick start

```java
import com.hotkv.HotKV;

try (HotKV client = HotKV.create("hotkv://localhost:6379")) {
    client.set("greeting", "hello, HotKV");
    System.out.println(client.get("greeting"));

    client.hset("user:1", Map.of("name", "Ada", "role", "engineer"));
    System.out.println(client.hgetAll("user:1"));
}
```

`HotKV` is thread safe: share one instance across your application and let its internal
connection pool handle concurrency.

## Connection options

`HotKV.create(url)` parses a connection URL:

```
hotkv://[username:password@]host[:port][/db]
hotkvs://...                                  # TLS
redis://... , rediss://...                    # accepted for drop-in migration
unix:///path/to/socket.sock                   # Unix domain socket, where the platform allows it
```

For everything else, build `HotKVOptions` with the fluent builder (`HotKVOptions.parse(url)`
returns a pre-filled builder you can keep customizing):

```java
import com.hotkv.HotKV;
import com.hotkv.HotKVOptions;
import com.hotkv.TlsOptions;

HotKVOptions options = HotKVOptions.parse("hotkvs://cache.internal:6400")
        .username("app")
        .password(System.getenv("HOTKV_PASSWORD"))
        .database(2)
        .clientName("orders-service")
        .connectTimeoutMillis(3000)
        .commandTimeoutMillis(2000)
        .poolSize(20)
        .maxRetries(1)                    // retry idempotent reads once on connection loss
        .tls(TlsOptions.builder()
                .caCertPath("/etc/hotkv/ca.pem")
                .clientCert("/etc/hotkv/client.pem", "/etc/hotkv/client.key.pk8.pem")
                .build())
        .build();

HotKV client = new HotKV(options);
```

Notes on TLS: client/CA certificates are PEM encoded X.509; private keys must be PEM encoded
**PKCS8** (`-----BEGIN PRIVATE KEY-----`). Convert a traditional PKCS1 key first:

```bash
openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem
```

`TlsOptions.builder().insecureSkipVerify(true)` disables certificate and hostname verification;
use it only against local test servers, never in production.

### Pooling

Every `HotKV` instance owns a bounded connection pool (`poolSize`, default 10): connections are
created lazily, health checked with `PING` after sitting idle past `healthCheckIntervalMillis`,
and recycled past `idleTimeoutMillis`. Inspect it with `client.poolStats()`. Reads classified as
idempotent (`GET`, `HGETALL`, `SCAN`, ...) are retried once on a fresh connection after a
connection loss; writes are never retried automatically unless you opt in with
`retryNonIdempotentWrites(true)`.

## Pipelines

Queue many commands and send them in a single round trip:

```java
import com.hotkv.Pipeline;
import com.hotkv.Response;

Pipeline pipeline = client.pipeline();
Response<Void> set = pipeline.set("a", "1");
Response<String> get = pipeline.get("a");
Response<Long> incr = pipeline.incr("counter");
pipeline.sync();

System.out.println(get.get() + " " + incr.get());
```

A failing command does not fail the batch: `Response.get()` throws the same typed exception a
direct call would have, only for that one command. Anything not covered by the built-in typed
helpers is available through `pipeline.command(...)`.

## Transactions

```java
import com.hotkv.Transaction;

try (Transaction tx = client.transaction()) {
    tx.watch("balance");                       // sent immediately, guarding what follows
    long current = Long.parseLong(client.get("balance"));
    Response<Long> newBalance = tx.incrBy("balance", current < 100 ? 10 : -10);

    List<Object> results = tx.exec();           // MULTI + queued commands + EXEC, one round trip
    if (results == null) {
        // "balance" changed after watch(): retry the whole operation
    } else {
        System.out.println(newBalance.get());
    }
}
```

`exec()` returns `null` when the transaction aborted (a watched key changed), matching `EXEC`'s
own null reply; `tx.discard()` (or letting the try-with-resources close) releases it cleanly.

## Pub/Sub

```java
import com.hotkv.PubSub;

try (PubSub sub = client.pubSub()) {
    sub.addListener(message -> {
        if (message.kind() == com.hotkv.PubSubMessage.Kind.MESSAGE) {
            System.out.println(message.channel() + ": " + message.payload());
        }
    });
    sub.subscribe("orders", "payments");
    Thread.sleep(30_000);
}

// elsewhere
client.publish("orders", "order:42 created");
```

Or pull messages yourself with `sub.nextMessage()` instead of registering a listener. Pub/Sub
runs on its own dedicated connection (never shared with the pool) and understands RESP3 push
frames automatically.

## Cluster

```java
import com.hotkv.HotKVCluster;

try (HotKVCluster cluster = HotKVCluster.create("hotkv://node-a:6379", "hotkv://node-b:6379")) {
    cluster.clientFor("user:42").set("user:42", "..."); // routed by CRC16 slot, full typed API
    Object result = cluster.command("user:42", "GET", "user:42"); // generic, routed automatically
}
```

`HotKVCluster` discovers the slot map with `CLUSTER SLOTS` (falling back to `CLUSTER SHARDS`),
supports `{hash tag}` routing, follows `MOVED` (refreshing the map) and `ASK` (sending `ASKING`
first) up to a bounded number of redirects, and keeps one pooled `HotKV` client per node.
`cluster.commandReadOnly(key, ...)` routes to a replica with `READONLY` when one is known.

## Error handling

Every exception extends the unchecked `HotKVException`:

```java
import com.hotkv.exceptions.*;

try {
    client.set("k", "v");
} catch (HotKVTimeoutException e) {
    // connect/read/pool timeout
} catch (HotKVConnectionException e) {
    // transport failure
} catch (WrongTypeException e) {
    // WRONGTYPE
} catch (NotLicensedException e) {
    // NOLICENSE: an enterprise/AI command on a server that is not licensed for it
} catch (MovedRedirectException | AskRedirectException e) {
    // cluster redirects (HotKVCluster handles these for you already)
} catch (HotKVServerException e) {
    // any other server error reply; e.errorPrefix() is e.g. "ERR"
}
```

## Command coverage

Typed methods exist for 215 of the 444 core command names and 144 of the 177 enterprise command
names in the HotKV 0.3.0 `COMMAND LIST` (sub-commands such as `CLIENT ID` count separately). Every
command without a typed method is reachable through the generic command method described below.

There are no typed methods for these families and commands:

- `CLIENT TRACKING`, `CLIENT CACHING`, `CLIENT GETREDIR`, `CLIENT TRACKINGINFO` and the other
  `CLIENT` sub-commands except `ID` and `SETNAME`.
- The `TENANT.*`, `IAM.*`, `AUDIT.*`, `ENCRYPTION.*` and `REPLICATION.*` enterprise families, and
  `FEATURE.SETAT`.
- The probabilistic structures `BF.*`, `CMS.*` and `TOPK.*`.
- Newer commands `MSETEX`, `DELEX`, `HIMPORT`, `XCFGSET`, `SFLUSH`, `CLUSTERSCAN` and
  `TRIMSLOTS` (`HGETDEL`, `HGETEX` and `HSETEX` do have typed methods).
- Blocking list and sorted set pops (`BLPOP`, `BRPOP`, `BLMOVE`, `BLMPOP`, `BZPOPMIN`...),
  `LMPOP`, `SORT`, `LCS`, `OBJECT`, `MEMORY`, `SLOWLOG`, `LATENCY`, `ACL`, `FUNCTION`/`FCALL`,
  `GEOHASH`, `GEORADIUS*` (use `geoSearch`), `FLUSHALL`, and the administrative and `CLUSTER`
  commands (`HotKVCluster` uses `CLUSTER SLOTS`/`SHARDS` internally).

All of them are reachable through the generic method, which accepts any command and decodes the
reply into plain Java types (`String`, `Long`, `Double`, `Boolean`, `List`, `Map`, `null`);
`executeRaw` returns the raw `RespValue`. Arguments may be `String`, `byte[]`, numbers or booleans.

```java
client.execute("BF.RESERVE", "seen", "0.01", 1000);
client.execute("BF.ADD", "seen", "user:1");              // 1 (or true under RESP3)
client.execute("MSETEX", 2, "a", "1", "b", "2", "EX", 60);
List<?> popped = (List<?>) client.execute("BLPOP", "queue", 2);   // blocks up to 2 seconds
```

A blocking command must use a timeout shorter than `commandTimeoutMillis` (default 5000), or raise
that option, otherwise the client gives up first with a `HotKVTimeoutException`.

Commands that need a pinned connection (for example `SELECT`, `CLIENT TRACKING`, or `WATCH`
outside a `Transaction`) are not safe through the pooled generic method; use the `database`
option for `SELECT` and `Transaction` for `WATCH`.

## HotKV commands

Most HotKV exclusive and AI commands have a typed home on the client:

```java
client.tag().set("doc:1", "draft", "reviewed");
client.nExpire("doc:1", 60_000_000_000L);        // nanosecond resolution TTL
client.gcra().limit("api:user:42", 60_000, 100);  // rate limiter primitive
client.vsim().search("embeddings", 5, queryVector, null, null);
client.scache();      // semantic cache
client.pcache();      // prompt/LLM response cache
client.toolCache(); client.ncache(); client.guard(); client.rerank();
client.llmstats(); client.llmBudget(); client.promptRegistry(); client.embedDedup();
client.amem(); client.amemSummary();
client.rag();
client.rateLimit(); client.timeSeries(); client.featureStore();
client.observe(); client.alerts();
```

Each accessor returns a small, focused typed API; results with structure come back as compact
immutable records (e.g. `ScacheCommands.ScacheResult`, `RagCommands.RagResult`).

`PcacheCommands.set`, `ScacheCommands.set`, the keyed caches' `set` (`toolCache()`/`ncache()`/
`guard()`/`rerank()`) and `LlmBudgetCommands.consume` all take a trailing `Usage` (built with
`Usage.of("gpt-4").input(12).output(8)...`, model required): recording the original call's
token usage lets a later cache hit attribute the tokens, and once the model is priced the
cost, it did not have to spend. Their `stats(...)` replies (`PcacheCommands.Stats`,
`ScacheCommands.ScacheStats`, the keyed caches' `Stats`) expose those savings as a
`UsageSavings` alongside the existing counters.

## AI caching example

`com.hotkv.ai.PromptCache` builds a deterministic cache key from the request (provider, model,
messages, generation parameters, canonicalized so every official HotKV SDK produces the same
key for the same logical request) and only calls your model when there is a miss. Reporting
`model` in the returned usage attaches it to the stored entry, so `cache.stats()` reports the
tokens and cost a later hit saved:

```java
import com.hotkv.ai.*;

PromptCache cache = new PromptCache(client, "chat-completions", 3600L)
        .withStatsBucket("chat-llm");
cache.createNamespace(null, null);

PromptCacheKeyInput request = new PromptCacheKeyInput(
        "openai", "gpt-6-sol", "",
        List.of(new PromptMessage("user", "Summarize this contract in three bullet points.")),
        Map.of("temperature", "0.2"));

PromptCache.Result result = cache.getOrCall(request, () -> {
    // This block runs only on a cache miss.
    String answer = myLlmClient.complete(request);
    return new LlmCallResult(answer, new LlmUsage("gpt-6-sol", 812, 96, 0, 0, 0, 0.0031, 640));
});

System.out.println((result.hit() ? "cache hit: " : "cache miss, called the LLM: ") + result.response());
System.out.println("tokens saved so far: " + cache.stats().usageSavings().inputTokens());
```

`com.hotkv.ai.SemanticCache` does the same for embedding based lookups: you supply the vector
(from any embedding provider you like, HotKV never computes embeddings itself) and it returns
the nearest cached answer above the namespace similarity threshold, or calls your function
(returning the same `LlmCallResult` shape as `PromptCache`'s).

## LLM cost tracking

`LLMSTATS.RECORD` with a model prices a call from HotKV's built-in price table instead of a
caller supplied cost; `LLMSTATS.PRICE SET` adds or overrides a model's price, and
`LLMBUDGET.CONSUME` with a model enforces a budget priced the same way:

```java
// Custom or fine-tuned model: 3 USD / 1M input tokens, 15 USD / 1M output.
client.llmstats().priceSet("acme-custom-v1", 3, 15, null);

client.llmstats().bucket("chatbot-usage");
client.llmstats().record("chatbot-usage", 500, 120, 0, 0, 340,
        LlmStatsRecordOptions.builder().model("acme-custom-v1"));

LlmStatsCommands.Snapshot usage = client.llmstats().get("chatbot-usage");
System.out.printf("spent $%.6f so far%n", usage.costUsd());

boolean allowed = client.llmBudget().consume("chatbot-daily", 0, 0,
        Usage.of("acme-custom-v1").input(500).output(120));
if (!allowed) {
    System.out.println("daily LLM budget exceeded");
}
```

## Testing

Unit tests need no server and always run:

```bash
mvn -B test
```

Integration tests run automatically when `HOTKV_TEST_URL` is set (skipped otherwise):

```bash
HOTKV_TEST_URL=hotkv://127.0.0.1:6399 mvn -B test
```

Commands gated on an enterprise license feature the running server does not have loaded reply
`NOLICENSE`; the integration tests detect that specifically and skip with a clear reason, so
they start passing on their own once a fuller license is loaded. `EnterpriseLiveTest` is skipped
as a whole on an unlicensed server. Run the integration tests only against a throwaway server,
because they create and delete keys (`TypedCoreLiveTest` also flushes database 14).

## Floating point arguments

Every `double` argument is written as plain decimal digits: never an exponent, never a leading
`+`, never a locale dependent separator (`com.hotkv.protocol.Numbers`). NaN is rejected
everywhere and infinities except for sorted set scores and bounds. USD amounts for
`LLMBUDGET.SET` and `LLMSTATS.PRICE SET` use the server's exact fixed point format (at most six
fractional digits); a negative amount, or a positive one below one micro dollar (which the server
would read as "no limit"), throws `IllegalArgumentException`. Vector components and thresholds are
narrowed to single precision, as the server stores them.

## Contributing

Bug reports and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Support

- Bugs and feature requests: [GitHub issues](https://github.com/hotkv-ltd/hotkv-java-client/issues)
- Questions and commercial support: support@hotkv.com
- Security vulnerabilities: see [SECURITY.md](SECURITY.md)

## License

Licensed under the Apache License, Version 2.0: see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Trademarks and affiliation

"HotKV" is a trademark of HotKV Ltd; the license does not grant rights to use it.
The HotKV server is a separate commercial product, and its enterprise commands need a HotKV license.

HotKV is an independent product of HotKV Ltd. It speaks the RESP protocol and implements
many Redis commands so that existing tools and client habits carry over, but it is not Redis,
Valkey, Dragonfly or KeyDB, and this SDK is built for and tested against HotKV. HotKV Ltd is not
affiliated with, endorsed by or sponsored by Redis Ltd., the Valkey project, DragonflyDB or KeyDB.
Redis is a registered trademark of Redis Ltd. Valkey, Dragonfly, KeyDB and all other product and
company names are trademarks of their respective owners; they are used here only to describe
protocol and command compatibility.
