import { Page, expect } from '@playwright/test';
import { BasePage } from './base.page';

export class ProjectsPage extends BasePage {
  constructor(page: Page) {
    super(page, 'projects');
  }

  async clickProject(projectName: string): Promise<void> {
    await this.page.getByRole('link', { name: projectName }).click();
  }

  async searchProject(projectName: string): Promise<void> {
    await this.page.getByTestId('search-input').click();
    await this.page.getByTestId('search-input').fill(projectName);
  }

  async checkProjectExists(projectName: string): Promise<void> {
    await expect(
      this.page.getByRole('cell', { name: projectName, exact: true })
    ).toBeVisible({ timeout: 3000 });
  }

  async checkProjectNotExists(projectName: string): Promise<void> {
    await expect(
      this.page.getByRole('cell', { name: projectName, exact: true })
    ).not.toBeVisible();
  }

  async checkProjectExistsWithRetry(
    projectName: string,
    timeout: number = 5000
  ): Promise<void> {
    await expect(
      this.page.getByRole('cell', { name: projectName, exact: true })
    ).toBeVisible({ timeout });
  }

  async createNewProject(projectName: string): Promise<void> {
    await this.page.getByRole('button', { name: 'Create new project' }).first().click();

    const projectNameInput = this.page.getByPlaceholder('Project name');
    await projectNameInput.click();
    await projectNameInput.fill(projectName);

    await this.page.getByRole('button', { name: 'Create project' }).click();
  }

  async deleteProjectByName(projectName: string): Promise<void> {
    await this.searchProject(projectName);

    const row = this.page
      .getByRole('row')
      .filter({ hasText: projectName })
      .filter({ has: this.page.getByRole('cell', { name: projectName, exact: true }) });

    await row.getByRole('button').click();
    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    await this.page.getByRole('button', { name: 'Delete project' }).click();

    await this.checkProjectNotExists(projectName);
  }
}
