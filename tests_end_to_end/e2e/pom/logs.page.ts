import { test, expect, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';
import { ThreadPanelPage } from './thread-panel.page';

export type ExplainKind = 'error' | 'duration' | 'cost';

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

  async goto(projectId: string): Promise<void> {
    return test.step(`Open Logs for project ${projectId}`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/logs`);
    });
  }

  /** Open Logs with the Threads tab active for the given project. */
  async gotoThreads(projectId: string): Promise<void> {
    return test.step(`Open Logs (Threads) for project ${projectId}`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/logs?logsType=threads`,
      );
    });
  }

  /** Open Logs with the Spans tab active for the given project. */
  async gotoSpans(projectId: string): Promise<void> {
    return test.step(`Open Logs (Spans) for project ${projectId}`, async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/logs?logsType=spans`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for Logs table ready', async () => {
      const realRow = this.page.locator('tr[data-row-id]').first();
      const emptyState = this.page.getByText('No traces yet');
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        emptyState.waitFor({ state: 'visible' }),
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

  /**
   * A span row in the Spans view, keyed by span id — the same `data-row-id`
   * hook the traces table stamps, since both render through the shared
   * DataTable.
   */
  spanRow(spanId: string): Locator {
    return this.page.locator(`tr[data-row-id="${spanId}"]`);
  }

  /**
   * The "Cost" cell of a span row, addressed by the table's own
   * `data-cell-id` (`<rowId>_<columnId>`).
   *
   * By cell id rather than by column position: the Spans table's columns are
   * user-configurable and their order is persisted, so any `nth-child` locator
   * would be asserting on a column layout instead of on a cost. `Cost` is one
   * of `DEFAULT_SPANS_COLUMNS`, so it is present without touching the column
   * picker; it may be scrolled out of the viewport, which is why callers should
   * assert on its text rather than on its visibility.
   */
  spanCostCell(spanId: string): Locator {
    return this.page.locator(`td[data-cell-id="${spanId}_total_estimated_cost"]`);
  }

  /**
   * Wait for the Spans view to have rendered rows.
   *
   * Deliberately has no empty-state escape hatch: the only callers seed spans
   * first, so an empty table is a seeding failure, and waiting it out reports
   * that instead of letting a later assertion pass against nothing.
   */
  async waitForSpansReady(): Promise<void> {
    return test.step('Wait for Logs (Spans) table ready', async () => {
      await this.page.locator('tr[data-row-id]').first().waitFor({ state: 'visible', timeout: 30_000 });
    });
  }

  /** Tick the selection checkbox on a trace's row. */
  async selectTrace(traceId: string): Promise<void> {
    return test.step(`Select trace ${traceId}`, async () => {
      await this.traceRow(traceId).getByRole('checkbox', { name: 'Select row' }).click();
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
  async waitForThreadsReady(threadId?: string): Promise<void> {
    return test.step('Wait for Threads table ready', async () => {
      const target = threadId
        ? this.threadRow(threadId)
        : this.page.locator('tr[data-row-id]').first();
      await target.waitFor({ state: 'visible' });
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
