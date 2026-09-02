import { test, expect } from '@e2e/fixtures';
import { AlertFormPage } from '@e2e/pom/alerts.page';

/**
 * A new alert is created enabled, and the Enable alert switch is edit-only
 * (OPIK-8198).
 *
 * The PR removed the switch from the create form, so a new alert's enabled
 * state now comes entirely from the server default. If that default ever
 * drifts, alerts are created silently disabled and simply never fire — no
 * error, no failed request, and a row in the list that looks exactly like a
 * working alert. The API read is the only thing that catches it, which is why
 * this spec asserts through both surfaces.
 *
 * The edit-side toggle is then driven for real (checked -> off -> persisted),
 * so `alerts.enable-disable` is claimed on an assertion that the control works,
 * not merely that it is on screen.
 */

const ERRORS_TRIGGER = 'Trace errors threshold';
const WEBHOOK_URL = 'https://example.com/opik-e2e-alert-enabled';

test.describe('Alerts — enabled on create', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test('a new alert is created enabled and the toggle lives on the edit form', { tag: ['@cap:alerts.create-alert', '@cap:alerts.enable-disable'] }, async ({
    project,
    backendClient,
    registerAlertCleanup,
    testNamespace,
    page,
  }) => {
    const alertName = `${testNamespace}-enabled`;
    const alertForm = new AlertFormPage(page);

    await test.step('The Create alert form renders no Enable alert control', async () => {
      await alertForm.gotoCreate(project.id);
      await expect(alertForm.enabledSwitch).toHaveCount(0);
      await expect(page.getByText('Enable alert', { exact: true })).toHaveCount(0);
    });

    await test.step('Create an alert without ever touching an enabled control', async () => {
      await alertForm.addTrigger(ERRORS_TRIGGER);
      await alertForm.setThreshold('5');
      await alertForm.selectWindow('5 mins');
      await alertForm.fillName(alertName);
      await alertForm.fillEndpointUrl(WEBHOOK_URL);
      await alertForm.submit('Create alert');
    });

    const alertId = await test.step('The saved alert is enabled server-side', async () => {
      const alerts = await backendClient.listAlertsForProject(project.id);
      for (const alert of alerts) registerAlertCleanup(alert.id, alert.name);

      expect(alerts, 'the form created exactly one alert in this project').toHaveLength(1);
      expect(alerts[0].name).toBe(alertName);
      expect(
        alerts[0].enabled,
        'a create form with no toggle must still produce an enabled alert',
      ).toBe(true);
      return alerts[0].id;
    });

    await test.step('The edit form does render the switch, checked', async () => {
      await alertForm.gotoEdit(project.id, alertId);
      await expect(alertForm.enabledSwitch).toHaveCount(1);
      await expect(alertForm.enabledSwitch).toBeChecked();
    });

    await test.step('Turning the switch off persists', async () => {
      await alertForm.enabledSwitch.click();
      await expect(alertForm.enabledSwitch).not.toBeChecked();
      await alertForm.submit('Update alert');

      const updated = await backendClient.getAlert(alertId);
      expect(updated, `alert ${alertId} still exists after the update`).not.toBeNull();
      expect(updated!.enabled, 'the edit form switch is what disables an alert').toBe(false);
      expect(updated!.name, 'disabling must not disturb the rest of the alert').toBe(alertName);
    });
  });
});
