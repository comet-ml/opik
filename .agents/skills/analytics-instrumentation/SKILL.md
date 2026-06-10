---
name: analytics-instrumentation
description: Add analytics events to Opik features. Use when wiring PostHog events on the frontend or backend for product analytics tracking.
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

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `OPIK_ANALYTICS_ENABLED` | `false` | Backend: controls whether analytics events are sent |
| `OPIK_ANALYTICS_ENVIRONMENT` | empty | Both: tags events with deployment name (e.g. `staging`, `production`) |
| `OPIK_POSTHOG_KEY` | — | Frontend: PostHog API key (set in `config.js`) |
| `OPIK_POSTHOG_HOST` | — | Frontend: PostHog API host (set in `config.js`) |

Analytics is disabled by default. OSS installations are unaffected.

## Event Flow

```
Frontend custom events:  Browser → Segment → PostHog
Backend events:          Java → comet-stats → Segment → PostHog
PostHog native:          Browser → posthog-js → PostHog (pageviews, feature flags, identification)
```

## Event Property Conventions

- **Consistent typing per property**: A given property key should always carry the same kind of value. Don't pass a UUID in one code path and a human-readable name in another for the same key.
- **Separate ID and name properties**: When both a UUID and a display name exist, use distinct keys (e.g. `blueprint_id` for the UUID, `blueprint_name` for the display name). If one is unavailable in a code path, omit the key or send an empty string — don't repurpose the other key.
- **Include `workspace_id`**: All backend analytics events should include the workspace ID for segmentation.

## Deciding Frontend vs Backend

- **Frontend**: UI interactions (button clicks, wizard steps, form submissions, page visits)
- **Backend**: SDK-triggered actions (trace creation, test suite runs), server-side computations, events that happen without the user being on the page
