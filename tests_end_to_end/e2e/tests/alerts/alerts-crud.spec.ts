import { test, expect, ALERT_EVENT_TITLE, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';
import { AlertEditorPage } from '@e2e/pom/alert-editor.page';

const PROMPT_CREATED = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.promptCreated];
const EXPERIMENT_FINISHED = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.experimentFinished];
const COST_THRESHOLD = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.traceCost];

test.describe('Alerts — smoke', { tag: ['@t1-smoke', '@area:alerts'] }, () => {
  test(
    'Seeded alerts render on the list with destination, events and status',
    { tag: ['@cap:alerts.list-alerts'] },
    async ({ project, alert, seedAlerts, page }) => {
      const [disabled] = await test.step('Seed a second, disabled alert', async () =>
        seedAlerts([
          {
            suffix: 'disabled',
            enabled: false,
            eventTypes: [ALERT_EVENT_TYPE.experimentFinished],
          },
        ]));

      const alerts = new AlertsPage(page);

      await test.step('Open the alerts list', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
      });

      await test.step('Verify the enabled alert row', async () => {
        await expect(alerts.alertRow(alert.id)).toBeVisible();
        await expect(alerts.cell(alert.id, alert.name)).toBeVisible();
        await expect(alerts.cell(alert.id, 'General')).toBeVisible();
        await expect(alerts.cell(alert.id, PROMPT_CREATED)).toBeVisible();
        await expect(alerts.cell(alert.id, 'Enabled')).toBeVisible();
      });

      // Both rows are checked so a Status column stuck on a constant still
      // fails — one row alone cannot show that.
      await test.step('Verify the disabled alert row reports its own status', async () => {
        await expect(alerts.cell(disabled.id, disabled.name)).toBeVisible();
        await expect(alerts.cell(disabled.id, EXPERIMENT_FINISHED)).toBeVisible();
        await expect(alerts.cell(disabled.id, 'Disabled')).toBeVisible();
      });

      await test.step('Verify search narrows the list to one alert', async () => {
        await alerts.searchByName('disabled');
        await expect(alerts.alertRow(disabled.id)).toBeVisible();
        await expect(alerts.alertRow(alert.id)).toHaveCount(0);

        await alerts.searchByName('');
        await expect(alerts.alertRow(alert.id)).toBeVisible();
      });
    },
  );

  test(
    'An alert created through the form lands on the list with its webhook destination',
    { tag: ['@cap:alerts.create-alert', '@cap:alerts.webhook-destination'] },
    async ({ project, uiAlertCleanup, testNamespace, backendClient, page }) => {
      const name = `${testNamespace}-alert-ui-created`;
      // Declared before the create, so a failure mid-flow cannot skip cleanup.
      uiAlertCleanup([name]);
      const webhookUrl = 'https://example.com/e2e-webhook-ui-created';
      const alerts = new AlertsPage(page);

      await test.step('Open the alerts list on an empty project', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        await expect(alerts.emptyState).toBeVisible();
      });

      const editor = await test.step('Open the create form from the empty list', async () =>
        alerts.openCreateForm());

      await test.step('Fill the alert with a Slack destination and one trigger', async () => {
        await editor.fillName(name);
        await editor.selectDestination('Slack');
        await editor.fillWebhookUrl(webhookUrl);
        await editor.addTrigger(PROMPT_CREATED);
        await editor.submit();
      });

      const createdId = await test.step('Verify the new alert is on the list', async () => {
        await alerts.waitForReady();
        const row = page.locator('tbody tr[data-row-id]').filter({ hasText: name });
        await expect(row).toHaveCount(1);

        const id = await row.getAttribute('data-row-id');
        expect(id).toBeTruthy();

        await expect(alerts.cell(id!, 'Slack')).toBeVisible();
        await expect(alerts.cell(id!, PROMPT_CREATED)).toBeVisible();
        await expect(alerts.cell(id!, 'Enabled')).toBeVisible();
        return id!;
      });

      // The endpoint URL is not in the list's default columns, so the one field
      // the feature exists to deliver would otherwise go unasserted.
      await test.step('Verify the webhook URL persisted, via the edit form', async () => {
        const reopened = new AlertEditorPage(page);
        await reopened.gotoEdit(project.id, createdId);
        await expect(reopened.nameInput).toHaveValue(name);
        await expect(reopened.webhookUrlInput).toHaveValue(webhookUrl);
        await expect(reopened.destinationOption('Slack')).toBeChecked();
      });

      await test.step('Verify the alert exists in the API', async () => {
        const persisted = await backendClient.listAlertsWithPrefix(name);
        expect(persisted.map((a) => a.id)).toContain(createdId);
      });
    },
  );
});

