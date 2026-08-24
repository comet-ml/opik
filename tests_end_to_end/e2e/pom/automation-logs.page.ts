import { test, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * Automation logs page object — `/$workspaceName/automation-logs?rule_id=<id>`.
 *
 * The view is workspace-scoped, not project-scoped: the only thing that selects
 * a rule is the `rule_id` search param, and without it the page renders
 * "No rule parameters set." instead of a table.
 *
 * Rows are addressed by their Message text, never by index. The shared
 * DataTable derives this table's row id from `timestamp-level-md5(message)`, so
 * there is no entity id a spec could predict and `data-row-id` is useless as a
 * key — but every cell is still stamped `data-cell-id="<rowId>_<columnId>"`,
 * which is what the locators below key on. That survives the user-configurable
 * column order a `:nth-child()` selector would not.
 */
export class AutomationLogsPage {
  constructor(private readonly page: Page) {}

  async goto(ruleId: string): Promise<void> {
    return test.step(`navigate to Automation logs for rule ${ruleId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/automation-logs?rule_id=${encodeURIComponent(ruleId)}`,
      );
    });
  }

  /**
   * Wait for the log table to be populated.
   *
   * Deliberately does NOT race an empty state: a caller that has already
   * confirmed through the API that the rule logged something wants a real row,
   * and treating "There are no logs for this rule." as ready would hand the
   * spec a table it can only make vacuous assertions about.
   */
  async waitForReady(): Promise<void> {
    return test.step('wait for the automation log table to render rows', async () => {
      await this.page.getByRole('heading', { name: 'Logs', level: 1 }).waitFor({ state: 'visible' });
      await this.rows().first().waitFor({ state: 'visible' });
    });
  }

  /** Every rendered log row. */
  rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * The rows whose Message text matches `pattern`.
   *
   * `pattern` is matched against the message *text span*, not the whole cell:
   * a multi-line message renders collapsed (`ExpandableTextCell` shows only the
   * first line) alongside an "Expand" button, so the cell's own text content
   * carries a trailing "Expand" that would defeat an anchored pattern. For the
   * same reason `pattern` must describe the FIRST line of the message.
   *
   * Returns a Locator rather than a row so the caller can assert
   * `toHaveCount(1)` — an ambiguous match must fail loudly rather than silently
   * resolve through `.first()`.
   */
  rowsWithMessage(pattern: RegExp): Locator {
    return this.rows().filter({
      has: this.page.locator('[data-cell-id$="_message"] span').filter({ hasText: pattern }),
    });
  }

  /** The Level cell ("INFO" / "WARN" / "ERROR") of a row. */
  levelCell(row: Locator): Locator {
    return row.locator('[data-cell-id$="_level"]');
  }

  /** Every Level cell in the table, for whole-table assertions. */
  allLevelCells(): Locator {
    return this.page.locator('tbody tr[data-row-id] [data-cell-id$="_level"]');
  }
}
