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
- **Tracking utility**: `apps/opik-frontend/src/plugins/comet/posthog/tracking.ts`
- **PostHog init**: `apps/opik-frontend/src/plugins/comet/posthog/index.ts`
- **Plugin init**: `apps/opik-frontend/src/plugins/comet/init.tsx`

### Adding a new event

1. Add the event name to the `OpikEvent` const in `tracking.ts`:
```typescript
export const OpikEvent = {
  ONBOARDING_AGENT_NAME_SUBMITTED: "opik_onboarding_agent_name_submitted",
} as const;
```

2. Call `trackEvent` from the component or hook where the action happens:
```typescript
import { trackEvent, OpikEvent } from "@/plugins/comet/posthog/tracking";

trackEvent(OpikEvent.ONBOARDING_AGENT_NAME_SUBMITTED, {
  agent_name: agentName,
});
```

### How it works
- `trackEvent()` safely no-ops when PostHog isn't loaded (OSS mode)
- `opik_` prefix is enforced at runtime as a safety net
- `OPIK_ANALYTICS_ENVIRONMENT` is registered as a PostHog super property at init — it's automatically attached to every event (including pageviews)
- Frontend events go directly to PostHog via `posthog-js`

## Backend Events

### Files
- **Service**: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/bi/AnalyticsService.java`
- **Config**: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/AnalyticsConfig.java`
- **YAML config**: `apps/opik-backend/config.yml` (under `analytics:`)

### Adding a new event

1. Inject `AnalyticsService` into your service or listener:
```java
private final @NonNull AnalyticsService analyticsService;
```

2. Call `trackEvent`:
```java
analyticsService.trackEvent("onboarding_first_trace",
    Map.of("trace_id", traceId, "project_id", projectId));
```

### How it works
- `trackEvent()` no-ops when `OPIK_ANALYTICS_ENABLED` is `false` (default)
- `opik_` prefix is auto-prepended if missing
- `environment` property is auto-injected from `OPIK_ANALYTICS_ENVIRONMENT`
- Events flow: Backend → comet-stats → Segment → PostHog
- Uses the same comet-stats endpoint as existing usage reporting (`usageReport.url`)

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
Frontend events:  Browser → posthog-js → PostHog
Backend events:   Java → comet-stats → Segment → PostHog
```

## Deciding Frontend vs Backend

- **Frontend**: UI interactions (button clicks, wizard steps, form submissions, page visits)
- **Backend**: SDK-triggered actions (trace creation, test suite runs), server-side computations, events that happen without the user being on the page
