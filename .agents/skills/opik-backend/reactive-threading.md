# Reactive Threading

Reactor runs every operator on the thread that delivered the signal, and it never switches back on its own. After an
async call, everything downstream runs on the client's thread until the code moves it. A step lands on the right
thread only because the chain put it there, never because of what ran before it.

## Know Which Thread You Are On

| After | Downstream runs on | What that thread is |
|---|---|---|
| A reactive or async Redisson call | `redisson-netty-*` | Netty event loop shared by **every** Redis call in the service. Blocking one stalls every reply on its connections. |
| An r2dbc ClickHouse read or write | `ClickHouseWorker-*` | The driver's shared pool (2 × CPU + 1 threads, unbounded queue). Every query in the service waits for a free worker. |
| A client-v2 call (`JsonEachRowBulkInsert`) | `chc-operation-*` | The v2 client's unbounded cached pool. |
| `timeout`, `delay`, `interval`, retry backoff | `parallel-*` | One thread per core, shared by all CPU steps and all Reactor timers. |
| `fromCallable(x).subscribeOn(boundedElastic())` | `boundedElastic-*` | The pool for blocking calls (10 × cores threads), shared by every MySQL, S3 and sync HTTP call. Light work may follow there; heavy CPU may not. |
| A Jersey `async()` call | the Jersey client executor | Shared by every async outbound HTTP call. |
| `block()` | the caller's thread | The only place where the thread actually comes back. |

`subscribeOn` moves only the subscription. On a reactive client it moves the send, but the reply still arrives on the
client's thread. `publishOn` is the only operator that moves the signals after it.

## Name the Work, Not the Scheduler

`com.comet.opik.utils.Offload` puts each kind of work on its scheduler. Use it instead of hand-writing
`fromCallable(...).subscribeOn(...)`:

| Work | Call | Runs on |
|---|---|---|
| Blocking call (JDBI, sync Redisson, S3, sync HTTP, DNS) | `Offload.blocking(() -> ...)` | `boundedElastic` |
| CPU that grows with the input | `Offload.cpu(() -> ...)` | `parallel`, or the entry point's pool |
| Per-item blocking in a `Flux`, plus the light work around it | `Offload.blockingEach(flux)` | `boundedElastic`, every item |
| Back to the entry point's pool after a call | `.transform(Offload::resumeOriginal)` | the pool set with `Offload.originalOn`: a subscriber's workers, a `request-vt` virtual thread for a resource that sets the request context, or no hop |
| Light work (map one row, build a DTO) | nothing | wherever it lands |

Use `resumeOriginal` once, after the last client call and before your own logic. Not between client calls, and not
before `Offload.blocking` or `Offload.cpu`: those hop themselves, and every extra hop is a queue handoff.

## Critical: Always Request Changes

These shapes have all shipped and held a shared thread in tests: S3 deletes on `ClickHouseWorker`, a MySQL transaction
and a sync HTTP call on `redisson-netty`, response chunks written on `ClickHouseWorker`. Flag them as critical when a diff
adds or moves them, wherever the chain sits:

1. A blocking call inside an operator lambda (`map`, `flatMap`, `filter`, `handle`, `doOnNext`, `doOnSuccess`,
   `then(...)`), or inside `Mono.fromCallable` / `fromRunnable` / `fromSupplier` / `Mono.create` with no
   `Offload.blocking` or `subscribeOn` directly on it.
2. A per-item blocking call or chunk write on a `Flux` from a DAO or Redis with no `Offload.blockingEach` before it.
3. `.block()`, `.toFuture().join()` or `CompletableFuture.get()` / `join()` inside a reactive chain.

**How to tell a call is blocking:** it does I/O and returns a plain value or `void`, not a `Mono`, `Flux` or
`CompletableFuture`. In Opik that includes:

- `FileService` (S3, MinIO) and anything that calls it
- JDBI: `TransactionTemplate`, `*DAO` interfaces on MySQL, and services whose methods return plain values from them
- sync Redisson: `RBucket.get/set`, `RMap.get/put`, `RLock.lock`, and `@Cacheable` methods that return a plain value
  (the cache interceptor reads Redis synchronously; Redisson refuses that on `redisson-netty`, so the cache never hits
  there)
- sync HTTP: a Jersey `Invocation.Builder.get/post/put/delete` without `.async()` or `.rx()`, and `WorkspaceNameService`
- `InetAddress.getAllByName`, `Thread.sleep`, `ChunkedOutput.write`, `OutputStream.write` to a response

When unsure, open the callee. A name never proves a method is non-blocking.

## Rule 1: A Blocking Call Wraps Itself

Every blocking call must run inside its own `Offload.blocking(...)`, which is
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` with the hop **immediately after the source**. Blocking calls include JDBI/MySQL
(`TransactionTemplate`), sync Redisson, S3 (`FileService`), sync HTTP clients, DNS lookups (`InetAddress`),
`Thread.sleep` and `ChunkedOutput.write` to a slow client.

```java
// ✅ GOOD - the blocking call carries its own hop
return attachmentDAO.getAttachmentsByEntityIds(entityType, entityIds, containerId)
        .flatMap(attachments -> Offload.blocking(() -> fileService.deleteObjects(keys(attachments))))
        .then(attachmentDAO.deleteByEntityIds(entityType, entityIds, containerId));

// ❌ BAD - S3 runs on ClickHouseWorker, holding a driver thread for every round-trip
return attachmentDAO.getAttachmentsByEntityIds(entityType, entityIds, containerId)
        .flatMap(attachments -> Mono.fromRunnable(() -> fileService.deleteObjects(keys(attachments))))
        .then(attachmentDAO.deleteByEntityIds(entityType, entityIds, containerId));

