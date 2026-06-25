---
name: metrics-instrumentation
description: Specification for instrumenting an opik-backend workflow with operational OpenTelemetry metrics and a flow-ordered Grafana dashboard. Use when a pipeline (scoring, ingestion, experiments, jobs) needs per-stage throughput/latency/error visibility and a per-customer (workspace) drill. Distinct from analytics-instrumentation (PostHog product events).
---

# Metrics Instrumentation

Normative spec for adding operational observability to a backend workflow: per-stage OpenTelemetry metrics in `apps/opik-backend`, plus a flow-ordered Grafana dashboard (maintained in the deployment/monitoring configuration, outside this repo). The deliverable is a funnel that reads top-to-bottom so the failing stage is the one whose numbers break, and that supports a per-workspace drill.

Conformant reference implementations:
- `apps/opik-backend/.../events/BaseRedisSubscriber.java` — consumer counters + native histograms.
- `apps/opik-backend/.../events/OnlineScoringSampler.java`, `OnlineScoringSpanSampler.java`, `OnlineScoringSamplerSupport.java` — producer counters + the shared fire-and-forget enqueue helper.
- `apps/opik-backend/.../domain/evaluators/OnlineScorePublisher.java` — reactive enqueue (`Mono<Void>`) + counter.
- `apps/opik-backend/.../infrastructure/metrics/ErrorMetricsResolver.java` — shared metric label keys.

Apply alongside the `opik-backend` skill (general conventions, logging rule).

---

## 1. Model

1.1 Decompose the workflow into ordered stages, each defined by: input, output, failure mode, existing instrumentation.

1.2 Assign each stage a signal type: **counter** (counts: throughput, decisions, errors, results), **native histogram** (durations: latency, queue delay), or **gauge** (levels: batch/queue size).

1.3 Define identity dimensions bounded in cardinality: `workspace_id` + `workspace_name` (customer drill), a stage/type label, and `result` ∈ {`success`, `error`}. Cardinality MUST stay bounded by `#workspaces × #types`.

---

## 2. Backend metrics (OpenTelemetry)

2.1 Meters MUST be created via the OTel API (`GlobalOpenTelemetry.getMeter(namespace)`), one namespace per workflow:
```java
private static final String METRIC_NAMESPACE = "<workflow>";
var meter = GlobalOpenTelemetry.getMeter(METRIC_NAMESPACE);
meter.counterBuilder("%s_<stage>_total".formatted(METRIC_NAMESPACE)).setDescription("…").build();
meter.histogramBuilder("%s_<stage>_time".formatted(METRIC_NAMESPACE)).setUnit("ms").ofLongs().build(); // native histogram
meter.gaugeBuilder("%s_<stage>_size".formatted(METRIC_NAMESPACE)).build();
```
Counters surface in Prometheus with a `_total` suffix. Native histograms MUST NOT define explicit buckets (no `_bucket`/`_sum`/`_count`/`le`).

2.2 The stage/type dimension MUST be a **label**, not part of the metric name (e.g. `online_scoring_enqueue_total{evaluator_type, result}`), so the dashboard can `sum by(evaluator_type)(rate(...))`. Name-encoding the dimension is permitted ONLY to extend an existing metric family, and triggers the §6.3 constraint.

2.3 `workspace_id` and `workspace_name` MUST be read from the reactive context (`RequestContext.WORKSPACE_ID` / `WORKSPACE_NAME`), reusing `ErrorMetricsResolver.WORKSPACE_ID_KEY` / `WORKSPACE_NAME_KEY`:
```java
var workspaceId   = StringUtils.defaultIfBlank(ctx.getOrDefault(RequestContext.WORKSPACE_ID, UNKNOWN), UNKNOWN);
var workspaceName = StringUtils.defaultIfBlank(ctx.getOrDefault(RequestContext.WORKSPACE_NAME, workspaceId), workspaceId);
counter.add(1, Attributes.of(TYPE_KEY, type, WORKSPACE_ID_KEY, workspaceId, WORKSPACE_NAME_KEY, workspaceName, RESULT_KEY, "success"));
```
- `workspace_name` MUST fall back to `workspace_id`, never to the literal `"unknown"`.
- A service lookup (e.g. `WorkspaceNameService`) MUST NOT be used to resolve the name on a path where the reactive context already carries it.
- If an event does not yet carry the name, add a nullable `workspaceName` and populate it from `ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "")` at the publish site (see `TracesCreated` / `SpansCreated`).

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

## 3. Dashboard (Grafana)

The dashboard is maintained in the deployment/monitoring configuration, not in this repo; this section specifies the query and layout contract the metrics must satisfy.

3.1 The dashboard MUST be generated (committed JSON) and laid out as the funnel: one row per stage, a glance strip of `stat` tiles (one per stage, red = the break), a per-customer funnel row, and a per-stage `text` panel describing the stage and its failure signature.

3.2 Native-histogram queries MUST use `histogram_quantile(0.95, sum(rate(m[$__rate_interval])))` (no `by(le)`) for percentiles and `histogram_count(rate(m[...]))` for throughput.

