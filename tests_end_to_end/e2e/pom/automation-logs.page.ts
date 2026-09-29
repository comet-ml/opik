import { expect, test, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The levels the rule log stream renders in its Level column.
 *
 * All five the product defines, not just the three today's specs assert on:
 * the backend's `LogItem.LogLevel` and the frontend's `EVALUATOR_LOG_LEVEL`
 * both carry `DEBUG` and `TRACE`, and a union narrower than the surface would
 * make a future spec reach for a type escape to address a row the page can
 * genuinely render.
 */
export type AutomationLogLevel = 'INFO' | 'WARN' | 'ERROR' | 'DEBUG' | 'TRACE';

/** One rendered row of the rule log table, as `readRows` reads it back. */
export interface RenderedLogRow {
  level: string;
  /** The `trace_id` marker column — how the page attributes a line to a trace. */
  traceId: string;
  /**
   * The message as displayed. Collapsed rows show only the first line of a
   * multi-line message, which is what `expandRow` exists to reveal.
   */
  message: string;
}

/**
 * `/$workspaceName/automation-logs?rule_id=<id>` — the page behind an online
 * evaluation rule's **Show logs**, and the only place in the product where a
 * user can see whether their rule ran, what it sent, and why it failed.
 *
 * The table is the shared `DataTable`, so rows carry `data-row-id` and cells
 * carry `data-cell-id="<rowId>_<columnId>"`. Columns are addressed by that id
 * suffix rather than by position: a marker column (`marker_thread_model_id`)
 * appears only when the rendered lines carry markers, so the Message column is
 * the third cell for a trace-scope rule and the fourth for a thread-scope one.
 * `nth-child` would therefore read the wrong column depending on which rule
 * you opened.
 */
export class AutomationLogsPage {
  constructor(private readonly page: Page) {}

  async goto(ruleId: string): Promise<void> {
    return test.step(`Open automation logs for rule ${ruleId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/automation-logs?rule_id=${encodeURIComponent(ruleId)}`,
      );
    });
  }

  /**
   * Settle on either a rendered table or the page's own empty state, so a spec
   * asserting an absence waits for the page to finish rather than racing it.
   */
  async waitForReady(): Promise<void> {
    return test.step('Wait for the automation logs table', async () => {
      await Promise.race([
        this.rows().first().waitFor({ state: 'visible' }),
        this.emptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  /**
   * Poll until the table settles on exactly `expected` rows.
   *
   * `waitForReady` only proves the FIRST row arrived, and `readRows` is a
   * one-shot DOM read with no retry of its own — so reading straight after it
   * can catch a stream mid-render and silently compare a subset.
   *
   * Exact, never a lower bound, for the same reason as
   * `TraceLogsSidebar.waitForTraceRows`: this page is read as the whole of one
   * rule's stream, so a count above the expected one means another rule's lines
   * leaked in, which is one of the failures this view exists to catch.
   */
  async waitForRowCount(expected: number, timeoutMs = 30_000): Promise<void> {
    return test.step(`Wait for ${expected} rule log rows`, async () => {
      await expect(this.rows()).toHaveCount(expected, { timeout: timeoutMs });
    });
  }

  get emptyState(): Locator {
    return this.page.getByText('There are no logs for this rule.');
  }

  /** Every rendered log row. */
  rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * Rows whose Level cell reads exactly `level`.
   *
   * Anchored, so `INFO` cannot also match a hypothetical `INFO_DEBUG`; the
   * Level column holds one word per row.
   */
  rowsAtLevel(level: AutomationLogLevel): Locator {
    return this.rows().filter({
      has: this.page.locator('td[data-cell-id$="_level"]', {
        hasText: new RegExp(`^\\s*${level}\\s*$`),
      }),
    });
  }

  /**
   * Rows whose Message cell contains `text`, optionally narrowed to one level.
   *
   * `text` is matched as a substring on purpose: every line the scorer writes
   * embeds a trace id and a rule name, so callers identify a line by the
   * fragment that names it. The level, when given, is still matched exactly
   * through `rowsAtLevel`, because `WARN` must never satisfy a check for
   * `ERROR`.
   */
  rowsWithMessage(text: string, level?: AutomationLogLevel): Locator {
    const base = level === undefined ? this.rows() : this.rowsAtLevel(level);
    return base.filter({
      has: this.page.locator('td[data-cell-id$="_message"]', { hasText: text }),
    });
  }

  /**
   * Rows at `level` whose message names `threadId '<threadId>'`.
   *
   * The quotes the backend puts around the id are part of the match, and they
   * are what makes it exact: an unquoted `hasText` on `<ns>-thread-a` would
   * also match a row about `<ns>-thread-ab`, so two sibling threads could not
   * be told apart — which is precisely the claim these specs make.
   */
  rowsForThreadAtLevel(level: AutomationLogLevel, threadId: string): Locator {
    return this.rowsAtLevel(level).filter({
      has: this.page.locator('td[data-cell-id$="_message"]', {
        hasText: new RegExp(`threadId '${escapeForRegExp(threadId)}'`),
      }),
    });
  }

  /**
   * Every rendered row, newest first — the order the page sorts them in.
   *
   * For callers that pin the WHOLE stream by exhaustion rather than filtering
   * for the lines they expect: a `rowsWithMessage` count says nothing about a
   * foreign rule's line also rendering. Read through `data-cell-id` suffixes
   * for the same reason the locators above are, and never by column position.
   */
  async readRows(): Promise<RenderedLogRow[]> {
    return test.step('Read the rendered log rows', async () => {
      return this.rows().evaluateAll((rows) =>
        rows.map((row) => {
          const cellText = (columnId: string) =>
            (
              row.querySelector(`td[data-cell-id$="_${columnId}"]`) as HTMLElement | null
            )?.innerText ?? '';
          return {
            level: cellText('level').trim(),
            traceId: cellText('marker_trace_id').trim(),
            // The Expand/Collapse control lives inside the message cell, so its
            // label rides along in innerText; drop the trailing line it adds.
            message: cellText('message').replace(/\n(Expand|Collapse)$/, '').trim(),
          };
        }),
      );
    });
  }

  /**
   * Expand a row's message cell so the whole multi-line message renders.
   *
   * The collapsed cell shows only the first line, so anything a scorer appends
   * after a newline — a provider's error body, for one — is invisible until
   * this is clicked.
   */
  async expandRow(row: Locator): Promise<void> {
    return test.step('Expand the row message', async () => {
      await row.getByRole('button', { name: 'Expand' }).click();
    });
  }
}

/** Ids are namespaced with `-`, but escape the whole string rather than assume that. */
function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
