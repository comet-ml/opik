import type { Page, Locator } from '@playwright/test';
import { expect, test } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The built-in view every project falls back to, as `PROJECT_TEMPLATE_LIST[0]`
 * declares it. The id is what the page writes into the `dashboardId` query
 * param; the name is what the selector renders.
 */
export const DEFAULT_PROJECT_VIEW_ID = 'template:project-overview';
export const DEFAULT_PROJECT_VIEW_NAME = 'Project overview';

/**
 * A project's Dashboards tab — `/{workspace}/projects/{projectId}/dashboards`.
 *
 * Distinct from `DashboardsPage`, which is the workspace-level Dashboards list.
 * The two read different collections: this page lists *insights views*
 * (`/v1/private/insights-views`, scoped to one project since OPIK-8322), the
 * other lists workspace dashboards (`/v1/private/dashboards`).
 *
 * Selectors are role- and text-based, the same choice `DashboardsPage`
 * documents: nothing in this header carries a `data-testid`. The view selector
 * is addressed by the view it currently displays, because its accessible name
 * *is* that view's name — there are three popover-trigger buttons on the page
 * (project selector, project breadcrumb, view selector) and no attribute
 * separates them, so a positional locator would silently follow whichever the
 * layout put third. Every lookup asserts it resolved to exactly one element
 * rather than taking `.first()`, so an ambiguous name fails loudly.
 */
export class ProjectDashboardsPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  /**
   * Opens the project's Dashboards tab, optionally deep-linked to a view.
   *
   * `dashboardId` is the shape a shared link carries. It is passed through
   * verbatim — including ids the project does not own, which is exactly what
   * the fallback has to answer for.
   */
  async goto(opts: { dashboardId?: string } = {}): Promise<void> {
    return test.step(
      `Open the Dashboards tab of project ${this.projectId}${
        opts.dashboardId ? ` at view ${opts.dashboardId}` : ''
      }`,
      async () => {
        const env = loadEnvConfig();
        const query = opts.dashboardId
          ? `?dashboardId=${encodeURIComponent(opts.dashboardId)}`
          : '';
        await this.page.goto(
          `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/dashboards${query}`,
        );
      },
    );
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for the project Dashboards tab to be ready', async () => {
      await this.page.getByRole('heading', { name: 'Dashboards' }).waitFor({ state: 'visible' });
      // Rendered alongside the view selector in the same header row, and its
      // name never changes — unlike the selector's, which is the thing under
      // test.
      await this.page.getByRole('button', { name: 'Share' }).waitFor({ state: 'visible' });
    });
  }

  /**
   * The view selector trigger, addressed by the view name it is displaying.
   *
   * A caller asserts the selection by asserting this locator resolves: the
   * trigger is the only element carrying the selected view's name while the
   * popover is closed.
   */
  viewSelector(viewName: string): Locator {
    return this.page.getByRole('button', { name: viewName, exact: true });
  }

  /** Waits until the selector settles on `viewName`, and pins it to one element. */
  async expectSelectedView(viewName: string): Promise<void> {
    await test.step(`The view selector shows "${viewName}"`, async () => {
      const trigger = this.viewSelector(viewName);
      await expect(trigger, `the selected view is "${viewName}"`).toBeVisible();
      await expect(trigger, `"${viewName}" names exactly one control`).toHaveCount(1);
    });
  }

  /**
   * Opens the view selector.
   *
   * Radix renders the popover content with `role="dialog"`, so `viewOption`
   * addresses it by role rather than through the
   * `[data-radix-popper-content-wrapper]` CSS hook the older `DashboardsPage`
   * methods use.
   */
  async openViewSelector(currentViewName: string): Promise<void> {
    await test.step('Open the view selector', async () => {
      await this.viewSelector(currentViewName).click();
      await this.viewSelectorPopover.waitFor({ state: 'visible' });
    });
  }

  private get viewSelectorPopover(): Locator {
    return this.page.getByRole('dialog');
  }

  /**
   * A view's row inside the open selector, addressed by its exact label.
   *
   * Exact rather than substring: the seeded names in one run share a prefix, so
   * a substring match would let `…-view-a` satisfy an assertion about
   * `…-view-a-copy`.
   */
  viewOption(viewName: string): Locator {
    return this.viewSelectorPopover.getByText(viewName, { exact: true });
  }

  /** The `dashboardId` the URL currently carries, decoded. */
  selectedDashboardId(): string | null {
    return new URL(this.page.url()).searchParams.get('dashboardId');
  }
}
