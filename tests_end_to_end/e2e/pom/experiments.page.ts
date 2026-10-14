import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { ExperimentDetailPage } from './experiment-detail.page';

export class ExperimentsPage {
  private projectId: string | null = null;

  constructor(private readonly page: Page) {}

  /**
   * Open a project's Experiments page.
   *
   * `size` is a real query param (`useTablePageSize` prefers a valid `?size=`
   * over the stored value and over the deployment default), so a spec that
   * asserts over ALL of a project's experiments can pin the page it reads
   * instead of inheriting `UI_DEFAULT_PAGE_SIZE` — which is deployment
   * configuration, not a constant, and would silently turn a membership
   * assertion into one about whatever fitted on page 1.
   */
  async goto(projectId: string, opts: { size?: number } = {}): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    const query = new URLSearchParams();
    if (opts.size !== undefined) query.set('size', String(opts.size));
    const suffix = query.size > 0 ? `?${query}` : '';
    await this.page.goto(
      `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments${suffix}`,
    );
  }

  async waitForReady(): Promise<void> {
    const heading = this.page.getByRole('heading', { name: 'Experiments', level: 1 });
    await heading.waitFor({ state: 'visible' });
    const realRow = this.rows.first();
    const emptyState = this.page.getByText('No experiments yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  async countExperiments(): Promise<number> {
    return this.rows.count();
  }

  rowById(experimentId: string): Locator {
    return this.page.locator(`tr[data-row-id="${experimentId}"]`);
  }

  async expectExperimentNameInList(experimentId: string, expectedName: string): Promise<void> {
    const cell = this.page.locator(`td[data-cell-id="${experimentId}_name"]`);
    await expect(cell, `experiment row ${experimentId} name cell`).toHaveText(expectedName);
  }

  async openExperimentById(experimentId: string): Promise<ExperimentDetailPage> {
    if (!this.projectId) {
      throw new Error('ExperimentsPage.openExperimentById: call goto(projectId) first');
    }
    const row = this.rowById(experimentId);
    await row.waitFor({ state: 'visible' });
    // The row is cursor-pointer but the dataset cell contains a link to the
    // dataset page. Click the experiment-name cell to navigate to detail.
    await this.page.locator(`td[data-cell-id="${experimentId}_name"]`).click();
    await this.page.waitForURL((url) => {
      return (
        url.pathname.includes(`/experiments/`) &&
        url.pathname.endsWith(`/compare`) &&
        url.search.includes(encodeURIComponent(experimentId))
      );
    });
    return new ExperimentDetailPage(this.page, experimentId);
  }

  /**
   * The dialog heading and its confirm button share the name "Delete
   * experiment", so scope by heading first, then resolve the button inside.
   */
  async deleteExperimentById(experimentId: string): Promise<void> {
    return test.step(`delete experiment ${experimentId} via row actions`, async () => {
      const row = this.rowById(experimentId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();

      const confirm = this.deleteExperimentConfirmDialog;
      await confirm.waitFor({ state: 'visible' });
      await confirm.getByRole('button', { name: 'Delete experiment' }).click();

      await confirm.waitFor({ state: 'hidden' });
      // Refetch, not optimistic update, and a cached placeholder renders
      // meanwhile — so wait on the row, not the table.
      await row.waitFor({ state: 'detached' });
    });
  }

  /** The destructive confirm dialog raised by the row's Delete action. */
  get deleteExperimentConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete experiment' }),
    });
  }

  get rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  // --- Selection and the Compare gesture ---

  /**
   * Tick the select checkbox on each named experiment row.
   *
   * By row id, never by position: the list's order is the server's and a
   * positional pick would silently select a different experiment the moment
   * sorting or a new row changes it.
   */
  async selectExperiments(experimentIds: string[]): Promise<void> {
    await test.step(`select ${experimentIds.length} experiment(s)`, async () => {
      for (const id of experimentIds) {
        const checkbox = this.rowById(id).getByRole('checkbox', { name: 'Select row' });
        await expect(checkbox, `select checkbox for experiment ${id}`).toHaveCount(1);
        await checkbox.click();
        await expect(checkbox, `experiment ${id} after ticking`).toBeChecked();
      }
    });
  }

  /** The Compare button in the experiments actions panel. */
  get compareButton(): Locator {
    return this.page.getByRole('button', { name: 'Compare', exact: true });
  }

  /** Click Compare, without asserting what it does — the branch IS the subject. */
  async clickCompare(): Promise<void> {
    await test.step('click Compare', async () => {
      await expect(this.compareButton, 'the Compare button').toBeEnabled();
      await this.compareButton.click();
    });
  }

  /**
   * The same-dataset picker, raised when exactly one experiment is selected.
   *
   * Titled "Compare experiments" — distinct from the mixed-dataset guard
   * below, which is a different dialog with a different title, and telling the
   * two apart is most of what this PR's branches are about.
   */
  get comparePickerDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Compare experiments' }),
    });
  }

  /** The mixed-dataset guard, raised when the selection spans two datasets. */
  get datasetFilterDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Select experiments to compare' }),
    });
  }

  /**
   * One experiment's checkbox inside the compare picker.
   *
   * Scoped to the dialog and found through the entry's NAME, because the
   * picker's rows carry no id: `CompareExperimentsDialog` renders one `<label>`
   * per experiment holding an aria-labelled checkbox beside the name. The
   * `toHaveCount(1)` at each call site is what keeps a name that happens to be
   * a prefix of another from resolving to two.
   */
  comparePickerCheckbox(experimentName: string): Locator {
    return this.comparePickerDialog
      .locator('label')
      .filter({ hasText: experimentName })
      .getByRole('checkbox', { name: 'Select experiment' });
  }

  /** The picker's submit button, whose label carries the running selection count. */
  get comparePickerSubmit(): Locator {
    return this.comparePickerDialog.getByRole('button', { name: /^Compare \d+ experiments?$/ });
  }
}
