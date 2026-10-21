import { test, expect, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';
import { ThreadPanelPage } from './thread-panel.page';
import { AddToDatasetDialogPage } from './add-to-dataset-dialog.page';

export type ExplainKind = 'error' | 'duration' | 'cost';

/**
 * The Logs table's row-height setting — `ROW_HEIGHT` in
 * apps/opik-frontend/src/types/shared.ts, labelled Compact / Medium / Detailed
 * in the selector.
 *
 * It matters to more than line count: cells switch RENDER PATH on it. At
 * small/medium a feedback score's reason goes into a hover tooltip; at large it
 * is written inline into the cell (`FeedbackScoreCell`), which is a different
 * element with a different white-space rule.
 */
export type LogsRowHeight = 'small' | 'medium' | 'large';

/**
 * One filter as the Logs URL carries it (`traces_filters` / `spans_filters`).
 *
 * Every field is optional because the shape is what is under test: a quick
 * filter on a span's `provider` must produce a `string` filter with NO `key`,
 * while one on a metadata attribute must produce a `dictionary` filter WITH
 * one. A type that required `key` could not express the first, and a spec
 * reading through it could not tell the two apart.
 */
export type LogsUrlFilter = {
  id?: string;
  field?: string;
  type?: string;
  key?: string;
  operator?: string;
  value?: string;
};

// Maps an explain kind to the Traces table column id (used in data-cell-id)
// and the owl trigger's aria-label, per apps/opik-frontend/src/plugins/comet/explain/registry.ts.
const EXPLAIN_COLUMN: Record<ExplainKind, string> = {
  error: 'error_info',
  duration: 'duration',
  cost: 'total_estimated_cost',
};
const EXPLAIN_LABEL: Record<ExplainKind, string> = {
  error: 'Explain error',
  duration: 'Explain duration',
  cost: 'Explain cost',
};

export class LogsPage {
  private projectId: string | null = null;

  constructor(private readonly page: Page) {}

  async goto(projectId: string, opts: { rowHeight?: LogsRowHeight } = {}): Promise<void> {
    return test.step(
      `Open Logs for project ${projectId}${opts.rowHeight ? ` at ${opts.rowHeight} row height` : ''}`,
      async () => {
        this.projectId = projectId;
        const env = loadEnvConfig();
        const query = opts.rowHeight ? `?height=${opts.rowHeight}` : '';
        await this.page.goto(
          `${env.baseUrl}/${env.workspace}/projects/${projectId}/logs${query}`,
        );
      },
    );
  }

  /**
   * Open Logs with the Spans tab active, optionally at a chosen page size and
   * date range.
   *
   * `size` and `timeRange` are the table's own URL query params (`size` and
   * `time_range`, see TracesSpansTab and MetricDateRangeSelect). Both are also
   * persisted in localStorage, so a spec that depends on either must state it
   * rather than inherit whatever the profile last stored.
   *
   * There is deliberately no `page` option. The table reads `page` from the URL
   * too, but `DataTablePagination` resets it to 1 whenever
   * `(page - 1) * size > total` — and `total` is 0 until the count query lands,
   * so a deep link to page 2 always bounces back to page 1. Paging is done by
   * clicking, through `goToNextPage()`.
   */
  async gotoSpans(
    projectId: string,
    opts: { size?: number; timeRange?: string } = {},
  ): Promise<void> {
    return test.step(`Open Logs (Spans) for project ${projectId}`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      const params = new URLSearchParams({ logsType: 'spans' });
      if (opts.size !== undefined) params.set('size', String(opts.size));
      if (opts.timeRange !== undefined) params.set('time_range', opts.timeRange);
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/logs?${params}`,
      );
    });
  }

  /**
   * Open Logs with the Traces tab active, optionally at a chosen date range.
   *
   * The sibling of `gotoSpans` and `gotoThreads`, and not the same thing as
   * `goto()`: that one states no `logsType` at all, so the active tab is
   * whatever localStorage last persisted for the project — and a bare `/logs`
   * on a fresh profile resolves to Threads, not Traces (`useLogsType`). A spec
   * about the Traces table has to say so.
   *
   * `timeRange` is the page's own `time_range` query param, the same one the
   * other two take. It is also persisted per project, and it decides whether
   * the read is windowed at all — so an unstated range is whichever one the
   * profile last stored.
   *
   * `size` is the table's page size, as `gotoSpans` already takes it. Worth
   * stating for the same reason `timeRange` is: the page keeps it in
   * localStorage as well as in the URL and syncs the two on init
   * (`useQueryParamAndLocalStorageState`), so a spec that wants its whole seed
   * on one page has to say how big a page is rather than inherit whatever the
   * profile last stored.
   */
  async gotoTraces(
    projectId: string,
    opts: { timeRange?: string; size?: number } = {},
  ): Promise<void> {
    return test.step(
      `Open Logs (Traces) for project ${projectId}${opts.timeRange ? ` over ${opts.timeRange}` : ''}`,
      async () => {
        this.projectId = projectId;
        const env = loadEnvConfig();
        const params = new URLSearchParams({ logsType: 'traces' });
        if (opts.timeRange !== undefined) params.set('time_range', opts.timeRange);
        if (opts.size !== undefined) params.set('size', String(opts.size));
        await this.page.goto(
          `${env.baseUrl}/${env.workspace}/projects/${projectId}/logs?${params}`,
        );
      },
    );
  }

  /** The Threads/Traces/Spans tab toggle for "Spans". */
  get spansTab(): Locator {
    return this.page.getByRole('radio', { name: 'Spans' });
  }

  /** The Threads/Traces/Spans tab toggle for "Traces". */
  get tracesTab(): Locator {
    return this.page.getByRole('radio', { name: 'Traces' });
  }

  /**
   * Which entity table is on screen, read from the toggle itself.
   *
   * Asserted rather than assumed by every spec that cares: `logsType` is
   * persisted per project in localStorage, so the active tab survives between
   * visits and a spec that inherited it would silently be driving the other
   * table.
   */
  async activeLogsTab(): Promise<'threads' | 'traces' | 'spans'> {
    return test.step('Read which entity tab is active', async () => {
      const checked = async (tab: Locator) =>
        (await tab.getAttribute('aria-checked')) === 'true';
      if (await checked(this.tracesTab)) return 'traces';
      if (await checked(this.spansTab)) return 'spans';
      if (await checked(this.threadsTab)) return 'threads';
      throw new Error('LogsPage.activeLogsTab: no entity toggle reported itself selected');
    });
  }

  /**
   * The filters the URL carries for one of the two tables, parsed.
   *
   * `null` when the param is absent, which is deliberately distinct from `[]`:
   * "the Traces filter was never written" is the assertion that separates a
   * quick filter correctly routed to the Spans table from one that wrote to
   * both, and an empty array would be a write.
   *
   * Read from the URL rather than from the chip bar because the URL is where
   * the filter's wire shape lives — field, type, operator and key, exactly as
   * the table will send them.
   */
  async readUrlFilters(type: 'traces' | 'spans'): Promise<LogsUrlFilter[] | null> {
    return test.step(`Read the ${type} filters from the URL`, async () => {
      const raw = new URL(this.page.url()).searchParams.get(`${type}_filters`);
      if (raw === null) return null;
      return JSON.parse(raw) as LogsUrlFilter[];
    });
  }

  /**
   * Wait until the URL carries filters for `type` that satisfy `predicate`.
   *
   * The quick filter writes the param with `replaceIn`, so there is no
   * navigation to await — the settle point is the param itself holding the
   * expected shape. Polling the parsed value (rather than string-matching the
   * URL) keeps the wait honest about JSON key order and encoding.
   */
  async waitForUrlFilters(
    type: 'traces' | 'spans',
    predicate: (filters: LogsUrlFilter[]) => boolean,
  ): Promise<LogsUrlFilter[]> {
    return test.step(`Wait for the ${type} filters in the URL`, async () => {
      await expect
        .poll(
          async () => {
            const filters = await this.readUrlFilters(type);
            return filters !== null && predicate(filters);
          },
          { message: `${type}_filters in the URL`, timeout: 15_000 },
        )
        .toBe(true);
      return (await this.readUrlFilters(type))!;
    });
  }

  /**
   * The chip ids currently pinned to one table's chip bar, read from the store
   * that owns them.
   *
   * `null` when nothing has been stored yet, which is the state a chip bar
   * showing its defaults is in — and the precondition that makes "the filter
   * pinned a chip that was not pinned before" mean something. Read from
   * localStorage because the bar renders a chip for a default and for a pinned
   * id identically, so the DOM cannot distinguish "already there" from
   * "just added".
   */
  async readPinnedChipIds(type: 'traces' | 'spans'): Promise<string[] | null> {
    return test.step(`Read the pinned chips stored for the ${type} table`, async () => {
      const key = `chips:pinnedConfig:logs.${type}`;
      const raw = await this.page.evaluate((k) => window.localStorage.getItem(k), key);
      if (raw === null) return null;
      return JSON.parse(raw) as string[];
    });
  }

  /**
   * Switch the entity toggle from whatever is active to Spans, and wait until
   * the toggle itself reports the change.
   *
   * Gated on `aria-checked` rather than on a row appearing: the two views share
   * the same table, so "some row is visible" is satisfied by the view the test
   * just navigated away from.
   */
  async switchToSpans(): Promise<void> {
    return test.step('Switch the Logs entity toggle to Spans', async () => {
      await this.spansTab.click();
      await expect(this.spansTab, 'the Spans toggle is selected').toHaveAttribute(
        'aria-checked',
        'true',
      );
    });
  }

  /**
   * A span row in the Spans view, keyed by span id. Same `data-row-id`
   * contract the traces view uses — the shared DataTable stamps it from the
   * row model — and named separately because a span id and a trace id are
   * different things to assert on.
   */
  spanRow(spanId: string): Locator {
    return this.page.locator(`tr[data-row-id="${spanId}"]`);
  }

  /**
   * The ids rendered on the current page of the table, in table order.
   *
   * A span row's `data-row-id` is the span id, the same contract the traces
   * view uses for trace ids.
   */
  async readRowIdsOnPage(): Promise<string[]> {
    return test.step('Read the row ids on the current page', async () => {
      await this.traceRows.first().waitFor({ state: 'visible' });
      const ids = await this.traceRows.evaluateAll((rows) =>
        rows.map((row) => row.getAttribute('data-row-id') ?? ''),
      );
      if (ids.some((id) => id === '')) {
        throw new Error('LogsPage.readRowIdsOnPage: a rendered row carried no data-row-id');
      }
      return ids;
    });
  }

  /** The pagination footer's "Showing 1-25 of 130" label. */
  private get paginationSummary(): Locator {
    return this.page.getByText(/^Showing [\d,]+-[\d,]+ of [\d,]+$/);
  }

  /**
   * The population the table's footer reports, or `null` while it reports none.
   *
   * `DataTablePagination` renders nothing at all when `total` is 0, so an
   * absent footer is not a missing element — it is the table saying it has no
   * rows, which is also what a caller sees while the list request is still in
   * flight. Returned rather than waited on, so a caller polls for the number it
   * expects instead of racing the fetch and reading whichever view answered
   * first.
   */
  async readPaginationTotal(): Promise<number | null> {
    return test.step('Read the population the table footer reports', async () => {
      const summary = this.paginationSummary;
      if ((await summary.count()) === 0) return null;
      const text = ((await summary.textContent()) ?? '').trim();
      const match = /of ([\d,]+)$/.exec(text);
      return match ? Number(match[1].replace(/,/g, '')) : null;
    });
  }

  /**
   * The pagination footer's "Showing 1-25 of 130", parsed.
   *
   * `total` is what the table tells the user the population is, and it comes
   * from the listing's own envelope rather than from the rows on screen — so a
   * read that lost rows shows up here as a `total` the collected ids cannot
   * account for. Rendered with `toLocaleString()`, hence the comma strip.
   */
  async readPaginationSummary(): Promise<{ from: number; to: number; total: number }> {
    return test.step('Read the table pagination summary', async () => {
      const summary = this.paginationSummary;
      await summary.waitFor({ state: 'visible' });
      const text = ((await summary.textContent()) ?? '').trim();
      const match = /^Showing ([\d,]+)-([\d,]+) of ([\d,]+)$/.exec(text);
      if (!match) {
        throw new Error(`LogsPage.readPaginationSummary: could not parse "${text}"`);
      }
      const toNumber = (value: string) => Number(value.replace(/,/g, ''));
      return {
        from: toNumber(match[1]),
        to: toNumber(match[2]),
        total: toNumber(match[3]),
      };
    });
  }

  /**
   * The shared pagination control's "next page" button.
   *
   * The four nav buttons in `DataTablePagination` are icon-only: no text, no
   * accessible name, no `data-testid`, and identical class lists — so the icon
   * is the only thing that tells them apart. They are addressed here by the
   * lucide class the icon carries (`lucide-chevron-right`), scoped to the
   * element holding the "Showing …" label so a chevron elsewhere on the page
   * cannot match. **A `data-testid` belongs on these buttons**; it is not added
   * in this change because these specs are verified against a deployed
   * environment, which a front-end change in the same PR would not reach — so
   * the spec could not be run before review.
   */
  private get nextPageButton(): Locator {
    return this.paginationSummary
      .locator('xpath=..')
      .locator('button:has(svg.lucide-chevron-right)');
  }

  /**
   * Advance the table one page, and wait until the rows on screen are actually
   * the next page's.
   *
   * Both conditions are needed, and the second is the one that matters. The
   * footer's "Showing 51-100" is derived from the page counter, so it flips the
   * instant the click lands — while the table keeps rendering the previous
   * page's rows until the new fetch resolves (`isPlaceholderData`, which is
   * also what the loading overlay is driven from). Waiting on the footer alone
   * reads the page you just left, so a caller collecting ids across pages
   * counts it twice and never sees the page it missed. Observed as a ~1-in-4
   * flake before this gate was added.
   *
   * The first row's id is the discriminator: two pages of a uniform table look
   * alike, but no id appears on both.
   */
  async goToNextPage(): Promise<void> {
    return test.step('Advance to the next page of the table', async () => {
      const from = (await this.readPaginationSummary()).from;
      const firstRowId = await this.traceRows.first().getAttribute('data-row-id');
      const button = this.nextPageButton;
      await expect(button, 'exactly one next-page control').toHaveCount(1);
      await button.click();

      await expect
        .poll(
          async () => {
            const summary = await this.readPaginationSummary();
            const firstRowNow = await this.traceRows.first().getAttribute('data-row-id');
            return summary.from > from && firstRowNow !== firstRowId;
          },
          { timeout: 30_000 },
        )
        .toBe(true);
    });
  }

  /**
   * The value a metrics card renders, e.g. "0.5s" for Avg duration.
   *
   * `type` is the KPI metric key the card is keyed on — `count`, `errors`,
   * `avg_duration`, `total_cost` (see MetricsSummary).
   */
  metricsCardValue(type: string): Locator {
    return this.page.getByTestId(`metrics-card-${type}-value`);
  }

  /**
   * The period-over-period delta a metrics card renders next to its value,
   * e.g. "125%" or "25pp". Returns the bare magnitude+unit; the arrow direction
   * is an icon, not text.
   *
   * The delta carries no test id of its own, but it is not merely "the card's
   * trailing text" either: `MetricCard` renders it as the span immediately
   * after the value span, both inside the same flex row, so it is addressed
   * structurally. Subtracting the value's text from the card's instead would
   * mis-parse whenever the value's characters also occur in the delta — a card
   * reading `0` beside a `-100%` delta finds the `0` in `100` and returns
   * `"%"`. The current seed happens to avoid that; the next one need not.
   *
   * Only rendered when each card is at least 240px wide (`getCardMode`), so a
   * caller asserting on it must widen the viewport. `renderChange()` also
   * returns nothing at all when the delta is undefined or non-finite, which is
   * why an absent sibling is reported as such rather than read as "".
   */
  async readMetricsCardDelta(type: string): Promise<string> {
    return test.step(`Read the "${type}" metrics card delta`, async () => {
      const value = this.metricsCardValue(type);
      await value.waitFor({ state: 'visible' });
      const delta = value.locator('xpath=following-sibling::span[1]');
      if ((await delta.count()) === 0) {
        throw new Error(
          `LogsPage.readMetricsCardDelta: card "${type}" rendered no delta beside its ` +
            `value — the viewport may be too narrow (needs ~240px per card), or the ` +
            `delta is undefined/non-finite`,
        );
      }
      return ((await delta.innerText()) ?? '').replace(/\s+/g, ' ').trim();
    });
  }

  /**
   * Open Logs with the Threads tab active for the given project.
   *
   * `timeRange` is the page's own `time_range` query param, the same one
   * `gotoSpans` takes. Two reasons a spec states it rather than inheriting the
   * default: the value is also persisted in localStorage and the URL is what
   * outranks it, so an unstated range is whatever the profile last stored; and
   * it decides whether the read is windowed at all — `alltime` sends no
   * `from_time`, and only a windowed read takes the `trace_threads` inner-join
   * branch. A spec about that branch has to say which range it means.
   *
   * Note that `alltime` is NOT available here: `ThreadsTab` passes
   * `excludePresets: [DATE_RANGE_PRESET_ALLTIME]`, so the Threads read is always
   * windowed and a caller asking for it gets the default preset instead. A
   * threads spec therefore states a bounded preset (`past24hours`, …) and seeds
   * inside it, rather than reaching for the unwindowed read its Traces sibling
   * can use.
   *
   * `size` is the table's page size, stated for the same reason `gotoTraces`
   * states it: the value is persisted in localStorage as well as carried in the
   * URL, so a spec that wants its whole seed on one page has to say how big a
   * page is.
   */
  async gotoThreads(
    projectId: string,
    opts: { timeRange?: string; size?: number } = {},
  ): Promise<void> {
    return test.step(
      `Open Logs (Threads) for project ${projectId}${opts.timeRange ? ` over ${opts.timeRange}` : ''}`,
      async () => {
        this.projectId = projectId;
        const env = loadEnvConfig();
        const params = new URLSearchParams({ logsType: 'threads' });
        if (opts.timeRange !== undefined) params.set('time_range', opts.timeRange);
        if (opts.size !== undefined) params.set('size', String(opts.size));
        await this.page.goto(
          `${env.baseUrl}/${env.workspace}/projects/${projectId}/logs?${params}`,
        );
      },
    );
  }

  /**
   * Wait until the Logs table has either a row or its empty state on screen.
   *
   * `timeout` is opt-in and defaults to the config's 15s `actionTimeout`, which
   * is what every existing caller gets. A caller whose project is large or
   * freshly seeded should raise it: the first row cannot paint until the listing
   * AND its count query have both answered over a project the write path is
   * still catching up with, and on a shared cloud workspace that has been
   * observed to take longer than 15s for a ~25-row project — surfacing as a
   * flake in `waitForReady` rather than in whatever the spec went on to assert.
   *
   * Not simply raised for everyone: a longer default would also lengthen the
   * failure of every spec whose project legitimately has no rows, turning a
   * quick, clear failure into a slow one.
   */
  async waitForReady(opts: { timeout?: number } = {}): Promise<void> {
    return test.step('Wait for Logs table ready', async () => {
      const realRow = this.page.locator('tr[data-row-id]').first();
      const emptyState = this.page.getByText('No traces yet');
      await Promise.race([
        realRow.waitFor({ state: 'visible', ...(opts.timeout ? { timeout: opts.timeout } : {}) }),
        emptyState.waitFor({
          state: 'visible',
          ...(opts.timeout ? { timeout: opts.timeout } : {}),
        }),
      ]);
      await this.page.waitForFunction(() => {
        const txt = document.body.innerText;
        return /Traces\s+\d+/i.test(txt);
      });
    });
  }

  async countTraces(): Promise<number> {
    return test.step('Read trace count', async () => {
      // Prefer the value-only testid so we never accidentally parse the delta
      // (e.g. "+5.0%") that the card also renders.
      const valueEl = this.page.getByTestId('metrics-card-count-value');
      if (await valueEl.isVisible().catch(() => false)) {
        const text = (await valueEl.textContent()) ?? '';
        const digits = text.replace(/\D/g, '');
        if (digits) return Number(digits);
      }
      // Fallback for staging deploys that don't yet have the value-only testid:
      // pull the count out of the "Traces N" stat text in the body.
      const handle = await this.page.waitForFunction(() => {
        const txt = document.body.innerText;
        const m = txt.match(/Traces\s+(\d+)/i);
        return m ? Number(m[1]) : null;
      });
      return (await handle.jsonValue()) as number;
    });
  }

  async openTraceById(traceId: string): Promise<TracePanelPage> {
    return test.step(`Open trace ${traceId}`, async () => {
      if (!this.projectId) {
        throw new Error('LogsPage.openTraceById: call goto(projectId) first');
      }
      const env = loadEnvConfig();
      const url = `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/logs?trace=${traceId}`;
      await this.page.goto(url);
      return new TracePanelPage(this.page, traceId);
    });
  }

  /**
   * Open a trace by clicking its row, the way a user reaches one.
   *
   * Distinct from {@link openTraceById}, which navigates to the trace's URL and
   * so reloads the page: a spec about what the panel remembers between openings
   * needs the in-app path, because a reload resets everything for free and
   * would make the assertion pass without the panel doing anything.
   */
  async openTraceByRow(traceId: string): Promise<TracePanelPage> {
    return test.step(`Open trace ${traceId} from its row`, async () => {
      const row = this.traceRow(traceId);
      await expect(row, 'exactly one row for this trace').toHaveCount(1);
      await row.click();
      const panel = new TracePanelPage(this.page, traceId);
      await panel.waitForFullyLoaded();
      return panel;
    });
  }

  async openFirstTrace(): Promise<TracePanelPage> {
    return test.step('Open first trace in table', async () => {
      const row = this.traceRows.first();
      await row.waitFor({ state: 'visible' });
      const traceId = await row.getAttribute('data-row-id');
      if (!traceId) {
        throw new Error('LogsPage.openFirstTrace: first row has no data-row-id attribute');
      }
      await row.click();
      return new TracePanelPage(this.page, traceId);
    });
  }

  async readTraceIdsInOrder(): Promise<string[]> {
    return test.step('Read trace IDs in table order', async () => {
      await this.traceRows.first().waitFor({ state: 'visible' });
      const rows = await this.traceRows.all();
      const ids: string[] = [];
      for (const row of rows) {
        const id = await row.getAttribute('data-row-id');
        if (id) ids.push(id);
      }
      return ids;
    });
  }

  /**
   * The current project's item in the breadcrumb, shown when navigated to /logs.
   * Matched by text rather than role: older UIs render it as a link, newer ones
   * (project menu redesign) as a dropdown button — the name is present in both.
   */
  breadcrumbProjectLink(projectName: string): Locator {
    return this.page
      .getByRole('navigation', { name: 'breadcrumb' })
      .getByText(projectName, { exact: true });
  }

  get traceRows(): Locator {
    return this.page.locator('tr[data-row-id]');
  }

  /**
   * A trace row, keyed by trace id. `data-row-id` is set from the row model by
   * the shared DataTable, so it is a first-class hook rather than a structural
   * fallback — the same one datasets/dataset-items/compare-experiments key on.
   * There is no text-based alternative: the id is a filter field, not a rendered
   * column, so it appears nowhere in the row's visible cells.
   */
  traceRow(traceId: string): Locator {
    return this.page.locator(`tr[data-row-id="${traceId}"]`);
  }

  /** Tick the selection checkbox on a trace's row. */
  async selectTrace(traceId: string): Promise<void> {
    return test.step(`Select trace ${traceId}`, async () => {
      await this.traceRow(traceId).getByRole('checkbox', { name: 'Select row' }).click();
    });
  }

  /**
   * Open the "Add to" dropdown in the traces actions panel and pick "Dataset".
   *
   * The dropdown offers Test suite / Dataset / Annotation queue from one
   * trigger (`AddToDropdown`), so the menu item is matched exactly — "Dataset"
   * as a substring would also match nothing else today, but the list is the
   * kind that grows. Callers select rows first via `selectTrace()`; the
   * trigger is disabled until at least one is ticked.
   */
  async openAddToDataset(): Promise<AddToDatasetDialogPage> {
    return test.step('Open Add to → Dataset', async () => {
      await this.page.getByRole('button', { name: 'Add to' }).click();
      await this.page.getByRole('menuitem', { name: 'Dataset', exact: true }).click();
      const dialog = new AddToDatasetDialogPage(this.page);
      await expect(dialog.root, 'the Add to dataset dialog is open').toBeVisible();
      return dialog;
    });
  }

  /**
   * The bulk-delete (trash) button in the traces actions panel. It renders as an
   * icon-only button with no accessible name — the "Delete" label lives in a
   * hover tooltip portal — so the testid is the only stable handle.
   */
  get bulkDeleteButton(): Locator {
    return this.page.getByTestId('traces-bulk-delete-button');
  }

  /** The "Delete traces" confirmation dialog. */
  get deleteTracesDialog(): Locator {
    return this.page.getByRole('dialog').filter({ hasText: 'Delete traces' });
  }

  /**
   * Bulk-delete the currently selected traces: open the confirm dialog and
   * accept it. Callers select rows first via selectTrace().
   */
  async bulkDeleteSelected(): Promise<void> {
    return test.step('Bulk-delete selected traces', async () => {
      await this.bulkDeleteButton.click();
      const dialog = this.deleteTracesDialog;
      await dialog.waitFor({ state: 'visible' });
      await dialog.getByRole('button', { name: 'Delete traces' }).click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * The "Selected: N" label in the selection action bar, which only renders
   * while at least one row is ticked.
   *
   * Matched on text because the bar exposes no testid and no role of its own —
   * it is a plain `<span>` inside a sticky container. The count is part of the
   * match rather than something read back out of it, so asserting visibility
   * asserts the number too: a selection that reached four rows renders
   * "Selected: 4" and this locator finds nothing.
   */
  selectionCount(count: number): Locator {
    return this.page.getByText(`Selected: ${count}`, { exact: true });
  }

  /**
   * The "Manage tags" button in the traces actions panel, which opens the
   * shared-tags dialog for the current selection.
   */
  get manageTagsButton(): Locator {
    return this.page.getByRole('button', { name: 'Manage tags' });
  }

  /** The "Manage shared tags" dialog. */
  get manageTagsDialog(): Locator {
    return this.page.getByRole('dialog').filter({ hasText: 'Manage shared tags' });
  }

  /**
   * Add one tag to every selected trace through the Manage shared tags dialog.
   *
   * `itemCount` is not a convenience: the confirm button is labelled
   * "Update tags for N items", so passing the number the caller believes it
   * selected makes the click itself an assertion that the dialog agrees. A
   * dialog that had picked up a different row set would render a different
   * label and this method would fail rather than quietly tag the wrong traces.
   *
   * The tag input is a bare `<input type="text">` that only mounts after the
   * "Add tag" chip is clicked, and it has neither a testid nor a label — the
   * textbox role inside the dialog is the most stable handle available. Enter
   * commits it: the dialog's own Enter handler is guarded on `!isAdding`, so
   * while the input is open it is the input that consumes the key.
   */
  async addSharedTagToSelection(tag: string, itemCount: number): Promise<void> {
    return test.step(`Add shared tag "${tag}" to ${itemCount} selected traces`, async () => {
      await this.manageTagsButton.click();
      const dialog = this.manageTagsDialog;
      await dialog.waitFor({ state: 'visible' });
      await dialog.getByTestId('add-tag-button').click();
      const input = dialog.getByRole('textbox');
      await input.waitFor({ state: 'visible' });
      await input.fill(tag);
      await input.press('Enter');
      const confirm = dialog.getByRole('button', {
        name: `Update tags for ${itemCount} ${itemCount === 1 ? 'item' : 'items'}`,
        exact: true,
      });
      await expect(confirm).toBeEnabled();
      await confirm.click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * The Duration cell of a trace row.
   *
   * Worth addressing directly because it is the one column that renders the
   * difference between a finished trace and one that was never closed: the FE's
   * `formatDuration` answers "NA" for a null duration, which is what a trace
   * submitted without an `end_time` shows while otherwise looking entirely
   * ordinary in the table.
   */
  durationCell(traceId: string): Locator {
    return this.page.locator(`[data-cell-id="${traceId}_duration"]`);
  }

  /**
   * A trace row's cell for ONE named feedback score.
   *
   * Each score in the project is its own dynamic column, and every score the
   * project has ever carried is auto-selected into the table on a fresh profile
   * (`useDynamicColumnsCache`), so a seeded score needs no column configuration
   * to be visible.
   *
   * The column is declared with `id: 'feedback_scores.<name>'`, which
   * `mapColumnDataFields` hands to TanStack as an `accessorKey` and no explicit
   * id — and TanStack derives the id from an accessorKey by replacing the first
   * `.` with `_`. Hence `feedback_scores_<name>` here while the wire-level
   * `sorting`/`filters` params still take the dotted form. Same idiom as
   * `CompareExperimentsPage.readItemScore`.
   */
  feedbackScoreCell(traceId: string, scoreName: string): Locator {
    return this.page.locator(`td[data-cell-id="${traceId}_feedback_scores_${scoreName}"]`);
  }

  /**
   * A feedback-score cell's text AS RENDERED, and its height.
   *
   * `innerText`, never `textContent`, and that is the whole point of this
   * helper: the reason is seeded with a real `\n`, so a `textContent` read
   * reports the newline back from the DOM even on a build whose CSS collapsed it
   * on screen — and the spec would pass having verified nothing. `innerText` is
   * computed from the rendered box, so `white-space: normal` shows up in it as a
   * space.
   *
   * The height comes back with it because the two are one observation: at
   * Detailed row height the reason is written inline into the cell, so "the
   * newline survived" and "the cell grew to hold two lines" are the same claim
   * seen twice, and a caller that reads them in separate round-trips could have
   * them straddle a re-render.
   *
   * Counted before reading: a dynamic feedback-score column is named by the
   * score, so an ambiguous match would mean the table is rendering two columns
   * for one score — worth failing on rather than silently taking `.first()`.
   */
  async readFeedbackScoreCell(
    traceId: string,
    scoreName: string,
  ): Promise<{ text: string; height: number }> {
    return test.step(`read the rendered "${scoreName}" cell of trace ${traceId}`, async () => {
      const cell = this.feedbackScoreCell(traceId, scoreName);
      await expect(cell, `exactly one "${scoreName}" cell for trace ${traceId}`).toHaveCount(1);
      await expect(cell, `the "${scoreName}" cell for trace ${traceId}`).toBeVisible();
      return cell.evaluate((el) => ({
        text: (el as HTMLElement).innerText,
        height: el.getBoundingClientRect().height,
      }));
    });
  }

  /**
   * The hover trigger that holds a feedback score's reason at Compact/Medium
   * height — `FeedbackScoreReasonTooltip`'s `MessageSquareMore` icon.
   *
   * Addressed by the Lucide icon class because the trigger is a bare `div` with
   * no role, name or `data-testid`; the same idiom `CompareExperimentsPage` uses
   * for the shared table's icon-only controls, and for the same reason — these
   * specs run against a pre-built deployment, so a front-end attribute added
   * beside them would not exist in the build under test.
   *
   * Only ever used to tell the two render paths apart: its presence is what says
   * the cell took the tooltip branch rather than the inline one.
   */
  feedbackScoreReasonTooltipTrigger(traceId: string, scoreName: string): Locator {
    return this.feedbackScoreCell(traceId, scoreName).locator('svg.lucide-message-square-more');
  }

  /** The Errors/Duration/Estimated cost cell for a trace row, keyed by Ollie explain kind. */
  explainCell(traceId: string, kind: ExplainKind): Locator {
    return this.page.locator(`[data-cell-id="${traceId}_${EXPLAIN_COLUMN[kind]}"]`);
  }

  /**
   * Hover a trace's Errors/Duration/Estimated cost cell and click its Ollie
   * "Explain" owl trigger, opening the popover. The trigger only renders once
   * the Ollie assistant bridge handshake (mounted via the page's assistant
   * sidebar) completes, which can lag a beat after the table itself is
   * interactive — so this polls hover+lookup rather than asserting once.
   */
  async openExplain(traceId: string, kind: ExplainKind, timeoutMs = 60_000): Promise<void> {
    return test.step(`open Ollie explain (${kind}) for trace ${traceId}`, async () => {
      const cell = this.explainCell(traceId, kind);
      const button = cell.getByRole('button', { name: EXPLAIN_LABEL[kind] });
      await expect
        .poll(
          async () => {
            await cell.hover();
            return button.count();
          },
          { timeout: timeoutMs, intervals: [500, 1000, 2000] },
        )
        .toBeGreaterThan(0);
      await button.click();
    });
  }

  /**
   * Wait for the open Ollie explain popover to settle (loading -> done/error)
   * and return its rendered text. Scoped to the last `[role="status"]` live
   * region on the page — Radix unmounts a closed popover's content, so only
   * the currently-open one's region should be present.
   */
  async readExplanation(timeoutMs = 60_000): Promise<string> {
    return test.step('wait for Ollie explain popover to settle', async () => {
      const status = this.page.locator('[role="status"]').last();
      await expect(status).toHaveAttribute('aria-busy', 'false', { timeout: timeoutMs });
      const text = ((await status.textContent()) ?? '').trim();
      if (!text) {
        throw new Error('Ollie explain popover settled but rendered no text');
      }
      return text;
    });
  }

  /** Close the open Ollie explain popover. */
  async closeExplain(): Promise<void> {
    return test.step('close Ollie explain popover', async () => {
      await this.page.keyboard.press('Escape');
    });
  }

  /**
   * The "Continue conversation" link in the currently open Ollie explain
   * popover. Only rendered once the popover has settled with text (see
   * ExplainPopover.tsx) — call after `readExplanation()`.
   */
  continueConversationButton(): Locator {
    return this.page.getByRole('button', { name: 'Continue conversation' });
  }

  /**
   * Click "Continue conversation" to hand the explain popover's question +
   * cached answer off to the Ollie sidebar chat. This closes the popover as
   * a side effect (see ExplainPopover's onContinue).
   */
  async continueConversation(): Promise<void> {
    return test.step('continue the Ollie explain conversation in the sidebar', async () => {
      await this.continueConversationButton().click();
    });
  }

  // --- Filter chips ---

  /**
   * A filter chip's trigger button, keyed by chip id (see TRACE_CHIP_ORDER in
   * TracesSpansTab.tsx). Keyed by testid rather than accessible name because an
   * applied chip rewrites its own label — "Tags" becomes "Tags: contains prod" —
   * so a name-based locator would stop matching the moment the filter lands.
   *
   * Chip ids are snake_case domain keys; the rendered testid is kebab-case (see
   * chipTestId in the FE), so callers pass the id and this maps it.
   */
  filterChip(chipId: string): Locator {
    return this.page.getByTestId(`filter-chip-${chipId.replace(/_/g, '-')}`);
  }

  /**
   * The open chip's popover. Keyed by testid, not by `role=dialog`: the Logs
   * page mounts other dialogs (the delete-traces confirmation among them), and
   * a bare role lookup would match those too — so the filter helpers would
   * Escape-dismiss an unrelated confirmation.
   *
   * Only one chip popover is mounted at a time, so this resolves the open one —
   * but it still says nothing about *which* chip owns it, so callers acting on
   * a specific chip gate on that chip's aria-expanded (see openFilterChip).
   */
  get filterChipPopover(): Locator {
    return this.page.getByTestId('filter-chip-popover');
  }

  /** The "Clear all (N)" button, rendered only while at least one filter is applied. */
  get clearAllFiltersButton(): Locator {
    return this.page.getByTestId('filter-chips-clear-all');
  }

  /**
   * Open a chip's popover, leaving *this* chip the open one.
   *
   * Readiness is gated on the requested chip's own aria-expanded, not on "some
   * dialog is visible": only one chip popover is mounted at a time, so a
   * generic dialog check would report success while a different chip owned it
   * and the caller would then fill that chip's row instead. When another chip
   * is open it is dismissed first — Radix ignores a click on a second trigger
   * while one popover holds the pointer.
   *
   * The click is retried because Radix keeps a pointer-blocking layer mounted
   * for a beat after a popover closes, which swallows the first click.
   */
  async openFilterChip(chipId: string): Promise<void> {
    return test.step(`Open the "${chipId}" filter chip`, async () => {
      const chip = this.filterChip(chipId);
      await chip.waitFor({ state: 'visible' });
      const isOpen = async () =>
        (await chip.getAttribute('aria-expanded').catch(() => null)) === 'true';

      await expect
        .poll(
          async () => {
            if (await isOpen()) return true;
            if (await this.filterChipPopover.isVisible().catch(() => false)) {
              await this.closeFilterChip();
            }
            await chip.click().catch(() => {});
            return isOpen();
          },
          { intervals: [100, 250, 500, 1000] },
        )
        .toBe(true);
    });
  }

  /**
   * Close the open chip popover and wait for it to detach, so the next click
   * isn't swallowed by the closing animation.
   *
   * Escape is pressed twice by design: the autocomplete cells handle the first
   * one themselves (it resets the draft and blurs the input) without letting it
   * reach the popover, so a single press leaves the popover open. The second
   * press — now that focus has left the input — dismisses the popover itself.
   */
  async closeFilterChip(): Promise<void> {
    return test.step('Close the open filter chip popover', async () => {
      const popover = this.filterChipPopover;
      await expect
        .poll(
          async () => {
            if (!(await popover.isVisible().catch(() => true))) return false;
            await this.page.keyboard.press('Escape');
            return popover.isVisible().catch(() => false);
          },
          { intervals: [100, 250, 500, 1000] },
        )
        .toBe(false);
    });
  }

  /**
   * One row of the open chip's query builder. A chip can hold several rows
   * ("Add tag" appends one) and every row reuses the same cell testids, so the
   * row scope is what keeps `fill()` unambiguous under Playwright strict mode.
   * Defaults to the first row, which is the one a freshly-opened chip renders.
   */
  filterChipRow(index = 0): Locator {
    return this.filterChipPopover.getByRole('listitem').nth(index);
  }

  /**
   * Apply a single-value filter (tags, name, error type, ...): open the chip,
   * type the value, then close so the debounced change commits.
   */
  async applyFilter(chipId: string, value: string, rowIndex = 0): Promise<void> {
    return test.step(`Filter by ${chipId} = "${value}"`, async () => {
      await this.openFilterChip(chipId);
      await this.filterChipRow(rowIndex).getByTestId('filter-chip-value-input').fill(value);
      await this.closeFilterChip();
    });
  }

  /**
   * Apply a keyed filter (feedback scores, metadata): these render a key cell
   * plus a value cell, and the key must be set before the value counts as applied.
   */
  async applyKeyedFilter(
    chipId: string,
    key: string,
    value: string,
    rowIndex = 0,
  ): Promise<void> {
    return test.step(`Filter by ${chipId} "${key}" = "${value}"`, async () => {
      await this.openFilterChip(chipId);
      const row = this.filterChipRow(rowIndex);
      await row.getByTestId('filter-chip-key-input').fill(key);
      await row.getByTestId('filter-chip-value-input').fill(value);
      await this.closeFilterChip();
    });
  }

  /** Toggle a boolean chip (e.g. "With errors"), which applies on a single click. */
  async toggleBooleanFilter(chipId: string): Promise<void> {
    return test.step(`Toggle the "${chipId}" filter`, async () => {
      await this.filterChip(chipId).click();
    });
  }

  /**
   * Pin a chip that isn't shown by default by picking it from the "All filters"
   * manager. Selecting an item pins the chip and opens its popover, so callers
   * that follow with applyKeyedFilter() get a popover that's already open —
   * openFilterChip() tolerates that.
   */
  async pinFilterChip(menuItemLabel: string): Promise<void> {
    return test.step(`Pin the "${menuItemLabel}" filter chip`, async () => {
      await this.page.getByTestId('filter-chip-manager-trigger').click();
      const menu = this.page.getByRole('menu');
      await menu.waitFor({ state: 'visible' });
      await menu.getByText(menuItemLabel, { exact: true }).click();
    });
  }

  /** Clear every applied filter via the "Clear all (N)" button. */
  async clearAllFilters(): Promise<void> {
    return test.step('Clear all filters', async () => {
      await this.clearAllFiltersButton.click();
      await this.clearAllFiltersButton.waitFor({ state: 'hidden' });
    });
  }

  // --- Free-text search ("Search by anything") ---

  /**
   * The "Search by anything" box that sits as `FilterChipBar`'s prefix on the
   * Traces, Spans and Threads tabs.
   *
   * `data-testid="search-input"` comes from the shared `SearchInput`, so it is
   * the FE's own stability contract rather than a structural fallback. The
   * testid is generic (the component is shared), so every method below asserts
   * the lookup resolved to exactly ONE box: a popover that mounted a second
   * SearchInput of its own would otherwise be typed into silently.
   */
  get searchBox(): Locator {
    return this.page.getByTestId('search-input');
  }

  /**
   * Type `term` into the search box and wait for the page to have taken it.
   *
   * The settle is on the URL's own `<type>_search` param, which is the page's
   * single source of truth for the term and what the next list/stats request is
   * built from — `SearchInput` is a `DebounceInput` with a 300ms delay, so a
   * caller that read the table straight after typing would be reading the
   * pre-search answer. Waiting on the committed param instead of on a fixed
   * delay is what keeps that deterministic.
   *
   * Compared lower-cased because `TracesSpansTab` commits
   * `search.trim().toLowerCase()` — the term that reaches the API is the
   * folded one, and `ilike` makes that equivalent. Callers that want the API
   * and the UI to be asking the identical question should pass a term that is
   * already lower-case.
   *
   * `type` is `'threads'` as well as the two entity tables, because the Threads
   * tab mounts the same `SearchInput` under its own `threads_search` param. Note
   * that `ThreadsTab` passes the raw term to the STATS read and the trimmed,
   * folded one to the listing, so a caller asserting that the count card and the
   * rows agree must pass a term that is already trimmed and lower-case — the
   * two are otherwise asking different questions by construction.
   *
   * Does NOT wait for the rows: what the table then shows is the assertion, and
   * a POM that waited for a particular row count would be deciding the answer
   * before the spec got to.
   */
  async searchFor(
    term: string,
    type: 'traces' | 'spans' | 'threads' = 'traces',
  ): Promise<void> {
    return test.step(`Search the ${type} table for "${term}"`, async () => {
      await expect(this.searchBox, 'exactly one search box on the Logs page').toHaveCount(1);
      await this.searchBox.fill(term);
      const committed = term.trim().toLowerCase();
      await this.page.waitForURL(
        (url) => url.searchParams.get(`${type}_search`) === committed,
        { timeout: 15_000 },
      );
    });
  }

  /**
   * Clear the search box, and wait for the term to leave the URL.
   *
   * Through the box's own Clear button — the control a user reaches for, and
   * the one that also resets the page back to 1. The param is dropped entirely
   * rather than set empty, so the wait is on absence.
   */
  async clearSearch(type: 'traces' | 'spans' | 'threads' = 'traces'): Promise<void> {
    return test.step(`Clear the ${type} table's search`, async () => {
      await expect(this.searchBox, 'exactly one search box on the Logs page').toHaveCount(1);
      // The Clear button only renders while the box holds text, and it is an
      // icon-only `Button` with no name — addressed as the button inside the
      // search box's own wrapper, which is the only one there.
      const clear = this.searchBox.locator('xpath=..').getByRole('button');
      await expect(clear, 'exactly one clear button beside the search box').toHaveCount(1);
      await clear.click();
      await this.page.waitForURL(
        (url) => {
          const value = url.searchParams.get(`${type}_search`);
          return value === null || value === '';
        },
        { timeout: 15_000 },
      );
    });
  }

  // --- Threads tab ---

  /** The Threads/Traces/Spans tab toggle for "Threads". */
  get threadsTab(): Locator {
    return this.page.getByRole('radio', { name: 'Threads' });
  }

  /**
   * Wait for the Threads table to be ready. When a threadId is given, wait for
   * that specific row — threads are eventually consistent, so gating on "any
   * row" can pass before the seeded thread has been aggregated into the list.
   */
  async waitForThreadsReady(
    threadId?: string,
    opts: { timeout?: number } = {},
  ): Promise<void> {
    return test.step('Wait for Threads table ready', async () => {
      const target = threadId
        ? this.threadRow(threadId)
        : this.page.locator('tr[data-row-id]').first();
      // `timeout` opt-in, defaulting to the config's 15s actionTimeout, for the
      // same reason `waitForReady` takes one — and more so here: a thread row is
      // materialised from the traces that share its id, so the Threads listing
      // trails the trace write by a further aggregation step.
      await target.waitFor({
        state: 'visible',
        ...(opts.timeout ? { timeout: opts.timeout } : {}),
      });
    });
  }

  /**
   * The number shown in the "Threads" metrics card. The Threads view reuses the
   * same count-card testid as the Traces view; with the tab active this is the
   * thread count.
   */
  async countThreads(): Promise<number> {
    return test.step('Read thread count', async () => {
      const valueEl = this.page.getByTestId('metrics-card-count-value');
      await valueEl.waitFor({ state: 'visible' });
      const text = (await valueEl.textContent()) ?? '';
      const digits = text.replace(/\D/g, '');
      return digits ? Number(digits) : 0;
    });
  }

  /** A thread row, keyed by thread id (the row's data-row-id IS the thread id). */
  threadRow(threadId: string): Locator {
    return this.page.locator(`tr[data-row-id="${threadId}"]`);
  }

  /**
   * Every rendered thread row.
   *
   * The same locator as {@link traceRows} — one shared `DataTable` stamps
   * `data-row-id` on whichever entity it is rendering — but named for the
   * Threads tab so a spec about threads does not read as though it were
   * asserting on traces. Which table is on screen is `activeLogsTab()`'s
   * business, and a threads spec asserts that first.
   */
  get threadRows(): Locator {
    return this.page.locator('tr[data-row-id]');
  }

  /**
   * The thread ids rendered on the current page, in table order.
   *
   * The Threads table's `data-row-id` is the THREAD id — a string the producer
   * chose, not a UUID — which is also the only place it appears in the row: the
   * id is not one of the rendered columns, so there is no text-based alternative.
   */
  async readThreadIdsOnPage(): Promise<string[]> {
    return test.step('Read the thread ids on the current page', async () => {
      await this.threadRows.first().waitFor({ state: 'visible' });
      const ids = await this.threadRows.evaluateAll((rows) =>
        rows.map((row) => row.getAttribute('data-row-id') ?? ''),
      );
      if (ids.some((id) => id === '')) {
        throw new Error('LogsPage.readThreadIdsOnPage: a rendered row carried no data-row-id');
      }
      return ids;
    });
  }

  /**
   * Read the "Message count" cell for a thread. Note: the Threads view counts
   * messages, so a conversation of N turns (N traces) reports 2*N messages
   * (each trace contributes an input and an output message).
   */
  async readThreadMessageCount(threadId: string): Promise<number> {
    return test.step(`Read message count for thread ${threadId}`, async () => {
      const cell = this.threadRow(threadId).locator(
        `[data-cell-id="${threadId}_number_of_messages"]`,
      );
      await cell.waitFor({ state: 'visible' });
      const text = (await cell.textContent()) ?? '';
      const digits = text.replace(/\D/g, '');
      return digits ? Number(digits) : 0;
    });
  }

  /** The "First message" cell text for a thread. */
  threadFirstMessageCell(threadId: string): Locator {
    return this.threadRow(threadId).locator(`[data-cell-id="${threadId}_first_message"]`);
  }

  /** The "Last message" cell text for a thread. */
  threadLastMessageCell(threadId: string): Locator {
    return this.threadRow(threadId).locator(`[data-cell-id="${threadId}_last_message"]`);
  }

  /** Open a thread's detail panel by id, returning the conversation panel POM. */
  async openThreadById(threadId: string): Promise<ThreadPanelPage> {
    return test.step(`Open thread ${threadId}`, async () => {
      if (!this.projectId) {
        throw new Error('LogsPage.openThreadById: call gotoThreads(projectId) first');
      }
      const env = loadEnvConfig();
      const url = `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/logs?logsType=threads&thread=${threadId}`;
      await this.page.goto(url);
      return new ThreadPanelPage(this.page, threadId);
    });
  }
}
