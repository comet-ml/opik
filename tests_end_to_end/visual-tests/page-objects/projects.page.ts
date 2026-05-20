import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class ProjectsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(): Promise<void> {
    await this.page.goto(this.url('projects'));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async searchAndWait(projectName: string): Promise<void> {
    const searchBox = this.page.getByRole('textbox', { name: 'Search by name' });
    await searchBox.fill(projectName);
    // Wait for the debounced search request to fire and complete
    await this.page.waitForResponse(
      resp => resp.url().includes('/projects') && resp.url().includes(encodeURIComponent(projectName)) && resp.status() === 200,
      { timeout: 10000 },
    );
  }

  async waitForProject(projectName: string, retries = 3): Promise<void> {
    const cell = this.page.getByRole('cell', { name: projectName, exact: true });
    for (let i = 0; i < retries; i++) {
      try {
        await cell.waitFor({ state: 'visible', timeout: 8000 });
        return;
      } catch {
        if (i === retries - 1) throw new Error(`Project "${projectName}" not found after ${retries} attempts`);
        await this.page.reload();
        await this.page.waitForLoadState('load');
        await this.page.getByRole('textbox', { name: 'Search by name' }).fill(projectName);
      }
    }
  }

  async clickProjectAndGetId(projectName: string): Promise<string> {
    await this.page.locator('tbody tr').filter({ hasText: projectName }).first().click();
    await this.page.waitForURL(/\/projects\/[^/?#]+\//, { timeout: 20000 });
    const match = this.page.url().match(/\/projects\/([^/?#]+)/);
    return match ? match[1] : '';
  }
}
