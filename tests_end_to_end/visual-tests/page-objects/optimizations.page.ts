import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class OptimizationsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/optimizations`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: /no optimization runs yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
