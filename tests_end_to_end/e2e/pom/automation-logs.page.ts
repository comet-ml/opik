import { test, expect, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export type LogLevel = 'INFO' | 'WARN' | 'ERROR' | 'DEBUG' | 'TRACE';

/**
 * The workspace-level Automation logs page
 * (`/$workspaceName/automation-logs?rule_id=…`), rendered by
 * `AutomationLogsPage.tsx`.
 *
 * It is the only surface that tells a user WHY a rule stopped producing scores,
 * so the rows it renders are the product, not decoration. Two things about the
 * table shape drive the locators below:
 *
 *   - Rows carry no domain id. `getRowId` hashes timestamp+level+message, so a
 *     row can only be addressed by its content — and per-trace attribution lives
 *     in the `trace_id` marker column, which the page builds dynamically from
 *     whichever marker keys the backend returned.
 *   - The message column is an `ExpandableTextCell`: a multi-line message shows
 *     only its FIRST line until the row's own Expand button is pressed. Most
 *     online-scoring messages are multi-line (the detail lives after a blank
 *     line), so a spec that reads a collapsed cell is reading a headline.
 */
export class AutomationLogsPage {
  constructor(private readonly page: Page) {}

  /**
   * Open the page directly for a rule. The product path is the rule row's "Show
   * logs" link (`OnlineEvaluationPage.openLogsForRule`), which opens this same
   * route in a new tab; this exists for specs that only need the page's state.
   */
  async goto(ruleId: string): Promise<void> {
    return test.step(`Open Automation logs for rule ${ruleId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/automation-logs?rule_id=${ruleId}`,
      );
    });
  }

  /**
   * Resolve to either a real log row or the empty state, whichever arrives —
   * the same shape as the other list POMs. An empty rule renders `NoData`
   * instead of the table, so waiting on the table alone would hang on the very
   * case a spec might be asserting.
   */
  async waitForReady(): Promise<void> {
    return test.step('Wait for the Automation logs table ready', async () => {
      await Promise.race([
        this.logRows.first().waitFor({ state: 'visible' }),
        this.emptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  get emptyState(): Locator {
    return this.page.getByText('There are no logs for this rule.').first();
  }

  get logRows(): Locator {
    return this.page.locator('tr[data-row-id]');
  }

  /**
   * Rows attributed to `traceId` at `level`.
   *
   * Matched on the `trace_id` MARKER cell, not on row text: every message body
   * also quotes its own trace id, so a text filter over the whole row would
   * match the message column too — and the marker cell is the column that
   * carries the backend's actual attribution. Both filters are anchored and
   * exact so a partial id or a level prefix cannot match.
   */
  rowsFor(
    traceId: string,
    level: LogLevel,
    opts: { messageContains?: string } = {},
  ): Locator {
    const rows = this.logRows
      .filter({ has: this.cellWithText('marker_trace_id', traceId) })
      .filter({ has: this.cellWithText('level', level) });
    if (opts.messageContains === undefined) return rows;
    // Matches the COLLAPSED text: an unexpanded cell renders only the message's
    // first line, so this has to be something the headline carries.
    return rows.filter({
      has: this.page
        .locator('[data-cell-id$="_message"]')
        .filter({ hasText: opts.messageContains }),
    });
  }

  /** The message cell of a row, collapsed or expanded. */
  messageCell(row: Locator): Locator {
    return row.locator('[data-cell-id$="_message"]');
  }

  /**
   * Reveal a multi-line message's body. The button only exists on rows the page
   * considers expandable (more than one line), so this asserts it is there
   * rather than silently no-op'ing on a message that unexpectedly arrived as a
   * single line.
   */
  async expandMessage(row: Locator): Promise<void> {
    return test.step('Expand the log message', async () => {
      const button = this.messageCell(row).getByRole('button', { name: 'Expand' });
      await expect(button, 'a multi-line message renders an Expand control').toBeVisible();
      await button.click();
      await expect(
        this.messageCell(row).getByRole('button', { name: 'Collapse' }),
      ).toBeVisible();
    });
  }

  /**
   * A cell of the given column whose text is exactly `text`.
   *
   * `data-cell-id` is `<rowId>_<columnId>`, so the suffix match names the column
   * without depending on its position — this table's column set is dynamic (one
   * column per marker key the backend returned), so an index would drift.
   */
  private cellWithText(columnId: string, text: string): Locator {
    return this.page
      .locator(`[data-cell-id$="_${columnId}"]`)
      .filter({ hasText: new RegExp(`^\\s*${escapeRegExp(text)}\\s*$`) });
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
