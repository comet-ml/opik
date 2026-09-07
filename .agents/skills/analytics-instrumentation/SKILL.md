---
name: analytics-instrumentation
description: Add product analytics (BI) events to Opik features. Use when wiring events on the frontend, the backend, or the Python SDK - all three report through Segment to PostHog.
---

# Analytics Instrumentation

## Event Naming

All events MUST be prefixed with `opik_`. Segment routes `opik_*` events to PostHog. The tooling enforces this automatically, but event names defined in code should already include the prefix.

Examples: `opik_onboarding_agent_name_submitted`, `opik_eval_suite_created`, `opik_optimization_created`

## Frontend Events

### Files
- **Tracking utility**: `apps/opik-frontend/src/lib/analytics/tracking.ts` (mode-agnostic; safe to import from any project code)
- **Segment init**: `apps/opik-frontend/src/plugins/comet/analytics/index.ts` (comet-only)
- **Plugin init**: `apps/opik-frontend/src/plugins/comet/init.tsx` (comet-only)

### Adding a new event

1. Add the event name to the `OpikEvent` const in `tracking.ts`:
```typescript
export const OpikEvent = {
  ONBOARDING_AGENT_NAME_SUBMITTED: "opik_onboarding_agent_name_submitted",
} as const;
```

2. Call `trackEvent` from the component or hook where the action happens:
```typescript
import { trackEvent, OpikEvent } from "@/lib/analytics/tracking";

trackEvent(OpikEvent.ONBOARDING_AGENT_NAME_SUBMITTED, {
  agent_name: agentName,
});
```

### How it works
- `trackEvent()` safely no-ops when Segment isn't loaded (OSS mode)
- `opik_` prefix is enforced at runtime as a safety net
- `OPIK_ANALYTICS_ENVIRONMENT` is injected into event properties automatically by `trackEvent()`
- Frontend custom events flow through Segment (same pipeline as backend): Segment → PostHog
- PostHog still handles automatic pageviews, user identification, and feature flags directly

## Backend Events

### Files
- **Service**: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/bi/AnalyticsService.java`
- **Config**: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/AnalyticsConfig.java`
- **YAML config**: `apps/opik-backend/config.yml` (under `analytics:`)

### API
`AnalyticsService` exposes two overloads:

```java
void trackEvent(String eventType, Map<String, String> properties);
void trackEvent(String eventType, Map<String, String> properties, String identity);
```

- 2-arg resolves identity from the current request scope via `RequestContext`.
- 3-arg takes an explicit identity — use it any time the call executes outside a request scope (reactive schedulers, background threads, event listeners).

### How it works
- `trackEvent()` no-ops when `OPIK_ANALYTICS_ENABLED` is `false` (default).
- `opik_` prefix is auto-prepended if missing — but keep the prefix in code for grep-ability.
- `environment` property is auto-injected from `OPIK_ANALYTICS_ENVIRONMENT`.
- Events flow: Backend → comet-stats → Segment → PostHog.
- `AnalyticsService.sendEvent` wraps the body in `catch (RuntimeException)` — callers must not add their own try/catch.

### From a synchronous request handler

Inject and call inline. The 2-arg overload resolves identity from `RequestContext`.

```java
private final @NonNull AnalyticsService analyticsService;

analyticsService.trackEvent("opik_onboarding_first_trace",
        Map.of("trace_id", traceId, "project_id", projectId));
```

### From a reactive chain (`doOnSuccess`, `doOnNext`, etc.)

Two things are required: **offload with `Schedulers.boundedElastic()`** and **pass identity explicitly**.

**Why offload**: when identity is absent `AnalyticsService.resolveIdentity()` falls back to `UsageReportService.getAnonymousId()`, which is a synchronous JDBC read. Inside a `doOnSuccess` lambda that runs on the reactor event loop, that read blocks a scheduler-critical thread.

**Why explicit identity**: `RequestContext` is bound to the request thread via a Guice scope — inside the scheduler's lambda it throws `ProvisionException`, and you silently degrade to the anonymous-ID fallback, losing user attribution.

