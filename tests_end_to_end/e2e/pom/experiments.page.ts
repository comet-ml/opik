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
   *
   * `groups=[]` opens the list ungrouped. The page groups by Item source by
   * default (OPIK-8654), which adds a group row per item source and gives each
   * experiment row a composite `data-row-id` (`<experimentId>|<group>`), so the
   * id-based locators here only hold on the flat list.
   */
  async goto(projectId: string, opts: { size?: number } = {}): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    const query = new URLSearchParams({ groups: '[]' });
    if (opts.size !== undefined) query.set('size', String(opts.size));
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

  // --- The GROUPED list (the page's default since opik#9374 / OPIK-8654) ---

  /**
   * Open a project's Experiments page as a user who has never changed the
   * grouping sees it.
   *
   * Three things make this different from `goto()`, which states `groups=[]`:
   *
   *  1. No `groups` param at all, because the DEFAULT is the subject. Sending
   *     the default explicitly would assert that the page honours a param,
   *     which is a different claim and one that holds on a build whose default
   *     had been removed.
   *  2. The stored grouping is cleared first. `useExperimentsGroupsAndFilters`
   *     reads through `useQueryParamAndLocalStorageState`, so with no param the
   *     page takes whatever `experiments-columns-groups` holds and only falls
   *     back to `DEFAULT_GROUPS` when that is empty. That key is per WORKSPACE,
   *     not per project, so a profile that had ever cleared the grouping would
   *     make this open flat — and the storage state the suite authenticates
   *     with is shared. Clearing it is how "a context that has never set a
   *     grouping" is expressed; the fallback it exercises is the same one a
   *     genuinely new profile takes.
   *  3. It lands on the Datasets tab's grouped table rather than the flat one,
   *     so the row locators to use afterwards are the group-aware ones below,
   *     not `rowById`.
   */
  async gotoDefaultGrouped(projectId: string, opts: { size?: number } = {}): Promise<void> {
    return test.step(`Open Experiments for project ${projectId} with the default grouping`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      // Must be on the origin before localStorage is reachable.
      await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments`);
      await this.page.evaluate(() => {
        window.localStorage.removeItem('experiments-columns-groups');
      });
      const query = new URLSearchParams();
      if (opts.size !== undefined) query.set('size', String(opts.size));
      const suffix = query.toString() ? `?${query}` : '';
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments${suffix}`,
      );
    });
  }

  /**
   * The grouping control, whose label carries the number of active groups —
   * "Group (1)" by default, "Group (0)" once cleared.
   *
   * Matched on the prefix so the count can be ASSERTED by the caller rather
   * than baked into the locator: a getter that only resolved "Group (1)" would
   * make "the grouping was cleared" a timeout instead of a comparison.
   */
  get groupsButton(): Locator {
    return this.page.getByRole('button', { name: /^Group \(\d+\)$/ });
  }

  /**
   * Every group row in the table.
   *
   * Group rows are the ones whose `data-row-id` starts with the frontend's
   * `GROUPING_ROW_PREFIX` sentinel (`$__grouping__`), which is what
   * `checkIsGroupRowType` tests. Experiment rows under a group carry a
   * composite id instead — `<experimentId>|<groupRowId>|<meta>` — so the two
   * are distinguishable without any positional reasoning.
   */
  get groupRows(): Locator {
    return this.page.locator('tbody tr[data-row-id^="$__grouping__"]');
  }

  /**
   * One group row, addressed by the NAME its grouping cell renders.
   *
   * By identity rather than by the row id, even though the row id is a stable
   * `data-row-id`: for the default grouping that id is
   * `$__grouping___dataset_id_:<md5 of the dataset id>`, and re-deriving the
   * hash here would make the POM a paraphrase of `buildGroupFieldId` that goes
   * quietly wrong the day the frontend changes it. The group's own dataset name
   * is the identity a reader of the table uses, and callers seed run-prefixed
   * names, so `exact` is safe.
   *
   * Scoped to group rows only: an experiment row also renders its dataset's
   * name, in its own Item source cell.
   */
  groupRowFor(groupName: string): Locator {
    return this.groupRows.filter({
      has: this.page.getByText(groupName, { exact: true }),
    });
  }

  /**
   * One cell of one group row, by column id.
   *
   * Addressed through the cell's own `data-cell-id` suffix rather than by
   * position. The shared `DataTable` stamps `data-cell-id="<rowId>_<columnId>"`,
   * and in this table the column ORDER is user-configurable and persisted, so
   * an `nth-child` would read a different column for the next profile. The
   * suffix form avoids having to name the row id (see `groupRowFor`).
   *
   * Column ids are the table's own: `total_estimated_cost_avg` is the Cost per
   * trace (avg.) column the page shows by DEFAULT, and `duration_p50` is
   * Duration (avg.) — note the dot in the column id `duration.p50` is replaced
   * in the rendered cell id.
   */
  groupRowCell(groupName: string, columnId: string): Locator {
    return this.groupRowFor(groupName).locator(`td[data-cell-id$="_${columnId}"]`);
  }

  /**
   * The expand/collapse control on a group row.
   *
   * Its accessible name is the group's PREFIX — "Dataset", "Test suite" or
   * "Item source" — because `ItemSourceGroupCell` renders the prefix as the
   * button's only text (the chevron beside it is an unlabelled icon). Scoped to
   * the one row, so the shared name is unambiguous.
   */
  groupExpandToggle(groupName: string, prefix = 'Dataset'): Locator {
    return this.groupRowFor(groupName).getByRole('button', { name: prefix, exact: true });
  }

  /**
   * Whether a group row is currently expanded, read off its chevron.
   *
   * A CSS class, and the only handle there is: `ItemSourceGroupCell` renders
   * `row.getIsExpanded() ? <ChevronDown/> : <ChevronRight/>` and sets no
   * `aria-expanded`, no `data-state` and no testid on the toggle, so the icon
   * identity is the only thing in the DOM that distinguishes the two states.
   * The accessible name is the group PREFIX in both. Lucide stamps
   * `lucide-chevron-down` / `lucide-chevron-right`, which is at least an
   * identity rather than a position — but the right fix is an `aria-expanded`
   * on that Button, which would also make the control answerable to a screen
   * reader; this POM cannot add it because the QA branch may not touch
   * `apps/opik-frontend/`.
   *
   * Asserted to have resolved exactly one chevron before answering: a lookup
   * that found neither state would otherwise return `false` and read as
   * "collapsed", which is a plausible-looking answer that no amount of waiting
   * would correct.
   */
  async groupIsExpanded(groupName: string, prefix = 'Dataset'): Promise<boolean> {
    return test.step(`read whether group "${groupName}" is expanded`, async () => {
      const toggle = this.groupExpandToggle(groupName, prefix);
      await expect(toggle, `the expand toggle of group '${groupName}'`).toHaveCount(1);
      const down = toggle.locator('svg.lucide-chevron-down');
      const right = toggle.locator('svg.lucide-chevron-right');
      const [downCount, rightCount] = await Promise.all([down.count(), right.count()]);
      if (downCount + rightCount !== 1) {
        throw new Error(
          `ExperimentsPage.groupIsExpanded('${groupName}'): the toggle renders ` +
            `${downCount} down and ${rightCount} right chevrons — expected exactly one, so the ` +
            'expansion state cannot be read. The icon is the only handle; see the doc comment.',
        );
      }
      return downCount === 1;
    });
  }

  /**
   * Expand a group row if it is not already expanded, and leave it expanded.
   *
   * Needed because the page auto-expands the FIRST group on load
   * (`useExperimentsAutoExpandingLogic` → `generateAutoExpandMap` over
   * `flattenGroups[0]`), so a spec that clicked every group's toggle to open
   * them would CLOSE that one. A caller that wants a group open has to say so
   * declaratively rather than by gesture.
   *
   * Deliberately not used by specs whose subject IS the auto-expansion: this
   * reads the state it then changes, so it cannot also be the thing that
   * asserts it.
   */
  async ensureGroupExpanded(groupName: string, prefix = 'Dataset'): Promise<void> {
    return test.step(`ensure group "${groupName}" is expanded`, async () => {
      if (await this.groupIsExpanded(groupName, prefix)) return;
      await this.groupExpandToggle(groupName, prefix).click();
      await expect
        .poll(() => this.groupIsExpanded(groupName, prefix), {
          message: `group '${groupName}' must be expanded after its toggle is clicked`,
          timeout: 10_000,
        })
        .toBe(true);
    });
  }

  /**
   * One experiment's row INSIDE a group.
   *
   * The grouped list's row id is `<experimentId>|<groupRowId>|<groupMeta>`, so
   * the bare-id locator `rowById` resolves nothing here. Anchored with `^=` on
   * the experiment id followed by the separator, so an id that is a prefix of
   * another cannot match — and `toHaveCount(1)` at the call site is what turns
   * an ambiguous match into a failure rather than a silent pick.
   */
  groupedExperimentRow(experimentId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id^="${experimentId}|"]`);
  }

  /** One cell of an experiment's row inside a group, by column id. */
  groupedExperimentCell(experimentId: string, columnId: string): Locator {
    return this.groupedExperimentRow(experimentId).locator(`td[data-cell-id$="_${columnId}"]`);
  }

  /**
   * Wait until the grouped table has rendered at least one group row.
   *
   * `waitForReady()` races a real row against "No experiments yet", and on the
   * grouped list the first thing to appear is a group row — which `rows`
   * matches as well, since it matches every `data-row-id`. This is the narrower
   * wait a spec about group rows wants: it does not return while the table
   * still holds only the flat placeholder.
   */
  async waitForGroupedReady(opts: { timeout?: number } = {}): Promise<void> {
    return test.step('Wait for the grouped Experiments table', async () => {
      await this.groupRows
        .first()
        .waitFor({ state: 'visible', timeout: opts.timeout ?? 30_000 });
    });
  }
}