3.3 Error-count panels MUST use raw `sum(<metric>_total)`, not `increase()`/`$__rate_interval` (which inflates short windows). The reset-on-restart caveat applies.

---

## 4. Per-customer name resolution

4.1 Workspace names exist only in Prometheus labels and the metadata DB; the ClickHouse analytics tables key on `workspace_id` only (no name column).

4.2 Prometheus panels MUST resolve names via the `workspace_name` label: `sum by(workspace_name)(rate(...{workspace_id=~"$ws"}))`.

4.3 ClickHouse panels MUST resolve names in-query via `transform(workspace_id, ['id1',…], ['name1',…], workspace_id)`, keyed off an id→name map captured at generate time (`group by(workspace_id,workspace_name)(last_over_time({__name__=~"…_total", NS}[14d]))`). This map is a build-time snapshot; unmapped/new workspaces render as the id and a regenerate refreshes it.

4.4 A Grafana mixed-datasource `joinByField` on `workspace_id` MAY be used for **instant** ClickHouse tables, but MUST NOT be used for timeseries (a timeseries series key is the id and cannot be join-relabeled) — timeseries MUST use the in-query `transform()`.

4.5 The dashboard MUST expose a single Grafana **custom** variable `ws` (text = name, value = `workspace_id`, `"All" → ".*"`) built from the same map. ClickHouse panels filter `('$ws'='.*' OR workspace_id='$ws')`; Prometheus panels filter `workspace_id=~"$ws"`. A raw-UUID picker MUST NOT be used.

4.6 vertamedia ClickHouse macros: `$timeSeries`, `$timeFilter`, `$columns(key, value)`, `$from`/`$to` (epoch-ms; per-minute = `count()*60000/($to-$from)`).

---

## 5. Validation

5.1 Every panel query MUST be validated against a live datasource (via the Grafana datasource proxy) before delivery — Prometheus `/api/v1/query`, ClickHouse `?query=` with macros manually substituted (`$timeFilter`→a concrete `created_at >=` range, `$to-$from`→ms, `$ws`→`.*`).

5.2 A metric not yet deployed returns empty; the PromQL MUST still be syntactically valid (`status:"success"`, empty result) and the panel labelled "activates after deploy".

5.3 Tests: introducing a `Mono<Void>` (lazy) return breaks tests that called the method for its side effect. Restore green by stubbing the reactive method (`lenient().when(pub.enqueue(any(),any())).thenReturn(Mono.empty())`) where production composes it, `.block()`-ing the returned `Mono` in unit tests that assert downstream effects, and replacing `verifyNoInteractions(mock)` with `verify(mock, never()).method(...)` where a lenient stub now exists.

---

## 6. Constraints (normative)

6.1 A returned `Mono` is inert until subscribed; an unsubscribed enqueue is a silent no-op. Every call site MUST compose or subscribe it.

6.2 `workspace_name` MUST default to `workspace_id`, never `"unknown"`.

6.3 `rate()`/`increase()` over a multi-name selector (`{__name__=~"a|b"}`) is invalid ("vector cannot contain metrics with the same labelset"). When a dimension is name-encoded (§2.2), per-target sums, guarded scalar sums (`(sum(rate(A)) or vector(0)) + …`), or `sum by(G)(label_replace(sum by(G)(rate(A)),"_x","0",..) or …)` MUST be used instead.

6.4 ClickHouse timeseries MUST NOT rely on a widget-level join for names (§4.4).

6.5 A poll-tick / backpressure counter (e.g. `backpressure_drops_total`) counts skipped scheduler ticks while a consumer is busy; it MUST NOT be interpreted as lost work or alerted on alone. Loss/backlog is indicated by rising queue delay, non-retryable errors, and stream length vs `streamMaxLen`.

6.6 The workspace MUST be sourced from the reactive context, not a name-service lookup, where the context carries it (§2.3).

6.7 IO/enqueue work MUST run on a bounded scheduler, off the caller's thread (§2.4).

6.8 Work MUST be done against `origin/main` (create the worktree from it), not a possibly-stale local checkout.

---

## 7. Delivery

7.1 The change is delivered as two PRs — the metrics PR in this repo and the dashboard PR in the monitoring/deployment configuration repository — each on its own branch `<user>/<TASK>-<name>`.

7.2 The metrics PR (this repo) MUST contain no customer, cluster, or infrastructure identifiers, MUST follow `.github/pull_request_template.md` including a `## Documentation` section (the PR linter fails without it), `## Issues` (`Resolves OPIK-XXXX`), and `## AI-WATERMARK: yes` with tools/model/scope/human-verification. Title `[OPIK-XXXX] [BE] …`. Announce per `.agents/commands/comet/send-code-review-slack.md`.

7.3 The dashboard PR is delivered to the monitoring/deployment configuration repository. The new metric's panel ships in that cut (filtered by `$ws`) and stays empty until the backend deploys.
