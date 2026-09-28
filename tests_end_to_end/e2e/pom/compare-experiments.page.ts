import * as fs from 'node:fs/promises';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * How long an export may take to reach the browser as a download. Generous
 * because an unscoped export re-reads the whole result set untruncated, and
 * then serialises it in the tab, before the file exists at all; this budget is
 * a failure message rather than a wait.
 */
const EXPORT_TIMEOUT_MS = 60_000;

/** How long the grid may take to answer a newly-applied filter. */
const FILTER_SETTLE_TIMEOUT_MS = 30_000;

/**
 * How long the grid's own data read may take. Generous because a deep offset
 * over a large un-aggregated experiment is the slowest shape this endpoint
 * serves, and because a page load has to get through auth and the bundle first.
 */
const GRID_READ_TIMEOUT_MS = 60_000;

/**
 * How many times a cold load of the compare view may be re-attempted when the
 * app renders its own failure panel instead of the page.
 *
 * Not flake-hiding: the panel means a request the ROUTE depends on (the project
 * read) was refused, so the grid never mounts and never issues the read this POM
 * is waiting for — the symptom is a 60s timeout on a request that was never
 * going to be made. Observed on staging after a spec had just spent its own
 * budget on a few thousand rows of API reads, i.e. it is the workspace rate
 * limiter, which is a budget over time and so clears on its own. A reload is the
 * only remedy from here, and exhausting the attempts still fails the test with
 * the panel named.
 */
const PAGE_LOAD_ATTEMPTS = 3;

/**
 * The compare view lives at /experiments/{datasetId}/compare?experiments=[...]
 * and renders the SAME page in single- and multi-experiment mode. This POM
 * targets multi-experiment (comparison) mode: two experiments over one dataset.
 *
 * In comparison mode the table is one row per DATASET ITEM. Each cell is
 * vertically split into one band per experiment, ordered by the position of
 * the experiment id in the `experiments` query array — so band index 0 is the
 * first id passed to `goto`, index 1 the second, etc.
 */
