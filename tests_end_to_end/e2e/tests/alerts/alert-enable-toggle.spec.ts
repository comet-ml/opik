import { test, expect, ALERT_EVENT_TITLE, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';

const PROMPT_CREATED = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.promptCreated];

/**
 * Where the "Enable alert" toggle lives, and what a new alert lands as
 * (OPIK-8198 / opik#8099).
 *
 * 8099 changed both halves: the switch is now rendered only on the edit route
 * (`{isEdit && …}` in `AlertForm`), and on that route it sits immediately
 * before the submit row rather than elsewhere in the form.
 *
 * The half worth failing over is the create one. A create form that shows no
 * enable control has decided the answer on the user's behalf, and a new alert
 * silently landing DISABLED is the kind of wrongness nobody notices until an
 * alert that should have fired did not — there is no screen that reports it.
 * So the create assertion is not "the switch is absent" alone; it is "the
 * switch is absent AND the alert the form then wrote is enabled", read back
 * over the API rather than off the form that claimed it.
 */
test.describe('Alerts — enable toggle', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'The toggle is absent on create, and present above the submit row on edit',
    { tag: ['@cap:alerts.enable-disable', '@cap:alerts.create-alert'] },
    async ({ project, testNamespace, uiAlertCleanup, backendClient, page }) => {
      const alertName = `${testNamespace}-toggle`;
      // Declared before the create, not after: the alert exists from the
      // moment the form submits, and a failure between submit and read-back
      // must still take it with it. Alerts do not cascade with the project.
      uiAlertCleanup([alertName]);

      const alerts = new AlertsPage(page);

      const editor = await test.step('Open the create form', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        return alerts.openCreateForm();
      });

      await test.step('The create form renders no enable switch at all', async () => {
        await editor.addTrigger(PROMPT_CREATED);
        await editor.fillName(alertName);
        await editor.fillWebhookUrl(`https://example.com/e2e-webhook-${alertName}`);
        // Count, not "not visible": the control must not be on the form, and a
        // hidden-but-present switch would satisfy a visibility check while
        // still being something the form could submit a value from.
        await expect(editor.enableAlertSwitch).toHaveCount(0);
      });

      const alertId = await test.step('Submit, and find the alert that landed', async () => {
        await editor.submit();
        await alerts.waitForReady();

        const persisted = await backendClient.listAlertsInProject(project.id);
        const match = persisted.filter((a) => a.name === alertName);
        // Exactly one: two would mean a previous run leaked into this project
        // and the read-back below would be asserting about a stranger.
        expect(match, `exactly one alert named ${alertName} in this project`).toHaveLength(1);
        return match[0].id;
      });

      await test.step('The alert the form created is enabled', async () => {
        const created = await backendClient.getAlert(alertId);
        expect(created, `alert ${alertId} is readable after create`).not.toBeNull();
        expect(
          created!.enabled,
          'a create form with no enable control must still land the alert enabled',
        ).toBe(true);
      });

      await test.step('The edit form renders the switch, hydrated from that value', async () => {
        await editor.gotoEdit(project.id, alertId);
        await expect(editor.enableAlertSwitch).toHaveCount(1);
        await expect(editor.enableAlertSwitch).toBeChecked();
      });

      // The placement half. 8099 moved this control, and "above the submit
      // row" is the arrangement it moved it into — a toggle rendered below the
      // buttons is past where a user stops reading the form.
      await test.step('The switch sits above the submit button', async () => {
        const switchBox = await editor.enableAlertSwitch.boundingBox();
        const submitBox = await editor.submitButton.boundingBox();
        expect(switchBox, 'the enable switch has a rendered box').not.toBeNull();
        expect(submitBox, 'the submit button has a rendered box').not.toBeNull();
        expect(
          switchBox!.y + switchBox!.height,
          'the enable switch must end above the submit button starts',
        ).toBeLessThanOrEqual(submitBox!.y);
      });

      // `setEnabled` asserts the starting state before it clicks, so a form
      // that hydrated the switch from the wrong value fails inside it.
      await test.step('Flipping the switch off persists', async () => {
        await editor.setEnabled(false);
        await editor.submit();

        const updated = await backendClient.getAlert(alertId);
        expect(updated, `alert ${alertId} survives the update`).not.toBeNull();
        expect(updated!.enabled, 'the disabled alert is what persisted').toBe(false);
        // The update must have changed the enable flag and nothing else — a
        // PUT that dropped the trigger or the destination would leave the
        // alert "disabled" in a way this spec would otherwise call a pass.
        expect(updated!.name, 'the update kept the name').toBe(alertName);
        expect(
          updated!.triggers.map((trigger) => trigger.eventType),
          'the update kept the trigger',
        ).toEqual([ALERT_EVENT_TYPE.promptCreated]);
      });
    },
  );
});
