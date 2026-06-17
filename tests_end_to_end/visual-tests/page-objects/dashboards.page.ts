import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class DashboardsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/dashboards`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByText('At a glance').waitFor({ state: 'visible', timeout: 20000 });
  }
}
