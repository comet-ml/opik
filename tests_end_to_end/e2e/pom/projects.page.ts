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
}
