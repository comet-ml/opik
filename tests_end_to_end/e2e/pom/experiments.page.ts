import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { ExperimentDetailPage } from './experiment-detail.page';
import { ExperimentGroupRows } from './experiment-group-rows';

/**
 * The two tabs the Experiments page splits into (opik#9611), each named by the
 * `?tab=` value the page keeps in the URL and the label it shows.
 *
 * Both halves are here because the spec needs both: the URL value is what a
 * deep link carries and what `setTab` writes, while the label is the only
 * handle on the rendered tab.
 */
export const EXPERIMENTS_TABS = {
  testSuites: { param: 'test_suites', label: 'Test suites' },
  datasets: { param: 'datasets', label: 'Datasets' },
} as const;

export type ExperimentsTabKey = keyof typeof EXPERIMENTS_TABS;

/**
 * Every tab-strip state the browser COMMITTED, oldest first, as
 * `armTabStripRecorder` collected it.
 *
 * `null` for a sample taken while no tab strip was in the DOM at all (the
 * pre-mount document, and the skeleton the page shows until the starting tab is
 * resolved). Consecutive identical samples are collapsed, so attribute churn
 * that does not change the strip leaves no trace.
 */
export type TabStripSample = { label: string; active: boolean }[] | null;

/** The window the recorder writes its samples onto. */
type TabStripWindow = Window & { __opikTabStrip?: string[] };

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

  /**
   * Open a project's Experiments page on its DEFAULT view — grouped by Item
   * source — by sending no `groups` param at all.
   *
   * The opposite of `goto` above, which pins `groups=[]` because its row
   * locators need the flat list. Deliberately NOT sending the default grouping
   * explicitly: what a group-row spec is about is the view a user lands on, and
   * a spec that posted the grouping itself could pass against a page that had
   * stopped defaulting to it. The grouping is self-verifying — with it off there
   * are no group rows to find, and `ExperimentGroupRows` fails saying so.
   *
   * The groups then come from `DEFAULT_GROUPS` because `groups` is also backed
   * by localStorage, which a fresh browser context leaves empty.
   */
  async gotoGrouped(projectId: string): Promise<void> {
    return test.step(`open project ${projectId} Experiments, grouped by Item source`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments`,
      );
    });
  }

  /**
   * The grouped table's rows, when the page is on a grouped view.
   *
   * The same object the prompt detail page's Experiments tab exposes, because
   * the two render the same component — see `ExperimentGroupRows`.
   */
  get groups(): ExperimentGroupRows {
    return new ExperimentGroupRows(this.page);
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
   * Titled "Add to compare" — distinct from the mixed-dataset guard
   * below, which is a different dialog with a different title, and telling the
   * two apart is most of what this PR's branches are about.
   */
  get comparePickerDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Add to compare' }),
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

  // --- The Test suites / Datasets tab split (opik#9611) ---

  /**
   * Open a project's Experiments page on a named tab, through the `?tab=` the
   * page keeps in the URL.
   *
   * `groups=[]` for the same reason `goto` above pins it: the tab's row
   * locators are id-based, and the page's default Item-source grouping gives
   * each experiment row a composite `data-row-id`. Pass `grouped: true` to see
   * the tab as a user lands on it instead.
   */
  async gotoTab(
    projectId: string,
    tab: ExperimentsTabKey,
    opts: { size?: number; grouped?: boolean } = {},
  ): Promise<void> {
    return test.step(`open project ${projectId} Experiments on the ${EXPERIMENTS_TABS[tab].label} tab`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      const query = new URLSearchParams({ tab: EXPERIMENTS_TABS[tab].param });
      if (!opts.grouped) query.set('groups', '[]');
      if (opts.size !== undefined) query.set('size', String(opts.size));
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments?${query}`,
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
   * Addressed as the group row's ONLY button, not by an accessible name —
   * because on this page it does not have one. `ItemSourceGroupCell` renders
   * its prefix ("Dataset" / "Test suite" / "Item source") only when
   * `hidePrefix` is false, and `useExperimentsTableConfig` sets
   * `hidePrefix: Boolean(itemSourceLabel)`. Since opik#9611 gave the
   * Experiments page's tabs an `itemSourceLabel`, the prefix is suppressed here
   * and the toggle is a bare chevron with no text at all. The prompt detail
   * tab passes no label and so still renders the prefix — which is exactly why
   * naming it was addressing a moving target, and why
   * `ExperimentGroupRows.expandGroup` already addressed it this way.
   *
   * The row holds a checkbox, this button and a link, so "its only button" is
   * unambiguous; the `toHaveCount(1)` in `groupIsExpanded` keeps it honest if
   * that ever stops being true.
   */
  groupExpandToggle(groupName: string): Locator {
    return this.groupRowFor(groupName).getByRole('button');
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
   * `apps/opik-frontend/`. Since opik#9611 suppressed the prefix on this page
   * the chevron is the toggle's only content at all, which makes the point
   * sharper rather than weaker: there is now nothing else to go by.
   *
   * Asserted to have resolved exactly one chevron before answering: a lookup
   * that found neither state would otherwise return `false` and read as
   * "collapsed", which is a plausible-looking answer that no amount of waiting
   * would correct.
   */
  async groupIsExpanded(groupName: string): Promise<boolean> {
    return test.step(`read whether group "${groupName}" is expanded`, async () => {
      const toggle = this.groupExpandToggle(groupName);
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
   * Open a project's Experiments page with NO `?tab=` at all — the shape every
   * link into the page from the sidebar, the breadcrumbs and "Back to
   * experiments" carries, and therefore the one that exercises the tab the page
   * resolves for itself.
   *
   * `groups=[]` is still sent, and does not weaken the subject:
   * `useExperimentsTab` reads only `tab`, `search`, `filters`, `page`, `size`,
   * `groups` and `limits`, and decides on `tab` alone. What it buys is a flat
   * table, so the row assertion beside the tab assertion addresses experiments
   * by their own ids.
   */
  async gotoDefaultTab(projectId: string): Promise<void> {
    return test.step(`open project ${projectId} Experiments with no ?tab=`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      const query = new URLSearchParams({ groups: '[]' });
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments?${query}`,
      );
    });
  }

  /** One tab in the strip, by the label it shows. */
  tabTrigger(tab: ExperimentsTabKey): Locator {
    return this.page.getByRole('tab', { name: EXPERIMENTS_TABS[tab].label, exact: true });
  }

  /**
   * Wait for the tab strip to be rendered at all, on its own budget.
   *
   * It needs one because the page deliberately does not render the strip
   * straight away: `isTabPending` holds a skeleton in its place until the
   * starting tab is resolved, and resolving it takes a count query against the
   * project. Under a parallel suite that query has been seen to outlast the
   * suite's 10s assertion default, which surfaces as "there is no tab strip" —
   * a different failure from any a caller is asserting, and a misleading one.
   *
   * Both triggers, so a strip that rendered half of itself fails here.
   *
   * There is a THIRD state the count alone cannot tell from a slow strip, and
   * it is the one worth naming: `ExperimentsPage` renders the strip only inside
   * the `isEmpty` false branch, where `isEmpty` is
   * `!isExistencePending && (existenceData?.total ?? 0) === 0`. An existence
   * query that ERRORS resolves pending to false with no data, so the page
   * settles on the "No experiments yet" empty state and no amount of waiting
   * will ever produce a strip. Observed while reviewing this suite against a
   * local stack that was 500ing under load: every tab assertion burned its full
   * budget and then reported "there is no tab strip", which reads as the tab
   * split having broken rather than as the page never having been given a row
   * count. So the empty state is read back on failure and said out loud.
   */
  async waitForTabStrip(): Promise<void> {
    try {
      await expect(
        this.page.getByRole('tab'),
        'the Experiments tab strip (skeletoned until the starting tab resolves)',
      ).toHaveCount(Object.keys(EXPERIMENTS_TABS).length, { timeout: 30_000 });
    } catch (err) {
      // Only to attribute a failure that has already happened — never to
      // suppress one. Whatever this finds, the original error is what throws if
      // the empty state is not what is showing.
      if (await this.page.getByText('No experiments yet').isVisible().catch(() => false)) {
        throw new Error(
          'ExperimentsPage.waitForTabStrip: the page is showing its "No experiments yet" empty ' +
            'state, which renders INSTEAD of the tab strip — so the strip was never going to ' +
            'appear and the wait above is not the failure. The page had no experiment count to ' +
            'go by: either the project genuinely holds none (check the fixture seeded this ' +
            'project and not another), or the existence query failed, which the page cannot ' +
            'distinguish from a total of 0. Check the backend answered ' +
            'GET /v1/private/experiments for this project at all.',
          { cause: err },
        );
      }
      throw err;
    }
  }

  /**
   * Assert which tab the strip has selected — both halves, so that a strip with
   * two selected tabs or none fails here.
   *
   * `aria-selected` rather than the `data-state` the component also sets: it is
   * the attribute a screen reader and a user both go by, and Radix writes the
   * two together, so reading the accessible one costs nothing.
   */
  async expectActiveTab(tab: ExperimentsTabKey): Promise<void> {
    return test.step(`the ${EXPERIMENTS_TABS[tab].label} tab is selected`, async () => {
      await this.waitForTabStrip();
      for (const key of Object.keys(EXPERIMENTS_TABS) as ExperimentsTabKey[]) {
        const trigger = this.tabTrigger(key);
        await expect(trigger, `the ${EXPERIMENTS_TABS[key].label} tab`).toHaveCount(1);
        await expect(
          trigger,
          `the ${EXPERIMENTS_TABS[key].label} tab's aria-selected`,
        ).toHaveAttribute('aria-selected', String(key === tab));
      }
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
  async ensureGroupExpanded(groupName: string): Promise<void> {
    return test.step(`ensure group "${groupName}" is expanded`, async () => {
      if (await this.groupIsExpanded(groupName)) return;
      await this.groupExpandToggle(groupName).click();
      await expect
        .poll(() => this.groupIsExpanded(groupName), {
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

  /**
   * Switch tabs by clicking, and resolve once the URL says the switch happened.
   *
   * The URL, not a row count: `setTab` writes `?tab=` and clears the table
   * params in one go, and the new tab then loads its own saved view — so a
   * caller that read rows the instant the click returned would be as likely to
   * read the previous tab's.
   */
  async clickTab(tab: ExperimentsTabKey): Promise<void> {
    return test.step(`click the ${EXPERIMENTS_TABS[tab].label} tab`, async () => {
      // The strip may still be skeletoned; clicking into the skeleton would
      // fail on the action timeout rather than on anything under test.
      await this.waitForTabStrip();
      await this.tabTrigger(tab).click();
      await this.page.waitForURL((url) => url.searchParams.get('tab') === EXPERIMENTS_TABS[tab].param);
    });
  }

  /**
   * Record every tab-strip state the browser COMMITS, from the next navigation
   * on. Call before `goto*`.
   *
   * This is what makes "the strip never flashes the wrong tab" assertable at
   * all. The page holds the strip behind a skeleton until the starting tab is
   * known (`isTabPending`), and the regression it guards against — rendering
   * the fallback tab first and correcting it when the experiments query
   * answers — is over in one network round trip, so nothing an `expect` could
   * wait for is still on screen by the time the test looks.
   *
   * A MutationObserver rather than a poll: its callback runs after every batch
   * of DOM mutations, so a strip mounted with the wrong tab and corrected on a
   * later render is two separate samples however fast the correction lands.
   * Consecutive identical samples are collapsed, so attribute churn elsewhere
   * on the page adds nothing.
   *
   * `addInitScript` runs again on each navigation, which resets the log — so the
   * samples a later `tabStripSamples()` reads belong to the LAST navigation
   * only, never to several of them pooled together.
   */
  async armTabStripRecorder(): Promise<void> {
    return test.step('record every committed tab-strip state', async () => {
      await this.page.addInitScript(() => {
        const win = window as Window & { __opikTabStrip?: string[] };
        win.__opikTabStrip = [];
        const sample = () => {
          const line = Array.from(document.querySelectorAll('[role="tab"]'))
            .map((tab) => `${(tab.textContent ?? '').trim()}=${tab.getAttribute('data-state')}`)
            .join('|');
          const log = win.__opikTabStrip!;
          if (log[log.length - 1] !== line) log.push(line);
        };
        new MutationObserver(sample).observe(document, {
          subtree: true,
          childList: true,
          attributes: true,
        });
        sample();
      });
    });
  }

  /**
   * The samples {@link armTabStripRecorder} collected during the current
   * navigation, oldest first.
   *
   * Throws when the recorder was never armed, rather than answering `[]`: an
   * empty log and a missing log would otherwise both read as "the strip was
   * never committed with the wrong tab", and only one of them is evidence.
   */
  async tabStripSamples(): Promise<TabStripSample[]> {
    const raw = await this.page.evaluate(
      () => (window as TabStripWindow).__opikTabStrip,
    );
    if (!Array.isArray(raw)) {
      throw new Error(
        'ExperimentsPage.tabStripSamples: no recorder log on the page — ' +
          'armTabStripRecorder() must be awaited BEFORE the navigation being observed',
      );
    }
    return raw.map((line) =>
      line === ''
        ? null
        : line.split('|').map((entry) => {
            const [label, state] = entry.split('=');
            return { label, active: state === 'active' };
          }),
    );
  }

  /**
   * One experiment's row, whether the list is flat or grouped.
   *
   * Under grouping `getExperimentRowId` composes the row id as
   * `<experimentId>|<grouping field>:<value>|…`, so an exact match holds only on
   * the flat list and a prefix match only on the grouped one. Switching tabs
   * clears the URL's `groups` and lets the new tab apply its own saved
   * grouping, which means one spec sees both forms — hence both selectors here
   * rather than a caller having to know which view it is on.
   */
  experimentRowAnywhere(experimentId: string): Locator {
    return this.page.locator(
      `tbody tr[data-row-id="${experimentId}"], tbody tr[data-row-id^="${experimentId}|"]`,
    );
  }

  /**
   * One cell of an experiment's row, by the table's own `data-cell-id`
   * (`<rowId>_<columnId>`), matched on the column id and scoped to the row.
   *
   * On the column id suffix rather than the whole attribute so the row id never
   * has to be reconstructed — it is composite under grouping. By identity and
   * never by position, because column order here is user-configurable and
   * persisted, so an `nth-child` would address whatever column the profile
   * happened to have put there.
   */
  experimentCell(experimentId: string, columnId: string): Locator {
    return this.experimentRowAnywhere(experimentId).locator(`td[data-cell-id$="_${columnId}"]`);
  }

  /** The rendered text of one cell, trimmed, asserted to resolve to exactly one element. */
  async experimentCellText(experimentId: string, columnId: string): Promise<string> {
    return test.step(`read the ${columnId} cell of experiment ${experimentId}`, async () => {
      const cell = this.experimentCell(experimentId, columnId);
      await expect(cell, `the ${columnId} cell of experiment ${experimentId}`).toHaveCount(1);
      return (await cell.innerText()).trim();
    });
  }

  /** One column header, by its visible label. */
  columnHeader(label: string): Locator {
    return this.page.getByRole('columnheader', { name: label, exact: true });
  }

  /**
   * The labelled column headers, left to right.
   *
   * The select, pin and row-actions headers render no text and are dropped, so
   * what comes back is the user-visible column order — which is what an
   * assertion about a column's POSITION (the version column sits immediately
   * after Name) has to compare against. Order here is the table's own and is
   * asserted as a sequence, never as a set.
   */
  async columnHeaderLabels(): Promise<string[]> {
    return test.step('read the column headers left to right', async () => {
      const texts = await this.page.locator('thead th').allInnerTexts();
      return texts.map((text) => text.trim()).filter((text) => text.length > 0);
    });
  }

  /** The Columns dropdown trigger in the list toolbar. */
  get columnsButton(): Locator {
    return this.page.getByTestId('columns-button');
  }

  /**
   * Every column the Columns picker offers, by label, including the per-score
   * section's dynamic entries.
   *
   * The picker is worth reading separately from the table because the table
   * alone cannot tell "not selected by default" from "not available at all" —
   * and the tab split's exclusions are the second: a column a tab does not show
   * is filtered out of the column set before it reaches the picker, so a user
   * cannot re-enable it.
   *
   * Each row is a Radix `CheckboxItem` over which `SortableMenuItem` spreads
   * dnd-kit's sortable attributes, and those set `role="button"` — so the rows
   * are addressed as buttons, exactly as `datasets.page.ts` does. Neither the
   * rows nor the menu carry a data-testid.
   */
  async columnPickerLabels(): Promise<string[]> {
    return test.step('read the Columns picker entries', async () => {
      await this.columnsButton.click();
      const menu = this.page.getByRole('menu');
      const entries = menu.getByRole('button');
      await expect(entries.first(), 'the Columns picker entries').toBeVisible();
      const labels = (await entries.allInnerTexts()).map((label) => label.trim());
      // The menu keeps itself open on select; close it rather than leaving an
      // overlay across the table the next assertion has to read through.
      await this.page.keyboard.press('Escape');
      await expect(menu, 'the Columns picker after Escape').toBeHidden();
      return labels;
    });
  }

  /**
   * Turn a column ON through the Columns picker, if the table is not already
   * showing it.
   *
   * Needed because a tab's DEFAULT column set is not the whole offered set, and
   * the two diverged at opik#9611: `TAB_CONFIG` gives the Test suites tab
   * `total_estimated_cost_avg` and Pass rate by default, and the Datasets tab
   * Feedback Scores instead — so a spec about a cost cell on the Datasets tab
   * has to ask for the column rather than assume it.
   *
   * Idempotent, and by the rendered HEADER rather than by the picker's own tick
   * state: the question a caller is asking is "is this column in the table",
   * and clicking an already-selected entry would turn it off. Asserting the
   * header afterwards is what makes this a precondition rather than a gesture —
   * a column the tab excludes entirely is not offered in the picker at all
   * (see `columnPickerLabels`), and this fails loudly on it instead of
   * continuing to an assertion about a cell that cannot exist.
   */
  async enableColumn(label: string): Promise<void> {
    return test.step(`show the "${label}" column`, async () => {
      if ((await this.columnHeader(label).count()) > 0) return;
      await this.columnsButton.click();
      const menu = this.page.getByRole('menu');
      const entry = menu.getByRole('button', { name: label, exact: true });
      await expect(entry, `the "${label}" entry in the Columns picker`).toHaveCount(1);
      await entry.click();
      // The menu keeps itself open on select; close it rather than leaving an
      // overlay across the table the next assertion has to read through.
      await this.page.keyboard.press('Escape');
      await expect(menu, 'the Columns picker after Escape').toBeHidden();
      await expect(
        this.columnHeader(label),
        `the "${label}" column header after selecting it`,
      ).toHaveCount(1);
    });
  }

  /**
   * The experiment ids the table lists, in document order, with group rows
   * dropped and any grouping suffix stripped.
   *
   * Duplicates are deliberately NOT removed: under grouping the same experiment
   * can be rendered under more than one group, and a set comparison would hide
   * exactly that.
   */
  async listedExperimentIds(): Promise<string[]> {
    const rowIds = await this.rows.evaluateAll((rows) =>
      rows.map((row) => row.getAttribute('data-row-id') ?? ''),
    );
    return (
      rowIds
        // `$__grouping__` prefixes a group row and `$__grouping_row__` the
        // synthetic load-more/pending rows; an experiment row starts with the
        // experiment's own uuid.
        .filter((rowId) => !rowId.startsWith('$__grouping_'))
        .map((rowId) => rowId.split('|')[0])
    );
  }

  /**
   * Poll until the table lists EXACTLY these experiments, then assert it listed
   * each of them once.
   *
   * Exhaustive rather than "my rows are in there": a tab that also listed a row
   * it should have excluded is the bug the partition assertion exists to catch,
   * and finding the expected ids inside the answer would pass either way.
   */
  async expectListedExperiments(expectedIds: string[], what: string): Promise<void> {
    return test.step(`${what} lists exactly ${expectedIds.length} experiment(s)`, async () => {
      const expected = [...expectedIds].sort();
      await expect
        .poll(async () => [...new Set(await this.listedExperimentIds())].sort(), {
          message: `${what}: the set of experiment ids the table lists`,
          timeout: 30_000,
        })
        .toEqual(expected);
      // Only once the set has settled, and as its own assertion: a row rendered
      // twice is invisible to the comparison above.
      expect(
        (await this.listedExperimentIds()).sort(),
        `${what}: one row per experiment, no duplicates`,
      ).toEqual(expected);
    });
  }
}
