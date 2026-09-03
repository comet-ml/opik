import { test, expect, ALERT_EVENT_TITLE, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';

const TRACE_ERRORS = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.traceErrors];
const EXPERIMENT_FINISHED = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.experimentFinished];

/**
 * The create form names the alert after its triggers until the user types
 * their own name. The suggestion is a pure function of the trigger config, so
 * every string below is asserted literally rather than rebuilt from the same
 * helper the app uses — a test that recomputed the expected value with
 * `buildAlertName` would agree with any regression in it.
 *
 * "Trace errors", not the "Trace errors threshold" of the trigger picker: the
 * generated name uses the short label, because it appends the trigger's own
 * threshold after it.
 */
const NAMED_BY_TRIGGER = 'Trace errors';
const NAMED_WITH_THRESHOLD = 'Trace errors > 5';
const NAMED_WITH_WINDOW = 'Trace errors > 5 in 5 mins';
const NAMED_WITH_SECOND_TRIGGER = 'Trace errors > 5 in 5 mins +1 more';

test.describe('Alerts — suggested name', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'The create form names the alert after its triggers, and that name is what gets saved',
    { tag: ['@cap:alerts.suggested-alert-name', '@cap:alerts.create-alert'] },
    async ({ project, projectAlertCleanup, backendClient, page }) => {
      const webhookUrl = 'https://example.com/e2e-webhook-suggested-name';
      const alerts = new AlertsPage(page);

      await test.step('Open the alerts list on a project with no alerts', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        await expect(alerts.emptyState).toBeVisible();
      });

      const editor = await test.step('Open the create form', async () =>
        alerts.openCreateForm());

      // The starting point the rest of the test reads against: with no
      // triggers there is nothing to name the alert after, and the project has
      // no alerts whose names could pull in a " (2)" uniqueness suffix.
      await test.step('Verify the name starts empty', async () => {
        await expect(editor.nameInput).toHaveValue('');
      });

      await test.step('Add a trace-errors trigger and verify the name follows it', async () => {
        await editor.addTrigger(TRACE_ERRORS);
        await expect(editor.nameInput).toHaveValue(NAMED_BY_TRIGGER);
      });

      // Threshold and window are set separately: each feeds the name on its
      // own, and setting both at once would not notice one of them dropping out.
      await test.step('Set the threshold and verify it reaches the name', async () => {
        await editor.setTriggerThreshold(ALERT_EVENT_TYPE.traceErrors, '5');
        await expect(editor.nameInput).toHaveValue(NAMED_WITH_THRESHOLD);
      });

      await test.step('Set the rolling window and verify it reaches the name', async () => {
        await editor.setTriggerWindow(ALERT_EVENT_TYPE.traceErrors, '5 mins');
        await expect(editor.nameInput).toHaveValue(NAMED_WITH_WINDOW);
      });

      await test.step('Add a second trigger and verify it is summarised', async () => {
        await editor.addTrigger(EXPERIMENT_FINISHED);
        await expect(editor.nameInput).toHaveValue(NAMED_WITH_SECOND_TRIGGER);
      });

      // Removing is asserted too, not just adding: the name is re-derived by
      // one effect over the whole trigger array, and an effect that only ever
      // grew the name would pass every assertion above.
      await test.step('Remove the second trigger and verify the name is re-derived', async () => {
        await editor.removeTrigger(ALERT_EVENT_TYPE.experimentFinished);
        await expect(editor.nameInput).toHaveValue(NAMED_WITH_WINDOW);
      });

      await test.step('Submit the alert with a webhook destination', async () => {
        await editor.fillWebhookUrl(webhookUrl);
        await editor.submit();
      });

      const alertId = await test.step('Verify the list holds exactly that alert', async () => {
        await alerts.waitForReady();
        // The whole list, not just "a row with this name exists": the alert
        // was never named by the test, so a suggestion that silently saved
        // something else would otherwise leave a passing row lookup behind.
        await expect(alerts.alertRows).toHaveCount(1);
        const id = await alerts.alertRows.getAttribute('data-row-id');
        expect(id).toBeTruthy();

        await expect(alerts.cell(id!, NAMED_WITH_WINDOW)).toBeVisible();
        await expect(alerts.eventsCellContaining(id!, TRACE_ERRORS)).toBeVisible();
        return id!;
      });

      // The list renders the name the client already holds, so it agrees with
      // the form even if nothing was persisted. This is the read that shows
      // the generated string survived the POST.
      await test.step('Verify the persisted alert carries the generated name', async () => {
        const persisted = await backendClient.listAlertsByProject(project.id);
        expect(persisted).toHaveLength(1);
        expect(persisted[0].id).toBe(alertId);
        expect(persisted[0].name).toBe(NAMED_WITH_WINDOW);
      });
    },
  );

  test(
    'A name typed before any trigger exists is never overwritten by one',
    { tag: ['@cap:alerts.suggested-alert-name'] },
    async ({ project, testNamespace, page }) => {
      const typedName = `${testNamespace}-typed-first`;
      const alerts = new AlertsPage(page);

      await test.step('Open the alerts list', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
      });

      const editor = await test.step('Open the create form', async () =>
        alerts.openCreateForm());

      await test.step('Type a name while the form has no triggers', async () => {
        await editor.fillName(typedName);
        await expect(editor.nameInput).toHaveValue(typedName);
      });

      await test.step('Add a trigger and verify the typed name survives', async () => {
        await editor.addTrigger(TRACE_ERRORS);
        await expect(editor.nameInput).toHaveValue(typedName);
      });

      await test.step('Configure the trigger and verify the typed name still survives', async () => {
        await editor.setTriggerThreshold(ALERT_EVENT_TYPE.traceErrors, '5');
        await editor.setTriggerWindow(ALERT_EVENT_TYPE.traceErrors, '5 mins');
        await expect(editor.nameInput).toHaveValue(typedName);
      });
    },
  );

  test(
    'A name typed over a suggestion is never regenerated by a later trigger edit',
    { tag: ['@cap:alerts.suggested-alert-name'] },
    async ({ project, testNamespace, page }) => {
      const typedName = `${testNamespace}-typed-over`;
      const alerts = new AlertsPage(page);

      await test.step('Open the alerts list', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
      });

      const editor = await test.step('Open the create form', async () =>
        alerts.openCreateForm());

      // Asserted, not assumed: if the suggestion never landed there is nothing
      // to type over, and the rest of this test would pass without exercising
      // the guard it exists to check.
      await test.step('Let the form suggest a name from a configured trigger', async () => {
        await editor.addTrigger(TRACE_ERRORS);
        await editor.setTriggerThreshold(ALERT_EVENT_TYPE.traceErrors, '5');
        await editor.setTriggerWindow(ALERT_EVENT_TYPE.traceErrors, '5 mins');
        await expect(editor.nameInput).toHaveValue(NAMED_WITH_WINDOW);
      });

      // `fill` replaces the value in one edit, which is what typing over a
      // selection does. Deliberately not clearing the field first: an empty
      // field is documented to hand naming back to the form, so that path is a
      // different behaviour and not the one under test here.
      await test.step('Type over the suggestion', async () => {
        await editor.fillName(typedName);
        await expect(editor.nameInput).toHaveValue(typedName);
      });

      await test.step('Change the threshold and verify the typed name survives', async () => {
        await editor.setTriggerThreshold(ALERT_EVENT_TYPE.traceErrors, '42');
        await expect(editor.nameInput).toHaveValue(typedName);
      });

      await test.step('Add a second trigger and verify the typed name still survives', async () => {
        await editor.addTrigger(EXPERIMENT_FINISHED);
        await expect(editor.nameInput).toHaveValue(typedName);
      });
    },
  );
});
