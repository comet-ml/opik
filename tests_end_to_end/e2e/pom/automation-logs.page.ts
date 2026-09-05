import { test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The rule log stream at `/$workspaceName/automation-logs?rule_id=…` — the only
 * page in the app that says why an online-evaluation rule did or did not score a
 * trace.
 *
 * Rows are addressed by their `level` and `message` cells rather than by index:
 * the page sorts newest-first, so a rule that was redelivered shifts every row,
 * and the message column is resizable/persisted like every other DataTable.
 * Row ids here are a content hash (`AutomationLogsPage.tsx`), not an entity id,
 * so `data-row-id` identifies a row but cannot be predicted by a test — hence
 * lookups go through the cells.
 */
export class AutomationLogsPage {
  constructor(private readonly page: Page) {}

  async goto(ruleId: string): Promise<void> {
    return test.step(`Open the automation logs for rule ${ruleId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/automation-logs?rule_id=${ruleId}`,
      );
    });
  }

  /**
   * Race a real row against the "no logs" empty state, so a rule that has not
   * logged yet fails on the assertion that names it rather than on a timeout
   * inside the wait. Skeleton rows carry no `data-row-id`, so neither branch can
   * match a still-loading table.
   */
  async waitForReady(timeoutMs = 30_000): Promise<void> {
    return test.step('Wait for the rule log table ready', async () => {
      await Promise.race([
        this.page
          .locator('tbody tr[data-row-id]')
          .first()
          .waitFor({ state: 'visible', timeout: timeoutMs }),
        this.emptyState.waitFor({ state: 'visible', timeout: timeoutMs }),
      ]);
    });
  }

  get emptyState(): Locator {
    return this.page.getByText('There are no logs for this rule.');
  }

  get rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * Rows whose message cell contains `text`, optionally narrowed to one level.
   *
   * `message` is matched as a substring on purpose: every line the scorer writes
   * embeds a trace id and a rule name, so callers identify a line by the fragment
   * that names it. The level cell is matched exactly, because `WARN` must never
   * satisfy a check for `ERROR`.
   */
  rowsWithMessage(text: string, level?: string): Locator {
    const byMessage = this.rows.filter({
      has: this.page.locator('[data-cell-id$="_message"]', { hasText: text }),
    });
    if (level === undefined) return byMessage;
    return byMessage.filter({
      has: this.page.locator(`[data-cell-id$="_level"]`, {
        hasText: new RegExp(`^\\s*${level}\\s*$`),
      }),
    });
  }
}
