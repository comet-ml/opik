import { test, expect } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';

/**
 * The create-alert form names an alert after its triggers, and keeps that name
 * up to date as the trigger's own fields are filled in — until the user types
 * a name of their own, at which point the form must stop touching it.
 *
 * That hand-over is the risky half: a suggestion that overwrote a name the user
 * had already typed would be silent data loss inside the form, with nothing in
 * the API to show for it afterwards. It is asserted here rather than at the API
 * level for exactly that reason — the whole behaviour lives in the form, and
 * the backend only ever sees the final string.
 *
 * Deterministic by construction: threshold triggers need no traces, no feedback
 * score definitions and no provider key, and the de-duplication is scoped to
 * the project, which the `project` fixture makes exclusive to this test.
 */
test.describe('Alerts — generated alert names', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test('The name follows the triggers until the user types one, and returns when the field is cleared', { tag: ['@cap:alerts.create-alert'] }, async ({
    project,
    testNamespace,
    page,
    alertsCleanup,
  }) => {
    const alerts = new AlertsPage(page);
    const typedName = `${testNamespace}-my-own-name`;

    await test.step('A new trigger names the alert after itself', async () => {
      await alerts.gotoNewAlert(project.id);
      await expect(alerts.nameInput, 'the form opens with an empty name').toHaveValue('');

      await alerts.addTrigger('Cost threshold');
      await expect(
        alerts.nameInput,
        'a trigger with nothing filled in yet still names the alert',
      ).toHaveValue('Cost');
    });

    await test.step("The name accretes the trigger's own detail as it is filled in", async () => {
      await alerts.fillTriggerThreshold(0, '7');
      await expect(alerts.nameInput).toHaveValue('Cost > 7');

      await alerts.selectTriggerWindow('1 hour');
      await expect(alerts.nameInput).toHaveValue('Cost > 7 in 1 hour');
    });

    await test.step('A typed name survives edits that would change the suggestion', async () => {
      await alerts.nameInput.fill(typedName);

      await alerts.fillTriggerThreshold(0, '42');
      await expect(
        alerts.nameInput,
        'editing a threshold must not overwrite a name the user typed',
      ).toHaveValue(typedName);

      // A second trigger changes the suggestion's shape (it gains a "+n more"
      // suffix), so it is a stronger negative than another threshold edit.
      await alerts.addTrigger('Experiment finished');
      await expect(
        alerts.nameInput,
        'adding a trigger must not overwrite a name the user typed',
      ).toHaveValue(typedName);
    });

    await test.step('Clearing the field hands naming back to the form', async () => {
      await alerts.nameInput.fill('');
      await expect(
        alerts.nameInput,
        'the suggestion resumes from the triggers as they now stand',
      ).toHaveValue('Cost > 42 in 1 hour +1 more');
    });
  });

  test('A second alert with the same triggers is suggested a de-duplicated name, and the edit form never re-suggests', { tag: ['@cap:alerts.create-alert'] }, async ({
    project,
    backendClient,
    page,
    alertsCleanup,
  }) => {
    const alerts = new AlertsPage(page);
    const firstName = 'Cost > 7 in 1 hour';
    const secondName = 'Cost > 7 in 1 hour (2)';

    // Both alerts are named by the product, so neither carries the run prefix
    // the global sweep looks for — the `alertsCleanup` fixture is what removes
    // them, whatever the outcome.
    const createCostAlert = async (expectedName: string, hookPath: string) => {
      await alerts.gotoNewAlert(project.id);
      await alerts.addTrigger('Cost threshold');
      await alerts.fillTriggerThreshold(0, '7');
      await alerts.selectTriggerWindow('1 hour');
      await expect(alerts.nameInput).toHaveValue(expectedName);
      await alerts.endpointUrlInput.fill(`https://example.com/${hookPath}`);
      await alerts.submitCreate();
    };

    const firstId = await test.step('Create the first alert under its generated name', async () => {
      await createCostAlert(firstName, 'first-hook');

      const projectAlerts = await backendClient.listAlertsForProject(project.id);
      expect(projectAlerts.map((a) => a.name)).toEqual([firstName]);
      return projectAlerts[0].id;
    });

    await test.step('An identical second alert is suggested a suffixed name and saves', async () => {
      await createCostAlert(secondName, 'second-hook');

      const projectAlerts = await backendClient.listAlertsForProject(project.id);
      // The whole answer: the suffix is only useful if BOTH alerts survived —
      // the backend has no unique constraint on alert name, so a save that
      // silently replaced the first one would otherwise read as a pass.
      expect(projectAlerts.map((a) => a.name).sort()).toEqual([firstName, secondName].sort());
    });

    await test.step('Both alerts are listed under their generated names', async () => {
      expect((await alerts.listedAlertNames()).sort()).toEqual([firstName, secondName].sort());
    });

    await test.step('Editing an existing alert never re-suggests over its saved name', async () => {
      await alerts.openAlertForEdit(firstId);
      await expect(alerts.nameInput).toHaveValue(firstName);

      await alerts.addTrigger('Experiment finished');
      await expect(
        alerts.nameInput,
        'the naming effect is exempt in edit mode — a saved name is the user’s',
      ).toHaveValue(firstName);

      await alerts.submitUpdate();

      const edited = await backendClient.getAlert(firstId);
      expect(edited?.name, 'the saved name survives the edit').toBe(firstName);
      expect(
        edited?.eventTypes.sort(),
        'the trigger really was added — the name check above is not vacuous',
      ).toEqual(['experiment:finished', 'trace:cost']);
    });
  });
});
