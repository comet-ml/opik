import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class AgentPlaygroundPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/agent-playground`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: /connect your agent/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
