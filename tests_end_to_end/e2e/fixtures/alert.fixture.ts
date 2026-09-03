import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend/uuid7';

/** Wire values of `AlertTriggerWriteEventType`; the UI renders them under its own titles. */
export const ALERT_EVENT_TYPE = {
  promptCreated: 'prompt:created',
  promptCommitted: 'prompt:committed',
  promptDeleted: 'prompt:deleted',
  experimentFinished: 'experiment:finished',
  traceCost: 'trace:cost',
  traceLatency: 'trace:latency',
  traceErrors: 'trace:errors',
} as const;

export type AlertEventType = (typeof ALERT_EVENT_TYPE)[keyof typeof ALERT_EVENT_TYPE];

/** Trigger titles the alerts list and editor show for each event type. */
export const ALERT_EVENT_TITLE: Record<AlertEventType, string> = {
  [ALERT_EVENT_TYPE.promptCreated]: 'New prompt added',
  [ALERT_EVENT_TYPE.promptCommitted]: 'New prompt version created',
  [ALERT_EVENT_TYPE.promptDeleted]: 'Prompt deleted',
  [ALERT_EVENT_TYPE.experimentFinished]: 'Experiment finished',
  [ALERT_EVENT_TYPE.traceCost]: 'Cost threshold',
  [ALERT_EVENT_TYPE.traceLatency]: 'Latency threshold',
  [ALERT_EVENT_TYPE.traceErrors]: 'Trace errors threshold',
};

export interface AlertSeed {
  /** Appended to the test namespace, so one test can seed several alerts. */
  suffix: string;
  enabled?: boolean;
  eventTypes?: AlertEventType[];
}

export interface AlertRef {
  id: string;
  name: string;
  webhookUrl: string;
  enabled: boolean;
  eventTypes: AlertEventType[];
}

export interface AlertFixtures {
  /**
   * One alert under the `project` fixture: enabled, General destination, a
   * single `prompt:created` trigger. That trigger is what makes it render a
   * full list row — a trigger-less alert shows "-" under Events.
   */
  alert: AlertRef;

  /**
   * Seeds extra alerts, for tests needing more than one row. A callback
   * because the count and trigger mix differ per test; each is torn down
   * alongside the `alert` fixture's own.
   */
  seedAlerts: (seeds: AlertSeed[]) => Promise<AlertRef[]>;

  /**
   * Cleans up alerts a test creates through the UI, which have no id until the
   * form submits and the row renders.
   *
   * Requesting the fixture is the whole API — alerts are found at teardown by
   * the test's own namespace prefix rather than registered by the test. An
   * id-registration call would be skipped by a failure between the create and
   * the registration, leaking exactly the alert whose run went wrong.
   */
  uiAlertCleanup: void;
}

/**
 * Seeding and teardown for project-scoped alerts.
 *
 * Teardown is mandatory: alerts do not cascade with their project. `alerts`
 * holds `project_id` as a plain indexed column with no FK, and
 * `ProjectService.delete` only touches `ProjectDAO` — so an alert outlives the
 * project that scoped it and surfaces in the next spec's list. Same hazard
 * `automationRulesCleanup` documents for rules.
 *
 * Ids are minted client-side because `POST /v1/private/alerts` answers 201
 * with no body; without them teardown would need a workspace-wide paginated
 * read to find each alert by name.
 *
 * Best-effort: a failed delete warns rather than throws, so cleanup cannot
 * mask the assertion failure that explains the run.
 */
export const test = baseTest.extend<AlertFixtures>({
  seedAlerts: async ({ sdkClient, project, backendClient, testNamespace }, use, testInfo) => {
    const created: AlertRef[] = [];

    await use(async (seeds) => {
      const refs: AlertRef[] = [];
      for (const seed of seeds) {
        const ref: AlertRef = {
          id: uuid7(),
          name: `${testNamespace}-alert-${seed.suffix}`,
          webhookUrl: `https://example.com/e2e-webhook-${seed.suffix}`,
          enabled: seed.enabled ?? true,
          eventTypes: seed.eventTypes ?? [ALERT_EVENT_TYPE.promptCreated],
        };
        // The Python bridge has no alert routes; the TS SDK is the public surface.
        await sdkClient.typescript.api.alerts.createAlert({
          id: ref.id,
          name: ref.name,
          enabled: ref.enabled,
          alertType: 'general',
          projectId: project.id,
          webhook: { url: ref.webhookUrl },
          triggers: ref.eventTypes.map((eventType) => ({ eventType })),
        });
        created.push(ref);
        refs.push(ref);
      }
      return refs;
    });

    if (created.length === 0) return;

    await testInfo.attach('opik.alerts', {
      body: JSON.stringify(created, null, 2),
      contentType: 'application/json',
    });

    if (shouldLeaveArtifacts(testInfo)) {
      console.warn(
        `[alert fixture] leaving ${created.length} alert(s) under ${project.name} for debugging`,
      );
      return;
    }

    try {
      await backendClient.deleteAlertsBatch(created.map((a) => a.id));
    } catch (err) {
      console.warn('[alert fixture] batch delete warning:', err);
    }
  },

  alert: async ({ seedAlerts }, use) => {
    const [seeded] = await seedAlerts([{ suffix: 'seeded' }]);
    await use(seeded);
  },

  uiAlertCleanup: [
    async ({ backendClient, testNamespace }, use, testInfo) => {
      await use();

      if (shouldLeaveArtifacts(testInfo)) return;

      // Deletes by prefix, so this also sweeps the fixture-seeded alerts —
      // harmless, since `deleteAlertsBatch` is idempotent for ids already gone.
      try {
        const leftover = await backendClient.listAlertsWithPrefix(`${testNamespace}-alert-`);
        await backendClient.deleteAlertsBatch(leftover.map((a) => a.id));
      } catch (err) {
        console.warn('[alert fixture] UI-created alert cleanup warning:', err);
      }
    },
    // Opted into by name, so the extra workspace-wide read stays off every
    // test that only seeds through `seedAlerts`.
    { auto: false },
  ],
});

export { expect } from './dashboard-cleanup.fixture';
