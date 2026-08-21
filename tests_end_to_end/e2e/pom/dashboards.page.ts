import type { Page, Locator } from '@playwright/test';
import { test } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/** The dashboard date-range presets, labelled as the control renders them. */
export type DashboardDateRange =
  | 'Past 24 hours'
  | 'Past 3 days'
  | 'Past 7 days'
  | 'Past 30 days'
  | 'Past 60 days';

/**
 * The workspace Dashboards list and a single dashboard.
 *
 * Selectors are role- and text-based throughout: the dashboards UI carries no
 * `data-testid`, but every control it needs exposes an accessible name, and
 * `getByRole` is the estate's dominant selector anyway. The option rows inside
 * the metric/date popovers are plain divs with no role, so those are addressed
 * by exact text within the open popover rather than by `getByRole('option')`.
 */
export class DashboardsPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    return test.step('Open the Dashboards page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/dashboards`);
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for the Dashboards page to be ready', async () => {
      await this.page
        .getByRole('button', { name: /Create( your first)? dashboard/ })
        .first()
        .waitFor({ state: 'visible' });
    });
  }

  /**
   * Creates a dashboard and leaves the browser on it.
   *
   * The list offers "Create dashboard" in the header and "Create your first
   * dashboard" in the empty state; either opens the same dialog, so match both
   * and take the first. Inside the dialog the submit button is also called
   * "Create dashboard", hence `.last()` — the dialog is later in the DOM than
   * the header.
   */
  async createDashboard(name: string): Promise<void> {
    await test.step(`Create dashboard "${name}"`, async () => {
      await this.page
        .getByRole('button', { name: /Create( your first)? dashboard/ })
        .first()
        .click();
      const dialog = this.page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });
      await dialog.getByPlaceholder('Dashboard name').fill(name);
      await dialog.getByRole('button', { name: 'Create dashboard' }).click();
      await dialog.waitFor({ state: 'hidden' });
      await this.page.getByRole('heading', { name, exact: true }).waitFor({ state: 'visible' });
    });
  }

  /**
   * Adds a Time series widget scoped to the whole workspace.
   *
   * Ticking "All projects in the workspace" is what points the widget at
   * `POST /v1/private/workspaces/metrics/spans` — a widget scoped to one project
   * reads `/projects/{id}/metrics` instead — and the form responds by selecting
   * "Span token usage" as the metric, since that is the workspace-scoped
   * default. Asserted rather than assumed, so this stops being silently true.
   */
  async addWorkspaceSpanTokenUsageWidget(): Promise<void> {
    await test.step('Add a workspace-scoped Span token usage widget', async () => {
      await this.page.getByRole('button', { name: 'Add widget' }).first().click();
      const dialog = this.page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      await dialog.getByRole('checkbox', { name: 'All projects in the workspace' }).click();
      await dialog
        .getByRole('button', { name: 'Span token usage' })
        .first()
        .waitFor({ state: 'visible' });

      await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * The rendered widget carrying `title`.
   *
   * Anchored on `data-testid="dashboard-widget"` — the one selector in this page
   * object that is not role- or text-based, and the reason the attribute was
   * added alongside this spec. The dashboard nests several containers (section,
   * grid, card) that all contain the title text and all match a generic `div`
   * filter, and which one is "last" shifts as the page re-renders: a changed
   * date range was enough to move it, which read as a lost chart. A widget is a
   * repeated, unlabelled container, so there is no role or accessible name that
   * identifies one — exactly the case a test id is for.
   */
  widget(title: string): Locator {
    return this.page
      .getByTestId('dashboard-widget')
      .filter({ has: this.page.getByText(title, { exact: true }) });
  }

  /**
   * Series names present in the widget's legend.
   *
   * Returned in a fixed order rather than DOM order so a caller can compare to a
   * literal: the legend also carries `original_usage.*` variants, and asserting
   * on the raw order would couple the test to how many of those the backend
   * happens to emit.
   */
  async widgetSeriesNames(title: string): Promise<string[]> {
    return test.step(`Read the legend of the "${title}" widget`, async () => {
      const widget = this.widget(title);
      await widget.locator('svg').first().waitFor({ state: 'visible' });
      // The chart re-fetches on a range change; wait for the legend to come back
      // rather than reading a frame where it has been torn down.
      const legend = widget.getByText('total_tokens', { exact: true });
      await legend.waitFor({ state: 'visible' });
      const text = await widget.innerText();
      return ['total_tokens', 'prompt_tokens', 'completion_tokens'].filter((s) =>
        text.includes(s),
      );
    });
  }

  /** The date-range control's current label. */
  async selectedDateRange(): Promise<string> {
    return test.step('Read the selected dashboard date range', async () => {
      return (await this.page.getByRole('combobox').first().innerText()).trim();
    });
  }

  /** Picks a preset from the date-range control. */
  async selectDateRange(range: DashboardDateRange): Promise<void> {
    await test.step(`Set the dashboard date range to "${range}"`, async () => {
      await this.page.getByRole('combobox').first().click();
      const popover = this.page.locator('[data-radix-popper-content-wrapper]');
      await popover.waitFor({ state: 'visible' });
      await popover.getByText(range, { exact: true }).click();
      await popover.waitFor({ state: 'hidden' });
    });
  }
}
