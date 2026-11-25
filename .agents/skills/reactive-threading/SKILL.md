---
name: reactive-threading
description: Put every step of a backend Reactor chain on the right thread, and prove it. Use when writing or reviewing a Mono/Flux in apps/opik-backend that calls ClickHouse, Redis, MySQL, S3 or an HTTP client, serializes a batch, streams a response, or runs in a job or subscriber.
---

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
| `Offload.blocking(...)` | `boundedElastic-*` | The pool for blocking calls (10 × cores threads), shared by every MySQL, S3 and sync HTTP call. Light work may follow there; heavy CPU may not. |
| A Jersey `async()` call | the Jersey client executor | Shared by every async outbound HTTP call. |
| `block()` | the caller's thread | The only place where the thread actually comes back. |

`subscribeOn` moves only the subscription. On a reactive client it moves the send, but the reply still arrives on the
client's thread. `publishOn` is the only operator that moves the signals after it.

A resource method runs on a Jetty request thread (`dw-*`). It builds the chain, subscribes in `block()`, and waits
there: no operator of the chain runs on it unless the source emits synchronously. An event-bus listener runs on its own
virtual thread, whatever thread posted the event.

## Name the Work, Not the Scheduler

`com.comet.opik.utils.Offload` puts each kind of work on its scheduler. Use it instead of hand-writing
`fromCallable(...).subscribeOn(...)`:

| Work | Call | Runs on |
|---|---|---|
| Blocking call (JDBI, sync Redisson, S3, sync HTTP, DNS) | `Offload.blocking(() -> ...)` | `boundedElastic` |
| CPU that grows with the input | `Offload.cpu(() -> ...)` | `parallel` |
| Per-item blocking in a `Flux`, plus the light work around it | `Offload.blockingEach(flux)` | `boundedElastic`, every item |
| Back to the entry point's pool after the last client call | `.transform(Offload::resumeOriginal)` | the pool set with `Offload.originalOn`: a subscriber's workers, a `request-vt` virtual thread wherever `AsyncUtils.setRequestContext(context, requestContext)` is written into the context, or no hop |
| Light work (map one row, build a DTO, post an event) | nothing | wherever it lands |

Use `resumeOriginal` once, after the last client call and before your own logic. Not between client calls, and not
before `Offload.blocking` or `Offload.cpu`: those hop themselves, and every extra hop is a queue handoff.

## How to Tell a Call Is Blocking

It does I/O and returns a plain value or `void`, not a `Mono`, `Flux` or `CompletableFuture`. In Opik that includes:

- `FileService` (S3, MinIO) and anything that calls it
- JDBI: `TransactionTemplate`, `*DAO` interfaces on MySQL, and services whose methods return plain values from them
- sync Redisson: `RBucket.get/set`, `RMap.get/put`, `RLock.lock`, and `@Cacheable` methods that return a plain value
  (the cache interceptor reads Redis synchronously; Redisson refuses that on `redisson-netty`, so the cache never hits
  there)
- sync HTTP: a Jersey `Invocation.Builder.get/post/put/delete` without `.async()` or `.rx()`, and `WorkspaceNameService`
- `InetAddress.getAllByName`, `Thread.sleep`, `ChunkedOutput.write`, `OutputStream.write` to a response
- `.block()`, `.toFuture().join()`, `CompletableFuture.get()` / `join()`

When unsure, open the callee. A name never proves a method is non-blocking.

## Rules

1. **A blocking call wraps itself.** Every blocking call runs inside its own `Offload.blocking(...)`, with the hop
   directly on the source. Never rely on the upstream thread: a blocking `fromCallable` that lands on
   `boundedElastic` today only because the step before emits there moves with the next refactor. Wrap only the call,
   never a whole chain. Only the edges may block directly: a resource method body outside the chain, a job's entry point,
   and an event-bus listener. A service never knows which thread calls it.
