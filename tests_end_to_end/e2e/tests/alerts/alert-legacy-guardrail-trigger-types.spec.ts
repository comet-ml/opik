import {
  test,
  expect,
  ALERT_EVENT_TITLE,
  ALERT_EVENT_TYPE,
  GUARDRAIL_TYPE_CONFIG,
  GUARDRAIL_TYPES_KEY,
  SEEDED_GUARDRAIL_TYPES,
} from '@e2e/fixtures';
import { AlertEditorPage } from '@e2e/pom/alert-editor.page';
import { AlertsPage } from '@e2e/pom/alerts.page';

/**
 * A guardrail type an alert was saved with stays visible and removable, even
 * after the editor stopped offering it (opik#9486, OPIK-9017).
 *
 * 9486 narrowed the guardrail-triggered alert's type list to the three the
 * checks actually run — topic, PII and prompt injection — leaving out LLM judge
 * and custom classifier. The narrowing alone would have been a trap: the
 * checkbox list drove the form's `guardrailTypes` field, so an alert already
 * saved with `LLM_JUDGE` would render four unticked boxes, none of them the
 * stored one, and then SAVE that — silently dropping a filter the user never
 * chose to drop and could not see to remove. So the same PR made the list the
 * offered types PLUS whatever the alert was saved with:
 *
 * ```ts
 * const options = [
 *   ...ALERT_GUARDRAIL_TYPES,
 *   ...selected.filter((type) => !ALERT_GUARDRAIL_TYPES.includes(type)),
 * ];
 * ```
 *
 * ## Why this flow and not the alerts CRUD spec
 *
 * `alerts` reads 9/9 covered, but `alerts-crud.spec.ts` drives `prompt:created`
 * and `trace:cost` — a trigger with no config and a threshold one. Nothing in
 * the estate adds a `trace:guardrails_triggered` trigger at all, so the one
 * trigger type whose config is a SET rather than a number is undriven, and a
 * revert of the three lines above would pass the suite.
 *
 * ## Why the seed has to come from the API
 *
 * The state under test cannot be created through the product any more: the
 * editor offers no LLM-judge checkbox to tick. An alert holding one is what an
 * older build left behind, and the API is the only way to reproduce it — which
 * is what `guardrailTriggerAlert` does, verifying server-side that the type
 * really stored before the browser opens.
 *
 * ## Surface
 *
 * Both, in the direction that catches a disagreement: the alert is written
 * through the API, read back through the editor, changed in the editor, and the
 * result read again through the API. A UI-only version could not tell "the row
 * disappeared because it was unticked" from "the row disappeared and took the
 * stored filter with it", which is the whole failure mode.
 */

/** `GuardrailNamesLabelMap`, for the five types — the editor's own copy. */
const LABEL = {
  TOPIC: 'Topic guardrail',
  PII: 'PII guardrail',
  LLM_JUDGE: 'LLM judge guardrail',
  PROMPT_INJECTION: 'Prompt injection guardrail',
  CUSTOM_CLASSIFIER: 'Custom guardrail',
} as const;

/**
 * The three types a NEW alert is offered, in the order `ALERT_GUARDRAIL_TYPES`
 * lists them. The list 9486 narrowed to.
 */
const OFFERED_LABELS = [LABEL.TOPIC, LABEL.PII, LABEL.PROMPT_INJECTION];

/**
 * What the editor should show for the seeded alert: the three offered types,
 * then the saved-but-no-longer-offered ones appended in stored order.
 *
 * Derived from the seed rather than written out, so the two cannot drift: the
 * seed is `LLM_JUDGE,CUSTOM_CLASSIFIER,PII`, and `PII` is already offered, so
 * only the other two are appended.
 */
const EXPECTED_LABELS = [
  ...OFFERED_LABELS,
  ...SEEDED_GUARDRAIL_TYPES.filter((type) => !['TOPIC', 'PII', 'PROMPT_INJECTION'].includes(type)).map(
    (type) => LABEL[type],
  ),
];

/** Reads one alert's stored guardrail-type strings, in order. */
const storedGuardrailTypes = async (
  backendClient: { getAlert: (id: string) => Promise<{ triggers: Array<{ eventType: string; triggerConfigs: Array<{ type: string; configValue: Record<string, string> }> }> } | null> },
  alertId: string,
): Promise<string[]> => {
  const alert = await backendClient.getAlert(alertId);
  expect(alert, `alert ${alertId} is still there to read`).not.toBeNull();
  return alert!.triggers
    .filter((trigger) => trigger.eventType === ALERT_EVENT_TYPE.traceGuardrailsTriggered)
    .flatMap((trigger) => trigger.triggerConfigs)
    .filter((config) => config.type === GUARDRAIL_TYPE_CONFIG)
    .map((config) => config.configValue[GUARDRAIL_TYPES_KEY]);
};

