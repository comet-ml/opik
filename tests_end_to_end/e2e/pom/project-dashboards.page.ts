import type { Page, Locator } from '@playwright/test';
import { expect, test } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/** The built-in project view every project starts on, as the picker labels it. */
export const DEFAULT_PROJECT_VIEW_NAME = 'Project overview';
/** …and the `dashboardId` it puts in the URL (`createTemplateId(PROJECT_OVERVIEW)`). */
export const DEFAULT_PROJECT_VIEW_ID = 'template:project-overview';
/** A section title only the built-in template carries, so its presence names it. */
export const DEFAULT_PROJECT_VIEW_SECTION = 'At a glance';

/**
 * A project's Dashboards page — `/$workspaceName/projects/$projectId/dashboards`.
 *
 * A different page from `DashboardsPage`, which models the workspace-level list
 * at `/$workspaceName/dashboards`. They read different collections (this one
 * `insights-views`, that one `dashboards`) and share no controls, which is the
 * whole subject of the scoping spec: a dashboard belongs to exactly one of them.
 *
 * Selectors are role- and text-based. Nothing here carries a `data-testid`, and
 * one could not be used anyway — this page object drives a deployed release
 * build, so a test hook added alongside it would not exist in the app under
 * test. The view picker is a Radix popover whose trigger renders the selected
 * view's name, so it is addressed by that name: the caller passes the name it
 * expects to be showing, which makes every lookup an assertion about the
 * selection as well as a handle on the control. The option rows inside the
 * popover are plain clickable divs with no role at all — the same shape
 * `DashboardsPage` documents for its metric and date popovers — so they are
 * matched by exact text within the open popover.
 */