export class CompareExperimentsPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
    private readonly datasetId: string,
    private readonly experimentIds: string[],
  ) {}

  private compareUrl(tab: 'items' | 'config' | 'scores'): string {
    const env = loadEnvConfig();
    const experiments = encodeURIComponent(JSON.stringify(this.experimentIds));
    return `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/experiments/${this.datasetId}/compare?experiments=${experiments}&tab=${tab}`;
  }

  async gotoResults(): Promise<void> {
    await test.step('open the compare Results tab', async () => {
      await this.page.goto(this.compareUrl('items'));
    });
  }

  async gotoConfiguration(): Promise<void> {
    await test.step('open the compare Configuration tab', async () => {
      await this.page.goto(this.compareUrl('config'));
    });
  }

  async gotoFeedbackScores(): Promise<void> {
    await test.step('open the compare Feedback scores tab', async () => {
      await this.page.goto(this.compareUrl('scores'));
    });
  }

  /**
   * Open the Results tab at an explicit page and page size, and return the read
   * the grid itself made for it.
   *
   * Both params are explicit because neither default is the suite's to assume:
   * `size` falls back to `ServiceTogglesConfig.defaultPageSize`, a per-deployment
   * value, so a spec that omitted it would be asserting different pagination
   * arithmetic on different environments. Both are URL state (`?page=`, `?size=`),
   * which is why this can be driven by navigation — the same reason
   * `sortByColumn` does.
   *
   * The grid's own response rather than the DOM, because the table is
   * virtualised: only the rows in the viewport carry a `data-row-id`, so reading
   * the rendered rows would compare a screenful against a page. The response is
   * what the page actually received, and comparing it to a direct API read at the
   * same offset is what catches the front end and the backend disagreeing about
   * which slice a page is.
   *
   * ONLY SAFE FOR PAGE 1 on a cold load. `DataTablePagination` sends the view
   * back to page 1 whenever `(page - 1) * size > total`, and `total` is still 0
   * until the row read answers — so a deep offset asked for here is requested
   * and then abandoned, leaving the footer reading 1-100. Use
   * `clickLastResultsPage` to reach the end.
   */
  async gotoResultsPage(pageNumber: number, size: number): Promise<{ total: number; ids: string[] }> {
    return test.step(`open the compare Results tab at page ${pageNumber} (size ${size})`, async () => {
      const url = new URL(this.compareUrl('items'));
      url.searchParams.set('page', String(pageNumber));
      url.searchParams.set('size', String(size));

      for (let attempt = 1; ; attempt++) {
        // The response wait has to be armed BEFORE the navigation, or the answer
        // can arrive first and the wait then hangs for a request that has already
        // been served.
        const settled = this.gridReadFor(pageNumber, size);
        // The loser of the race below is abandoned; without this its rejection
        // lands as an unhandled promise and Playwright reports it against
        // whichever step happens to be open at the time.
        settled.catch(() => {});

        await this.page.goto(url.toString());

        // Armed only AFTER the navigation, and that ordering is load-bearing on
        // a retry: the previous attempt's failure panel is still in the DOM until
        // the new document replaces it, so a wait armed before `goto` would see
        // the OLD panel, resolve immediately, and spend every remaining attempt
        // in a few milliseconds without ever giving the reload a chance.
        // Registering here is safe for the fast case too — a panel already
        // rendered by the time `goto` resolves satisfies `visible` at once.
        const failed = this.appFailurePanel.waitFor({
          state: 'visible',
          timeout: GRID_READ_TIMEOUT_MS,
        });
        failed.catch(() => {});

        const outcome = await Promise.race([
          settled.then((response) => ({ kind: 'read' as const, response })),
          failed.then(() => ({ kind: 'failed' as const })),
        ]);

        if (outcome.kind === 'read') return this.readGridAnswer(outcome.response, pageNumber);

        if (attempt >= PAGE_LOAD_ATTEMPTS) {
          throw new Error(
            `CompareExperimentsPage.gotoResultsPage: the app rendered "Something went wrong" ` +
              `instead of the compare view on all ${attempt} attempts, so the grid never issued ` +
              `its read of page ${pageNumber}. A request the route depends on is being refused — ` +
              'check the workspace rate limiter before reading this as a paging defect.',
          );
        }
      }
    });
  }

  /** The grid's own data read for one exact offset. */
  private gridReadFor(pageNumber: number, size: number) {
    // Matched on parsed params, never a substring: `page=1` occurs inside
    // `page=10`, and the whole point of this helper is which offset was read.
    return this.page.waitForResponse((response) => {
      const requested = new URL(response.url());
      return (
        requested.pathname.endsWith('/items/experiments/items') &&
        requested.searchParams.get('page') === String(pageNumber) &&
        requested.searchParams.get('size') === String(size) &&
        response.ok()
      );
    }, { timeout: GRID_READ_TIMEOUT_MS });
  }

  /**
   * The app's route-level failure card — what renders in place of the page when
   * a read the route depends on is refused.
   *
   * Matched on the heading text because the card carries no `data-testid`; it is
   * only ever used to decide "the page is not coming", never asserted on, so a
   * text match is proportionate here.
   */
  private get appFailurePanel(): Locator {
    return this.page.getByRole('heading', { level: 3, name: 'Something went wrong' });
  }

  /**
   * The grid's own data response, narrowed to the two fields a paging assertion
   * reads.
   *
   * `total` and `content` are both checked rather than defaulted: a page that
   * answered without them is indistinguishable from an empty comparison once a
   * `?? 0` or a `?? []` has been applied, and "the grid received no rows" is
   * exactly the failure these callers exist to catch.
   */
  private async readGridAnswer(
    response: import('@playwright/test').Response,
    pageNumber: number,
  ): Promise<{ total: number; ids: string[] }> {
    const body: unknown = await response.json();
    const { total, content } = body as { total?: unknown; content?: unknown };
    if (typeof total !== 'number') {
      throw new Error(
        `CompareExperimentsPage: the grid's read of page ${pageNumber} answered without a total — ` +
          'cannot tell a complete comparison from a partial one.',
      );
    }
    if (!Array.isArray(content)) {
      throw new Error(
        `CompareExperimentsPage: the grid's read of page ${pageNumber} answered with no content ` +
          `array (got ${typeof content}).`,
      );
    }
    return { total, ids: content.map((row) => String((row as { id: unknown }).id)) };
  }

  /**
   * Click through to the last page, and return the read the grid made for it.
   *
   * By the control rather than by `?page=`, and NOT as a matter of taste.
   * `DataTablePagination` runs `if (page !== 1 && (page - 1) * size > total)
   * pageChange(1)` in an effect that sits above its own `total === 0` early
   * return, so on a cold document load at a deep offset the row read has not
   * answered yet, `total` is still 0, and the component sends the view back to
   * page 1 before the data arrives. The request for the deep page IS issued, so a
   * spec that waited on the response and then read the footer would be told 1-100
   * while believing it had jumped to the end. Clicking gets there with `total`
   * already known, which is also the only way a user reaches it.
   *
   * `expectedPage` is passed in rather than read off the control so the wait
   * pins the offset the caller means; a mismatch fails on the response wait
   * instead of silently asserting against whatever page was served.
   */
  async clickLastResultsPage(
    expectedPage: number,
    size: number,
  ): Promise<{ total: number; ids: string[] }> {
    return test.step(`jump to the last page (${expectedPage})`, async () => {
      const button = this.lastPageButton;
      await expect(button, 'exactly one last-page control').toHaveCount(1);
      await expect(button, 'last-page control').toBeEnabled();

      const settled = this.gridReadFor(expectedPage, size);
      await button.click();
      return this.readGridAnswer(await settled, expectedPage);
    });
  }

  /**
   * Assert the pagination footer reads exactly this.
   *
   * The footer is the only place the grid states its own paging arithmetic — the
   * offset it believes it is at and the total it believes exists — so at a deep
   * offset it is the user-visible half of "the pages partition the experiment".
   * Note the asymmetric formatting the component produces: the from-to pair is
   * raw (`4901-5000`) while the total is localised (`5,000`).
   *
   * Addressed by its text. `DataTablePagination` is shared across every table in
   * the app and carries no `data-testid`, and the footer is a bare `<span>` with
   * no role or label, so there is nothing more stable to select on. A
   * `data-testid` belongs on that component — it is not added here for the reason
   * `exportButton` below gives: these specs run against a pre-built deployment,
   * where a front-end attribute added alongside them would not exist. The count
   * assertion keeps the match honest — the sibling "Rows per page:" span has the
   * same class, and a second paginator on the page would be a real ambiguity
   * rather than something to silently take the first of.
   */
  async expectPaginationFooter(expected: string): Promise<void> {
    await test.step(`the pagination footer reads "${expected}"`, async () => {
      const footer = this.paginationFooter;
      await expect(footer, 'exactly one pagination footer').toHaveCount(1);
      await expect(footer, 'pagination footer').toHaveText(expected);
    });
  }

  private get paginationFooter(): Locator {
    return this.page.locator('span.comet-body-s').filter({ hasText: /^Showing / });
  }

  /**
   * The "jump to last page" control — icon-only, no accessible name and no
   * `data-testid`, so it is addressed by its Lucide icon class, the same idiom
   * `exportButton` and `filtersButton` below already use for this shared
   * component. Callers assert `toHaveCount(1)` before clicking.
   */
  private get lastPageButton(): Locator {
    return this.page.locator('button:has(svg.lucide-chevron-last)');
  }

  async waitForResultsReady(): Promise<void> {
    await test.step('wait for the Results grid to render', async () => {
      await this.compareHeading.waitFor({ state: 'visible' });
      await this.itemRows.first().waitFor({ state: 'visible' });
    });
  }

  async countItemRows(): Promise<number> {
    return this.itemRows.count();
  }

  async expectCompareModeHeader(experimentCount: number): Promise<void> {
    await test.step(`header reads "Compare (${experimentCount})"`, async () => {
      await expect(this.compareHeading).toHaveText(`Compare (${experimentCount})`);
    });
  }

  async expectExperimentNamesInSummary(names: string[]): Promise<void> {
    await test.step('both experiment names appear in the compare summary', async () => {
      const summary = this.compareSummary;
      await expect(summary, 'compare summary row').toBeVisible();
      for (const name of names) {
        await expect(
          summary.getByText(name, { exact: true }),
          `experiment "${name}" named in the compare summary`,
        ).toBeVisible();
      }
    });
  }

  /**
   * The per-experiment score for one shared dataset item. `experimentIndex` is
   * the position of the experiment in the array passed to the constructor,
   * which is the order the split bands render in.
   */
  async readItemScore(datasetItemId: string, experimentIndex: number, metricName: string): Promise<number> {
    return test.step(`read score for item ${datasetItemId} / experiment #${experimentIndex}`, async () => {
      const band = this.splitBand(datasetItemId, experimentIndex, `feedback_scores_${metricName}`);
      await expect(band, `score band for item ${datasetItemId} experiment #${experimentIndex}`).toBeVisible();
      const text = ((await band.textContent()) ?? '').trim();
      const value = parseFloat(text);
      if (Number.isNaN(value)) {
        throw new Error(
          `CompareExperimentsPage.readItemScore: could not parse "${text}" for item ${datasetItemId} experiment #${experimentIndex}`,
        );
      }
      return value;
    });
  }

  /** The per-experiment evaluation-task output for one shared dataset item. */
  async readItemOutput(datasetItemId: string, experimentIndex: number): Promise<string> {
    return test.step(`read output for item ${datasetItemId} / experiment #${experimentIndex}`, async () => {
      const band = this.splitBand(datasetItemId, experimentIndex, 'output_output');
      await expect(band, `output band for item ${datasetItemId} experiment #${experimentIndex}`).toBeVisible();
      return ((await band.textContent()) ?? '').trim();
    });
  }

  /** The aggregate (mean) score for one experiment on the Feedback scores tab. */
  async readAggregateScore(experimentId: string): Promise<number> {
    return test.step(`read aggregate score for experiment ${experimentId}`, async () => {
      const cell = this.page.locator(`td[data-cell-id="0_${experimentId}"]`);
      await expect(cell, `aggregate score cell for experiment ${experimentId}`).toBeVisible();
      const value = parseFloat(((await cell.textContent()) ?? '').trim());
      if (Number.isNaN(value)) {
        throw new Error(`CompareExperimentsPage.readAggregateScore: could not parse a number for ${experimentId}`);
      }
      return value;
    });
  }

  async searchItems(term: string): Promise<void> {
    await test.step(`search the grid for "${term}"`, async () => {
      const url = new URL(this.page.url());
      url.searchParams.set('search', term);
      await this.page.goto(url.toString());
      // Wait for the grid to settle on the filtered result: either matching
      // rows, or the explicit no-data row — not the mid-reload empty table.
      await this.page
        .locator('tbody tr[data-row-id], tbody tr[data-testid="no-data-row"]')
        .first()
        .waitFor({ state: 'visible' });
    });
  }

  async openRowPanel(datasetItemId: string): Promise<void> {
    await test.step(`open the detail panel for item ${datasetItemId}`, async () => {
      const url = new URL(this.page.url());
      url.searchParams.set('row', datasetItemId);
      await this.page.goto(url.toString());
      // The panel's Close control only exists once the slide-over is mounted.
      await this.page.getByRole('button', { name: 'Close' }).waitFor({ state: 'visible' });
    });
  }

  /**
   * In the row-detail panel each compared experiment is its own section headed
   * by an h2 with the experiment name; assert both the output and score there.
   */
  async expectPanelExperimentResult(
    experimentName: string,
    expected: { output: string; score: number; metricName: string },
  ): Promise<void> {
    await test.step(`panel shows ${experimentName}'s output and score`, async () => {
      const section = this.panelExperimentSection(experimentName);
      await expect(section, `panel section for ${experimentName}`).toBeVisible();
      await expect(section, `${experimentName} output in panel`).toContainText(expected.output);
      const scoreRow = section.locator('tr', { hasText: expected.metricName });
      await expect(scoreRow, `${experimentName} ${expected.metricName} score row`)
        .toContainText(String(expected.score));
    });
  }

  /**
   * The output text as the row-detail panel renders it, with every inline image
   * already replaced by its `[image_N]` placeholder.
   *
   * Read off the panel rather than the grid cell behind it: the grid shows a
   * truncated projection, and the placeholder numbering is only meaningful next
   * to the thumbnails, which render in the panel alone.
   */
  async readPanelOutputText(): Promise<string> {
    return test.step('read the output text in the row-detail panel', async () => {
      const output = this.panelOutputBlock;
      // Count first: every thumbnail is also labelled with its placeholder, so a
      // locator that widened to pick one of those up would return
      // "Base64: [image_0]" and compare it happily against the output line.
      await expect(output, 'the rendered output carrying image placeholders').toHaveCount(1);
      await output.scrollIntoViewIfNeeded();
      return ((await output.textContent()) ?? '').trim();
    });
  }

  /**
   * The rendered output line, in either of the two ways the panel can show it.
   *
   * `p` is the prettified view (the default, a markdown paragraph); `.cm-line`
   * is the raw JSON view behind the same toggle. Matching both keeps the spec
   * independent of which one a session happens to open in, and either way the
   * element type is what excludes the thumbnail labels — those are spans, and
   * they carry the very same `[image_N]` tokens.
   */
  private get panelOutputBlock(): Locator {
    return this.rowPanel.locator('p, .cm-line').filter({ hasText: /\[image_\d+\]/ });
  }

  /**
   * One inline-image thumbnail in the row-detail panel, addressed by the
   * placeholder token it is labelled with.
   *
   * By placeholder, never by position. `AttachmentsList` re-sorts by media type
   * and deduplicates by URL before rendering, so display order tracks neither
   * the order the images appeared in the output nor the order they were
   * numbered in — an index-based locator would be asserting on that sort. The
   * alt text is the only thing tying a rendered picture back to the token in the
   * text, which is exactly the mapping OPIK-4954 fixed.
   */
  panelMediaThumbnail(placeholder: string): Locator {
    return this.rowPanel.locator(`img[alt="Base64: ${placeholder}"]`);
  }

  /** Every inline-image thumbnail in the row-detail panel. */
  get panelMediaThumbnails(): Locator {
    return this.rowPanel.locator('img[alt^="Base64: "]');
  }

  /**
   * Assert one thumbnail resolves to one exact picture.
   *
   * `toHaveCount(1)` before reading the `src`, so an ambiguous match fails loudly
   * instead of silently asserting against whichever element came first.
   */
  async expectThumbnailResolvesTo(placeholder: string, expectedUrl: string): Promise<void> {
    await test.step(`${placeholder} resolves to its own picture`, async () => {
      const thumbnail = this.panelMediaThumbnail(placeholder);
      await expect(thumbnail, `exactly one thumbnail labelled ${placeholder}`).toHaveCount(1);
      await expect(thumbnail, `the picture ${placeholder} resolves to`).toHaveAttribute(
        'src',
        expectedUrl,
      );
    });
  }

  /**
   * The thumbnail actually decoded, rather than rendering as a broken image.
   *
   * A wrong `src` and an unreadable one both leave an `<img>` in the DOM, so the
   * attribute assertion alone cannot tell "resolved to the right picture" from
   * "resolved to a 404".
   */
  async expectThumbnailDecodes(placeholder: string): Promise<void> {
    await test.step(`${placeholder} decodes`, async () => {
      const thumbnail = this.panelMediaThumbnail(placeholder);
      await expect(thumbnail, `${placeholder} is visible`).toBeVisible();
      // Scrolled into view first: the thumbnails are `loading="lazy"`, so one
      // that has never entered the viewport reports naturalWidth 0 whether its
      // source is good or not.
      await thumbnail.scrollIntoViewIfNeeded();
      await expect
        .poll(
          async () => thumbnail.evaluate((img) => (img as HTMLImageElement).naturalWidth),
          { message: `naturalWidth of the ${placeholder} thumbnail` },
        )
        .toBeGreaterThan(0);
    });
  }

  /** The compare row-detail slide-over. */
  private get rowPanel(): Locator {
    return this.page.getByTestId('compare-experiments');
  }

  async expectExperimentColumnsInConfiguration(experiments: { id: string; name: string }[]): Promise<void> {
    await test.step('each experiment is a named column on the Configuration tab', async () => {
      for (const exp of experiments) {
        await expect(
          this.configHeader(exp.id),
          `configuration column header for experiment ${exp.id}`,
        ).toContainText(exp.name);
      }
    });
  }

  /**
   * The score column header is a sticky, overlay-covered element that a direct
   * click can't reliably hit; the grid instead reads sort state from the
   * `sorting` query param (the same the header click writes). Driving sort via
   * the URL exercises the real server-side sort path deterministically and
   * still asserts on the rendered row order.
   */
  async sortByScoreDescending(metricName: string): Promise<void> {
    await this.sortByColumn(`feedback_scores_${metricName}`, 'desc');
  }

  /**
   * Sorts the grid by an arbitrary column id, in either direction.
   *
   * `columnId` is the id the table uses in its own `sorting` state — the same
   * value a header click writes — so a dynamic JSON column is addressed exactly
   * as the grid addresses it: `output.<key>`, `data.<key>`, `metadata.<key>`.
   * The front end maps that id to the backend `sorting` field on the wire, so
   * driving the query param exercises the real serialise → sort → render path,
   * including the `+`-encoding of a key containing a space.
   *
   * Driven through the URL rather than by clicking the header for the reason
   * given on sortByScoreDescending above: the header is sticky and overlaid by
   * the statistics sub-row, so a click lands unreliably.
   */
  async sortByColumn(columnId: string, direction: 'asc' | 'desc'): Promise<void> {
    await test.step(`sort the grid by "${columnId}" ${direction}ending`, async () => {
      const url = new URL(this.page.url());
      url.searchParams.set(
        'sorting',
        JSON.stringify([{ id: columnId, desc: direction === 'desc' }]),
      );
      await this.page.goto(url.toString());
      await this.itemRows.first().waitFor({ state: 'visible' });
    });
  }

  /** Dataset-item ids in current row order, top to bottom. */
  async itemRowOrder(): Promise<string[]> {
    return test.step('read the current row order', async () => {
      const ids = await this.itemRows.evaluateAll((rows) =>
        rows.map((r) => r.getAttribute('data-row-id') ?? ''),
      );
      return ids;
    });
  }

  /**
   * Add one filter through the FiltersButton popover, and return the row total
   * the grid received for it.
   *
   * Driven through the real popover rather than the `filters` query param —
   * unlike `sorting`, the filter's wire shape is assembled by the control
   * itself (column id, type and the operator it defaults to), so a spec that
   * hand-wrote the param would be asserting its own guess at that shape rather
   * than the one a user produces.
   *
   * Two things this has to get right, both of which cost an exploration pass:
   *  - the popover COMMITS on click-outside and DISCARDS on Escape, so
   *    dismissing it the obvious way leaves the view unfiltered — and a spec
   *    that then exported would assert happily against 250 rows;
   *  - the filter applies live as the value is typed, so the settle point is
   *    the grid's own data response carrying the final value, not the popover
   *    closing.
   *
   * The total comes from that response because the grid is virtualised: only
   * the rows in view carry a `data-row-id`, so counting the DOM would report
   * the viewport, not the result set.
   */
  async addGridFilter(column: string, value: string): Promise<number> {
    return test.step(`filter the grid where "${column}" contains "${value}"`, async () => {
      await this.filtersButton.click();

      const columnSelect = this.page.locator(
        'button[role="combobox"]:has([data-testid="filter-column"])',
      );
      await columnSelect.click();
      await this.page.getByRole('option', { name: column, exact: true }).click();

      // The grid's own row read, not one of the sibling column/statistics calls
      // that carry the same `filters` param and answer without a `total` —
      // hence pathname equality rather than a substring match.
      const settled = this.page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname.endsWith('/items/experiments/items') &&
          decodeURIComponent(response.url()).includes(`"value":"${value}"`) &&
          response.ok(),
        { timeout: FILTER_SETTLE_TIMEOUT_MS },
      );

      await this.page.locator('input[placeholder="value"]').fill(value);
      // Commit by clicking outside. The page heading is inert and always present.
      await this.compareHeading.click();

      const body: unknown = await (await settled).json();
      const total = (body as { total?: unknown }).total;
      if (typeof total !== 'number') {
        throw new Error(
          'CompareExperimentsPage.addGridFilter: the filtered read answered without a total — ' +
            'cannot tell a narrowed result set from an unfiltered one.',
        );
      }
      await this.page
        .locator('tbody tr[data-row-id], tbody tr[data-testid="no-data-row"]')
        .first()
        .waitFor({ state: 'visible' });
      return total;
    });
  }

  /**
   * How many rows the grid currently has in the DOM.
   *
   * The table is virtualised, so this is the viewport rather than the result
   * set — only assert on it when the view has been narrowed to fewer rows than
   * a screen holds.
   */
  async expectRenderedRowCount(expected: number): Promise<void> {
    await test.step(`the grid renders ${expected} row(s)`, async () => {
      await expect(this.itemRows, 'rendered comparison rows').toHaveCount(expected);
    });
  }

  /** Tick the select checkbox on each named row, addressed by dataset-item id. */
  async selectRows(datasetItemIds: string[]): Promise<void> {
    await test.step(`select ${datasetItemIds.length} row(s)`, async () => {
      for (const id of datasetItemIds) {
        const checkbox = this.rowCheckbox(id);
        await expect(checkbox, `select checkbox for row ${id}`).toHaveCount(1);
        await checkbox.click();
        await expect(checkbox, `row ${id} after ticking`).toBeChecked();
      }
    });
  }

  /** Untick the select checkbox on each named row. */
  async deselectRows(datasetItemIds: string[]): Promise<void> {
    await test.step(`deselect ${datasetItemIds.length} row(s)`, async () => {
      for (const id of datasetItemIds) {
        const checkbox = this.rowCheckbox(id);
        await expect(checkbox, `select checkbox for row ${id}`).toHaveCount(1);
        await checkbox.click();
        await expect(checkbox, `row ${id} after unticking`).not.toBeChecked();
      }
    });
  }

  /**
   * The control exists and is offered.
   *
   * Two different reasons it can be disabled, and the message says so because
   * only one of them is a bug: the `export_enabled` service toggle is off for
   * this deployment (it varies — the sibling `dataset_export_enabled` ships off
   * in OSS), or the result set is past `EXPORT_ROW_LIMIT`.
   */
  async expectExportEnabled(): Promise<void> {
    await test.step('the export control is offered', async () => {
      await expect(this.exportButton, 'export button').toHaveCount(1);
      await expect(
        this.exportButton,
        'export button — if this is disabled, check the `export_enabled` service toggle on this deployment and that the view is under EXPORT_ROW_LIMIT before assuming the export is broken',
      ).toBeEnabled();
    });
  }

  /**
   * Export the current view as JSON and return the parsed file.
   *
   * The file, not the page: the export is built in the tab and handed to the
   * browser as a download, so the only way to tell "the file holds every row"
   * from "the file holds the page on screen" is to read the bytes on disk.
   *
   * With nothing selected the export re-reads the whole result set untruncated
   * before writing anything, so the click can outlive the default action budget
   * on a large comparison.
   */
  async exportAsJson(): Promise<Record<string, unknown>[]> {
    return test.step('export the view as JSON and read the downloaded file', async () => {
      await this.exportButton.click();
      const menuItem = this.page.getByRole('menuitem', { name: 'Export as JSON' });
      await expect(menuItem, 'Export as JSON menu item').toBeEnabled();

      const [download] = await Promise.all([
        this.page.waitForEvent('download', { timeout: EXPORT_TIMEOUT_MS }),
        menuItem.click(),
      ]);

      const path = await download.path();
      const parsed: unknown = JSON.parse(await fs.readFile(path, 'utf-8'));
      if (!Array.isArray(parsed)) {
        throw new Error(
          `CompareExperimentsPage.exportAsJson: expected an array, got ${typeof parsed}`,
        );
      }
      return parsed as Record<string, unknown>[];
    });
  }

  /** The rendered text of one dataset column's cell — what the user actually sees. */
  async readDatasetCellText(datasetItemId: string, field: string): Promise<string> {
    return test.step(`read the on-screen "${field}" cell for item ${datasetItemId}`, async () => {
      // TanStack derives a column id from the accessor key by replacing dots,
      // so the `data.detail` column is addressed as `data_detail` here while
      // `sorting` and `filters` still take `data.detail` (see sortByColumn).
      const cell = this.page.locator(
        `td[data-cell-id="${datasetItemId}_data_${field}"]`,
      );
      await expect(cell, `"${field}" cell for item ${datasetItemId}`).toHaveCount(1);
      return ((await cell.textContent()) ?? '').trim();
    });
  }

  /**
   * The export trigger, addressed by its Download icon.
   *
   * No `data-testid` and no accessible name: the control is an icon-only button
   * whose only label is a hover tooltip, which contributes nothing to the
   * accessibility tree. A `data-testid` belongs on `ExportToButton` — it is not
   * added here because these specs are verified against a pre-built deployment
   * of the PR under test, where a front-end attribute added alongside them
   * would not exist. The count assertion in `expectExportEnabled` keeps the
   * match honest if a second download control ever joins the panel.
   */
  private get exportButton(): Locator {
    return this.page.locator('button:has(svg.lucide-download)');
  }

  /** The FiltersButton trigger — icon-only, labelled only by a hover tooltip. */
  private get filtersButton(): Locator {
    return this.page.locator('button:has(svg.lucide-filter)');
  }

  private rowCheckbox(datasetItemId: string): Locator {
    return this.page
      .locator(`tbody tr[data-row-id="${datasetItemId}"]`)
      .getByRole('checkbox', { name: 'Select row' });
  }

  private get compareHeading(): Locator {
    return this.page.getByRole('heading', { level: 1 });
  }

  /** The "Baseline of X compared against Y" summary row (compare mode only). */
  private get compareSummary(): Locator {
    return this.page.locator('div').filter({ hasText: /^Baseline of/ }).last();
  }

  private get itemRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * A per-experiment band inside a vertically-split grid cell. `columnId` is the
   * table column id (e.g. `feedback_scores_equals_metric`, `output_output`);
   * `experimentIndex` is the experiment's position in the `experiments` query
   * array, which is the order the bands render in.
   */
  private splitBand(datasetItemId: string, experimentIndex: number, columnId: string): Locator {
    const cell = this.page.locator(`td[data-cell-id="${datasetItemId}_${columnId}"]`);
    return cell.locator(`div[data-virtual-row-id="${datasetItemId}-${experimentIndex}"]`);
  }

  private configHeader(experimentId: string): Locator {
    return this.page.locator(`th[data-header-id="${experimentId}"]`);
  }

  /** A compared experiment's section in the row-detail panel, keyed by its h2 name. */
  private panelExperimentSection(experimentName: string): Locator {
    return this.page
      .getByRole('heading', { level: 2, name: experimentName })
      .locator('xpath=ancestor::*[.//table][1]');
  }
}