2. **Nothing blocks on an event loop, the driver pools or `parallel`.** Never block on `redisson-netty`,
   `ClickHouseWorker`, `chc-operation` or `parallel`. `block()` belongs only at the edges: a resource method, a job's entry
   point, or an event-bus listener. Reactor throws for `block()` on `parallel`; the other pools fail silently by queueing.
3. **CPU that scales with the input gets its own step.** Light work may run where it lands. Work that grows with the
   batch, page or payload (serializing a batch, building CSV, clustering, vector math, sorting a window, parsing large
   JSON) is an `Offload.cpu(...)`, also right after a blocking call: every MySQL, S3 and sync HTTP call waits for a
   free `boundedElastic` thread. CPU that runs for seconds also needs a concurrency cap at its caller (`flatMap(...,
   n)` or a semaphore), because `parallel` also runs every timer. Never route CPU to a subscriber's workers: that pool
   is sized for waiting.
4. **Per-item work on a stream moves once.** When each item of a `Flux` triggers a blocking side effect (a response
   chunk write, an S3 part, a per-item lookup), put one `Offload.blockingEach(...)` directly before that operator.
   Subscribing on a scheduler does not change where `onNext` runs.
5. **Timers and retries land on `parallel`.** A retry re-subscribes the source on `parallel`, so a source that blocks
   at subscription blocks there on every retry. Make the retried source an `Offload.blocking(...)`, inside the retry.
6. **Services don't create schedulers.** Pools sized for a purpose (subscriber consumer and workers, inference pools)
   live in infrastructure, are bounded and named. Only the code that writes the context picks a pool, through `Offload.originalOn`: a subscriber, or
   `AsyncUtils.setRequestContext` for request paths. Never `publishOn` mid-chain to move one step: it moves every step after it.

## How to Apply

For each chain you write or change:

1. **List the steps** in order: every client call (ClickHouse, Redis, MySQL, S3, HTTP) and every piece of your own
   logic between them.
2. **Name the thread** that emits into each step, from the table above.
3. **Classify each piece of your own logic**: blocking, heavy CPU or light.
4. **Pick the call**: blocking → `Offload.blocking`; heavy CPU → `Offload.cpu`; per-item blocking on a stream →
   `Offload.blockingEach`; light → nothing. Wrap the smallest piece that does the work.
5. **In a job or subscriber**, decide whether your logic after the last client call should run on the entry point's
   pool. If so, add one `resumeOriginal` there, and make sure the entry point sets `originalOn`.
6. **Validate** (below) before you open the PR.

## Examples

### S3 after a ClickHouse read

```java
// ✅ GOOD - the S3 call carries its own hop; the driver thread is released when the read replies
return attachmentDAO.getAttachmentsByEntityIds(entityType, entityIds, containerId)
        .flatMap(attachments -> Offload.blocking(() -> fileService.deleteObjects(keys(attachments))))
        .then(attachmentDAO.deleteByEntityIds(entityType, entityIds, containerId));

// ❌ BAD - every S3 round-trip holds a ClickHouseWorker thread
return attachmentDAO.getAttachmentsByEntityIds(entityType, entityIds, containerId)
        .flatMap(attachments -> Mono.fromRunnable(() -> fileService.deleteObjects(keys(attachments))))
        .then(attachmentDAO.deleteByEntityIds(entityType, entityIds, containerId));
```

### MySQL after a Redis reply

```java
// ✅ GOOD
return bucketService.readyBuckets()
        .flatMap(bucket -> Offload.blocking(() -> alertService.getByIdAndWorkspace(bucket.alertId(), bucket.workspaceId())));

// ❌ BAD - a MySQL transaction on the netty event loop stalls every Redis reply on its connections
return bucketService.readyBuckets()
        .flatMap(bucket -> Mono.fromCallable(() -> alertService.getByIdAndWorkspace(bucket.alertId(), bucket.workspaceId())));
```

