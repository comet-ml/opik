import { test, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TracePanelPage } from './trace-panel.page';

export class AnnotationQueuePage {
  constructor(private readonly page: Page) {}

  async goto(projectId: string, queueId: string): Promise<void> {
    return test.step(`Open annotation queue ${queueId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/annotation-queues/${queueId}`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for annotation queue page ready', async () => {
      await this.page.getByRole('tab', { name: 'Queue items' }).waitFor({ state: 'visible' });
    });
  }

  /**
   * Open a queue item's trace panel by navigating directly with a `trace` query
   * param — the same pattern LogsPage uses. Avoids depending on table row
   * selectors for a table whose row set changes as items are scored.
   */
  async openItem(projectId: string, queueId: string, traceId: string): Promise<TracePanelPage> {
    return test.step(`Open queue item ${traceId}`, async () => {
      const env = loadEnvConfig();
      const url = `${env.baseUrl}/${env.workspace}/projects/${projectId}/annotation-queues/${queueId}?trace=${traceId}`;
      await this.page.goto(url);
      const panel = new TracePanelPage(this.page, traceId);
      await panel.waitForFullyLoaded();
      return panel;
    });
  }
}
