import { Page } from '@playwright/test';

export class BasePage {
  protected page: Page;
  protected baseUrl: string;
  protected workspace: string;

  constructor(page: Page, baseUrl: string, workspace: string) {
    this.page = page;
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.workspace = workspace;
  }

  protected url(path: string): string {
    return `${this.baseUrl}/${this.workspace}/${path.replace(/^\/+/, '')}`;
  }

  async dismissWelcomeDialogIfPresent(): Promise<void> {
    // Scoped to a dialog: banners and callouts have close buttons too.
    const dismissButton = this.page.getByRole('dialog').getByRole('button', { name: /skip|close|dismiss/i }).first();
    try {
      await dismissButton.waitFor({ state: 'visible', timeout: 2000 });
      await dismissButton.click();
    } catch {
      // dialog not present, continue
    }
  }
}
