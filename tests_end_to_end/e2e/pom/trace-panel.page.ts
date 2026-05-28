import { test, type Page, type Locator } from '@playwright/test';

export class TracePanelPage {
  constructor(
    private readonly page: Page,
    private readonly traceId: string,
  ) {}

  async waitForFullyLoaded(): Promise<void> {
    return test.step(`Wait for trace panel ${this.traceId} to load`, async () => {
      await this.page.waitForURL((url) => url.searchParams.get('trace') === this.traceId);
      await this.closeButton.waitFor({ state: 'visible', timeout: 30_000 });
      await this.page.getByTestId('data-viewer-created-at').waitFor({ state: 'visible', timeout: 30_000 });
    });
  }

  /** Root locator for the side-panel content, scoped to the panel testid. */
  get root(): Locator {
    return this.page.getByTestId('traces');
  }

  get closeButton(): Locator {
    return this.root.getByRole('button', { name: 'Close' });
  }

  get inputSection(): Locator {
    return this.root.getByRole('button', { name: 'Input', expanded: true });
  }

  get outputSection(): Locator {
    return this.root.getByRole('button', { name: 'Output', expanded: true });
  }

  /** Heading-area locator for the trace name shown in the panel toolbar. */
  traceNameInHeader(name: string): Locator {
    return this.root.getByText(name, { exact: true }).first();
  }

  /** Text matching `Spans (n)` shown above the spans tree. */
  spansCountLabel(n: number): Locator {
    return this.root.getByText(new RegExp(`^Spans\\s*\\(${n}\\)$`)).first();
  }

  /** Rendered input text inside the panel's Details tab. */
  inputValue(value: string): Locator {
    return this.root.getByText(value, { exact: true }).first();
  }

  /** Rendered output text inside the panel's Details tab. */
  outputValue(value: string): Locator {
    return this.root.getByText(value, { exact: true }).first();
  }

  async close(): Promise<void> {
    return test.step('Close trace panel', async () => {
      await this.closeButton.click();
      await this.page.waitForURL((url) => !url.searchParams.get('trace'));
    });
  }

  /** Locator for the Feedback scores tab inside the panel. */
  get feedbackScoresTab(): Locator {
    return this.root.getByRole('tab', { name: 'Feedback scores' });
  }

  /** Locator for the Feedback scores tab panel content (the table area). */
  get feedbackScoresTabPanel(): Locator {
    return this.root.getByRole('tabpanel', { name: 'Feedback scores' });
  }

  /** Switches to the Feedback scores tab. Idempotent if already selected. */
  async openFeedbackScoresTab(): Promise<void> {
    await this.feedbackScoresTab.click();
    await this.feedbackScoresTabPanel.waitFor({ state: 'visible' });
  }

  /**
   * Row in the Trace scores table matching the given score name. The Key cell
   * truncates long names with CSS ellipsis, so the accessible name reads as
   * "cuj-..." rather than the full string; matching by `hasText` against the
   * row's DOM text content (which preserves the full name) is reliable across
   * panel widths.
   */
  feedbackScoreRow(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.getByRole('row').filter({ hasText: scoreName });
  }

  /**
   * Read the numeric value rendered in the Score column for the given score name.
   * Requires the Feedback scores tab to be open (call openFeedbackScoresTab first).
   * Throws if the row doesn't exist or the cell isn't a parseable number.
   */
  async readFeedbackScoreValue(scoreName: string): Promise<number> {
    const row = this.feedbackScoreRow(scoreName);
    await row.waitFor({ state: 'visible' });
    // Columns are: Key | Score | Reason | <actions>
    const cellText = (await row.getByRole('cell').nth(1).textContent()) ?? '';
    const parsed = Number(cellText.trim());
    if (Number.isNaN(parsed)) {
      throw new Error(
        `TracePanelPage.readFeedbackScoreValue: cell text "${cellText}" for score "${scoreName}" is not a number`,
      );
    }
    return parsed;
  }
}
