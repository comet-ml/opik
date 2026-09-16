import { test, expect, ALERT_EVENT_TITLE, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';
import { AlertEditorPage } from '@e2e/pom/alert-editor.page';

const TRACE_ERRORS = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.traceErrors];
const PROMPT_CREATED = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.promptCreated];

/**
 * What `buildAlertName` produces for a `trace:errors` trigger at each stage of
 * being filled in. Spelled out rather than imported from the frontend: these
 * strings are what a user reads and what gets persisted, so the spec should
 * fail when they change, not silently follow the helper.
 */
const SUGGESTED_BARE = 'Trace errors';
const SUGGESTED_CONFIGURED = 'Trace errors > 5 in 5 mins';
const SUGGESTED_WITH_SECOND = 'Trace errors > 5 in 5 mins +1 more';
/** `ensureUniqueAlertName`'s counter, once the plain name is taken. */
const SUGGESTED_DEDUPED = 'Trace errors > 5 in 5 mins (2)';

test.describe('Alerts — name suggestion', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'The create form names a new alert after its triggers until the user types',
    { tag: ['@cap:alerts.suggest-alert-name', '@cap:alerts.create-alert'] },
    async ({ project, page }) => {
      const alerts = new AlertsPage(page);

      const editor = await test.step('Open the create form on an empty project', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        return alerts.openCreateForm();
      });

      // The field is only suggested-into once there is a trigger to name it
      // after, so an untouched form must not arrive pre-filled.
      await test.step('Verify the name starts empty', async () => {
        await expect(editor.nameInput).toHaveValue('');
      });

      await test.step('Verify adding a trigger names the alert after it', async () => {
        await editor.addTrigger(TRACE_ERRORS);
        await expect(editor.nameInput).toHaveValue(SUGGESTED_BARE);
      });

      // The suggestion tracks the trigger's own config, so it sharpens as the
      // threshold and window are filled rather than freezing at the label.
      await test.step('Verify the threshold and window sharpen the name', async () => {
        await editor.configureThresholdTrigger(ALERT_EVENT_TYPE.traceErrors, '5', '5 mins');
        await expect(editor.nameInput).toHaveValue(SUGGESTED_CONFIGURED);
      });

      await test.step('Verify a second trigger is summarised, not spelled out', async () => {
        await editor.addTrigger(PROMPT_CREATED);
        await expect(editor.nameInput).toHaveValue(SUGGESTED_WITH_SECOND);
      });

      // The whole point of the guard: a typed name has to survive the trigger
      // edits that would otherwise regenerate it.
      const typed = 'my own alert name';
      await test.step('Verify a typed name survives a later trigger change', async () => {
        await editor.fillName(typed);
        await editor.configureThresholdTrigger(ALERT_EVENT_TYPE.traceErrors, '9', '1 hour');
        await expect(editor.nameInput).toHaveValue(typed);
      });

      await test.step('Verify clearing the field hands naming back to the form', async () => {
        await editor.clearName();
        await expect(editor.nameInput).toHaveValue('Trace errors > 9 in 1 hour +1 more');
      });
    },
  );

  test(
    'A suggested name is what persists, and the next alert is de-duplicated against it',
    { tag: ['@cap:alerts.suggest-alert-name', '@cap:alerts.create-alert'] },
    async ({ project, uiAlertCleanup, backendClient, page }) => {
      // Both names are deterministic — one `trace:errors` trigger at 5/5 mins
      // in a project with no other alerts — so cleanup is declared before the
      // first create rather than read back off the form.
      uiAlertCleanup([SUGGESTED_CONFIGURED, SUGGESTED_DEDUPED]);
      const webhookUrl = 'https://example.com/e2e-webhook-suggested-name';
      const alerts = new AlertsPage(page);

      const createWithSuggestedName = async (editor: AlertEditorPage, expectedName: string) => {
        await editor.addTrigger(TRACE_ERRORS);
        await editor.configureThresholdTrigger(ALERT_EVENT_TYPE.traceErrors, '5', '5 mins');
        await expect(editor.nameInput).toHaveValue(expectedName);
        await editor.fillWebhookUrl(webhookUrl);
        await editor.submit();
      };

      await test.step('Create an alert without touching the name field', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        await expect(alerts.emptyState).toBeVisible();
        const editor = await alerts.openCreateForm();
        await createWithSuggestedName(editor, SUGGESTED_CONFIGURED);
      });

      // Asserting the input alone would pass even if the field were decorative
      // and the POST sent something else, so the row and the API are checked.
      const createdId = await test.step('Verify the suggested name is what landed', async () => {
        await alerts.waitForReady();
        const row = page.locator('tbody tr[data-row-id]').filter({ hasText: SUGGESTED_CONFIGURED });
        await expect(row).toHaveCount(1);

        const id = await row.getAttribute('data-row-id');
        expect(id).toBeTruthy();
        await expect(alerts.cell(id!, SUGGESTED_CONFIGURED)).toBeVisible();
        return id!;
      });

      await test.step('Verify the persisted alert carries that name', async () => {
        const persisted = await backendClient.listAlertsInProject(project.id);
        expect(persisted.find((a) => a.id === createdId)?.name).toBe(SUGGESTED_CONFIGURED);
      });

      // The counter is decided against what the project already holds, so this
      // is the assertion that the uniqueness read is wired to real alerts and
      // not to the in-memory form state.
      await test.step('Verify the same trigger now suggests a de-duplicated name', async () => {
        const editor = await alerts.openCreateForm();
        await editor.addTrigger(TRACE_ERRORS);
        await editor.configureThresholdTrigger(ALERT_EVENT_TYPE.traceErrors, '5', '5 mins');
        await expect(editor.nameInput).toHaveValue(SUGGESTED_DEDUPED);
      });
    },
  );
});
