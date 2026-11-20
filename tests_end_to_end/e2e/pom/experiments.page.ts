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
   */
  async waitForTabStrip(): Promise<void> {
    await expect(
      this.page.getByRole('tab'),
      'the Experiments tab strip (skeletoned until the starting tab resolves)',
    ).toHaveCount(Object.keys(EXPERIMENTS_TABS).length, { timeout: 30_000 });
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
