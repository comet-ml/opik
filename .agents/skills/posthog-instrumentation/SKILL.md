---
name: posthog-instrumentation
description: Add PostHog analytics events to Opik. Use when instrumenting features for product metrics, adding tracking events, or working on launch analytics.
---

# PostHog Instrumentation

## Architecture

Three systems are involved in event tracking:

1. **Opik Frontend** — PostHog JS sends events directly to PostHog
2. **Opik Backend** — sends events to comet-stats via HTTP POST to the configured `usageReport.url`
3. **comet-stats** (separate repo: `comet-ml/comet-stats`) — receives events and routes them to Segment, PostHog, or both based on the `targets` field in the payload

## Frontend Events

### Where things live

- **Tracking utility:** `apps/opik-frontend/src/plugins/comet/posthog/tracking.ts`
- **PostHog init:** `apps/opik-frontend/src/plugins/comet/posthog/index.ts`
- **PostHog loaded via:** `apps/opik-frontend/src/plugins/comet/init.tsx` (reads key from `window.environmentVariablesOverwrite`)

### How to add a new FE event

1. Add the event name to the `OpikEvent` enum in `tracking.ts`:
   ```typescript
   export enum OpikEvent {
     MY_FEATURE_ACTION = "my_feature_action",
   }
   ```

2. Call `trackEvent()` in the relevant component, hook, or mutation callback:
   ```typescript
   import { trackEvent, OpikEvent } from "@/plugins/comet/posthog/tracking";

   trackEvent(OpikEvent.MY_FEATURE_ACTION, {
     project_id: projectId,
     some_property: value,
   });
   ```

3. Best places to fire events:
   - **Mutation `onSuccess` callbacks** — for actions that hit the API (create, update, deploy)
   - **`useEffect` with ref guard** — for async state transitions (e.g., first trace received)
   - **Event handlers** — for user actions (click skip, submit form)

### Important notes

- `trackEvent()` is a no-op when PostHog is not loaded (OSS mode) — safe to call unconditionally
- PostHog is only initialized in **comet mode** (`--mode comet`). In OSS mode, `posthog.__loaded` is false
- PostHog auto-captures `$pageview` events — don't add custom events for page visits
- Use `posthog.capture()` directly only if you need PostHog-specific features (groups, feature flags). Otherwise always use `trackEvent()`

## Backend Events

### Where things live

- **BiEvent record:** `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/bi/BiEvent.java`
- **BiEventService:** `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/bi/BiEventService.java`
- **Event listeners:** `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/bi/` (BiEventListener, DailyUsageReportJob, etc.)
- **Config:** `apps/opik-backend/config.yml` → `usageReport` section
- **Enable/disable:** `OPIK_USAGE_REPORT_ENABLED` env var (default: `true`)

### How to add a new BE event

1. Create an event listener class (or add to an existing one) in `infrastructure/bi/`:
   ```java
   @EagerSingleton
   @Slf4j
   public class MyFeatureEventListener {

       @Inject
       public MyFeatureEventListener(
               @NonNull BiEventService biEventService,
               @NonNull UsageReportService usageReportService,
               @NonNull OpikConfiguration config) {
           // store dependencies
       }

       @Subscribe
       public void onMyEvent(MyDomainEvent event) {
           if (!config.getUsageReport().isEnabled()) {
               return;
           }

           biEventService.reportEvent(
               usageReportService.getAnonymousId().orElseThrow(),
               "my_event_type",           // internal tracking key
               "opik_my_event",           // event_type sent to comet-stats
               Map.of(
                   "opik_app_version", config.getMetadata().getVersion(),
                   "some_property", someValue
               ));
       }
   }
   ```

2. The event is published via Guice EventBus — make sure the domain event class exists and is published with `eventBus.post(new MyDomainEvent(...))` from the relevant service.

### Routing events to PostHog

The `BiEvent` record has a `targets` field (`List<String>`) that controls where comet-stats forwards the event:

- `null` or not set → Segment only (default, backwards compatible)
- `List.of("posthog")` → PostHog only
- `List.of("segment", "posthog")` → both

To send to PostHog, build the BiEvent with targets:
```java
var event = BiEvent.builder()
    .anonymousId(anonymousId)
    .eventType("opik_my_event")
    .eventProperties(Map.of(...))
    .targets(List.of("posthog"))
    .build();
```

Note: the current `BiEventService.reportEvent()` method doesn't pass `targets` through — it constructs the BiEvent internally with `null` targets. To send events to PostHog, you'll need to either extend `reportEvent()` with a targets parameter or create the BiEvent directly.

### Identity for Comet Cloud vs OSS

- **OSS installations:** All events use a single anonymous UUID per installation (`usageReportService.getAnonymousId()`). No PII.
- **Comet Cloud:** For per-user/per-workspace metrics, use the actual `userName` or `workspaceId` from `RequestContext` as the identity, not the anonymous ID.

## Event Naming Convention

- Prefix: `opik_`
- Suffix: `_fe` for frontend, `_be` for backend
- Use snake_case: `opik_eval_suite_created_fe`, `opik_config_promoted_be`