test.describe('Alerts — guardrail trigger types', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'an alert keeps a guardrail type the editor no longer offers, and unticking it persists',
    { tag: ['@cap:alerts.event-triggers'] },
    async ({ project, guardrailTriggerAlert, backendClient, page }) => {
      const editor = new AlertEditorPage(page);

      await test.step('Open the seeded alert for editing', async () => {
        await editor.gotoEdit(project.id, guardrailTriggerAlert.id);
        await editor.waitForEditReady();
      });

      await test.step('All five types are listed — the three offered, plus the two saved ones', async () => {
        // The whole list and its order, not just "LLM judge is present". A
        // build that had lost the `selected.filter(...)` append would show
        // exactly OFFERED_LABELS, which an is-present check on PII would still
        // pass; and one that listed `Object.values(GuardrailTypes)` — the
        // pre-9486 code — would show all five too, but in the ENUM's order with
        // the narrowing undone. Only the exact sequence tells those apart.
        expect(
          await editor.guardrailTypeLabels(),
          'the offered types first, then the saved-but-unoffered ones appended in stored order',
        ).toEqual(EXPECTED_LABELS);
      });

      await test.step('The saved types are ticked and the rest are not', async () => {
        // Every row, both ways round. Asserting only that the three saved ones
        // are ticked would pass against a form that ticked everything, which
        // would then save two filters the user never chose.
        for (const label of [LABEL.PII, LABEL.LLM_JUDGE, LABEL.CUSTOM_CLASSIFIER]) {
          await expect(
            editor.guardrailTypeCheckbox(label),
            `"${label}" is one of the alert's saved types, so it is ticked`,
          ).toBeChecked();
        }
        for (const label of [LABEL.TOPIC, LABEL.PROMPT_INJECTION]) {
          await expect(
            editor.guardrailTypeCheckbox(label),
            `"${label}" was not saved on this alert, so it is offered unticked`,
          ).not.toBeChecked();
        }
      });

      await test.step('Unticking the LLM-judge type removes its row', async () => {
        await editor.untickGuardrailType(LABEL.LLM_JUDGE);
        // The row goes because it was only ever listed by virtue of being
        // selected — which is what makes "visible so it can be removed" a
        // complete story rather than a permanent extra checkbox. Asserted as a
        // count on the row, so a row that merely unticked would fail here.
        await expect(
          editor.guardrailTypeRow(LABEL.LLM_JUDGE),
          'a saved-but-unoffered type disappears once it is no longer selected',
        ).toHaveCount(0);
        expect(
          await editor.guardrailTypeLabels(),
          'and nothing else moved — the other saved type is still listed and still appended last',
        ).toEqual([...OFFERED_LABELS, LABEL.CUSTOM_CLASSIFIER]);
        await expect(
          editor.guardrailTypeCheckbox(LABEL.CUSTOM_CLASSIFIER),
          'the other legacy type is untouched and still ticked',
        ).toBeChecked();
      });

      await test.step('Saving stores the remaining types and only those', async () => {
        await editor.submit();
        // Server-side, because the editor's own rendering is what was just
        // asserted: the question here is whether the form WROTE what it showed.
        // The whole config list, so a save that appended a second
        // `filter:guardrail_type` config instead of replacing the first fails
        // rather than passing on a `find()`.
        expect(
          await storedGuardrailTypes(backendClient, guardrailTriggerAlert.id),
          'exactly one guardrail-type config, holding the two types left ticked in stored order',
        ).toEqual(['CUSTOM_CLASSIFIER,PII']);
      });

      await test.step('Reopening shows the saved state, with the unticked type gone for good', async () => {
        // The round trip, which is the user-visible consequence: a filter the
        // user removed must not come back, and the one they kept must not have
        // been dropped alongside it.
        //
        // Navigated rather than reloaded: `submit()` resolves on the alerts
        // LIST, which is where the form sends the user, so a reload here would
        // re-render the list and wait for an "Edit alert" heading that is not
        // on it.
        await editor.gotoEdit(project.id, guardrailTriggerAlert.id);
        expect(
          await editor.guardrailTypeLabels(),
          'the removed type is no longer saved, so it is no longer appended either',
        ).toEqual([...OFFERED_LABELS, LABEL.CUSTOM_CLASSIFIER]);
        await expect(
          editor.guardrailTypeCheckbox(LABEL.CUSTOM_CLASSIFIER),
          'and the type that was kept is still ticked',
        ).toBeChecked();
        await expect(
          editor.guardrailTypeCheckbox(LABEL.PII),
          'as is the offered one that was saved with it',
        ).toBeChecked();
      });
    },
  );

  test(
    'a new alert is offered only the three guardrail types the checks run',
    { tag: ['@cap:alerts.event-triggers'] },
    async ({ project, page }) => {
      const alerts = new AlertsPage(page);

      const editor = await test.step('Open the create form and add the guardrail trigger', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        // Through the page's own button rather than a URL, which is how the
        // estate's other alert specs reach the form — and it returns the
        // editor, so the create route is never addressed by hand.
        const created = await alerts.openCreateForm();
        await created.addTrigger(ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.traceGuardrailsTriggered]);
        return created;
      });
      // Nothing is submitted in this test, so no alert is created and there is
      // nothing to clean up: what is under test is the list the form OFFERS,
      // which is readable before any save. Submitting would add a second claim
      // (that the form can save an empty type set) without making this one any
      // stronger.

      await test.step('Exactly topic, PII and prompt injection are offered', async () => {
        // The narrowing itself, on a clean alert: no type is selected, so the
        // appended-saved-types branch contributes nothing and the list is
        // `ALERT_GUARDRAIL_TYPES` alone. This is the half that fails if someone
        // restores `Object.values(GuardrailTypes)` — the test above would still
        // pass against that, because it expects those two labels to be there.
        expect(
          await editor.guardrailTypeLabels(),
          'LLM judge and custom guardrails are not offered on a new alert — only the three ' +
            'the checks support',
        ).toEqual(OFFERED_LABELS);
      });

      await test.step('None of them is ticked, so the alert fires on any guardrail failure', async () => {
        // The documented default ("Leave empty to alert on any guardrail
        // failure"), and the precondition that makes the list above the
        // offered set rather than a selected one.
        for (const label of OFFERED_LABELS) {
          await expect(
            editor.guardrailTypeCheckbox(label),
            `"${label}" starts unticked on a new alert`,
          ).not.toBeChecked();
        }
      });
    },
  );
});
