import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class PromptsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/prompts`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: /no prompts yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
