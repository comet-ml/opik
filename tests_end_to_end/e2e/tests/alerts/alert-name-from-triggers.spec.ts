import { test, expect } from '@e2e/fixtures';
import { AlertFormPage } from '@e2e/pom/alerts.page';

/**
 * A new alert names itself after its triggers until the user types (OPIK-8198).
 *
 * `alertNameHelpers.test.ts` unit-tests the two pure helpers, and nothing
 * covers the wiring: that the suggestion actually reaches the Name field, that
 * it keeps up as the trigger's threshold and window change, that typing wins,
 * that clearing the field hands naming back, and that whatever the field holds
 * at submit is what gets persisted.
 *
 * Worth a permanent test because every failure mode here is silent. There is no
 * error and no failed request — the alert simply lands in the list with a blank
 * or stale name, or with a suggestion that overwrote what the user typed.
 *
 * The trigger set is chosen so the form holds exactly one threshold-style
 * trigger throughout: `Trace errors threshold` carries the detail the name is
 * built from, and the two prompt triggers only ever contribute to the
 * `+N more` suffix. That keeps the threshold input and window select
 * unambiguous, which the POM asserts before touching either.
 */

const ERRORS_TRIGGER = 'Trace errors threshold';
const PROMPT_ADDED_TRIGGER = 'New prompt added';
const PROMPT_VERSION_TRIGGER = 'New prompt version created';

const TYPED_NAME = 'My own alert name';
const WEBHOOK_URL = 'https://example.com/opik-e2e-alert-naming';

test.describe('Alerts — name generated from triggers', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test('the Create alert form names the alert after its triggers until the user types', { tag: ['@cap:alerts.create-alert'] }, async ({
    project,
    backendClient,
    registerAlertCleanup,
    page,
  }) => {
    const alertForm = new AlertFormPage(page);

    await test.step('A fresh Create alert form has an empty Name', async () => {
      await alertForm.gotoCreate(project.id);
      await expect(alertForm.nameInput).toHaveValue('');
    });

    await test.step('Adding a trigger names the alert after it', async () => {
      await alertForm.addTrigger(ERRORS_TRIGGER);
      await expect(alertForm.nameInput).toHaveValue('Trace errors');
    });

    await test.step('The name follows the trigger\'s threshold and window', async () => {
      await alertForm.setThreshold('5');
      await expect(alertForm.nameInput).toHaveValue('Trace errors > 5');

      await alertForm.selectWindow('5 mins');
      await expect(alertForm.nameInput).toHaveValue('Trace errors > 5 in 5 mins');
    });

    await test.step('A second trigger is summarised as "+1 more"', async () => {
      await alertForm.addTrigger(PROMPT_ADDED_TRIGGER);
      await expect(alertForm.nameInput).toHaveValue('Trace errors > 5 in 5 mins +1 more');
    });

    await test.step('A typed name survives further trigger edits', async () => {
      await alertForm.fillName(TYPED_NAME);
      await expect(alertForm.nameInput).toHaveValue(TYPED_NAME);

      await alertForm.addTrigger(PROMPT_VERSION_TRIGGER);
      await expect(
        alertForm.nameInput,
        'adding a third trigger must not overwrite a name the user typed',
      ).toHaveValue(TYPED_NAME);

      await alertForm.setThreshold('7');
      await expect(
        alertForm.nameInput,
        'editing a threshold must not overwrite a name the user typed',
      ).toHaveValue(TYPED_NAME);
    });

    await test.step('Clearing the Name field hands naming back to the form', async () => {
      await alertForm.clearName();
      await expect(
        alertForm.nameInput,
        'the regenerated name reflects the threshold and the two extra triggers',
      ).toHaveValue('Trace errors > 7 in 5 mins +2 more');
    });

    const submittedName = await test.step('Save the alert', async () => {
      await alertForm.fillEndpointUrl(WEBHOOK_URL);
      const nameAtSubmit = await alertForm.nameInput.inputValue();
      await alertForm.submit('Create alert');
      return nameAtSubmit;
    });

    await test.step('The persisted alert carries the name the field held at submit', async () => {
      const alerts = await backendClient.listAlertsForProject(project.id);
      // The generated name deliberately carries no run prefix, so the
      // `cuj-` teardown sweep cannot reach it. Register before asserting.
      for (const alert of alerts) registerAlertCleanup(alert.id, alert.name);

      expect(alerts, 'the form created exactly one alert in this project').toHaveLength(1);
      expect(alerts[0].name).toBe(submittedName);
      expect(alerts[0].name).toBe('Trace errors > 7 in 5 mins +2 more');
      // Sorted: the name is built from the form's trigger order, but the API
      // makes no ordering promise on the way back, and this assertion is about
      // the set that was saved, not about how the backend chose to list it.
      expect([...alerts[0].triggerEventTypes].sort(), 'all three triggers were saved').toEqual(
        ['prompt:committed', 'prompt:created', 'trace:errors'],
      );
      expect(alerts[0].webhookUrl).toBe(WEBHOOK_URL);
    });
  });
});