Capture `userName` up front from the reactor context alongside `workspaceId`, then pass both into the scheduled call:

```java
return Mono.deferContextual(ctx -> {
    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
    // Use getOrDefault on paths that internal/system callers reach without seeding USER_NAME
    // (e.g. a self-triggered cancellation written only with WORKSPACE_ID in the context).
    String userName = ctx.getOrDefault(RequestContext.USER_NAME, null);

    return someDao.write(...)
            .doOnSuccess(__ -> Schedulers.boundedElastic().schedule(
                    () -> analyticsService.trackEvent("opik_thing_happened",
                            Map.of(
                                    "thing_id", thing.id().toString(),
                                    "workspace_id", workspaceId),
                            userName)));
});
```

If you already depend on a `Schedulers.boundedElastic().schedule(() -> { ... })` block that does other non-reactive work (e.g. a blocking `datasetService.getById` like `ExperimentService.trackEvalSuiteRunIfApplicable`), add the `trackEvent` call inside that existing lambda instead of nesting another.

### Don'ts

- **Don't add try/catch around `trackEvent`** — `sendEvent` catches `RuntimeException` internally. Extra catches are noise and diverge from the codebase pattern.
- **Don't add helper methods that only delegate to `trackEvent`** — inline the call at the entry point. Wrap in a helper only when it encapsulates real logic (e.g. applicability check + enrichment + tracking).
- **Don't re-fetch ClickHouse rows to get "fresh" values for analytics payloads** — a write and a read-after-write can land on different replicas, so you may see a stale snapshot or even a spurious `NotFound`. Use the pre-write snapshot; some analytics drift is acceptable, a failed user-facing request is not.
- **Don't add unit tests that `verify(analyticsService)...`** — the codebase convention is for existing integration tests to exercise these paths organically. Sister analytics PRs (#6326 eval suite, #6333 onboarding, #6338 agent config) ship without emission assertions.
- **Don't assume `trackEvent` is fully non-blocking** — the Javadoc contract is aspirational; the identity-fallback path is synchronous JDBC today. Offload from reactive chains as shown above.

## Python SDK Events

### Files
`sdks/python/src/opik/analytics/` — `api.py` (public surface), `rules.py` (when
reporting is allowed), `worker.py` (background thread), `comet_stats.py` (the HTTP
call). Config lives in `sdks/python/src/opik/config.py`, prefixed `analytics_`.

**Identity and environment metadata are shared with Sentry error tracking**, not
reimplemented. Both live at the top level so neither subsystem depends on the other:
`opik/environment.py::get_user_identifier()` (workspace name, falling back to a
hostname/username hash) and `opik/environment_details.py` (`collect_tags_once()` /
`collect_context_once()`). Analytics and `error_tracking/before_send.py` both read
them, so an event carries the same user id, the same `session_id` and the same
environment details as any error report from the same run. Add environment metadata
there, not in either consumer.

### API

One function, called explicitly as the first line of whatever is being reported:

```python
from opik import analytics

analytics.track_event("client", "create_dataset")
analytics.track_event("integration", "openai")
analytics.track_event("evaluation", "metric_created", metric=name)
```

No decorators, by design: the payload is written out at the call site, so what gets
sent is whatever you can read right there.

The positional arguments form a **path**, broadest first, and go as deep as an event
needs:

```python
analytics.track_event("integration", "bedrock")                  # the integration
analytics.track_event("integration", "bedrock", "invoke_agent")  # one part of it
```

The first element is a closed set (`analytics.Component`): `client`, `evaluation`,
`integration` — extend it there rather than passing a new string. Every level after
it is free-form, and the second is normally just the method being reported.

A longer path is a **different event**, not a repeat of the shorter one, so
instrumenting part of a feature never silences the feature itself.

Names are composed by joining the path with a **double** underscore —
`opik_python_sdk__integration__bedrock__invoke_agent` — in one private helper, so the
scheme can be changed for every event at once without touching a call site. The
separator is doubled so the name splits back into the path: segments are method names,
so they contain single underscores but never a pair. A test enforces that
(`test_event_names.py`); keep it true when adding events. Extra properties are keyword
arguments; adding one never changes the API.

