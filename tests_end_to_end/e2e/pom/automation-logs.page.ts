import { test, expect, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/** One rendered row of the automation-logs table. */
export interface RenderedLogRow {
  /** Value of the "Trace Id" marker column. */
  traceId: string;
  /** Value of the "Message" column, as displayed. */
  message: string;
}

/**
 * The workspace-level automation-logs page — `/$workspaceName/automation-logs?rule_id=…`,
 * the page the "Show logs" link on an online-evaluation rule row opens.
 *
 * Columns are not fixed: `AutomationLogsPage` renders Timestamp and Level, then
 * one column per distinct marker key present in the answer, then Message. For
 * the online-scoring path the only marker is `trace_id`, so the table reads
 * Timestamp / Level / Trace Id / Message. Cells are therefore addressed by the
 * shared `DataTable`'s `data-cell-id` (`<rowId>_<columnId>`) rather than by
 * position — column order shifts the moment a second marker key appears, and
 * `nth-child` would start reading the wrong column without failing.
 */
export class AutomationLogsPage {
  constructor(private readonly page: Page) {}

  async goto(ruleId: string): Promise<void> {
    return test.step(`Open automation logs for rule ${ruleId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/automation-logs?rule_id=${ruleId}`,
      );
    });
  }

  /** All table rows. Empty until the query resolves — wait via `waitForRowCount`. */
  get rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * The page's empty state, shown both when the rule has no logs and when
   * `rule_id` is missing. Exposed so a spec can distinguish "no rows yet" from
   * "the page decided there are none".
   */
  get noLogsMessage(): Locator {
    return this.page.getByText('There are no logs for this rule.');
  }

  /**
   * Wait until the table has settled on exactly `expected` rows.
   *
   * An equality, not a minimum: the log stream is eventually consistent, so a
   * `>= expected` wait would return on the first partial render and let a spec
   * assert a count that is still climbing. Given a rule whose line count is
   * fixed by construction, waiting for the exact number is both the correct
   * wait and the assertion.
   */
  async waitForRowCount(expected: number, timeoutMs = 60_000): Promise<void> {
    return test.step(`Wait for ${expected} log rows`, async () => {
      await expect(this.rows).toHaveCount(expected, { timeout: timeoutMs });
    });
  }

  /**
   * Read every rendered row as {traceId, message}.
   *
   * Cells are read per row by `data-cell-id` suffix so the pairing survives any
   * column reorder; a row missing either cell throws rather than yielding an
   * empty string, because a blank marker would quietly match nothing and turn a
   * missing-column regression into a passing set comparison.
   */
  async readRows(): Promise<RenderedLogRow[]> {
    return test.step('Read the automation-logs table', async () => {
      const rows = await this.rows.all();
      return Promise.all(
        rows.map(async (row) => ({
          traceId: await this.readCell(row, 'marker_trace_id'),
          message: await this.readCell(row, 'message'),
        })),
      );
    });
  }

  private async readCell(row: Locator, columnId: string): Promise<string> {
    const cell = row.locator(`[data-cell-id$="_${columnId}"]`);
    await expect(
      cell,
      `each log row must render exactly one "${columnId}" cell`,
    ).toHaveCount(1);
    return (await cell.innerText()).trim();
  }
}
