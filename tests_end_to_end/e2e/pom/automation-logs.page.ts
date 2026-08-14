import { test, type Page, type Locator } from '@playwright/test';

/**
 * The workspace-level Automation logs page (`/$workspace/automation-logs?rule_id=…`),
 * reached from a rule row's "Show logs" action. One row per line the online-
 * scoring engine emitted while evaluating that rule.
 *
 * The Message column renders only the first line until a row is expanded
 * (ExpandableTextCell), so assertions should target text that the engine puts
 * on the first line of its message.
 */
export class AutomationLogsPage {
  constructor(private readonly page: Page) {}

  /** The logs table body. */
  get rows(): Locator {
    return this.page.locator('tr[data-row-id]');
  }

  /**
   * Wait until the page has resolved into one of its two terminal states: at
   * least one log row, or the "no logs" empty state. Without this a caller can
   * read an empty table that is merely still loading.
   */
  async waitForReady(): Promise<void> {
    return test.step('Wait for the automation logs table', async () => {
      const emptyState = this.page.getByText('There are no logs for this rule.');
      await Promise.race([
        this.rows.first().waitFor({ state: 'visible', timeout: 30_000 }),
        emptyState.waitFor({ state: 'visible', timeout: 30_000 }),
      ]);
    });
  }

  /** Log rows whose (first-line) message contains the given text. */
  rowsContaining(text: string | RegExp): Locator {
    return this.rows.filter({ hasText: text });
  }

  /** Reload the logs — the engine writes them asynchronously after the rule fires. */
  async refresh(): Promise<void> {
    await this.page.reload();
  }
}
