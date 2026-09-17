import { test, expect, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import type { AlertWrite } from '@e2e/core/backend';

/**
 * A threshold trigger needs both a `threshold` and a `window` to be evaluated at
 * all. Before this release a config missing either persisted happily and then
 * failed inside `MetricsAlertJob` on every run, so the alert's owner saw an
 * alert that simply never fired — the failure mode nobody notices, because
 * "no alert" and "no alert yet" look identical.
 *
 * This is the half of the change that decides whether an alert can ever fire,
 * and it is pure contract: deterministic, no wall-clock, no LLM, seedable from
 * nothing. `alerts-crud.spec.ts` round-trips one well-formed threshold config
 * through the editor and asserts nothing about rejection, and the editor cannot
 * express these payloads anyway — it only offers a number field and a window
 * picker, so the shapes under test here are reachable only from an API or SDK
 * client.
 *
 * API-level throughout, deliberately: the claim is about which payloads the
 * endpoint accepts and what it says when it refuses. Driving a form to observe
 * that second-hand would assert the form's own validation instead.
 */

/** The valid pair every negative case is a mutation of. */
const VALID_CONFIG = { threshold: '100', window: '3600' } as const;

/**
 * Every shape the endpoint has to refuse, with the message it has to refuse it
 * with. The message is asserted, not just the status: "rejected for the wrong
 * reason" is indistinguishable from "rejected" on a status code alone, and the
 * message is the only place the offending key and config type are named — which
 * is what an API client has to read to fix its payload.
 */
const MALFORMED_CONFIGS: Array<{
  label: string;
  configValue: Record<string, string>;
  message: RegExp;
}> = [
  {
    label: 'no window',
    configValue: { threshold: '100' },
    message: /Missing config value for key 'window' in trigger config of type 'threshold:cost'/,
  },
  {
    label: 'no threshold',
    configValue: { window: '3600' },
    message: /Missing config value for key 'threshold' in trigger config of type 'threshold:cost'/,
  },
  {
    label: 'empty config',
    configValue: {},
    message: /Missing config value for key 'threshold' in trigger config of type 'threshold:cost'/,
  },
  {
    label: "threshold 'abc'",
    configValue: { threshold: 'abc', window: '3600' },
    message:
      /Config value for key 'threshold' in trigger config of type 'threshold:cost' is not a number: 'abc'/,
  },
  {
    label: "window 'sixty'",
    configValue: { threshold: '100', window: 'sixty' },
    message:
      /Config value for key 'window' in trigger config of type 'threshold:cost' is not a number of seconds: 'sixty'/,
  },
  {
    label: "window '0'",
    configValue: { threshold: '100', window: '0' },
    message:
      /Config value for key 'window' in trigger config of type 'threshold:cost' must be a positive number of seconds, got '0'/,
  },
  {
    label: "window '-60'",
    configValue: { threshold: '100', window: '-60' },
    message:
      /Config value for key 'window' in trigger config of type 'threshold:cost' must be a positive number of seconds, got '-60'/,
  },
  {
    // Blank, not absent. `StringUtils.isBlank` is what decides this, so a
    // presence check written as `!= null` would let it through to the job.
    label: "window '  '",
    configValue: { threshold: '100', window: '  ' },
    message: /Missing config value for key 'window' in trigger config of type 'threshold:cost'/,
  },
];

/**
 * The other two metrics-based triggers, each with its own config type. Included
 * because the validation is driven by a per-event-type mapping: a rule wired up
 * for cost alone would leave the other two able to persist an inert config, and
 * nothing in the cost cases above could tell.
 */
const OTHER_THRESHOLD_TRIGGERS = [
  { eventType: ALERT_EVENT_TYPE.traceLatency, configType: 'threshold:latency' },
  { eventType: ALERT_EVENT_TYPE.traceErrors, configType: 'threshold:errors' },
] as const;

function alertWith(args: {
  id: string;
  name: string;
  projectId: string;
  eventType: string;
  configType: string;
  configValue: Record<string, string>;
}): AlertWrite {
  return {
    id: args.id,
    name: args.name,
    projectId: args.projectId,
    webhookUrl: 'https://example.com/e2e-webhook-threshold-validation',
    triggers: [
      {
        eventType: args.eventType,
        triggerConfigs: [{ type: args.configType, configValue: args.configValue }],
      },
    ],
  };
}

test.describe('Alerts — threshold trigger config validation', {
  tag: ['@t2-cuj', '@area:alerts'],
}, () => {
  test(
    'A threshold config missing or malforming window/threshold is rejected, and nothing is stored',
    { tag: ['@cap:alerts.create-alert'] },
    async ({ project, backendClient, uiAlertCleanup, testNamespace }) => {
      const controlName = `${testNamespace}-alert-control`;
      const nameFor = (label: string) =>
        `${testNamespace}-alert-${label.replace(/\W+/g, '-')}`;

      // Declared before the first write, so a failure mid-flow cannot skip it.
      // `uiAlertCleanup` discovers by exact name at teardown rather than by a
      // registration call, which is what makes that guarantee hold.
      //
      // The rejected names are registered too, even though a working backend
      // stores none of them: if a malformed config ever DOES persist — which is
      // the failure this test exists to catch — the row must still be swept, or
      // the first run to find the bug also poisons the workspace for the next.
      uiAlertCleanup([
        controlName,
        ...MALFORMED_CONFIGS.map((m) => nameFor(m.label)),
        ...OTHER_THRESHOLD_TRIGGERS.map((t) => nameFor(t.configType)),
      ]);

      for (const malformed of MALFORMED_CONFIGS) {
        await test.step(`'${malformed.label}' is rejected on create`, async () => {
          const { status, message } = await backendClient.writeAlert(
            'POST',
            alertWith({
              id: uuid7(),
              name: nameFor(malformed.label),
              projectId: project.id,
              eventType: ALERT_EVENT_TYPE.traceCost,
              configType: 'threshold:cost',
              configValue: malformed.configValue,
            }),
          );
          expect(status, `'${malformed.label}' answered: ${message}`).toBe(400);
          expect(message).toMatch(malformed.message);
        });
      }

      for (const trigger of OTHER_THRESHOLD_TRIGGERS) {
        await test.step(`A ${trigger.eventType} config with no window is rejected too`, async () => {
          const { status, message } = await backendClient.writeAlert(
            'POST',
            alertWith({
              id: uuid7(),
              name: nameFor(trigger.configType),
              projectId: project.id,
              eventType: trigger.eventType,
              configType: trigger.configType,
              configValue: { threshold: '5' },
            }),
          );
          expect(status, `${trigger.eventType} answered: ${message}`).toBe(400);
          expect(message).toMatch(
            new RegExp(
              `Missing config value for key 'window' in trigger config of type '${trigger.configType}'`,
            ),
          );
        });
      }

      // Without this every step above would pass just as well against an
      // endpoint that had started refusing every alert.
      const controlId = uuid7();
      await test.step('A well-formed config on the same trigger is still accepted', async () => {
        const { status, message } = await backendClient.writeAlert(
          'POST',
          alertWith({
            id: controlId,
            name: controlName,
            projectId: project.id,
            eventType: ALERT_EVENT_TYPE.traceCost,
            configType: 'threshold:cost',
            configValue: { ...VALID_CONFIG },
          }),
        );
        expect(status, `the control alert answered: ${message}`).toBe(201);
      });

      await test.step('Every rejected write stored nothing', async () => {
        // The whole namespace, not a lookup of the control alert: a rejection
        // that answered 400 after committing the row would leave an alert here
        // that a `find()`-style assertion would never look at.
        const stored = await backendClient.listAlertsWithPrefix(`${testNamespace}-alert-`);
        expect(stored.map((a) => a.id)).toEqual([controlId]);
      });
    },
  );

  test(
    'A config carrying only the legacy window_seconds key is accepted and read back under window',
    { tag: ['@cap:alerts.create-alert'] },
    async ({ project, backendClient, uiAlertCleanup, testNamespace }) => {
      const name = `${testNamespace}-alert-legacy-window`;
      uiAlertCleanup([name]);
      const id = uuid7();

      await test.step('Create an alert whose config spells the window the legacy way', async () => {
        const { status, message } = await backendClient.writeAlert(
          'POST',
          alertWith({
            id,
            name,
            projectId: project.id,
            eventType: ALERT_EVENT_TYPE.traceCost,
            configType: 'threshold:cost',
            configValue: { threshold: '250', window_seconds: '1800' },
          }),
        );
        expect(status, `the legacy-key alert answered: ${message}`).toBe(201);
      });

      await test.step('It reads back with window filled in from the legacy key', async () => {
        const stored = await backendClient.getAlert(id);
        expect(stored, 'the alert must be readable by the id it was created with').not.toBeNull();

        // The whole config map, not just `window`: the normalisation copies the
        // legacy value across rather than moving it, and an assertion that only
        // looked at `window` could not tell a normalised config from one that
        // had quietly dropped the value the alert was configured with.
        expect(stored!.triggers).toEqual([
          {
            eventType: ALERT_EVENT_TYPE.traceCost,
            triggerConfigs: [
              {
                type: 'threshold:cost',
                configValue: { threshold: '250', window: '1800', window_seconds: '1800' },
              },
            ],
          },
        ]);
      });
    },
  );

  test(
    'The same rejection applies on update, and a valid update is stored',
    { tag: ['@cap:alerts.edit-alert'] },
    async ({ project, backendClient, uiAlertCleanup, testNamespace }) => {
      const name = `${testNamespace}-alert-updatable`;
      uiAlertCleanup([name]);
      const id = uuid7();

      await test.step('Create a valid threshold alert to update', async () => {
        const { status, message } = await backendClient.writeAlert(
          'POST',
          alertWith({
            id,
            name,
            projectId: project.id,
            eventType: ALERT_EVENT_TYPE.traceCost,
            configType: 'threshold:cost',
            configValue: { ...VALID_CONFIG },
          }),
        );
        expect(status, `the alert to update answered: ${message}`).toBe(201);
      });

      await test.step('An update that strips the window is rejected', async () => {
        const { status, message } = await backendClient.writeAlert(
          'PUT',
          alertWith({
            id,
            name,
            projectId: project.id,
            eventType: ALERT_EVENT_TYPE.traceCost,
            configType: 'threshold:cost',
            configValue: { threshold: '100' },
          }),
        );
        expect(status, `the window-stripping update answered: ${message}`).toBe(400);
        expect(message).toMatch(
          /Missing config value for key 'window' in trigger config of type 'threshold:cost'/,
        );
      });

      await test.step('The rejected update left the stored config untouched', async () => {
        const stored = await backendClient.getAlert(id);
        expect(stored, 'a rejected update must not have deleted the alert').not.toBeNull();
        expect(stored!.triggers[0].triggerConfigs[0].configValue).toEqual({ ...VALID_CONFIG });
      });

      await test.step('A well-formed update is accepted and readable', async () => {
        const { status, message } = await backendClient.writeAlert(
          'PUT',
          alertWith({
            id,
            name,
            projectId: project.id,
            eventType: ALERT_EVENT_TYPE.traceCost,
            configType: 'threshold:cost',
            configValue: { threshold: '500', window: '7200' },
          }),
        );
        expect(status, `the valid update answered: ${message}`).toBe(204);

        const stored = await backendClient.getAlert(id);
        expect(stored, 'the updated alert must still answer to its original id').not.toBeNull();
        expect(stored!.triggers).toEqual([
          {
            eventType: ALERT_EVENT_TYPE.traceCost,
            triggerConfigs: [
              { type: 'threshold:cost', configValue: { threshold: '500', window: '7200' } },
            ],
          },
        ]);
      });
    },
  );
});