### A hidden blocking call: a `@Cacheable` getter or sync HTTP

```java
// ✅ GOOD - the lookup may hit Redis synchronously or make an HTTP call
return metricsDAO.aggregate(alert)
        .flatMap(metrics -> Offload.blocking(() -> workspaceNameService.getWorkspaceName(workspaceId))
                .map(name -> payload(metrics, name)));

// ❌ BAD - looks like a getter, runs a sync HTTP call on ClickHouseWorker
return metricsDAO.aggregate(alert)
        .map(metrics -> payload(metrics, workspaceNameService.getWorkspaceName(workspaceId)));
```

### Heavy CPU: serializing a batch

```java
// ✅ GOOD - serialization grows with the batch, so it leaves the blocking pool
return Offload.cpu(() -> serialize(items, rowMapper))
        .flatMap(body -> send(table, logComment, body::writeTo));

// ❌ BAD - a large batch holds a boundedElastic thread that MySQL and S3 calls are waiting for
return Mono.fromCallable(() -> serialize(items, rowMapper))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(body -> send(table, logComment, body::writeTo));
```

### Streaming a response

```java
// ✅ GOOD - parsing, redaction and the chunk write leave the driver thread
Offload.blockingEach(flux)
        .doOnNext(item -> sendItem(item, outputStream, rules));

// ❌ BAD - subscribeOn moves the subscription, not the items: a slow client holds a ClickHouseWorker
Schedulers.boundedElastic().schedule(() -> flux.doOnNext(item -> sendItem(item, outputStream, rules)).subscribe());
```

### Retrying a blocking source

```java
// ✅ GOOD - every attempt runs on boundedElastic
return Offload.blocking(() -> httpClient.send(request))
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));

// ❌ BAD - after the first backoff, every attempt blocks a parallel thread
return Mono.fromCallable(() -> httpClient.send(request))
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
```

### A subscriber: back to its pool for its own logic

```java
// Entry point (BaseRedisSubscriber): set the pool once
Mono.defer(() -> processEvent(message, messageId))
        .subscribeOn(workersScheduler)
        .contextWrite(context -> Offload.originalOn(context, workersScheduler));

// Service: no resume between client calls; one resume before our own logic
return getWorkspaceTimeout(now, timeout)
        .zipWith(getLookbackBound(now, timeout))                        // Redis
        .flatMapMany(bounds -> threadDAO.streamPendingClosure(bounds))  // next is a query: no resume
        .transform(Offload::resumeOriginal)                             // our logic starts: back to workers
        .flatMap(this::closeAndEnqueue);
```

### What not to hop

```java
// ✅ GOOD - mapping a row and posting an event are light: no hop
return dao.insert(batch)
        .map(count -> new BatchResult(count))
        .doOnSuccess(result -> eventBus.post(new SpansCreated(ids, workspaceId, userName)));

// ❌ BAD - two handoffs that buy nothing
return dao.insert(batch)
        .publishOn(Schedulers.parallel())
        .map(count -> new BatchResult(count))
        .publishOn(Schedulers.boundedElastic())
        .doOnSuccess(result -> eventBus.post(new SpansCreated(ids, workspaceId, userName)));
```

## Validate

### 1. Read your diff

List the operators and sources your change adds, then check each blocking or heavy step against the rules:

```bash
git diff origin/main -U0 -- 'apps/opik-backend/src/main/**/*.java' \
  | grep -nE '^\+.*(Mono\.(fromCallable|fromRunnable|fromSupplier|create)|\.block\(\)|\.join\(\)|\.get\(\)|Schedulers\.|publishOn|subscribeOn)'
git diff origin/main -U0 -- 'apps/opik-backend/src/main/**/*.java' \
  | grep -nE '^\+.*(fileService\.|transactionTemplate\.|template\.inTransaction|getWorkspaceName|Thread\.sleep|InetAddress\.|\.write\()'
```

