---
name: metrics-instrumentation
description: Instrument an opik-backend workflow with operational OpenTelemetry metrics and a flow-ordered Grafana dashboard. Use when a pipeline (scoring, ingestion, experiments, jobs) is a black box and you need per-stage throughput/latency/error visibility plus a per-customer (workspace) drill. Distinct from analytics-instrumentation (PostHog product events).
---

# Metrics Instrumentation

Give a backend workflow end-to-end operational observability the way **online scoring** has it: per-stage OpenTelemetry metrics in `apps/opik-backend`, then a flow-ordered Grafana dashboard (in the internal `comet-monitoring` repo) with operational and per-customer views. The goal is a funnel you read top-to-bottom where the failing stage is where the numbers break.

Reference implementation (read these for live examples):
- `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/events/BaseRedisSubscriber.java` — consumer-side counters + native histograms.
- `apps/opik-backend/.../events/OnlineScoringSampler.java`, `OnlineScoringSpanSampler.java`, `OnlineScoringSamplerSupport.java` — producer counters + the fire-and-forget enqueue seam.
- `apps/opik-backend/src/main/java/com/comet/opik/domain/evaluators/OnlineScorePublisher.java` — reactive enqueue returning `Mono<Void>` + the enqueue counter.
- `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/metrics/ErrorMetricsResolver.java` — shared metric label keys + workspace resolution.

Pair this with the `opik-backend` skill for general backend conventions and `.agents/skills/opik-backend/SKILL.md`'s logging rule.

---

## Phase 0 — Map the workflow into stages

Read the code and draw the pipeline as ordered stages, each with: what enters, what leaves, where it can fail, what's already instrumented. Online scoring's stages were **Ingestion → Sampling → Enqueue (push to Redis) → Queue processing → Outcome**. The funnel shape is the goal: the dashboard reads top-to-bottom so the broken stage is obvious.

Per stage, pick the signal type:
- **counter** for "how many" (throughput, decisions, errors, results),
- **native histogram** for "how long" (latency, queue delay),
- **gauge** for "how full" (batch / queue size).

Pick identity dimensions that drill without exploding cardinality: `workspace_id` + `workspace_name` (the customer drill), a stage/type label, a `result` (success|error). Bounded by #workspaces × #types.

---

## Phase 1 — Backend metrics (OpenTelemetry)

**1.1 Build meters with the OTel API** (not Micrometer), one meter per namespace:
```java
private static final String METRIC_NAMESPACE = "<workflow>";   // e.g. online_scoring
var meter = GlobalOpenTelemetry.getMeter(METRIC_NAMESPACE);
meter.counterBuilder("%s_<stage>_total".formatted(METRIC_NAMESPACE)).setDescription("…").build();      // counter
meter.histogramBuilder("%s_<stage>_time".formatted(METRIC_NAMESPACE)).setUnit("ms").ofLongs().build();  // NATIVE histogram (no buckets)
meter.gaugeBuilder("%s_<stage>_size".formatted(METRIC_NAMESPACE)).build();                              // gauge
```
Counters surface in Prometheus with a `_total` suffix; native histograms have **no** `_bucket`/`_sum`/`_count`/`le`.

**1.2 Encode the stage/type dimension as a LABEL, not the metric name.** The legacy online-scoring metrics bake the scorer into the name (`online_scoring_<scorer>_processing_time`), which forces awkward multi-name `rate()` workarounds on the dashboard (see Gotchas). For new metrics prefer a label — e.g. `online_scoring_enqueue_total{evaluator_type, result}` — so the dashboard can simply `sum by(evaluator_type)(rate(...))`. Only use the name when joining an existing metric family.

