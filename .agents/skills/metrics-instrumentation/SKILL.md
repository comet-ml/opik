---
name: metrics-instrumentation
description: Specification for instrumenting an opik-backend workflow with operational OpenTelemetry metrics — per-stage throughput/latency/error counters and native histograms, dimensioned per-customer (workspace). Use when a pipeline (scoring, ingestion, experiments, jobs) needs per-stage visibility. Covers metric emission only; building the Grafana dashboard from these metrics is a separate skill. Distinct from analytics-instrumentation (PostHog product events).
---

# Metrics Instrumentation

Normative spec for the backend half of operational observability: per-stage OpenTelemetry metrics in `apps/opik-backend`. The metrics are designed so a flow-ordered Grafana dashboard can read top-to-bottom — the failing stage is the one whose numbers break — with a per-workspace drill. Building that dashboard (layout, query contracts, per-customer name resolution, dependency panels, validation) is a separate concern, specified by the dashboard-authoring skill in comet monitoring tooling; this skill covers only what to emit.

Patterns applied in this implementation (online scoring is the worked example):
- **Producer metrics** — every stage that emits work is counted at the source: sampler decisions (`sampler_decisions_total{decision}`) and enqueue-to-Redis (`enqueue_total{result}`). A producer that emits nothing makes downstream starvation explainable rather than mysterious.
- **Consumer metrics** — throughput and per-stage timing on the side that drains the queue (`processing_time`, plus per-Redis-op `read/claim/ack_and_remove/list_pending_time`).
- **Entrypoint RED** — the workflow's front door (the HTTP ingest route) is measured Rate / Errors / Duration from `http_server_request_duration_seconds`, with 5xx broken down by endpoint × `error_type` × workspace, so an ingestion problem is never mistaken for a scoring problem.
- **Errors** — a dedicated error counter per stage, dimensioned by `error_type` (the exception class) and, for shared async plumbing, by component (listener/subscriber): `processing_errors_total`, `unexpected_errors_total`, and `enqueue_total{result="error"}` (a push failure = real loss).
- **Success** — success is derived, never double-counted: throughput − errors, surfaced as one "success rate" headline tile.
- **Queue time & end-to-end latency** — `queue_delay` (enqueue→pickup) is kept separate from `processing_time` (scorer/LLM work) so a backlog is distinguishable from a slow scorer; end-to-end = queue_delay + processing_time.
- **Backpressure** — poll-tick skips are counted but are benign (consumer busy), never lost work.
- **Saturation & resource levels** — gauges for in-flight work (max per pod) and JVM heap used-vs-limit per pod expose the pipeline approaching a ceiling *before* it starts failing (the USE method — utilization / saturation / errors — complementing RED).
- **Volume & payload size** — byte/char counters (bandwidth, total bytes) and payload-size distributions, broken down by content type and workspace, for cost and impact attribution.
- **Per-workspace dimensioning** — the customer drill (§1.3) is a first-class label, not an afterthought.
- **Infrastructure dependencies** — the datastores the flow leans on (Redis streams, ClickHouse, locks, MySQL) are surfaced on the dashboard from their exporters and `system.query_log`, so a "slow pipeline" resolves to the dependency causing it.

To see these conventions already in practice, grep `apps/opik-backend` for the existing metric families rather than specific classes (metric names are the stable contract; class locations move): the online-scoring `*_sampler_decisions_total`, `*_enqueue_total{result}`, `*_processing_time_milliseconds`, `*_queue_delay_milliseconds`, `*_processing_errors_total{error_type}` / `*_unexpected_errors_total`, the per-Redis-op `*_{read,claim,ack_and_remove,list_pending}_time_milliseconds`, and the attachment upload byte/size families.

Apply alongside the `opik-backend` skill (general conventions, logging rule).

---

## 1. Model

1.1 Decompose the workflow into ordered stages, each defined by: input, output, failure mode, existing instrumentation.

1.2 Choose the instrument by the question it answers. Most stages need a **counter** (did it happen, and how often?) and a **histogram** (how long did it take?); add a **gauge** only for a level you cannot derive from those.

