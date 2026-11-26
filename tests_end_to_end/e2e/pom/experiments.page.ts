import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { ExperimentDetailPage } from './experiment-detail.page';
import { ExperimentsActionsPanel } from './experiments-actions-panel';

/**
 * The page's two tabs, as `?tab=` spells them (`EXPERIMENTS_PAGE_TAB`).
 *
 * Each tab filters the list on `evaluation_method` — Test suites on
 * `= evaluation_suite`, Datasets on `!=` it — so which tab is open decides
 * which experiments can be selected together at all.
 */
export type ExperimentsPageTab = 'datasets' | 'test_suites';

export class ExperimentsPage {
  private projectId: string | null = null;
  private readonly actions: ExperimentsActionsPanel;

  constructor(private readonly page: Page) {
    this.actions = new ExperimentsActionsPanel(page);
  }

  /**
   * Open a project's Experiments page.
   *
   * `size` is a real query param (`useTablePageSize` prefers a valid `?size=`
   * over the stored value and over the deployment default), so a spec that
   * asserts over ALL of a project's experiments can pin the page it reads
   * instead of inheriting `UI_DEFAULT_PAGE_SIZE` — which is deployment
   * configuration, not a constant, and would silently turn a membership
   * assertion into one about whatever fitted on page 1.
   *
   * `tab` pins which of the two tabs opens. Worth setting whenever the spec
   * cares: with no `?tab=` the page falls back to the tab last opened for this
   * project (localStorage) and then to "Test suites if the project has any
   * suite experiments", so a seed that grows a suite experiment silently moves
   * a spec that assumed Datasets onto the other tab.
   *
   * `groups=[]` opens the list ungrouped. The page groups by Item source by
   * default (OPIK-8654), which adds a group row per item source and gives each
   * experiment row a composite `data-row-id` (`<experimentId>|<group>`), so the
   * id-based locators here only hold on the flat list.
   */
  async goto(
    projectId: string,
    opts: { size?: number; tab?: ExperimentsPageTab } = {},
  ): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    const query = new URLSearchParams({ groups: '[]' });
    if (opts.size !== undefined) query.set('size', String(opts.size));
    if (opts.tab !== undefined) query.set('tab', opts.tab);
    await this.page.goto(
      `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments?${query}`,
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

  /**
   * The Compare button in the experiments actions panel.
   *
   * The panel is shared with the prompt detail page's Experiments tab, so its
   * controls live in `ExperimentsActionsPanel` and are re-exposed here — this
   * page's specs read `experiments.compareButton`, not
   * `experiments.actions.compareButton`.
   */
  get compareButton(): Locator {
    return this.actions.compareButton;
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
   * Titled "Add to compare". A selection spanning two item sources never
   * reaches a dialog: Compare is disabled and explains why in a tooltip.
   */
  get comparePickerDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Add to compare' }),
    });
  }

  /** Hover the Compare button to raise its tooltip. */
  async hoverCompare(): Promise<void> {
    await this.actions.hoverCompare();
  }

  get compareTooltip(): Locator {
    return this.actions.compareTooltip;
  }

  /** The panel's other two actions, which a mixed selection must not disable. */
  get manageTagsButton(): Locator {
    return this.actions.manageTagsButton;
  }

  get deleteButton(): Locator {
    return this.actions.deleteButton;
  }

  /** Every open dialog — for asserting a gesture raised none. */
  get openDialogs(): Locator {
    return this.actions.openDialogs;
  }

  /**
   * Click Compare while it is DISABLED, by dispatching the event on the button
   * itself rather than through the mouse.
   *
   * A real click cannot reach a disabled button, so it would assert nothing
   * about the handler. `handleCompareClick` carries its own
   * `if (compareDisabled) return;` guard, and that second layer is what this
   * drives: a build that lost the `disabled` prop but kept the guard, or kept
   * the prop and lost the guard, are different defects and only one of them is
   * visible to `toBeDisabled()`.
   */
  async dispatchCompareClick(): Promise<void> {
    await test.step('dispatch a click on the disabled Compare button', async () => {
      await expect(
        this.compareButton,
        'dispatchCompareClick is only meaningful while Compare is disabled',
      ).toBeDisabled();
      await this.compareButton.dispatchEvent('click');
    });
  }

  // --- The selection action bar, and the toolbar row it replaces ---

  /**
   * The selection bar's running count, e.g. "Selected: 2".
   *
   * Anchored and exact: the bar renders the count in its own element, and a
   * substring match on "Selected: 2" would also be satisfied by "Selected: 20".
   */
  get selectedCountLabel(): Locator {
    return this.page.getByText(/^Selected: \d+$/);
  }

  get deselectAllButton(): Locator {
    return this.page.getByRole('button', { name: 'Deselect all' });
  }

  async deselectAll(): Promise<void> {
    await test.step('deselect all experiments', async () => {
      await this.deselectAllButton.click();
      await expect(
        this.selectedCountLabel,
        'the selection bar must be gone once nothing is selected',
      ).toHaveCount(0);
    });
  }

  /**
   * The five toolbar controls the selection bar takes the place of, by the
   * label a failure should name.
   *
   * Returned as a map so a spec can assert the whole row appears or disappears
   * together, and say WHICH control broke the claim. The row is swapped
   * wholesale (`selectedRows.length > 0 ? bar : toolbar`), so a control left
   * behind or not restored is the regression.
   */
  get toolbarControls(): Record<string, Locator> {
    return {
      search: this.searchInput,
      groups: this.groupsButton,
      filters: this.filtersButton,
      columns: this.columnsButton,
      refresh: this.refreshButton,
    };
  }

  get searchInput(): Locator {
    return this.page.getByTestId('search-input');
  }

  /** Labelled `Group (N)` — N is the number of applied groupings. */
  get groupsButton(): Locator {
    return this.page.getByRole('button', { name: /^Group \(\d+\)$/ });
  }

  /**
   * The filters control, which has two labels: the list passes
   * `layout="add"`, so it renders as an "Add filter" chip until a filter is
   * applied and as `Filters (N)` afterwards. Matched as either, because the
   * claim here is about the control being in the row at all.
   */
  get filtersButton(): Locator {
    return this.page.getByRole('button', { name: /^(Add filter|Filters \(\d+\))$/ });
  }

  get columnsButton(): Locator {
    return this.page.getByTestId('columns-button');
  }

  /**
   * The refresh button.
   *
   * Icon-only with no accessible name and no `data-testid`, so its rendered
   * lucide icon is the only handle — the same compromise
   * `PromptDetailPage.goToPage` documents. A `data-testid` on the shared
   * `RefreshButton` would be better; it is not added here because these specs
   * also run against deployed Opik builds, where one added alongside them
   * would not exist. The pager's buttons render chevron icons, so this does
   * not collide with them.
   */
  get refreshButton(): Locator {
    return this.page.locator('button:has(svg.lucide-rotate-cw)');
  }

  /**
   * The experiments table's top edge, in viewport pixels.
   *
   * The selection bar replaces the toolbar row instead of stacking on top of
   * it, and is padded to match, so the table below must not move when a row is
   * ticked. A shift is the kind of regression that looks fine in a screenshot
   * of either state and only shows up as the list jumping under the cursor.
   */
  async tableTop(): Promise<number> {
    return test.step('read the experiments table top offset', async () => {
      const table = this.page.locator('table');
      await expect(table, 'exactly one experiments table').toHaveCount(1);
      const box = await table.boundingBox();
      if (box === null) {
        throw new Error('ExperimentsPage.tableTop: the experiments table has no bounding box');
      }
      return Math.round(box.y);
    });
  }

  /**
   * Type a term into the list's search box and wait until the list has settled
   * on `expectedRows`.
   *
   * The wait is on the row count rather than on the request: the input is
   * debounced into a `?search=` query param, so a spec that read the rows
   * straight after filling would be reading the unfiltered list.
   */
  async search(term: string, expectedRows: number): Promise<void> {
    await test.step(`search for "${term}"`, async () => {
      await this.searchInput.fill(term);
      await expect(
        this.rows,
        `rows matching "${term}"`,
      ).toHaveCount(expectedRows, { timeout: 30_000 });
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