**1.3 Get `workspace_id` + `workspace_name` from the REACTIVE CONTEXT, never a service lookup.** The ingest endpoint already puts `RequestContext.WORKSPACE_ID` / `WORKSPACE_NAME` on the request context. Thread them through events → messages so the metric site reads them. Reuse `ErrorMetricsResolver.WORKSPACE_ID_KEY` / `WORKSPACE_NAME_KEY` and these fallbacks:
```java
var workspaceId   = StringUtils.defaultIfBlank(ctx.getOrDefault(RequestContext.WORKSPACE_ID, UNKNOWN), UNKNOWN);
var workspaceName = StringUtils.defaultIfBlank(ctx.getOrDefault(RequestContext.WORKSPACE_NAME, workspaceId), workspaceId);
counter.add(1, Attributes.of(TYPE_KEY, type, WORKSPACE_ID_KEY, workspaceId, WORKSPACE_NAME_KEY, workspaceName, RESULT_KEY, "success"));
```
- `workspace_name` falls back to the **id**, never the literal `"unknown"` — a blank name from the context must resolve to the id so the dashboard's id-fallback works.
- If an event doesn't carry the name yet, add a nullable `workspaceName` and populate it from `ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "")` at the publish site — that's how `TracesCreated` / `SpansCreated` do it. **Do not** add a `WorkspaceNameService` lookup on the producer path when the context already has the value.

**1.4 Make the instrumented path reactive; read the context with `deferContextual`:**
```java
public Mono<Void> enqueue(List<?> messages, Type type) {
    return Flux.deferContextual(ctx -> { /* resolve workspace as in 1.3 */
        return Flux.fromIterable(messages).flatMap(m -> redisAdd(m)
            .doOnNext(id -> counter.add(1, successAttrs))
            .doOnError(e -> { counter.add(1, errorAttrs); log.error("Error … id='{}'", id, e); }));
    }).then();
}
```
- Return `Mono<Void>` — **do not self-subscribe.** A Mono does nothing until subscribed, so every caller must subscribe/compose or the work silently stops (and unit tests that `verify(...)` the call break — Phase 3).
- **Reactive, request-scoped callers** compose it (`.then(...)` / `flatMap`) so it inherits `WORKSPACE_NAME` from the request context.
- **Event-driven / synchronous callers** (EventBus handlers) fire-and-forget: subscribe with an explicit `.contextWrite(ctx -> ctx.put(WORKSPACE_ID, id).put(WORKSPACE_NAME, defaultIfBlank(name, id)))` and an error-logging consumer. Extract this seam into one shared helper so multiple samplers don't duplicate it (see `OnlineScoringSamplerSupport.publishSampled`).
- Wrap blocking lookups (`findById`, JDBC) in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. If a caller already holds the resolved object, add an overload that skips the lookup.

**1.5 Logging:** single-quote every placeholder per `.agents/skills/opik-backend/SKILL.md` — `log.error("… evaluator='{}' workspaceId='{}'", type, workspaceId, error)`.

**1.6 Verify:** `cd apps/opik-backend && mvn -o compile` → BUILD SUCCESS (spotless clean).

---

## Phase 2 — Grafana dashboard (in `comet-monitoring`, internal repo)

Generate it with a small Python generator; commit the JSON under `dashboards/comet/<workflow>.json`. Layout = the funnel.

- **One row per stage** + a **glance strip** of `stat` tiles (one per stage; the red tile marks the break) + a **per-customer funnel** row at the bottom + a short `text` "what happens here / what a problem looks like" per stage.
- **Native-histogram PromQL:** `histogram_quantile(0.95, sum(rate(m[$__rate_interval])))` (NO `by(le)`); throughput `histogram_count(rate(m[...]))`.
- **Multi-name `rate()` pitfall** (only if you baked type into the name): `rate({__name__=~"a|b"})` fails ("vector cannot contain metrics with the same labelset"). Use per-target sums, guarded scalar sums `(sum(rate(A)) or vector(0)) + …`, or `sum by(G)(label_replace(sum by(G)(rate(A)),"_x","0",..) or …)`. **Avoid it by using a label (1.2).**
- **Error panels: raw `sum(_total)`, not `increase()`/`$__rate_interval`** (rate intervals inflate short windows). Note the reset-on-restart caveat.
- **Per-customer name resolution** — names live in Prometheus / MySQL, never in ClickHouse:
  - Prometheus panels carry `workspace_name` → `sum by(workspace_name)(rate(...{workspace_id=~"$ws"}))`.
  - ClickHouse instant tables/pies: resolve in-query with `transform(workspace_id, ['id1',…], ['name1',…], workspace_id)` from a **baked id→name map** captured at generate-time (`group by(workspace_id,workspace_name)(last_over_time({__name__=~"…_total", NS}[14d]))`). Build-time snapshot — unmapped/new workspaces show the id; regenerate to refresh.
  - A Grafana mixed-datasource `joinByField` on `workspace_id` works for **instant** queries, but a **timeseries can't be join-relabeled** (its series key *is* the id) — timeseries must use the in-query `transform()`.
  - vertamedia ClickHouse macros: `$timeSeries`, `$timeFilter`, `$columns(key, value)`, and `$from`/`$to` (epoch-ms; per-minute = `count()*60000/($to-$from)`).