- **Counter** — a monotonically increasing total, read as a rate. Use for **events**: throughput, decisions, results, errors, and cumulative volume (messages, bytes). Anything you phrase "per second" or "how many since start". Surfaces with a `_total` suffix. MUST NOT be used for a value that can decrease.
- **Native histogram** — a latency/size distribution, read with `histogram_quantile`. Use whenever a **p95/p99 matters, not just an average**: processing time, queue delay, per-dependency op time, end-to-end latency, and payload size (bytes/chars). Prefer native (no explicit buckets, §2.1) over classic `le`-bucketed histograms; reach for classic buckets only when a downstream consumer (e.g. an existing exporter) forces them. Do NOT use a histogram where a counter suffices — a plain success/error tally needs no distribution.
- **Gauge** — an instantaneous level, read as-is. Use for a quantity that **rises and falls and can't be reconstructed from a counter**: current queue depth / backlog, in-flight / in-progress count (saturation), batch/read/claim size, lock waiters, heap used. If you actually want a trend, count events with a counter rather than sampling a level with a gauge.
- **UpDownCounter** (OTel) — when the level is naturally maintained as signed +1/−1 deltas (in-flight = increment on start, decrement on finish) rather than sampled. Exports like a gauge but is cheaper and less racy than reading a size on every observation.

Rule of thumb: "how many happened" → counter; "how long / how big" → histogram; "how much is there right now" → gauge (or UpDownCounter). Cover each stage with **RED** (Rate, Errors, Duration) for the work flowing through it and **USE** (Utilization, Saturation, Errors) for the resource it runs on.

1.3 Define identity dimensions, each bounded in cardinality:
- `workspace_id` **and** `workspace_name` — the customer drill. Always paired; `workspace_name` falls back to `workspace_id` when the name is absent (§2.3).
- a **stage/type** label — what kind of work this is (`evaluator_type`, `decision`, the Redis op, content/mime type). Lets the dashboard `sum by(...)` per stage (§2.2).
- `result` ∈ {`success`, `error`} on outcome counters.
- `error_type` on every error counter — the exception class / failure category — plus a component label (listener/subscriber/endpoint) where one counter serves many call sites, so the outcome panel breaks errors down by both cause and origin.

Cardinality MUST stay bounded by `#workspaces × #types × #error_types`; an unbounded value (trace id, user input, raw message, full URL) MUST NOT be placed on a label.

---

## 2. Backend metrics (OpenTelemetry)

2.1 Meters MUST be created via the OTel API (`GlobalOpenTelemetry.getMeter(namespace)`), one namespace per workflow:
```java
private static final String METRIC_NAMESPACE = "<workflow>";
var meter = GlobalOpenTelemetry.getMeter(METRIC_NAMESPACE);
meter.counterBuilder("%s_<stage>_total".formatted(METRIC_NAMESPACE)).setDescription("…").build();
meter.histogramBuilder("%s_<stage>_time".formatted(METRIC_NAMESPACE)).setUnit("ms").ofLongs().build(); // native histogram
meter.gaugeBuilder("%s_<stage>_size".formatted(METRIC_NAMESPACE)).build();
meter.upDownCounterBuilder("%s_<stage>_in_flight".formatted(METRIC_NAMESPACE)).build(); // signed level (inc/dec)
```
Counters surface in Prometheus with a `_total` suffix. Native histograms MUST NOT define explicit buckets (no `_bucket`/`_sum`/`_count`/`le`).

2.2 The stage/type dimension MUST be a **label**, not part of the metric name (e.g. `online_scoring_enqueue_total{evaluator_type, result}`), so the dashboard can `sum by(evaluator_type)(rate(...))`. Name-encoding the dimension is permitted ONLY to extend an existing metric family; it complicates dashboard aggregation (a name-encoded dimension cannot be `rate()`d across names in one selector), so prefer a label.