// ❌ BAD - JDBI on redisson-netty: the previous step was a Redis reply
return bucketService.readyBuckets()
        .flatMap(bucket -> Mono.fromCallable(() -> alertService.getByIdAndWorkspace(bucket.alertId(), bucket.workspaceId())));
```

- **Never rely on the upstream thread.** A blocking `fromCallable` without `Offload.blocking` (or its own `subscribeOn`) is a bug,
  even if the step before it happens to emit on `boundedElastic` today. The next refactor will move it.
- **Wrap only the call.** Keep light work outside the wrapped source. Never wrap a whole chain to make one call
  safe.
- Only a resource method body outside the chain (before it is built, or after `block()`) and an event-bus listener
  may block directly. A service never knows which thread calls it, so a blocking call in a service always wraps
  itself.
- Code that holds a Redis lock follows the same rule. `RedissonLockService` already moves the locked action off the
  event loop (`runAction`); do the same for any new Redisson-driven flow.

## Rule 2: Nothing Blocks on an Event Loop, the Driver Pool or parallel

Never block on `redisson-netty`, `ClickHouseWorker`, `chc-operation` or `parallel`. That includes calling `.block()`,
sync Redisson or a blocking client from inside a reactive chain. `block()` belongs only at the edge: a sync Jersey
resource, or a job's entry point. Reactor throws `IllegalStateException` for `block()` on `parallel`; the other pools
fail silently by queueing.

## Rule 3: CPU That Scales with the Input Gets Its Own Step

Light work (mapping one row, building a small DTO, logging) may run where it lands. Work that grows with the batch,
page or payload size (clustering, sorting a window, serializing a batch, building CSV, labelling a page, parsing large
JSON) must be its own step:

```java
// ✅ GOOD - the CPU step leaves the driver thread
return reader.readWindow(workspaceId, projectId)
        .flatMap(rows -> Offload.cpu(() -> algorithm.cluster(rows, maxTopics)));

// ❌ BAD - k-means runs on ClickHouseWorker while other queries wait for a worker
return reader.readWindow(workspaceId, projectId)
        .map(rows -> algorithm.cluster(rows, maxTopics));
```

- `parallel` is for short CPU work. Long CPU work (seconds) must also be bounded by a concurrency cap, because
  `parallel` has one thread per core and also runs every timer.
- An entry point whose CPU work runs for seconds may route it to its own core-sized pool once, and every
  `Offload.cpu` step upstream follows. Callers that set nothing use `parallel`. Never route CPU to a subscriber's
  workers: that pool is sized for waiting (batch size × 2 threads), not for cores.

```java
// ✅ GOOD - the subscriber picks the pools once; the services stay scheduler-free
return topicsRunService.discover(run)
        .contextWrite(context -> Offload.originalOn(Offload.cpuOn(context, clusteringScheduler), workersScheduler));
```
- Light work after `Offload.blocking` may stay on `boundedElastic`. Heavy CPU may not, even right after a blocking
  call: every MySQL transaction, S3 call and sync HTTP call in the service waits for a free `boundedElastic` thread, so
  a batch serialized there delays them all. Put it in its own `Offload.cpu(...)`. Never `publishOn` mid-chain to move
  one step: it moves every step after it.

## Rule 4: Per-Item Work on a Stream Moves Once

When each item of a `Flux` triggers CPU or a blocking side effect (a response chunk write, an S3 part, a per-item
lookup), put one `Offload.blockingEach(...)` (a `publishOn`) directly before that operator. Per-item `subscribeOn` wrappers don't help, because the
items arrive from the source's thread.

```java
// ✅ GOOD - readTree, redaction and the chunk write leave the driver thread
Offload.blockingEach(flux)
        .doOnNext(item -> sendItem(item, outputStream, rules));

// ❌ BAD - a slow client holds a ClickHouseWorker for the whole stream
Schedulers.boundedElastic().schedule(() -> flux.doOnNext(item -> sendItem(item, outputStream, rules)).subscribe());
```

Subscribing on a scheduler does not change where `onNext` runs: the subscription moves, the items don't.

## Rule 5: Timers and Retries Land on parallel

Anything after `timeout`, `delay`, `interval` or a `retryWhen` backoff runs on `parallel`. A retry re-subscribes the
source there, so a source that blocks at subscription (`Mono.create`, `fromCallable` without `subscribeOn`) blocks on
`parallel` on every retry. Make the retried source an `Offload.blocking(...)`, inside the retry.

## Rule 6: Services Don't Create Schedulers

Use `Offload`, which uses the shared schedulers: `boundedElastic()` for blocking, `parallel()` for CPU. Pools sized for
a purpose (subscriber consumer and workers, inference pools) live in infrastructure, are bounded, and are named so
thread dumps show them. Only an entry point picks a special pool, through `Offload.cpuOn`. A service never creates,
sizes or passes a scheduler.

## Review Checklist

For every new or changed reactive chain:

1. Which thread emits into each step? Name it from the table above.
2. Does any step block (see "How to tell a call is blocking")? Is it an `Offload.blocking(...)`? A miss is critical.
3. Does any step do CPU that grows with the input? Is it an `Offload.cpu(...)`, with a concurrency cap if it runs for
   seconds?
4. Does a `Flux` do per-item CPU or I/O? Is there an `Offload.blockingEach(...)` right before it?
5. Does a `timeout`, `delay` or retry precede blocking work?
6. Is there a `block()` anywhere but at the edge?
