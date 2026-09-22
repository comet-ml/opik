import { test, expect, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { uuid7, type AlertDetail, type AlertTriggerConfigDetail } from '@e2e/core/backend';

/**
 * A threshold alert needs both a threshold and a window to be evaluable:
 * `MetricsAlertJob` builds its condition from the pair. An alert stored without
 * one used to be accepted happily and then throw on every run of the job, so
 * its owner saw an alert that simply never fired and was never told. The create
 * and update endpoints now refuse that state, which is the only point a user
 * finds out.
 *
 * **API-level deliberately, not for want of a UI.** The alerts form's own zod
 * schema requires a threshold and a window before it will submit, so the
 * browser cannot produce any of the payloads under test — a UI spec here would
 * assert the client-side form validation, which is a different guard on a
 * different layer and would still pass if the server dropped its own. The form
 * round-trip that *is* reachable through the page (threshold and window filled,
 * saved, reopened) is already covered by `alerts-crud.spec.ts`.
 *
 * The legacy-key half matters most. Configs written before the key settled on
 * `window` store it as `window_seconds`, the job still reads it, and
 * `AlertService` keeps it valid on purpose. It is the assertion that catches
 * someone tightening this validation into rejecting rows that work today.
 */

/** The metrics event types this validation applies to, and the config type each carries. */
const THRESHOLD_CONFIG_TYPE = {
  [ALERT_EVENT_TYPE.traceCost]: 'threshold:cost',
  [ALERT_EVENT_TYPE.traceLatency]: 'threshold:latency',
  [ALERT_EVENT_TYPE.traceErrors]: 'threshold:errors',
} as const;

type ThresholdEventType = keyof typeof THRESHOLD_CONFIG_TYPE;

/** Verbatim, so a message naming the wrong key or the wrong config type fails. */
const missingKeyMessage = (key: 'threshold' | 'window', eventType: ThresholdEventType) =>
  `Missing config value for key '${key}' in trigger config of type '${THRESHOLD_CONFIG_TYPE[eventType]}'`;

interface ThresholdAlertPayload {
  id: string;
  name: string;
  projectId: string;
  eventType: ThresholdEventType;
  /** Sent as-is: the point of several of these is a key the write shape has no name for. */
  configValue: Record<string, string>;
}

/** The wire body, snake_case — these calls bypass the typed client on purpose. */
const thresholdAlertBody = ({
  id,
  name,
  projectId,
  eventType,
  configValue,
}: ThresholdAlertPayload) => ({
  id,
  name,
  enabled: true,
  alert_type: 'general',
  project_id: projectId,
  webhook: { url: 'https://example.com/e2e-webhook-threshold-validation' },
  triggers: [
    {
      event_type: eventType,
      trigger_configs: [
        { type: THRESHOLD_CONFIG_TYPE[eventType], config_value: configValue },
      ],
    },
  ],
});

/**
 * The single threshold config of an alert, asserting the lookup resolved to
 * exactly one trigger and one config — an ambiguous match fails here rather
 * than quietly asserting about whichever one came back first.
 */
const thresholdConfigOf = (
  alert: AlertDetail,
  eventType: ThresholdEventType,
): AlertTriggerConfigDetail => {
  const triggers = alert.triggers.filter((t) => t.eventType === eventType);
  expect(triggers, `exactly one ${eventType} trigger on ${alert.name}`).toHaveLength(1);

  const configs = triggers[0].configs.filter(
    (c) => c.type === THRESHOLD_CONFIG_TYPE[eventType],
  );
  expect(
    configs,
    `exactly one ${THRESHOLD_CONFIG_TYPE[eventType]} config on ${alert.name}`,
  ).toHaveLength(1);

  return configs[0];
};

test.describe('Alerts — threshold config validation', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'Creating a threshold alert without a threshold or a window is refused, and the legacy window_seconds key is still accepted',
    { tag: ['@cap:alerts.create-alert'] },
    async ({ project, backendClient, registerAlertCleanup, testNamespace }) => {
      const legacyName = `${testNamespace}-legacy-window-seconds`;

      /**
       * Ids are minted here, and registered before the write rather than after
       * it: a payload the API answered 400 to but persisted anyway is exactly
       * the leak worth sweeping, and the final step below is what would catch
       * it. A delete for an alert that really was refused is a no-op.
       */
      const post = async (
        suffix: string,
        eventType: ThresholdEventType,
        configValue: Record<string, string>,
      ) => {
        const id = uuid7();
        registerAlertCleanup(id);
        return backendClient.createAlertRaw(
          thresholdAlertBody({
            id,
            name: `${testNamespace}-${suffix}`,
            projectId: project.id,
            eventType,
            configValue,
          }),
        );
      };

      await test.step('A cost threshold with no window is refused, naming the window', async () => {
        const result = await post('no-window', ALERT_EVENT_TYPE.traceCost, { threshold: '100' });
        expect(result.status, `answered: ${result.message}`).toBe(400);
        expect(result.message).toBe(missingKeyMessage('window', ALERT_EVENT_TYPE.traceCost));
      });

      await test.step('A cost threshold with no threshold is refused, naming the threshold', async () => {
        const result = await post('no-threshold', ALERT_EVENT_TYPE.traceCost, { window: '3600' });
        expect(result.status, `answered: ${result.message}`).toBe(400);
        expect(result.message).toBe(missingKeyMessage('threshold', ALERT_EVENT_TYPE.traceCost));
      });

      // A present-but-blank value is the shape a form submits when a field is
      // cleared rather than removed. If the check were a null test it would
      // pass this through, and the alert would be just as unevaluable.
      await test.step('A blank threshold is refused — the check is emptiness, not absence', async () => {
        const result = await post('blank-threshold', ALERT_EVENT_TYPE.traceCost, {
          threshold: '  ',
          window: '3600',
        });
        expect(result.status, `answered: ${result.message}`).toBe(400);
        expect(result.message).toBe(missingKeyMessage('threshold', ALERT_EVENT_TYPE.traceCost));
      });

      // Each event type must be judged against its own config type. One
      // mapping serves both this validation and the job's evaluation, so a
      // latency alert reported against 'threshold:cost' would mean the two had
      // drifted apart.
      for (const eventType of [ALERT_EVENT_TYPE.traceLatency, ALERT_EVENT_TYPE.traceErrors] as const) {
        await test.step(`A ${eventType} threshold with no window is refused, naming its own config type`, async () => {
          const result = await post(`no-window-${eventType.replace(':', '-')}`, eventType, {
            threshold: '100',
          });
          expect(result.status, `${eventType} answered: ${result.message}`).toBe(400);
          expect(result.message).toBe(missingKeyMessage('window', eventType));
        });
      }

      const legacyId = uuid7();
      await test.step('A config using the legacy window_seconds key is accepted', async () => {
        registerAlertCleanup(legacyId);
        const result = await backendClient.createAlertRaw(
          thresholdAlertBody({
            id: legacyId,
            name: legacyName,
            projectId: project.id,
            eventType: ALERT_EVENT_TYPE.traceCost,
            configValue: { threshold: '100', window_seconds: '3600' },
          }),
        );
        expect(
          result.status,
          `the legacy key must stay valid: ${result.message}`,
        ).toBe(201);
      });

      // `AlertTriggerConfig.withNormalizedWindow` backfills `window` from the
      // legacy key as configs are read out of persistence, so the legacy
      // spelling never reaches a consumer — the alerts editor reads only
      // `window` and drops a config it cannot read, which would delete the
      // condition on the next save.
      await test.step('The legacy key is kept and the window is read back from it', async () => {
        const stored = await backendClient.getAlert(legacyId);
        expect(stored, 'the accepted alert must be readable').not.toBeNull();
        // The whole map, and both keys pinned to the value that was written:
        // dropping `window_seconds` would rewrite history for rows the job
        // still reads, and a `window` that does not equal it would make the
        // alert fire on a window nobody chose.
        expect(thresholdConfigOf(stored!, ALERT_EVENT_TYPE.traceCost).configValue).toEqual({
          threshold: '100',
          window: '3600',
          window_seconds: '3600',
        });
      });

      // The refusals above each read one response. This reads the whole answer:
      // a payload that was refused *and persisted anyway* leaves a row here,
      // and no per-request assertion could see it.
      await test.step('Only the accepted alert exists — every refused payload persisted nothing', async () => {
        const persisted = await backendClient.listAlertsWithPrefix(testNamespace);
        expect(persisted.map((a) => a.name)).toEqual([legacyName]);
      });
    },
  );

  test(
    'A refused threshold update leaves the stored alert untouched',
    { tag: ['@cap:alerts.edit-alert'] },
    async ({ project, backendClient, registerAlertCleanup, testNamespace }) => {
      const alertId = uuid7();
      const originalName = `${testNamespace}-original`;
      const attemptedName = `${testNamespace}-renamed`;

      const body = (name: string, configValue: Record<string, string>) =>
        thresholdAlertBody({
          id: alertId,
          name,
          projectId: project.id,
          eventType: ALERT_EVENT_TYPE.traceCost,
          configValue,
        });

      await test.step('Seed a valid cost threshold alert', async () => {
        registerAlertCleanup(alertId);
        const result = await backendClient.createAlertRaw(
          body(originalName, { threshold: '100', window: '3600' }),
        );
        expect(result.status, `seed failed: ${result.message}`).toBe(201);
      });

      // The precondition the rest of the test is asserted against: if the seed
      // had not stored what it sent, "unchanged" below would mean nothing.
      await test.step('Verify what was stored before any update', async () => {
        const stored = await backendClient.getAlert(alertId);
        expect(stored, 'the seeded alert must be readable').not.toBeNull();
        expect(stored!.name).toBe(originalName);
        expect(thresholdConfigOf(stored!, ALERT_EVENT_TYPE.traceCost).configValue).toEqual({
          threshold: '100',
          window: '3600',
        });
      });

      await test.step('An update that drops the window is refused', async () => {
        const result = await backendClient.updateAlertRaw(
          alertId,
          body(attemptedName, { threshold: '999' }),
        );
        expect(result.status, `answered: ${result.message}`).toBe(400);
        expect(result.message).toBe(missingKeyMessage('window', ALERT_EVENT_TYPE.traceCost));
      });

      // The riskier half of this contract: the update path now refuses payloads
      // for alerts that already exist, so a validation that threw after a
      // partial write would leave real data half-updated with nothing to
      // report it. The name and both config fields are re-read together — a
      // rename that landed while the config was rolled back is the failure this
      // step exists to catch.
      await test.step('The refused update changed nothing — not the name, not either config field', async () => {
        const stored = await backendClient.getAlert(alertId);
        expect(stored, 'a refused update must not delete the alert').not.toBeNull();
        expect(stored!.name, 'the attempted rename must not have landed').toBe(originalName);
        expect(thresholdConfigOf(stored!, ALERT_EVENT_TYPE.traceCost).configValue).toEqual({
          threshold: '100',
          window: '3600',
        });
      });

      await test.step('The same update with a window is accepted', async () => {
        const result = await backendClient.updateAlertRaw(
          alertId,
          body(attemptedName, { threshold: '999', window: '3600' }),
        );
        expect(result.status, `valid update failed: ${result.message}`).toBe(204);
      });

      // Without this the test would pass equally well against an endpoint that
      // refused every update: "unchanged" would be trivially true.
      await test.step('The accepted update round-tripped', async () => {
        const stored = await backendClient.getAlert(alertId);
        expect(stored, 'the updated alert must be readable').not.toBeNull();
        expect(stored!.name).toBe(attemptedName);
        expect(thresholdConfigOf(stored!, ALERT_EVENT_TYPE.traceCost).configValue).toEqual({
          threshold: '999',
          window: '3600',
        });
      });
    },
  );
});