export class ProjectDashboardsPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  /**
   * Opens the page, optionally deep-linking a `dashboardId` the way a shared
   * link does.
   *
   * The id is passed through verbatim, including one belonging to another
   * project: resolving a foreign id is a case this page has to handle, and a POM
   * that validated it away could not drive it.
   *
   * Waits for the project's own view list to come back SUCCESSFULLY, not just
   * for the navigation. `ProjectDashboardViewSelector` builds its options as
   * `[...TEMPLATE_OPTIONS, ...dashboards]`, where the templates are a module
   * constant and only `dashboards` comes from `useInsightsViewsList` — so every
   * built-in option is on screen a full request before any custom view is, and
   * an absence assertion made in that gap passes against a list that simply had
   * not arrived. Armed BEFORE `goto` because the selector mounts with the page
   * and issues the read immediately; a waiter created afterwards races it.
   */
  async goto(opts: { dashboardId?: string } = {}): Promise<void> {
    return test.step(
      `Open Dashboards for project ${this.projectId}${opts.dashboardId ? ` at dashboardId=${opts.dashboardId}` : ''}`,
      async () => {
        const env = loadEnvConfig();
        const query = opts.dashboardId
          ? `?${new URLSearchParams({ dashboardId: opts.dashboardId })}`
          : '';
        // Matched on the URL and asserted on the status afterwards, rather than
        // narrowed to a 2xx in the predicate: a refused read then fails saying
        // what actually came back, instead of as an opaque "no response
        // matched" timeout thirty seconds later.
        const viewsListed = this.page.waitForResponse(
          (res) =>
            res.url().includes('/v1/private/insights-views') &&
            res.url().includes(`project_id=${this.projectId}`),
        );
        await this.page.goto(
          `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/dashboards${query}`,
        );
        const listed = await viewsListed;
        // The list has to have SUCCEEDED, not merely answered. The consumer this
        // wait exists for is an absence assertion — "another project's view is
        // not offered here" — and the selector renders a failed read and a
        // genuinely empty one identically, since only the custom views come from
        // the request and the built-in options do not. So a 500 or a 403 would
        // satisfy `toHaveCount(0)` exactly as a correct backend does, which is
        // the one outcome an absence assertion must never be allowed to have.
        expect(
          listed.ok(),
          `the project's insights-views list must load before the picker is read — got ${listed.status()} from ${listed.url()}`,
        ).toBe(true);
      },
    );
  }

  /**
   * Waits for the page to have resolved which dashboard it is showing.
   *
   * Both halves matter. The heading proves the route mounted rather than
   * bouncing to a no-access guard. The `dashboardId` param is the page's own
   * first action on mount — it writes the default template's id when there is
   * nothing to restore — so waiting for it is what stops a caller from reading
   * the picker, or the URL, one render too early.
   */
  async waitForReady(): Promise<void> {
    return test.step('Wait for the project Dashboards page to resolve a view', async () => {
      await this.page
        .getByRole('heading', { name: 'Dashboards', exact: true })
        .waitFor({ state: 'visible' });
      await this.page.waitForURL((url) => (url.searchParams.get('dashboardId') ?? '') !== '');
    });
  }

  /** The `dashboardId` the URL currently carries, or null. */
  currentDashboardId(): string | null {
    return new URL(this.page.url()).searchParams.get('dashboardId');
  }

  /**
   * The view picker's trigger, addressed by the view name it is displaying.
   *
   * Deliberately not a bare "the picker" locator: the trigger's only stable,
   * non-structural handle IS its label, so a caller must say which view it
   * expects to be selected. That makes `expect(...).toBeVisible()` on this
   * locator a real assertion rather than a wait.
   */
  viewPickerShowing(viewName: string): Locator {
    return this.page.getByRole('button', { name: viewName, exact: true });
  }

  /**
   * The open picker popover.
   *
   * Filtered to the popper that holds the search box, not any
   * `[data-radix-popper-content-wrapper]` — the locator `DashboardsPage` uses
   * for its metric and date popovers, which have no such neighbour. This
   * trigger is wrapped in a `TooltipWrapper`, and a Radix tooltip renders into a
   * popper wrapper of exactly the same shape. A bare wrapper lookup therefore
   * matches the tooltip that appears the moment the pointer is over the trigger,
   * so "wait for the popover to close" never resolves: the picker closes, the
   * pointer is still on the control, and the tooltip stays up in its place.
   */
  get viewPickerPopover(): Locator {
    return this.page
      .locator('[data-radix-popper-content-wrapper]')
      .filter({ has: this.page.getByPlaceholder('Search') });
  }

  /**
   * Opens the picker from a known current selection.
   *
   * The built-in check here asserts the popover rendered its options at all —
   * and NOTHING about whether the custom views arrived. `Project overview` comes
   * from the module-level `TEMPLATE_OPTIONS`, so it is on screen whether or not
   * `useInsightsViewsList` has resolved. What makes an absence assertion
   * meaningful is `goto()` having awaited that request; this is a cheap guard on
   * the popover itself, not the readiness gate.
   */
  async openViewPicker(currentViewName: string): Promise<Locator> {
    return test.step(`Open the view picker (showing "${currentViewName}")`, async () => {
      await this.viewPickerShowing(currentViewName).click();
      const popover = this.viewPickerPopover;
      await popover.waitFor({ state: 'visible' });
      await expect(
        this.viewOption(DEFAULT_PROJECT_VIEW_NAME),
        'the picker lists the built-in view, so it really loaded its options',
      ).toHaveCount(1);
      return popover;
    });
  }

  /**
   * Asserts the picker has settled on `viewName`, and that the name is
   * unambiguous.
   *
   * Count first, then visibility. `toBeVisible()` on a name matching two
   * controls raises a strict-mode violation instead of this assertion's message,
   * so the ambiguity would surface as a Playwright internal rather than as "the
   * name is ambiguous" — and a second, merely transient match would fail here
   * outright rather than being quietly waited out.
   */
  async expectSelectedView(viewName: string): Promise<void> {
    await test.step(`The view picker shows "${viewName}"`, async () => {
      const trigger = this.viewPickerShowing(viewName);
      await expect(trigger, `"${viewName}" names exactly one control`).toHaveCount(1);
      await expect(trigger, `the selected view is "${viewName}"`).toBeVisible();
    });
  }

  /**
   * One option row in the open picker, by exact label.
   *
   * Exact, and scoped to the popover: a substring match would let a view named
   * `…-scoped-view` be satisfied by `…-scoped-view-2`, and an unscoped one would
   * match the trigger behind the popover as well as the row inside it. Returned
   * as a locator rather than asserted here so a caller can assert either
   * presence (`toHaveCount(1)`) or absence (`toHaveCount(0)`).
   */
  viewOption(label: string): Locator {
    return this.viewPickerPopover.getByText(label, { exact: true });
  }

  /** Dismisses the picker without changing the selection. */
  async dismissViewPicker(): Promise<void> {
    return test.step('Dismiss the view picker', async () => {
      await this.page.keyboard.press('Escape');
      await this.viewPickerPopover.waitFor({ state: 'hidden' });
    });
  }

  /**
   * Selects a view by label and waits for the page to adopt it.
   *
   * Waits on the URL rather than on the trigger's text: `dashboardId` is the
   * state the selection actually sets, and the trigger re-renders from it only
   * once the lookup resolves — so waiting on the trigger would be waiting on two
   * things and blaming the wrong one.
   */
  async selectView(label: string, expectedDashboardId: string): Promise<void> {
    return test.step(`Select the "${label}" view`, async () => {
      const option = this.viewOption(label);
      await expect(option, `exactly one "${label}" row in the picker`).toHaveCount(1);
      await option.click();
      await this.viewPickerPopover.waitFor({ state: 'hidden' });
      await this.page.waitForURL(
        (url) => url.searchParams.get('dashboardId') === expectedDashboardId,
      );
    });
  }

  /**
   * A section heading on the rendered dashboard.
   *
   * This is how the page says *which* dashboard is on screen: a custom view
   * renders its own section titles and the built-in template renders "At a
   * glance", so the two can never be mistaken for each other.
   *
   * Matched by the section header's ACCESSIBLE NAME rather than by its text,
   * because a section title is an inline-editable field: it renders the title
   * twice, once in the visible span and once in an `aria-hidden` mirror the input
   * sizes itself against. `getByText` resolves to both and fails strict mode, and
   * filtering the heading on its text does not help either — the header's own
   * inner text carries the title twice over for the same reason. The accessible
   * name is computed with `aria-hidden` subtrees excluded, so it carries the
   * title exactly once (followed by the header's "Add widget" action, which is
   * why this is a substring match and not an exact one).
   */
  sectionTitle(title: string): Locator {
    return this.page.getByRole('heading', { level: 3, name: title });
  }

  /**
   * Anything on the page mentioning `text`, for asserting a foreign dashboard
   * left no trace.
   *
   * Deliberately page-wide and deliberately a substring: the claim being checked
   * is that none of another project's dashboard leaked here — not into the
   * picker trigger, a section title, or a widget — so anything narrower or more
   * exact would be a weaker assertion than the one intended.
   */
  anyMentionOf(text: string): Locator {
    return this.page.getByText(text);
  }
}
