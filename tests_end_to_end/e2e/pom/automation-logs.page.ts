import { test, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The workspace-level automation-logs page: one rule's user-facing log stream,
 * at `/$workspace/automation-logs?rule_id=<id>`.
 *
 * This is where an operator finds out why the online-scoring engine did what it
 * did with a trace — a routing decision writes a log line and nothing else, so
 * for those behaviours the page is the only read surface there is.
 *
 * The page has no auto-refresh: it fetches once on load (there is a manual
 * Refresh button). A caller therefore has to know the lines exist before
 * navigating, or reload — `waitForReady()` will happily settle on the
 * "no logs for this rule" empty state.
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

  /**
   * Wait until the page has finished its first fetch — either it rendered rows
   * or it rendered the empty state. Both are "ready"; which one you got is the
   * caller's assertion to make.
   */
  async waitForReady(): Promise<void> {
    return test.step('Wait for the automation logs table ready', async () => {
      await Promise.race([
        this.rows.first().waitFor({ state: 'visible', timeout: 30_000 }),
        this.emptyState.waitFor({ state: 'visible', timeout: 30_000 }),
      ]);
    });
  }

  /** Rendered when the rule has no log lines at all. */
  get emptyState(): Locator {
    return this.page.getByText('There are no logs for this rule.');
  }

  /** Every log row currently rendered. */
  get rows(): Locator {
    return this.page.locator('tr[data-row-id]');
  }

  /**
   * Log rows whose Message cell matches. Anchored on the message rather than on
   * the row id: the id is an md5 of the message plus its server-side timestamp,
   * so a test cannot compute it, and row order depends on when the engine
   * happened to write each line.
   *
   * The message cell holds the full text even when CSS truncates it, so a
   * regex over the rendered cell is a real assertion about what a user can
   * read here — not just about what the API returned.
   */
  logRow(message: RegExp): Locator {
    return this.rows.filter({ has: this.page.locator('td', { hasText: message }) });
  }

  /** The Level cell (`INFO` / `WARN` / `ERROR`) of a row returned by `logRow`. */
  levelCell(row: Locator): Locator {
    return row.locator('[data-cell-id$="_level"]');
  }

  /**
   * The Trace Id marker cell of a row. The engine stamps the trace (or span) it
   * was reasoning about onto each line as a marker, and the page renders the
   * markers as their own columns — which is what lets a spec tie a log line to
   * the entity it seeded rather than to whatever else the rule has processed.
   */
  traceIdCell(row: Locator): Locator {
    return row.locator('[data-cell-id$="_marker_trace_id"]');
  }
}