### Adding an event

1. **Pick the path.** First element from the closed `analytics.Component` set —
   `client`, `evaluation`, `integration`. Second is normally the method being
   reported. Add further levels only to narrow a feature down
   (`"integration", "bedrock", "invoke_agent"`), remembering a longer path is a
   separate event, not a repeat of the shorter one.

2. **Check the segments.** No level may contain a double underscore, because that is
   the separator the name is joined with. Method names never do, so this is normally
   free — `test_event_names.py` fails the build if it is ever not.

3. **Put the call on the first line** of the user-facing function, before it does its
   work, so a call that goes on to fail still counts as usage. Do not wrap it in
   `try`/`except` and do not guard it with a config check; it already swallows
   everything and no-ops when reporting is off.

4. **Decide the properties, if any.** Keyword arguments, scalars only. 97 of the 98
   events carry none — reach for one only when the event genuinely has variants worth
   splitting, as `metric_created` does. Never a value the user chose: report the
   Opik-owned name and `"custom"` otherwise.

5. **Check it should be reported at all.** Skip it if Opik calls the same entry point
   internally (litellm's `track_completion`), or if it is a per-call hot path
   (`Opik.trace()`, `Opik.span()`, an OTel `on_start`) — instrument the constructor or
   the user-facing function instead.

6. **Verify it locally.** Intercepting the HTTP call is the quickest way to see the
   exact payload without sending anything:

   ```python
   import json, os, unittest.mock
   import httpx

   os.environ["OPIK_ANALYTICS_ENABLE"] = "true"

   sent = []

   def fake_post(self, url, **kwargs):
       sent.append(kwargs["json"])
       return type("R", (), {"status_code": 201})()

   # Patched for the block only. Replacing `post` outright leaves every later
   # request in the process - the SDK's own included - talking to the stub.
   with unittest.mock.patch.object(httpx.Client, "post", fake_post):
       from opik import analytics

       ...                              # exercise your new call site
       analytics.flush(timeout=10)      # batched; nothing appears without this

   print(json.dumps(sent, indent=2))
   ```

   Reporting is off under pytest, so this has to be a plain script, not a test. Then
   run the suite from the SDK directory, where it lives:

   ```bash
   cd sdks/python && pytest tests/unit/analytics    # 67 tests
   ```

7. **Know how it will be read.** The event surfaces on the
   [Python SDK Usage dashboard](https://us.posthog.com/project/222582/dashboard/2025904),
   where every tile counts `uniq(distinct_id)`. A new event needs no dashboard change
   to appear in the adoption tiles, which group on the name.

### How it works
- `track_event()` never raises, never blocks on I/O, and no-ops when reporting is off.
- **Calls Opik makes into its own API are not reported.** `evaluate_threads` calls
  `search_threads`, `get_or_create_dataset` calls `get_dataset`, the CLI calls both —
  35 of the 69 instrumented `Opik` methods are reachable this way. An event is dropped
  when either the reporting function was reached from a *different* Opik module, or
  some function further up the stack is already reporting. Being called from the
  reporter's own module is not enough on its own, so a private helper reporting on its
  caller's behalf (as `BaseMetric` does) still works. Internal calls record nothing, so
  the user's own call to the same API still reports.
- **Nothing needs decorating for that to hold.** Reporting functions are recognised by
  code object the first time they report, so a new `track_event` call site joins in
  automatically.
- **Counting is safe across threads and forks.** Claiming an event is done under a lock
  (a check-then-add lets every racing thread report a copy), and the worker is rebuilt
  after `fork()` — with `_ALREADY_REPORTED` deliberately inherited, so a child reports
  its own events but not the parent's. Separate processes cannot share that state, so
  a `spawn` pool reports one copy per worker: **count `uniq(anonymous_id)`, never raw
  event volume.**
- Events go to Comet's stats collector from a single background thread
  (`analytics/worker.py`), and on through Segment to PostHog — the same route the
  backend reports through. **The collector takes no credentials**, so there is no
  write key to configure; `OPIK_ANALYTICS_URL` alone points it somewhere else.
- **Each event is reported once per process.** Analytics answers "how many users use
  this feature", not "how often", so `Opik.span()` in a hot loop costs one event and a
  set lookup. Events differing in their properties count as different events, so one
  name still covers variants (each metric class, say).
- `OPIK_ANALYTICS_ENABLE=false` is the only way to switch reporting off. Reporting is
  also skipped under pytest, which is not a user-facing switch but the thing keeping
  test suites from making network calls. Add another process-level veto with
  `analytics.register_rule(lambda config: ...)` before the first tracked event.
- Config is read once, on the first tracked event - not at import time - so
  `opik.configure(...)` is taken into account.

### Don'ts

- **Don't pass user data as properties.** Whatever a call site passes is what gets sent
  — there is no scrubbing layer. Properties are typed as scalars
  (`str | int | float | bool | None`); pick each key deliberately. Counts, flags and
  library names only.
- **Don't report a user-defined class or function name.** Report the Opik-owned name and
  `"custom"` otherwise (see `_track_metric_creation` in
  `evaluation/metrics/base_metric.py`).
- **Don't add try/except around `track_event`** - it already swallows everything.
- **Don't instrument an entry point that Opik itself calls internally.** The
  litellm `track_completion` case is why: `LiteLLMChatModel` calls it, so the event would
  measure Opik's own behaviour rather than the user's.
- **Don't instrument per-callback hot paths** (e.g. an OTel `on_start`). Instrument the
  constructor or the user-facing function instead. `Opik.trace()` and `Opik.span()` are
  deliberately uninstrumented for the same reason - the backend already sees them.

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `OPIK_ANALYTICS_ENABLED` | `false` | Backend: controls whether analytics events are sent |
| `OPIK_ANALYTICS_ENVIRONMENT` | empty | Frontend and backend: tags events with deployment name (e.g. `staging`, `production`) |
| `OPIK_POSTHOG_KEY` | — | Frontend: PostHog API key (set in `config.js`) |
| `OPIK_POSTHOG_HOST` | — | Frontend: PostHog API host (set in `config.js`) |
| `OPIK_ANALYTICS_ENABLE` | `true` | Python SDK: controls whether usage events are sent |
| `OPIK_ANALYTICS_URL` | `stats.comet.com/notify/event/` | Python SDK: where events are sent. Needs no credentials; set it empty to stop reporting |

Backend analytics is disabled by default; the Python SDK's is opt-out. OSS installations
are unaffected on the backend.

## Event Flow

```
Frontend custom events:  Browser → Segment → PostHog
Backend events:          Java → comet-stats → Segment → PostHog
Python SDK events:       Python → comet-stats → Segment → PostHog (background thread)
PostHog native:          Browser → posthog-js → PostHog (pageviews, feature flags, identification)
```

## Event Property Conventions

- **Consistent typing per property**: A given property key should always carry the same kind of value. Don't pass a UUID in one code path and a human-readable name in another for the same key.
- **Separate ID and name properties**: When both a UUID and a display name exist, use distinct keys (e.g. `blueprint_id` for the UUID, `blueprint_name` for the display name). If one is unavailable in a code path, omit the key or send an empty string — don't repurpose the other key.
- **Include `workspace_id`**: All backend analytics events should include the workspace ID for segmentation.

## Deciding Frontend vs Backend vs Python SDK

- **Frontend**: UI interactions (button clicks, wizard steps, form submissions, page visits)
- **Backend**: SDK-triggered actions (trace creation, test suite runs), server-side computations, events that happen without the user being on the page
- **Python SDK**: which SDK APIs, integrations and metrics users reach for, and in which
  environment (Python version, OS, cloud vs self-hosted vs local). Use it when the
  backend cannot see the difference - e.g. `track_openai` vs `track_anthropic` both
  produce ordinary spans server-side.
