import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class OnlineEvaluationPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/online-evaluation`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: /no online evaluations yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
