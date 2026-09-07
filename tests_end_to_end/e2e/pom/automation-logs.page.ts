import { test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/** One rendered row of the rule log table. */
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
 * The workspace-scoped rule log stream at `/$workspaceName/automation-logs`.
 *
 * The page takes its rule from the `rule_id` query parameter and has no
 * in-product navigation of its own worth driving here — the Logs action on a
 * rule row just links to this URL — so the rule id is a constructor argument
 * and `goto()` builds the link.
 */
export class AutomationLogsPage {
  constructor(
    private readonly page: Page,
    private readonly ruleId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step(`Open automation logs for rule ${this.ruleId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/automation-logs?rule_id=${this.ruleId}`,
      );
    });
  }

  /**
   * Race a real row against the "no logs" state, the same way the alerts list
   * does: the table unmounts entirely when a rule has never logged, so waiting
   * on the table alone would hang there for the full timeout.
   *
   * Skeleton rows carry no `data-row-id`, so neither branch can match a
   * still-loading table.
   */
  async waitForReady(timeoutMs = 30_000): Promise<void> {
    return test.step('Wait for the rule log table to render', async () => {
      await Promise.race([
        this.rows.first().waitFor({ state: 'visible', timeout: timeoutMs }),
        this.emptyState.waitFor({ state: 'visible', timeout: timeoutMs }),
      ]);
    });
  }

  get rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  get emptyState(): Locator {
    return this.page.getByText('There are no logs for this rule.');
  }

  /**
   * Rows whose Level cell reads exactly `level`.
   *
   * Anchored and exact: a substring filter for `ERROR` would also match a
   * hypothetical `ERROR_RETRY`, and the caller counts what this returns.
   */
  rowsWithLevel(level: string): Locator {
    return this.rows.filter({
      has: this.page.locator('[data-cell-id$="_level"]', {
        hasText: new RegExp(`^\\s*${level}\\s*$`),
      }),
    });
  }

  /**
   * Every rendered row, newest first — the order the page sorts them in.
   *
   * Read through `data-cell-id` suffixes rather than column positions: the
   * column set here is data-driven (marker columns appear only when some line
   * carries that marker) and column widths are persisted per browser profile,
   * so an nth-child lookup would read a different cell on a different machine.
   */
  async readRows(): Promise<RenderedLogRow[]> {
    return test.step('Read the rendered log rows', async () => {
      return this.rows.evaluateAll((rows) =>
        rows.map((row) => {
          const cellText = (columnId: string) =>
            (
              row.querySelector(`[data-cell-id$="_${columnId}"]`) as HTMLElement | null
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
