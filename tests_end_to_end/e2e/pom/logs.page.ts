import type { Page, Locator } from '@playwright/test';
import type { BackendClient } from '../core/backend';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';

export class LogsPage {
  constructor(
    private readonly page: Page,
    private readonly backendClient: BackendClient,
  ) {}

  private projectId: string | null = null;

  async goto(projectId: string): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/logs`);
  }

  async waitForReady(): Promise<void> {
    const realRow = this.page.locator('tr[data-row-id]').first();
    const emptyState = this.page.getByText('No traces yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
    await this.page.waitForFunction(() => {
      const txt = document.body.innerText;
      return /Traces\s+\d+/i.test(txt);
    });
  }

  async countTraces(): Promise<number> {
    const card = this.page.getByTestId('metrics-card-count');
    if (await card.isVisible().catch(() => false)) {
      const text = (await card.textContent()) ?? '';
      const digits = text.replace(/\D/g, '');
      if (digits) return Number(digits);
    }
    const handle = await this.page.waitForFunction(() => {
      const txt = document.body.innerText;
      const m = txt.match(/Traces\s+(\d+)/i);
      return m ? Number(m[1]) : null;
    });
    return (await handle.jsonValue()) as number;
  }

  async openTraceById(traceId: string): Promise<TracePanelPage> {
    if (!this.projectId) {
      throw new Error('LogsPage.openTraceById: call goto(projectId) first');
    }
    const env = loadEnvConfig();
    const url = `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/logs?trace=${traceId}`;
    await this.page.goto(url);
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
    await this.traceRows.first().waitFor({ state: 'visible' });
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