2.3 `workspace_id` and `workspace_name` MUST be read from the reactive request context (the workspace-id / workspace-name context keys), reusing the shared workspace attribute-key constants rather than redeclaring them per call site:
```java
var workspaceId   = ctx.getOrDefault(WORKSPACE_ID, "");
var workspaceName = StringUtils.defaultIfBlank(ctx.getOrDefault(WORKSPACE_NAME, workspaceId), workspaceId);
counter.add(1, Attributes.of(TYPE_KEY, type, WORKSPACE_ID_KEY, workspaceId, WORKSPACE_NAME_KEY, workspaceName, RESULT_KEY, "success"));
```
- `workspace_name` MUST fall back to `workspace_id` when the name is absent.
- A name-service lookup MUST NOT be used to resolve the name on a path where the reactive context already carries it.
- If an event does not yet carry the name, add a nullable `workspaceName` field and populate it from the workspace-name context key at the publish site (as the entity-created events feeding this workflow already do).

2.4 The instrumented operation MUST be reactive and read the context with `deferContextual`:
```java
public Mono<Void> enqueue(List<?> messages, Type type) {
    return Flux.deferContextual(ctx -> { /* resolve workspace per 2.3 */
        return Flux.fromIterable(messages).flatMap(m -> redisAdd(m)
            .doOnNext(id -> counter.add(1, successAttrs))
            .doOnError(e -> { counter.add(1, errorAttrs); log.error("Error … id='{}'", id, e); }));
    }).then().subscribeOn(Schedulers.boundedElastic());
}
```
- The method MUST return `Mono<Void>` and MUST NOT self-subscribe. Callers MUST subscribe or compose it.
- Request-scoped reactive callers MUST compose it (`.then(...)` / `flatMap`) so it inherits the workspace context.
- Event-driven / synchronous callers MUST fire-and-forget via a single shared helper that subscribes with an explicit `.contextWrite(ctx -> ctx.put(WORKSPACE_ID, id).put(WORKSPACE_NAME, defaultIfBlank(name, id)))` and an error-logging consumer (one helper, not duplicated per caller).
- Blocking lookups (JDBC/`findById`) MUST run via `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. The enqueue/IO work MUST run on a bounded scheduler, not the caller's (e.g. EventBus) thread. Where a caller already holds a resolved object, provide an overload that skips the lookup.

2.5 Log statements MUST single-quote placeholders (`evaluator='{}' workspaceId='{}'`) per `.agents/skills/opik-backend/SKILL.md`, and SHOULD include the batch size on enqueue logs.

2.6 `mvn -o compile` MUST succeed (spotless clean) before delivery.

---

## 3. Tests

3.1 Introducing a `Mono<Void>` (lazy) return breaks tests that called the method for its side effect. Restore green by stubbing the reactive method (`lenient().when(pub.enqueue(any(),any())).thenReturn(Mono.empty())`) where production composes it, `.block()`-ing the returned `Mono` in unit tests that assert downstream effects, and replacing `verifyNoInteractions(mock)` with `verify(mock, never()).method(...)` where a lenient stub now exists.

---

## 4. Constraints (normative)

4.1 A returned `Mono` is inert until subscribed; an unsubscribed enqueue is a silent no-op. Every call site MUST compose or subscribe it.

4.2 The workspace MUST be sourced from the reactive context, not a name-service lookup, where the context carries it (§2.3).

4.3 IO/enqueue work MUST run on a bounded scheduler, off the caller's thread (§2.4).

4.4 A backpressure / poll-tick counter (e.g. `backpressure_drops_total`) counts skipped scheduler ticks while a consumer is busy; it is NOT lost work and MUST NOT be alerted on alone. Emit it, but document it as benign for whoever builds the dashboard.

4.5 Work MUST be done against `origin/main` (create the worktree from it), not a possibly-stale local checkout.

---

## 5. Delivery

5.1 The change is delivered as two PRs on their own branches `<user>/<TASK>-<name>`: this metrics PR (opik repo) and a companion dashboard PR built per the dashboard-authoring skill and delivered to comet monitoring.

5.2 The metrics PR (this repo) MUST contain no customer, cluster, or infrastructure identifiers, MUST follow `.github/pull_request_template.md` including a `## Documentation` section (the PR linter fails without it), `## Issues` (`Resolves OPIK-XXXX`), and `## AI-WATERMARK: yes` with tools/model/scope/human-verification. Title `[OPIK-XXXX] [BE] …`. Announce per `.agents/commands/comet/send-code-review-slack.md`.

5.3 The dashboard panel for a new metric stays empty until this backend PR deploys — so the two PRs are independent and can land in either order.
