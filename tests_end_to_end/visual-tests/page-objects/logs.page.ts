import { Page, expect } from '@playwright/test';
import { BasePage } from './base.page';

export class LogsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/logs`));
    await this.page.waitForLoadState('networkidle');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForTracesReady(): Promise<void> {
    await this.page.getByRole('radio', { name: 'Traces' }).waitFor({ state: 'visible', timeout: 10000 });
    // Wait for either rows or empty state
    await Promise.race([
      this.page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 10000 }),
      this.page.getByRole('heading', { name: /no traces yet/i }).waitFor({ state: 'visible', timeout: 10000 }),
    ]);
  }

  async switchToThreads(): Promise<void> {
    await this.page.getByRole('radio', { name: 'Threads' }).click();
    await this.page.waitForLoadState('networkidle');
  }

  async waitForThreadsReady(): Promise<void> {
    await Promise.race([
      this.page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 10000 }),
      this.page.getByRole('heading', { name: /no threads yet/i }).waitFor({ state: 'visible', timeout: 10000 }),
    ]);
  }
}
