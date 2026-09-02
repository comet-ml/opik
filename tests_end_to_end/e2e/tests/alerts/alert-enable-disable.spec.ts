import { test, expect } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';

/**
 * The create-alert form no longer offers an "Enable alert" toggle: every alert
 * created through the UI is written `enabled: true`, and disabling one is an
 * edit-only action.
 *
 * This is a silent change in what gets *written*, so the spec asserts the
 * persisted `enabled` value through the API as well as the list's Status badge.
 * A regression that leaves the badge right and the row wrong — or the reverse —
 * fails here; a UI-only assertion would catch only half of it.
 */
test.describe('Alerts — enable/disable', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test('A UI-created alert is enabled with no opt-out, and only the edit form can disable it', { tag: ['@cap:alerts.enable-disable'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    alertsCleanup,
  }) => {
    // A second alert the test never opens. Without it, "the target reads
    // disabled" is equally consistent with an update that disabled every alert
    // in the project — the bystander is what bounds the blast radius.
    const bystanderName = `${testNamespace}-bystander`;
    const bystanderId = await test.step('Seed a bystander alert through the API', async () => {
      const id = await backendClient.createAlert({
        projectId: project.id,
        name: bystanderName,
        webhookUrl: 'https://example.com/bystander-hook',
        eventType: 'prompt:created',
        enabled: true,
      });
      const seeded = await backendClient.getAlert(id);
      expect(seeded?.enabled, 'bystander starts out enabled').toBe(true);
      return id;
    });

    const alerts = new AlertsPage(page);
    const targetName = `${testNamespace}-target`;

    await test.step('The create form offers no way to create a disabled alert', async () => {
      await alerts.gotoNewAlert(project.id);
      await expect(
        alerts.formSwitches,
        'the create form must carry no toggle at all, whatever it is labelled',
      ).toHaveCount(0);
      await expect(alerts.enableAlertLabel).toHaveCount(0);
    });

    await test.step('Create an alert through the form', async () => {
      await alerts.addTrigger('New prompt added');
      await alerts.nameInput.fill(targetName);
      await alerts.endpointUrlInput.fill('https://example.com/target-hook');
      await alerts.submitCreate();
    });

    const targetId = await test.step('The created alert is persisted enabled', async () => {
      const projectAlerts = await backendClient.listAlertsForProject(project.id);
      // The whole answer, not just "mine is in there": a create that also
      // touched the bystander, or that landed twice, fails here.
      expect(projectAlerts.map((a) => a.name).sort()).toEqual(
        [bystanderName, targetName].sort(),
      );

      const target = projectAlerts.find((a) => a.name === targetName);
      expect(target, 'the created alert is scoped to the project it was made in').toBeDefined();
      expect(target!.projectId).toBe(project.id);
      expect(
        target!.enabled,
        'an alert created through the form with no toggle must be written enabled',
      ).toBe(true);
      return target!.id;
    });

    await test.step('The list shows the new alert as Enabled', async () => {
      await expect(alerts.alertNameCell(targetId)).toHaveText(targetName);
      await expect(alerts.alertStatusCell(targetId)).toHaveText('Enabled');
    });

    await test.step('The edit form hydrates exactly one switch, checked', async () => {
      await alerts.openAlertForEdit(targetId);
      await expect(
        alerts.formSwitches,
        'the toggle comes back in edit mode, and only once',
      ).toHaveCount(1);
      await expect(
        alerts.enableAlertSwitch,
        'the switch reflects the alert’s persisted enabled value',
      ).toBeChecked();
    });

    await test.step('Disable the alert and save', async () => {
      await alerts.enableAlertSwitch.click();
      await expect(alerts.enableAlertSwitch).not.toBeChecked();
      await alerts.submitUpdate();
    });

    await test.step('The disabled state is persisted and rendered', async () => {
      const target = await backendClient.getAlert(targetId);
      expect(target?.enabled, 'the edit form can still disable an alert').toBe(false);
      await expect(alerts.alertStatusCell(targetId)).toHaveText('Disabled');
    });

    await test.step('The bystander alert is untouched', async () => {
      const bystander = await backendClient.getAlert(bystanderId);
      expect(
        bystander?.enabled,
        'disabling one alert must not disable the others',
      ).toBe(true);
      await expect(alerts.alertStatusCell(bystanderId)).toHaveText('Enabled');
    });
  });
});