test.describe('Alerts — lifecycle', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'Editing an alert updates its name, triggers and enabled state on the list',
    {
      tag: [
        '@cap:alerts.edit-alert',
        '@cap:alerts.event-triggers',
        '@cap:alerts.enable-disable',
      ],
    },
    async ({ project, alert, page }) => {
      const alerts = new AlertsPage(page);
      const renamed = `${alert.name}-renamed`;

      await test.step('Open the alerts list with the seeded alert enabled', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        await expect(alerts.cell(alert.id, 'Enabled')).toBeVisible();
      });

      const editor = await test.step('Open the edit form from the row actions', async () =>
        alerts.openEditForm(alert.id));

      await test.step('Verify the form hydrated from the seeded alert', async () => {
        await expect(editor.nameInput).toHaveValue(alert.name);
        await expect(editor.webhookUrlInput).toHaveValue(alert.webhookUrl);
        await expect(editor.enableAlertSwitch).toBeChecked();
        await expect(editor.destinationOption('General')).toBeChecked();
      });

      await test.step('Rename, add a configured threshold trigger, and disable', async () => {
        await editor.fillName(renamed);
        await editor.addTrigger(COST_THRESHOLD);
        await editor.configureThresholdTrigger(ALERT_EVENT_TYPE.traceCost, '100', '1 hour');
        await editor.setEnabled(false);
        await editor.submit();
      });

      await test.step('Verify every edit landed on the list row', async () => {
        await alerts.waitForReady();
        await expect(alerts.cell(alert.id, renamed)).toBeVisible();
        await expect(alerts.cell(alert.id, 'Disabled')).toBeVisible();
        // Asserted as membership, not as one string: AlertDAO.FIND aggregates
        // triggers with JSON_ARRAYAGG and no ORDER BY, and AlertsEventsCell
        // joins them in API order, so the order is not guaranteed.
        await expect(alerts.eventsCellContaining(alert.id, PROMPT_CREATED)).toBeVisible();
        await expect(alerts.eventsCellContaining(alert.id, COST_THRESHOLD)).toBeVisible();
      });

      // A reopen, not a reload: the threshold and window live in trigger
      // configs the list never renders.
      await test.step('Verify the trigger config round-tripped', async () => {
        await editor.gotoEdit(project.id, alert.id);
        await expect(editor.nameInput).toHaveValue(renamed);
        await expect(editor.enableAlertSwitch).not.toBeChecked();

        const costConfig = editor.triggerConfig(ALERT_EVENT_TYPE.traceCost);
        await expect(costConfig.locator('input[type="number"]')).toHaveValue('100');
        await expect(costConfig.getByRole('combobox')).toHaveText('1 hour');
      });
    },
  );

  test(
    'Deleting an alert removes only that alert, and a bulk delete clears the rest',
    { tag: ['@cap:alerts.delete-alert'] },
    async ({ project, seedAlerts, backendClient, testNamespace, page }) => {
      const [doomed, survivor, alsoDoomed] = await test.step('Seed three alerts', async () =>
        seedAlerts([{ suffix: 'doomed' }, { suffix: 'survivor' }, { suffix: 'also-doomed' }]));

      const alerts = new AlertsPage(page);

      await test.step('Open the alerts list with all three present', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        await expect(alerts.alertRow(doomed.id)).toBeVisible();
        await expect(alerts.alertRow(survivor.id)).toBeVisible();
        await expect(alerts.alertRow(alsoDoomed.id)).toBeVisible();
      });

      await test.step('Delete one alert through its row actions', async () => {
        await alerts.deleteAlert(doomed.id);
      });

      await test.step('Verify only that alert left the list', async () => {
        await expect(alerts.alertRow(doomed.id)).toHaveCount(0);
        await expect(alerts.alertRow(survivor.id)).toBeVisible();
        await expect(alerts.alertRow(alsoDoomed.id)).toBeVisible();
      });

      await test.step('Verify the deletion survives a reload', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        await expect(alerts.alertRow(doomed.id)).toHaveCount(0);
        await expect(alerts.alertRow(survivor.id)).toBeVisible();
      });

      await test.step('Bulk-delete the two remaining alerts', async () => {
        await alerts.deleteAlerts([survivor.id, alsoDoomed.id]);
      });

      await test.step('Verify the list is empty again', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        await expect(alerts.emptyState).toBeVisible();
      });

      await test.step('Verify all three are gone from the API', async () => {
        const remaining = await backendClient.listAlertsWithPrefix(`${testNamespace}-alert-`);
        expect(remaining).toHaveLength(0);
      });
    },
  );
});
