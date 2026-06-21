import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class LogsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/logs`) + '?logsType=traces');
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForTracesReady(expectedCellText: string): Promise<void> {
    await this.page.getByRole('radio', { name: 'Traces' }).waitFor({ state: 'visible', timeout: 10000 });
    await Promise.race([
      this.page.locator('tbody tr td').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 20000 }),
      this.page.getByRole('heading', { name: /no traces yet/i }).waitFor({ state: 'visible', timeout: 20000 }),
    ]);
  }

  async switchToThreads(): Promise<void> {
    await this.page.getByRole('radio', { name: 'Threads' }).click();
    await this.page.waitForLoadState('load');
  }

  async waitForThreadsReady(expectedCellText: string): Promise<void> {
    await Promise.race([
      this.page.locator('tbody tr td').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 20000 }),
      this.page.getByRole('heading', { name: /no threads yet/i }).waitFor({ state: 'visible', timeout: 20000 }),
    ]);
  }

  async waitForEmptyTraces(): Promise<void> {
    await this.page.getByRole('radio', { name: 'Traces' }).waitFor({ state: 'visible', timeout: 10000 });
    await this.page.getByRole('heading', { name: /no traces yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }

  async waitForEmptyThreads(): Promise<void> {
    await this.page.getByRole('heading', { name: /no threads yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
