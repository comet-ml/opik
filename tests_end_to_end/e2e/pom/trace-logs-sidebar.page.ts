import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The shared trace-logs overlay (`TraceLogsSidebar` + `TraceLogsView`), opened
 * by the "Logs" triggers on entity-scoped views — an optimization trial, an
 * online-evaluation rule, a playground run.
 *
 * The overlay is a Radix Sheet, so it is the page's only `dialog`; the trial
 * side panel it opens over is a `ResizableSidePanel` addressed by its
 * `data-testid`, not a dialog, so the role stays unambiguous.
 */
export class TraceLogsSidebarPage {
  constructor(private readonly page: Page) {}

  get overlay(): Locator {
    return this.page.getByRole('dialog');
  }

  /** Trace rows in the overlay's table. `data-row-id` is the trace id — a
   *  first-class hook from the shared DataTable, not a structural guess. */
  get traceRows(): Locator {
    return this.overlay.locator('tbody tr[data-row-id]');
  }

  /**
   * The locked-scope chip. Its presence is what makes the narrowing visible to
   * a user: without it a scoped table is indistinguishable from an unscoped one
   * that happens to be short.
   */
  scopeChip(label: string): Locator {
    return this.overlay.getByText(label, { exact: true });
  }

  async waitForReady(): Promise<void> {
    return test.step('wait for the logs overlay', async () => {
      await expect(this.overlay).toBeVisible();
    });
  }

  /**
   * Poll until the overlay settles on exactly `expected` rows.
   *
   * Exact, never a lower bound: the scope is the point of this view, so a
   * count above the expected one means the scope leaked and the whole project
   * is being listed.
   */
  async waitForTraceRows(expected: number, timeoutMs = 30_000): Promise<void> {
    return test.step(`wait for ${expected} trace rows in the logs overlay`, async () => {
      await expect(this.traceRows).toHaveCount(expected, { timeout: timeoutMs });
    });
  }

  async readTraceIds(): Promise<string[]> {
    return this.traceRows.evaluateAll((rows) =>
      rows.map((r) => r.getAttribute('data-row-id') ?? ''),
    );
  }

  async close(): Promise<void> {
    return test.step('close the logs overlay', async () => {
      await this.page.keyboard.press('Escape');
      await expect(this.overlay).toBeHidden();
    });
  }
}