Every hit is either inside an `Offload` call, at the edge, or light work you can name the thread for.

### 2. Record which thread does the I/O

Run the integration test that drives your change under Java Flight Recorder with socket events on, then list every
blocking client call (S3, MySQL, JDBI, sync Jersey, DNS) made from a shared thread, with the Opik frames that made it:

```bash
cd apps/opik-backend
mvn test-compile
mvn surefire:test -Dtest=YourResourceTest -DreuseForks=false \
  "-DargLine=-XX:FlightRecorderOptions:stackdepth=512 -XX:StartFlightRecording:filename=/tmp/threads.jfr,dumponexit=true,jdk.SocketRead#threshold=0ms,jdk.SocketWrite#threshold=0ms"
jfr print --json --stack-depth 512 --events jdk.SocketRead,jdk.SocketWrite /tmp/threads.jfr | jq -r '.recording.events[].values
  | select(.eventThread.javaName | test("^(ClickHouseWorker|chc-operation|redisson-netty|parallel)"))
  | [.stackTrace.frames[]? | "\(.method.type.name | gsub("/"; ".")).\(.method.name):\(.lineNumber)"] as $f
  | ($f | map(select(test("^(software\\.amazon\\.awssdk|com\\.mysql|org\\.jdbi|org\\.glassfish\\.jersey\\.client|java\\.net\\.InetAddress)")))) as $b
  | select($b | length > 0)
  | "\(.eventThread.javaName | sub("-?[0-9]+$"; ""))\t\($b[0] | split(".")[0:3] | join("."))\t\([$f[] | select(startswith("com.comet.opik") and (contains("$$Lambda") | not))][0:2] | join(" <- "))"' \
  | sort | uniq -c
```

An empty result means no blocking client call ran on a shared thread in that test. Each line is a violation, for
example the S3 delete this check found in `AttachmentResourceMinIOTest`:

```text
1 ClickHouseWorker  software.amazon.awssdk  com.comet.opik.domain.attachment.FileServiceImpl.deleteObjects:220 <- com.comet.opik.domain.attachment.AttachmentServiceImpl.lambda$deleteByEntityIds$3:272
```

It sees only what the test drives, and only I/O made through sockets: it does not catch heavy CPU or a
`Thread.sleep`, so keep step 1 for those.

### 3. Prove a new helper with a thread test

When you add a scheduling helper, or change `Offload`, assert the thread from inside the step: let a source emit on a
named single-thread scheduler that stands in for the client, and check where the next step runs. `OffloadTest` does
this for every `Offload` method.

## Review

**Critical: always request changes** when a diff adds or moves:

1. A blocking call inside an operator lambda (`map`, `flatMap`, `filter`, `handle`, `doOnNext`, `doOnSuccess`,
   `then(...)`), or inside `Mono.fromCallable` / `fromRunnable` / `fromSupplier` / `Mono.create` with no
   `Offload.blocking` directly on it.
2. A per-item blocking call or chunk write on a `Flux` from a DAO or Redis with no `Offload.blockingEach` before it.
3. `.block()`, `.toFuture().join()` or `CompletableFuture.get()` / `join()` inside a reactive chain.

Each of these shapes has shipped and held a shared thread in tests: S3 deletes on `ClickHouseWorker`, a MySQL
transaction and a sync HTTP call on `redisson-netty`, response chunks written on `ClickHouseWorker`.

**Checklist** for every new or changed chain:

1. Which thread emits into each step?
2. Does any step block? Is it an `Offload.blocking(...)`?
3. Does any step do CPU that grows with the input? Is it an `Offload.cpu(...)`, with a cap if it runs for seconds?
4. Does a `Flux` do per-item I/O? Is there an `Offload.blockingEach(...)` right before it?
5. Does a `timeout`, `delay` or retry precede blocking work?
6. Is there a `block()` anywhere but at the edge?
7. Is there a hop that moves only light work? Remove it.
