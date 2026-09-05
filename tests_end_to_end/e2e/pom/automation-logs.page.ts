import { test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * Column ids the page's DataTable stamps into `data-cell-id`
 * (`<rowId>_<columnId>`).
 *
 * `marker_trace_id` is generated, not declared: the page builds one column per
 * key found in the log lines' `markers` map, so the Trace Id column only exists
 * once a line carrying that marker has loaded. Addressing cells by these ids
 * rather than by position is what keeps the POM correct after a user resizes or
 * reorders columns — the widths are persisted in localStorage.
 */
const COLUMN = {
  timestamp: 'timestamp',
  level: 'level',
  traceId: 'marker_trace_id',
  message: 'message',
} as const;

type ColumnName = keyof typeof COLUMN;

/**
 * Anchored, whitespace-tolerant exact match for a cell's rendered text.
 *
 * A substring `hasText` would be wrong here in a way that passes: an ERROR row
 * and a WARN row both contain "R", and a trace id is a prefix of nothing but
 * itself only by luck.
 */
function exactText(value: string): RegExp {
  return new RegExp(`^\\s*${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*$`);
}

/**
 * The workspace-scoped automation rule log stream at
 * `/$workspaceName/automation-logs`.
 *
 * The page is driven entirely by a `rule_id` search param — there is no rule
 * picker on it. It is reached from the online-evaluation rules table's "Logs"
 * action, which is why the bare route has an empty state rather than a listing.
 */
export class AutomationLogsPage {
  constructor(private readonly page: Page) {}

  /**
   * Open the log stream for a rule, or the bare route when `ruleId` is omitted
   * — the omission is a case worth driving, not an oversight.
   */
  async goto(ruleId?: string): Promise<void> {
    return test.step(
      ruleId ? `Open automation logs for rule ${ruleId}` : 'Open automation logs with no rule',
      async () => {
        const env = loadEnvConfig();
        const query = ruleId ? `?rule_id=${encodeURIComponent(ruleId)}` : '';
        await this.page.goto(`${env.baseUrl}/${env.workspace}/automation-logs${query}`);
      },
    );
  }

  /**
   * Race a real row against the two empty states. The table unmounts entirely
   * when there is nothing to show, so waiting on it alone hangs on a rule that
   * has not logged yet — and skeleton rows carry no `data-row-id`, so neither
   * branch can match a still-loading table.
   */
  async waitForReady(timeoutMs = 30_000): Promise<void> {
    return test.step('Wait for the automation logs view ready', async () => {
      await Promise.race([
        this.rows.first().waitFor({ state: 'visible', timeout: timeoutMs }),
        this.noLogsMessage.waitFor({ state: 'visible', timeout: timeoutMs }),
        this.noRuleParametersMessage.waitFor({ state: 'visible', timeout: timeoutMs }),
      ]);
    });
  }

  get rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /** Empty state of the bare route — no `rule_id` was supplied. */
  get noRuleParametersMessage(): Locator {
    return this.page.getByText('No rule parameters set.');
  }

  /** Empty state of a rule that exists but has written nothing yet. */
  get noLogsMessage(): Locator {
    return this.page.getByText('There are no logs for this rule.');
  }

  /**
   * The row for one log line, addressed by the two facts that identify it: the
   * trace it is about and its level.
   *
   * Deliberately not by `data-row-id`: the page derives that from
   * `<timestamp>-<level>-md5(message)`, so it is neither an entity id nor
   * anything a test can know in advance.
   */
  row({ traceId, level }: { traceId: string; level: string }): Locator {
    return this.rows
      .filter({
        has: this.page.locator(`td[data-cell-id$="_${COLUMN.level}"]`, {
          hasText: exactText(level),
        }),
      })
      .filter({
        has: this.page.locator(`td[data-cell-id$="_${COLUMN.traceId}"]`, {
          hasText: exactText(traceId),
        }),
      });
  }

  /** One cell of a row, by column rather than by position. */
  cell(row: Locator, column: ColumnName): Locator {
    return row.locator(`td[data-cell-id$="_${COLUMN[column]}"]`);
  }

  /**
   * The per-row Expand control, which the message cell renders only for a
   * multi-line message — a single-line one is shown whole and needs no toggle.
   */
  expandButton(row: Locator): Locator {
    return row.getByRole('button', { name: 'Expand', exact: true });
  }

  async expandRow(row: Locator): Promise<void> {
    return test.step('expand the log row', async () => {
      await this.expandButton(row).click();
    });
  }
}
