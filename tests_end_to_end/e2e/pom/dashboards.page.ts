import type { Page, Locator } from '@playwright/test';
import { expect, test } from '@playwright/test';
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
   *
   * Returns the new dashboard's id, read off the route the app lands on — the
   * only place it surfaces. A caller needs it to register the dashboard for
   * teardown: nothing else sweeps dashboards.
   */
  async createDashboard(name: string): Promise<string> {
    return test.step(`Create dashboard "${name}"`, async () => {
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

      const match = /\/dashboards\/([0-9a-fA-F-]{36})/.exec(this.page.url());
      if (!match) {
        throw new Error(
          `DashboardsPage.createDashboard: no dashboard id in ${this.page.url()} after creating "${name}"`,
        );
      }
      return match[1];
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
   * Adds a Time series widget scoped to exactly one project, plotting the
   * `total_tokens` span-usage series.
   *
   * The single-project scope is the whole point: it is what makes the widget
   * read `POST /v1/private/projects/{id}/metrics`. Ticking "All projects in the
   * workspace" instead would send it to the workspace endpoint, which is a
   * different query with a different project predicate.
   *
   * Two things are asserted rather than assumed as the form is filled — that
   * the project selector really holds the chosen project, and that the metric
   * selector really holds "Span token usage". Both are re-derived by the form
   * on every change (picking a second project rewrites the metric), so a silent
   * reset would otherwise leave the widget reading something else entirely and
   * the caller none the wiser.
   *
   * The project dropdown's search filters options client-side over the first
   * page the workspace returns, so this types the full project name: on a
   * shared workspace holding thousands of projects, scrolling for the row is
   * neither fast nor deterministic.
   */
  async addProjectSpanTokenUsageWidget(projectName: string): Promise<void> {
    await test.step(`Add a Span token usage widget scoped to "${projectName}"`, async () => {
      await this.page.getByRole('button', { name: 'Add widget' }).first().click();
      const dialog = this.page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });

      await dialog.getByRole('button', { name: 'Select projects' }).click();
      const projectPopover = this.page.locator('[data-radix-popper-content-wrapper]');
      await projectPopover.waitFor({ state: 'visible' });
      await projectPopover.getByPlaceholder('Search').fill(projectName);

      const option = projectPopover.getByRole('option', { name: projectName, exact: true });
      // A workspace can hold more projects than the dropdown's first page, and
      // the search filters that page client-side. Newest-first ordering means a
      // just-created project is normally on it, but "normally" is not a
      // guarantee on a shared workspace — so page the rest in when the empty
      // state says the name was not among them.
      const loadMore = projectPopover.getByRole('button', { name: 'Load more items' });
      await option.or(loadMore).first().waitFor({ state: 'visible' });
      if (await loadMore.isVisible()) {
        await loadMore.click();
      }
      // Exactly one: a project whose name merely contains this one would
      // otherwise be a candidate, and `.first()` would pick between them
      // silently.
      await expect(option).toHaveCount(1);
      await option.click();
      // Multiselect keeps the popover open; dismiss it before reaching the
      // metric control underneath.
      await this.page.keyboard.press('Escape');
      await projectPopover.waitFor({ state: 'hidden' });
      await expect(
        dialog.getByRole('button', { name: projectName, exact: true }),
        'the widget is scoped to the seeded project',
      ).toBeVisible();

      await this.selectWidgetMetric('Span token usage');

      await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * Picks a metric in the open widget dialog's "Metric type" control.
   *
   * The control is addressed by its *label* rather than by the metric it
   * currently shows: the widget preview alongside it renders a button carrying
   * the same generated title, so matching on the metric name would be a coin
   * toss between the selector and a title-edit affordance. The options inside
   * are plain divs carrying `role="option"`, which is why they are not
   * `getByRole('option')` on a listbox-backed combobox.
   */
  async selectWidgetMetric(metricLabel: string): Promise<void> {
    await test.step(`Select the "${metricLabel}" metric`, async () => {
      const control = this.page.getByRole('dialog').getByRole('button', { name: 'Metric type' });
      await control.click();
      const popover = this.page.locator('[data-radix-popper-content-wrapper]');
      await popover.waitFor({ state: 'visible' });
      await popover.getByRole('option', { name: metricLabel, exact: true }).click();
      await popover.waitFor({ state: 'hidden' });
      // The button's accessible name comes from the field label and does not
      // change, so the selection is read from its text.
      await expect(control, `the metric control holds "${metricLabel}"`).toContainText(metricLabel);
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

  /** A row in the dashboards list, addressed by its name cell. */
  row(name: string): Locator {
    return this.page
      .getByRole('row')
      .filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  /**
   * Applies a single list filter through the Filters popover.
   *
   * The popover's two selects expose `role="combobox"` but carry no accessible
   * name, so they are taken in DOM order — column first, operator second. The
   * value box is the only textbox inside the popover. Nothing here has a
   * `data-testid`; that matches the rest of this page object.
   */
  async applyListFilter(column: string, operator: string, value: string): Promise<void> {
    await test.step(`Filter the list by ${column} ${operator} "${value}"`, async () => {
      await this.page.getByRole('button', { name: /^Filters/ }).click();
      const popover = this.page.locator('[data-radix-popper-content-wrapper]');
      await popover.waitFor({ state: 'visible' });

      await popover.getByRole('combobox').nth(0).click();
      await this.page.getByRole('option', { name: column, exact: true }).click();

      // The operator select defaults to the first operator for the chosen
      // column; only touch it when the test wants a different one.
      const operatorSelect = popover.getByRole('combobox').nth(1);
      if ((await operatorSelect.innerText()).trim() !== operator) {
        await operatorSelect.click();
        await this.page.getByRole('option', { name: operator, exact: true }).click();
      }

      // `fill()` sets the value in one shot and the filter row never commits —
      // the list request goes out without a `filters` param. Typing emits the
      // per-key events the row listens for.
      const valueBox = popover.getByRole('textbox');
      await valueBox.click();
      await valueBox.pressSequentially(value);

      // Close the popover so it stops covering the table, and wait for the
      // filtered request rather than a generic idle — the value box is
      // debounced, so `networkidle` can resolve against the *unfiltered* list.
      // Close by clicking outside, not with Escape: Escape dismisses the popover
      // *and* discards the pending filter row, leaving the list unfiltered.
      //
      // Wait on the filtered request rather than a generic idle — the value box
      // is debounced, so `networkidle` can resolve against the *unfiltered*
      // list and the caller then asserts on stale rows. Any status is accepted
      // here so that a backend rejecting the filter surfaces as the caller's
      // row assertion (which says what the user would see) rather than as an
      // opaque wait timeout.
      const filtered = this.page.waitForResponse((res) =>
        res.url().includes('/v1/private/dashboards') && res.url().includes('filters='),
      );
      await this.page.getByRole('heading', { name: 'Dashboards' }).click();
      await popover.waitFor({ state: 'hidden' });
      await filtered;
    });
  }
}
