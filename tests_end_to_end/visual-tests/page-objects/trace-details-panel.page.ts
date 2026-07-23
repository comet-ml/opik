import { Page, Locator } from '@playwright/test';

export class TraceDetailsPanelPage {
  constructor(private page: Page) {}

  get root(): Locator {
    return this.page.getByTestId('traces');
  }

  /**
   * The panel's whole stats row (created-at, duration, score counts) as one block.
   * The row is `w-full` so its own box is stable even though the inline badges inside it
   * are not: real duration/timestamp text can render a pixel narrower or wider between
   * runs, shifting every badge after it — masking only the created-at div left those
   * shifted neighbors unmasked and flaky. Scoped here (not in the shared baseMasks)
   * since this row only exists in the trace/span sidebar.
   */
  get statsRowMask(): Locator {
    return this.page.getByTestId('data-viewer-created-at').locator('xpath=..');
  }

  async waitForLoaded(): Promise<void> {
    await this.root.getByRole('button', { name: 'Close' }).waitFor({ state: 'visible', timeout: 20000 });
    await this.page.getByTestId('data-viewer-created-at').waitFor({ state: 'visible', timeout: 20000 });
    // The initial tab's content (span input/output) lazy-loads after the panel chrome is up;
    // wait for its "Loading" placeholder to clear so we don't screenshot mid-layout-shift.
    await this.root.getByText('Loading', { exact: true }).waitFor({ state: 'hidden', timeout: 15000 });
  }

  async selectTab(name: string): Promise<void> {
    await this.root.getByRole('tab', { name }).click();
    const tabPanel = this.root.getByRole('tabpanel', { name });
    await tabPanel.waitFor({ state: 'visible', timeout: 15000 });
    await tabPanel.getByText('Loading', { exact: true }).waitFor({ state: 'hidden', timeout: 15000 });
  }
}
