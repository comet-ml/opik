import type { Page, Locator } from '@playwright/test';
import type { BackendClient, SpanRecord } from '../core/backend';

export type Span = SpanRecord;

export interface TraceMetadata {
  projectName: string;
}

export class TracePanelPage {
  constructor(
    private readonly page: Page,
    private readonly backendClient: BackendClient,
    private readonly traceId: string,
  ) {}

  async waitForFullyLoaded(): Promise<void> {
    await this.page.getByTestId('side-panel-close').waitFor({ state: 'visible' });
    await this.page.getByTestId('data-viewer-created-at').waitFor({ state: 'visible' });
  }

  async readSpans(): Promise<Span[]> {
    return this.backendClient.getTraceSpans(this.traceId);
  }

  async readMetadata(): Promise<TraceMetadata> {
    const trace = await this.backendClient.getTrace(this.traceId);
    const project = await this.backendClient.getProject(trace.projectId);
    return { projectName: project.name };
  }

  async close(): Promise<void> {
    await this.page.getByTestId('side-panel-close').click();
    await this.page.waitForURL((url) => !url.searchParams.get('trace'));
  }

  get closeButton(): Locator {
    return this.page.getByTestId('side-panel-close');
  }
}
