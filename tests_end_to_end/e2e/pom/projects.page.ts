import { test } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The workspace-level Projects list (`/<workspace>/projects`).
 *
 * The list is paginated at 10 rows by default and a real workspace easily runs
 * to dozens of projects, so every row lookup goes through the server-side name
 * search rather than hunting across pages. Rows are keyed by `data-row-id`
 * (the project id) — the only per-row attribute the shared DataTable exposes,
 * and stabler than matching on the name cell.
 */
export class ProjectsPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    return test.step('open the Projects list', async () => {
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/projects`);
      await this.waitForReady();
    });
  }

  async waitForReady(): Promise<void> {
    await this.searchInput.waitFor({ state: 'visible' });
    await this.page.locator('table').waitFor({ state: 'visible' });
  }

  get searchInput(): Locator {
    return this.page.getByTestId('search-input');
  }

  /**
   * Filters the list server-side. Narrowing to a single row is what makes the
   * row assertions meaningful — an unfiltered list only shows the first page.
   */
  async searchByName(name: string): Promise<void> {
    return test.step(`search projects for "${name}"`, async () => {
      await this.searchInput.fill(name);
      await this.page.waitForURL((url) => url.searchParams.get('search') === name);
    });
  }

  projectRow(projectId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${projectId}"]`);
  }

  get projectRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * Deletes via the row's actions menu and confirms. The confirm dialog's
   * heading and its confirm button share the accessible name "Delete project",
   * so the dialog is scoped by heading first (ConfirmDialog is a generic shared
   * component with no data-testid of its own).
   */
  async deleteProjectById(projectId: string): Promise<void> {
    return test.step(`delete project "${projectId}" via row actions`, async () => {
      const row = this.projectRow(projectId);
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

  get deleteProjectConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete project' }),
    });
  }
}
