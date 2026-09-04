import { test, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/** The levels the rule log stream renders in its Level column. */
export type AutomationLogLevel = 'INFO' | 'WARN' | 'ERROR';

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
}

/** Ids are namespaced with `-`, but escape the whole string rather than assume that. */
function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
