import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';

/**
 * Page object for the Playground logs sidebar (TraceLogsSidebar).
 *
 * The sidebar opens as a Sheet/dialog when the user clicks the "Go to logs"
 * icon button in the Playground header. The URL gains `tls_open=1`.
 * Clicking a trace row adds `tls_trace=<id>` and renders the trace detail
 * panel (`data-testid="traces"`) inside the same dialog.
 */
export class PlaygroundLogsSidebarPage {
  constructor(private readonly page: Page) {}

  /** The Sheet dialog element that hosts the sidebar. */
  get dialog(): Locator {
    return this.page.getByRole('dialog', { name: 'Playground logs' });
  }

  /** Wait for the sidebar sheet to be visible. */
  async waitForOpen(): Promise<void> {
    return test.step('wait for logs sidebar to open', async () => {
      await this.dialog.waitFor({ state: 'visible' });
    });
  }

  /** All trace rows in the sidebar table. */
  traceRows(): Locator {
    return this.dialog.locator('tr[data-row-id]');
  }

  firstTraceRow(): Locator {
    return this.traceRows().first();
  }

  /** Poll until at least one trace row appears (traces are async after a run). */
  async waitForTraceRow(timeoutMs = 30_000): Promise<void> {
    return test.step('wait for trace row to appear', async () => {
      await expect
        .poll(
          async () => (await this.traceRows().count()) > 0,
          { timeout: timeoutMs, intervals: [500, 1000, 2000] },
        )
        .toBe(true);
    });
  }

  /** Click the first trace row and wait for the trace detail panel to open. */
  async openFirstTrace(): Promise<void> {
    return test.step('open first trace', async () => {
      await this.firstTraceRow().click();
      await this.page.waitForURL((url) => !!url.searchParams.get('tls_trace'));
      await this.traceDetailPanel().waitFor({ state: 'visible' });
    });
  }

  /** The trace detail panel rendered inside the sidebar. */
  traceDetailPanel(): Locator {
    return this.dialog.getByTestId('traces');
  }

  /** Click the "Prompts" tab in the trace detail panel. */
  async clickPromptsTab(): Promise<void> {
    return test.step('click Prompts tab', async () => {
      await this.traceDetailPanel().getByRole('tab', { name: 'Prompts' }).click();
    });
  }

  /** The "Messages" tab. Absent when no format claims the trace's input or output. */
  messagesTab(): Locator {
    return this.traceDetailPanel().getByRole('tab', { name: 'Messages' });
  }

  async clickMessagesTab(): Promise<void> {
    return test.step('click Messages tab', async () => {
      await this.messagesTab().click();
    });
  }

  /**
   * Asserts the panel opened on Messages rather than falling back to Details, which is the
   * half of the behaviour a click would mask.
   */
  async expectMessagesTabSelectedByDefault(): Promise<void> {
    return test.step('expect Messages to be the default tab', async () => {
      await expect(this.messagesTab()).toHaveAttribute('aria-selected', 'true');
    });
  }

  /**
   * The active tab's panel. Only the selected tab is mounted, so scoping to this
   * keeps assertions off the raw JSON the Details tab renders.
   */
  activeTabPanel(): Locator {
    return this.traceDetailPanel().getByRole('tabpanel');
  }

  /** The header of a rendered conversation turn, by role. */
  messageRole(role: 'System' | 'User' | 'Assistant'): Locator {
    return this.activeTabPanel().getByRole('heading', { name: role, exact: true });
  }

  /** The body of a rendered conversation turn, by role. */
  messageBody(role: 'System' | 'User' | 'Assistant'): Locator {
    return this.activeTabPanel().getByRole('region', { name: role, exact: true });
  }

  /** The collapsed error block, present only when the entity carries error_info. */
  errorCallout(): Locator {
    return this.traceDetailPanel().getByRole('button', { name: 'Error' });
  }

  async expandErrorCallout(): Promise<void> {
    return test.step('expand the error callout', async () => {
      await this.errorCallout().click();
    });
  }

  /** The expanded error block, which renders error_info as a syntax-highlighted document. */
  errorCalloutBody(): Locator {
    return this.traceDetailPanel().locator('[class*="border-destructive"]');
  }
}
