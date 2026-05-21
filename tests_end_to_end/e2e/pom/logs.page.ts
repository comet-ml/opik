import type { Page, Locator } from '@playwright/test';
import type { BackendClient } from '../core/backend';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';

export class LogsPage {
  constructor(
    private readonly page: Page,
    private readonly backendClient: BackendClient,
  ) {}

  async goto(projectId: string): Promise<void> {
    const env = loadEnvConfig();
    await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/logs`);
  }

  async waitForReady(): Promise<void> {
    const table = this.page.getByRole('table');
    const emptyState = this.page.getByText('No traces yet');
    await Promise.race([
      table.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  async countTraces(): Promise<number> {
    const card = this.page.getByTestId('metrics-card-count');
    await card.waitFor({ state: 'visible' });
    const text = (await card.textContent()) ?? '';
    const digits = text.replace(/\D/g, '');
    if (!digits) {
      throw new Error(`LogsPage.countTraces: no digits in metrics-card-count text "${text}"`);
    }
    return Number(digits);
  }

  async openTraceById(traceId: string): Promise<TracePanelPage> {
    const url = new URL(this.page.url());
    url.searchParams.set('trace', traceId);
    await this.page.goto(url.toString());
    return new TracePanelPage(this.page, this.backendClient, traceId);
  }

  async openFirstTrace(): Promise<TracePanelPage> {
    const row = this.traceRows.first();
    await row.waitFor({ state: 'visible' });
    const traceId = await row.getAttribute('data-row-id');
    if (!traceId) {
      throw new Error('LogsPage.openFirstTrace: first row has no data-row-id attribute');
    }
    await row.click();
    return new TracePanelPage(this.page, this.backendClient, traceId);
  }

  async readTraceIdsInOrder(): Promise<string[]> {
    const rows = await this.traceRows.all();
    const ids: string[] = [];
    for (const row of rows) {
      const id = await row.getAttribute('data-row-id');
      if (id) ids.push(id);
    }
    return ids;
  }

  get traceRows(): Locator {
    return this.page.locator('tr[data-row-id]');
  }
}
