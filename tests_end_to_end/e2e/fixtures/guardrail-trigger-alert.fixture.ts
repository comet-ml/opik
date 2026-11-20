import { test as baseTest } from './project-scoped-ts-clients.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend/uuid7';
import { ALERT_EVENT_TYPE } from './alert.fixture';

/**
 * The `filter:guardrail_type` config type, as the alerts API spells it.
 *
 * Not in `ALERT_TRIGGER_CONFIG_TYPE` on the frontend's terms because the e2e
 * side has no copy of that map; the wire string is the contract either way.
 */
export const GUARDRAIL_TYPE_CONFIG = 'filter:guardrail_type';

/** The config key holding the comma-joined type list. */
export const GUARDRAIL_TYPES_KEY = 'guardrail_types';

/**
 * The guardrail types the seeded alert is saved with.
 *
 * Two of them — `LLM_JUDGE` and `CUSTOM_CLASSIFIER` — are exactly the pair
 * opik#9486 removed from the alert editor's offered list, and `PII` is one it
 * still offers. That mix is the point: an alert carrying both kinds is the only
 * seed that can show the legacy ones being KEPT rather than the list simply
 * being rendered.
 *
 * Order matters and is asserted on: the editor reads the stored string
 * left-to-right into a Set, so what it writes back on save preserves this
 * order minus whatever was unticked.
 */
export const SEEDED_GUARDRAIL_TYPES = ['LLM_JUDGE', 'CUSTOM_CLASSIFIER', 'PII'] as const;

export interface GuardrailTriggerAlertRef {
  id: string;
  name: string;
  webhookUrl: string;
}

export interface GuardrailTriggerAlertFixtures {
  /**
   * An alert on `project` whose single trigger is `trace:guardrails_triggered`,
   * saved with the three guardrail types above.
   *
   * Seeded through the REST client rather than the editor because the editor
   * CANNOT create it: it offers only topic, PII and prompt injection as of
   * 9486, so an alert holding `LLM_JUDGE` is a state only an older build — or
   * the API — can produce. That is precisely the state under test, and it is
   * why this is a fixture rather than a UI setup step.
   *
   * `writeAlert` rather than `seedAlerts`: `seedAlerts` posts event types with
   * no trigger configs at all, and the config IS the seed here.
   */
  guardrailTriggerAlert: GuardrailTriggerAlertRef;
}

export const test = baseTest.extend<GuardrailTriggerAlertFixtures>({
  guardrailTriggerAlert: async (
    { backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    // Minted client-side: `POST /v1/private/alerts` answers 201 with no body,
    // so without this the teardown would need a workspace-wide paginated read
    // to find the alert by name — the same reason `seedAlerts` does it.
    const ref: GuardrailTriggerAlertRef = {
      id: uuid7(),
      name: `${testNamespace}-guardrail-alert`,
      webhookUrl: 'https://example.com/e2e-webhook-guardrail',
    };

    const created = await backendClient.writeAlert('POST', {
      id: ref.id,
      name: ref.name,
      projectId: project.id,
      webhookUrl: ref.webhookUrl,
      triggers: [
        {
          eventType: ALERT_EVENT_TYPE.traceGuardrailsTriggered,
          triggerConfigs: [
            {
              type: GUARDRAIL_TYPE_CONFIG,
              configValue: { [GUARDRAIL_TYPES_KEY]: SEEDED_GUARDRAIL_TYPES.join(',') },
            },
          ],
        },
      ],
    });
    if (created.status !== 201) {
      throw new Error(
        `guardrailTriggerAlert: POST /v1/private/alerts answered ${created.status}: ` +
          `${created.message}. The seed is the test's precondition, so this is a setup ` +
          'failure rather than something for the spec to assert.',
      );
    }

    // That the seed really holds what it was asked to hold, before any browser
    // opens. A backend that silently dropped an unknown guardrail type would
    // leave the editor showing three rows — which is also what a REGRESSION in
    // 9486's keep-saved-types change looks like, and the two must not be
    // confusable.
    const stored = await backendClient.getAlert(ref.id);
    const storedTypes = stored?.triggers
      .flatMap((trigger) => trigger.triggerConfigs)
      .filter((config) => config.type === GUARDRAIL_TYPE_CONFIG)
      .map((config) => config.configValue[GUARDRAIL_TYPES_KEY]);
    if (storedTypes?.length !== 1 || storedTypes[0] !== SEEDED_GUARDRAIL_TYPES.join(',')) {
      throw new Error(
        `guardrailTriggerAlert: the alert stored ${JSON.stringify(storedTypes)} rather than ` +
          `one ${GUARDRAIL_TYPE_CONFIG} config of "${SEEDED_GUARDRAIL_TYPES.join(',')}" — ` +
          'the seed cannot discriminate, so the spec would assert over a bad precondition.',
      );
    }

    await testInfo.attach('opik.guardrail-trigger-alert', {
      body: JSON.stringify({ ...ref, guardrailTypes: SEEDED_GUARDRAIL_TYPES }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (shouldLeaveArtifacts(testInfo)) {
      console.warn(`[guardrailTriggerAlert] leaving ${ref.name} for debugging`);
      return;
    }
    // Mandatory: an alert does not cascade with its project — `alerts` holds
    // `project_id` with no FK — so it would outlive the project and surface in
    // the next spec's list. Same hazard `alert.fixture.ts` documents.
    try {
      await backendClient.deleteAlertsBatch([ref.id]);
    } catch (err) {
      console.warn(`[guardrailTriggerAlert] delete warning for ${ref.name}:`, err);
    }
  },
});

export { expect } from './project-scoped-ts-clients.fixture';
