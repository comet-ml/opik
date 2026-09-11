import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class AnnotationQueuesPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/annotation-queues`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: /no annotation queues yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
