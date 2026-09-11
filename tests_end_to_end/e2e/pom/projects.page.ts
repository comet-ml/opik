import { test } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The workspace's Projects list — the default landing page.
 *
 * Its statistic columns are windowed: the table always asks
 * `GET /v1/private/projects/stats` for the last 30 days (and for
 * `source=sdk`), and marks every column that carries that scope with a
 * "(30d)" suffix in its header. Columns describing the project itself (Name,
 * Last updated, Created) are unscoped and carry no suffix.
 *
 * The list is also paginated at 10 rows and a real workspace runs to dozens of
 * projects, so a spec must `search()` down to its own namespace before
 * asserting on rows — otherwise its rows may simply be on another page.
 */
export class ProjectsPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    return test.step('Open the Projects page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/projects`);
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for the projects table', async () => {
      const realRow = this.page.locator('tbody tr[data-row-id]').first();
      const emptyState = this.page.getByText('There are no projects yet');
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        emptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  /**
   * Narrows the table to projects matching `term`. Shared workspaces (cloud
   * staging) hold hundreds of projects across many runs, so a spec must filter
   * to its own namespace before asserting on rows or it reads someone else's.
   */
  async search(term: string): Promise<void> {
    return test.step(`Search projects for "${term}"`, async () => {
      // The table refetches as you type, so wait for the stats call carrying
      // this term to come back rather than for a fixed delay — otherwise the
      // assertions can read the pre-search rows.
      const settled = this.page.waitForResponse(
        (res) =>
          res.url().includes('/v1/private/projects/stats') &&
          res.url().includes(`name=${encodeURIComponent(term)}`) &&
          res.status() === 200,
      );
      await this.page.getByTestId('search-input').fill(term);
      await settled;
    });
  }

  projectRow(name: string): Locator {
    return this.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  /**
   * A row addressed by project id (the DataTable's `data-row-id`) rather than
   * by name. Preferred when the spec already holds the id from a seeded
   * fixture: it survives a rename and cannot match a second project whose name
   * merely contains this one.
   */
  projectRowById(projectId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${projectId}"]`);
  }

  columnHeader(label: string): Locator {
    return this.page.getByRole('columnheader', { name: label, exact: true });
  }

  /**
   * A statistic cell of a project's row, addressed by the table's own
   * `data-cell-id` (`<rowId>_<columnId>`). The rows carry no per-cell testid
   * and the column order is user-configurable and persisted in localStorage,
   * so a positional nth() would be neither stable nor portable between
   * machines. Column ids are the ones ProjectsPage.tsx declares:
   * `trace_count`, `error_count`, `feedback_scores`, `duration.p50`, …
   */
  statCell(name: string, columnId: string): Locator {
    return this.projectRow(name).locator(`[data-cell-id$="_${columnId}"]`);
  }

  /**
   * Deletes via the row's actions menu and confirms. The confirm dialog's
   * heading and its confirm button share the accessible name "Delete project",
   * so the dialog is scoped by heading first (ConfirmDialog is a generic shared
   * component with no data-testid of its own).
   */
  async deleteProjectById(projectId: string): Promise<void> {
    return test.step(`Delete project "${projectId}" via row actions`, async () => {
      const row = this.projectRowById(projectId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();

      const confirm = this.deleteProjectConfirmDialog;
      await confirm.waitFor({ state: 'visible' });
      await confirm.getByRole('button', { name: 'Delete project' }).click();

      await confirm.waitFor({ state: 'hidden' });
      await row.waitFor({ state: 'detached' });
    });
  }

  /** The destructive confirm dialog raised by the row's Delete action. */
  get deleteProjectConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete project' }),
    });
  }
}
