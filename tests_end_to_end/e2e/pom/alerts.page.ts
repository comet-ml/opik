import { test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { AlertEditorPage } from './alert-editor.page';

/** The project-scoped alerts list at /projects/$projectId/alerts. */
export class AlertsPage {
  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    return test.step('Open the alerts list', async () => {
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/alerts`);
    });
  }

  /**
   * Race a real row against the empty state — the table unmounts entirely on a
   * project with no alerts, so waiting on the table alone hangs there (and
   * after the last alert is deleted).
   *
   * Skeleton rows carry no `data-row-id`, so neither branch can match a
   * still-loading table. Allows longer than the 15s default action timeout:
   * the first list read after an idle backend routinely takes ~20s, which is
   * every worker's opening step on a cold run.
   */
  async waitForReady(timeoutMs = 30_000): Promise<void> {
    return test.step('Wait for alerts list ready', async () => {
      await Promise.race([
        this.page
          .locator('tbody tr[data-row-id]')
          .first()
          .waitFor({ state: 'visible', timeout: timeoutMs }),
        this.emptyState.waitFor({ state: 'visible', timeout: timeoutMs }),
      ]);
    });
  }

  /**
   * Row scoped by alert id. DataTable stamps `data-row-id` with the entity id,
   * which pins the row to the alert under test — every alert in a given spec
   * shares a name prefix.
   */
  alertRow(alertId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${alertId}"]`);
  }

  get emptyState(): Locator {
    return this.page.getByText('No alerts yet');
  }

  get createAlertButton(): Locator {
    return this.page.getByRole('button', { name: 'Create alert' });
  }

  /**
   * A cell of an alert's row, addressed by the text it should hold.
   *
   * Matching on content rather than column position: the visible column set is
   * user-configurable and persisted in localStorage, so an index-based lookup
   * would read the wrong cell on any profile that differs from a clean one.
   */
  cell(alertId: string, text: string): Locator {
    return this.alertRow(alertId).getByRole('cell', { name: text, exact: true });
  }

  /**
   * The Events cell of an alert's row, located by the trigger title it must
   * contain.
   *
   * Separate from `cell()` because the cell holds a joined list of every
   * trigger, so an exact-name match never hits it. Both this and `cell()`
   * avoid column indices: the visible column set is user-configurable and
   * persisted in localStorage.
   */
  eventsCellContaining(alertId: string, triggerTitle: string): Locator {
    return this.alertRow(alertId).getByRole('cell').filter({ hasText: triggerTitle });
  }

  /** Opens the create form through the page's own CTA. */
  async openCreateForm(): Promise<AlertEditorPage> {
    return test.step('open the create-alert form', async () => {
      await this.createAlertButton.click();
      const editor = new AlertEditorPage(this.page);
      await editor.waitForCreateReady();
      return editor;
    });
  }

  /** Opens an alert's edit form through its row actions menu. */
  async openEditForm(alertId: string): Promise<AlertEditorPage> {
    return test.step(`open the edit form for alert ${alertId}`, async () => {
      await this.openRowActions(alertId);
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      const editor = new AlertEditorPage(this.page);
      await editor.waitForEditReady();
      return editor;
    });
  }

  /**
   * Deletes an alert through its row's kebab menu, confirming the destructive
   * dialog. Resolves once the row has left the list.
   *
   * The confirm button is dialog-scoped and matched exactly: "Delete alert"
   * also names the dialog's heading, and "Delete" alone additionally matches
   * the kebab menu item still in the DOM.
   */
  async deleteAlert(alertId: string): Promise<void> {
    return test.step(`delete alert ${alertId} via row actions`, async () => {
      await this.openRowActions(alertId);
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();

      const confirm = this.confirmDialog('Delete alert');
      await confirm.waitFor({ state: 'visible' });
      await confirm.getByRole('button', { name: 'Delete alert', exact: true }).click();

      await confirm.waitFor({ state: 'hidden' });
      await this.alertRow(alertId).waitFor({ state: 'detached' });
    });
  }

  /**
   * Deletes several alerts at once: tick each row, then the toolbar's
   * bulk-delete button and its confirm dialog.
   */
  async deleteAlerts(alertIds: string[]): Promise<void> {
    return test.step(`bulk-delete ${alertIds.length} alert(s)`, async () => {
      for (const id of alertIds) {
        const row = this.alertRow(id);
        await row.waitFor({ state: 'visible' });
        await row.getByRole('checkbox', { name: 'Select row' }).click();
      }

      await this.page.getByTestId('alerts-bulk-delete-button').click();

      const confirm = this.confirmDialog('Delete alerts');
      await confirm.waitFor({ state: 'visible' });
      await confirm.getByRole('button', { name: 'Delete alerts', exact: true }).click();
      await confirm.waitFor({ state: 'hidden' });

      for (const id of alertIds) {
        await this.alertRow(id).waitFor({ state: 'detached' });
      }
    });
  }

  /** Filters the list by name. Pass an empty string to clear the filter. */
  async searchByName(term: string): Promise<void> {
    return test.step(`search alerts for "${term}"`, async () => {
      await this.page.getByTestId('search-input').fill(term);
      // Debounced and pushed through the URL, so the settled query string —
      // not a row count — is the signal that the term was applied.
      await this.page.waitForURL(
        (url) => (url.searchParams.get('alerts_search') ?? '') === term,
      );
    });
  }

  private async openRowActions(alertId: string): Promise<void> {
    const row = this.alertRow(alertId);
    await row.waitFor({ state: 'visible' });
    await row.getByRole('button', { name: 'Actions menu' }).click();
  }

  private confirmDialog(heading: string): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: heading }),
    });
  }
}
