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
}
