import { test, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { MetricDateRangeControl } from './metric-date-range.control';

/**
 * A project's Dashboards tab — the charts view at
 * `/{workspace}/projects/{projectId}/dashboards`.
 *
 * The page resolves the project before it mounts its content, so "ready" is
 * the header plus the date-range control: both only render once that lookup
 * has settled, which is precisely the gate the date-range default depends on.
 */
export class ProjectDashboardsPage {
  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    return test.step(`Open Dashboards for project ${projectId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/dashboards`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for the Dashboards header and controls', async () => {
      await this.heading.waitFor({ state: 'visible' });
      await this.dateRange.trigger.waitFor({ state: 'visible' });
    });
  }

  get heading(): Locator {
    return this.page.getByRole('heading', { name: 'Dashboards', exact: true });
  }

  /** The date-range select in the page's sticky toolbar. */
  get dateRange(): MetricDateRangeControl {
    return new MetricDateRangeControl(this.page);
  }
}
