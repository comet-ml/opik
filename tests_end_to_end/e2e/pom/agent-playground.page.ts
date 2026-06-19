import type { Page, Locator } from '@playwright/test';
import { test } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export class AgentPlaygroundPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('open the Agent Playground page', async () => {
      const env = loadEnvConfig();
      const url = `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/agent-playground`;
      await this.page.goto(url, { waitUntil: 'networkidle' });
      await this.page.waitForURL(new RegExp('/agent-playground'));
    });
  }

  async waitForHeading(): Promise<void> {
    return test.step('wait for the Agent playground heading', async () => {
      await this.page.getByRole('heading', { name: 'Agent playground', level: 1 }).waitFor();
    });
  }

  connectionBadge(): Locator {
    return this.page.getByText('Connected', { exact: true });
  }

  async isConnected(): Promise<boolean> {
    return (await this.connectionBadge().count()) > 0;
  }

  testInputPanel(): Locator {
    return this.page.getByText('Test input');
  }

  async fillInput(fieldName: string, value: string): Promise<void> {
    return test.step(`fill input "${fieldName}"`, async () => {
      await this.page.getByPlaceholder(`Enter ${fieldName}...`).fill(value);
    });
  }

  async clickRun(): Promise<void> {
    return test.step('click Run', async () => {
      await this.page.getByRole('button', { name: /^Run/ }).first().click();
    });
  }

  runningIndicator(): Locator {
    return this.page.getByText('Running your agent...');
  }

  resultPanel(): Locator {
    return this.page.locator('span').filter({ hasText: 'Result' });
  }

  resultText(query: string): Locator {
    return this.page.getByText(`Answer for: ${query}`);
  }

  viewTraceButton(): Locator {
    return this.page.getByRole('button', { name: 'View trace' });
  }
}