- **Workspace filter:** a single Grafana **custom** var `ws` (text = name, value = workspace_id, `"All" → ".*"`) from the same baked map. CH panels filter `('$ws'='.*' OR workspace_id='$ws')`; Prometheus panels filter `workspace_id=~"$ws"`. No raw-UUID picker.

---

## Phase 3 — Validate, then fix the tests

- **Validate every panel query against prod through the Grafana proxy before committing** — Prometheus `/api/v1/query`, ClickHouse `?query=` with macros manually substituted (`$timeFilter`→concrete `created_at >=` range, `$to-$from`→ms, `$ws`→`.*`). A 400 or wrong-shape result is the most common defect.
- New metrics return empty until the backend deploys — confirm the PromQL is **syntactically valid** (`status:"success"`, empty result) and label the panel "activates after deploy".
- **Backend tests:** making a method return `Mono<Void>` (lazy) breaks tests that called it for its side effect. Fix by: stub the reactive method (`lenient().when(pub.enqueue(any(),any())).thenReturn(Mono.empty())`) where production code chains on it; `.block()` the returned Mono in unit tests that assert on downstream effects; replace `verifyNoInteractions(mock)` with `verify(mock, never()).method(...)` where a lenient stub now exists. Run the affected `*Test` classes green.

---

## Phase 4 — Deliver (two PRs)

- **opik (this repo, PUBLIC):** the metrics PR. No customer names / cluster / RDS identifiers. Follow `.github/pull_request_template.md` — include `## Details`, `## Change checklist`, **`## Documentation`** (the PR linter fails without it), `## Issues` (`Resolves OPIK-XXXX`), `## AI-WATERMARK: yes` (tools/model/scope/human-verification), `## Testing`. Title `[OPIK-XXXX] [BE] …`. Announce with `.agents/commands/comet/send-code-review-slack.md` → #code-review.
- **comet-monitoring (INTERNAL):** the dashboard PR. Workspace/customer names in the baked map are acceptable there. Commit the JSON to `dashboards/comet/`. The panel for a new metric ships now (filter by `$ws`) but stays empty until the backend deploys.
- Use a worktree per repo; branch `<user>/<TASK>-<name>`.

---

## Gotchas (hard-won from online scoring)

- **Lazy Mono = silent no-op.** Returning `Mono<Void>` without a subscriber stops the work. Compose or fire-and-forget-subscribe at every call site.
- **`workspace_name` default must be the id, not `"unknown"`** — else `defaultIfBlank(name, id)` never triggers and you emit `workspace_name=unknown`.
- **ClickHouse has no workspace name** anywhere (`opik_prod.workspace_configurations` holds only `workspace_id`); the full id→name map is in MySQL, not reachable from Grafana — hence the baked `transform()` snapshot.
- **A timeseries can't be name-joined** at the widget level (series key is the id) — bake the names in-query.
- **Multi-name `rate()` throws** — prefer a label over name-encoding to avoid it.
- **`backpressure_drops_total`-style poll-tick counters ≠ lost work** — they count skipped poll ticks while a consumer is busy; don't alarm on them. Real loss = rising queue delay + non-retryable errors + stream length vs `streamMaxLen`.
- **Don't self-subscribe in the publisher; don't add a name-service lookup when the reactive context already has the name; quote log placeholders.**
- **Instrument the current code:** create the worktree from `origin/main`, not a possibly-stale local checkout.
