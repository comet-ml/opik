import type { Page, Locator } from '@playwright/test';
import { expect, test } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { openWidgetConfigDialog } from './add-widget-control';

/** The built-in project view every project starts on, as the picker labels it. */
export const DEFAULT_PROJECT_VIEW_NAME = 'Project overview';
/** …and the `dashboardId` it puts in the URL (`createTemplateId(PROJECT_OVERVIEW)`). */
export const DEFAULT_PROJECT_VIEW_ID = 'template:project-overview';
/** A section title only the built-in template carries, so its presence names it. */
export const DEFAULT_PROJECT_VIEW_SECTION = 'At a glance';

/**
 * The built-in template's three section titles, in the order it declares them
 * (`PROJECT_OVERVIEW_TEMPLATE` in `lib/dashboard/templates.ts`).
 *
 * A copy of the template has to reproduce them in this order, so the list is the
 * expectation a copy is compared against — not a lookup table for the page.
 */
export const DEFAULT_PROJECT_VIEW_SECTIONS = [
  'At a glance',
  'Volume, latency & cost',
  'Quality & conversations',
] as const;

/** How many widgets each of those sections carries in the template: `[5, 3, 4]`. */
export const DEFAULT_PROJECT_VIEW_WIDGET_COUNTS = [5, 3, 4] as const;

/**
 * The titles of the five "At a glance" stats cards, in template order.
 *
 * These are the numbers a user reads first, which is why a copy is compared
 * against the built-in view card by card: `buildCopyConfig` runs the whole
 * template through `regenerateAllIds`, and a rewrite that came out subtly wrong
 * still renders a card with a number in it.
 */
export const PROJECT_OVERVIEW_KPI_CARDS = [
  'Traces',
  'Errors',
  'Latency (p50)',
  'Latency (p99)',
  'Cost',
] as const;

/**
 * A `dashboardId` that is a real view rather than a built-in template.
 *
 * The template ids are `template:<name>`, so "the URL holds a UUID" is how a
 * test knows the page has switched to the copy that was just created.
 */
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** The name `useCustomizeTemplate` gives the first copy of the built-in view. */
export const PROJECT_OVERVIEW_COPY_NAME = 'Project overview (custom)';
/** …and the second, once the first name is taken. */
export const PROJECT_OVERVIEW_SECOND_COPY_NAME = 'Project overview (custom 2)';

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
   * The "Add widget" control in one section's header, addressed through that
   * section's heading.
   *
   * Scoped to the section rather than taken positionally: the whole subject of
   * the copy flow is that the widget lands in the section it was asked for, and
   * `getByRole('button', { name: 'Add widget' }).nth(2)` would couple the test
   * to the order the sections happen to render in — which is the thing under
   * test. The control sits inside the accordion trigger, which is inside the
   * section's `h3`, so the heading is a real parent of it.
   *
   * On the built-in template the control is present because
   * `useCustomizeTemplate` sets `onAddWidgetToTemplate`: the view is read-only,
   * but adding a widget to it is allowed and makes a copy.
   */
  sectionAddWidgetControl(sectionTitle: string): Locator {
    return this.sectionTitle(sectionTitle).getByRole('button', { name: 'Add widget' });
  }

  /**
   * Adds a widget from one section's header and gives it `widgetTitle`.
   *
   * The title is set rather than left to the generated one because it is how the
   * stored config is read afterwards: "the new widget is in section 3 and
   * nowhere else" needs the widget to be distinguishable from the twelve the
   * template already carries, and every generated title is drawn from the same
   * small vocabulary those use.
   *
   * The metric is left at the dialog's default, which is deliberate — this spec
   * is about where the widget lands and what the save creates, not about
   * configuring a chart (`DashboardsPage.selectWidgetMetric` covers that). One
   * fewer control touched is one fewer way for the seed to end up in a shape the
   * assertions were not written for.
   */
  async addWidgetToSection(sectionTitle: string, widgetTitle: string): Promise<void> {
    await test.step(`Add a widget titled "${widgetTitle}" from the "${sectionTitle}" section`, async () => {
      const dialog = await openWidgetConfigDialog(
        this.page,
        this.sectionAddWidgetControl(sectionTitle),
      );
      await this.setPreviewWidgetTitle(dialog, widgetTitle);
      await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * Renames the widget in the open config dialog's preview.
   *
   * The title and subtitle are `InlineEditableText`, which renders a
   * `div[role="button"]` in its resting state and swaps in a text input once
   * clicked — so neither carries an accessible name of its own that survives the
   * edit, and the resting name is the generated title, which is what we are
   * about to replace. Every other control in the dialog is a real `<button>`,
   * so the tag is what separates the two inline fields from them; the subtitle
   * is excluded by its placeholder, and the result is asserted to be a single
   * element rather than taken with `.first()`.
   *
   * No `data-testid`: this page object drives a deployed release build, so a
   * test hook added alongside it would not exist in the app under test.
   */
  private async setPreviewWidgetTitle(dialog: Locator, widgetTitle: string): Promise<void> {
    await test.step(`Title the preview widget "${widgetTitle}"`, async () => {
      const titleField = dialog
        .locator('div[role="button"]')
        .filter({ hasNotText: 'Click to add a description' });
      await expect(
        titleField,
        'exactly one inline-editable field in the preview that is not the description',
      ).toHaveCount(1);
      await titleField.click();

      // The input only exists while the field is in edit mode, which is why it
      // is looked up after the click rather than with it.
      const input = dialog.locator('input[type="text"]');
      await expect(input, 'the title field swapped in its editor').toHaveCount(1);
      await input.fill(widgetTitle);
      await this.page.keyboard.press('Enter');

      // Committed, not merely typed: Enter is what `InlineEditableText` listens
      // for, and a save that did not land would leave the widget carrying its
      // generated title — which the config assertions would then read as the
      // widget having gone to the wrong section.
      await expect(
        dialog.getByText(widgetTitle, { exact: true }),
        'the preview header shows the new title',
      ).toBeVisible();
    });
  }

  /**
   * One of the "At a glance" stats cards, addressed by the title in its header.
   *
   * Exact text inside a `dashboard-widget`: the card titles are short words that
   * appear as substrings of their neighbours' ("Cost" in "Estimated cost",
   * "Traces" in "Trace volume"), so a substring filter would match several
   * widgets at once.
   */
  kpiCard(title: string): Locator {
    return this.page
      .getByTestId('dashboard-widget')
      .filter({ has: this.page.getByText(title, { exact: true }) });
  }

  /**
   * The number one stats card is showing.
   *
   * A card renders three lines — its title, the metric's label, and the value —
   * and nothing else identifies the value: it is an unlabelled `div` with no
   * role, inside a widget that carries no test id of its own beyond the shared
   * `dashboard-widget`. So the card's text is read as a whole and the third line
   * taken, which is also what makes the wait correct: while the metric is
   * loading the card renders skeletons and only the title, so a three-line shape
   * IS the settled state. Polled rather than waited on a spinner, because the
   * skeleton is a bare `div` too.
   *
   * Returns the value verbatim, formatting included ("6", "0.4s", "-"), because
   * the comparison this exists for is between two renders of the same metric —
   * parsing it into a number would quietly let "0s" and "0" agree.
   */
  async kpiCardValue(title: string): Promise<string> {
    return test.step(`Read the "${title}" stats card`, async () => {
      const card = this.kpiCard(title);
      await expect(card, `exactly one "${title}" stats card`).toHaveCount(1);
      const lines = async () =>
        (await card.innerText())
          .split('\n')
          .map((line) => line.trim())
          .filter((line) => line.length > 0);
      await expect
        .poll(async () => (await lines()).length, {
          message: `the "${title}" card must settle on a title, a metric label and a value`,
          timeout: 30_000,
        })
        .toBe(3);
      const settled = await lines();
      expect(settled, `the "${title}" card's three lines`).toHaveLength(3);
      return settled[2];
    });
  }

  /** Every "At a glance" card's value, keyed by card title. */
  async kpiCardValues(): Promise<Record<string, string>> {
    return test.step('Read the "At a glance" stats cards', async () => {
      const values: Record<string, string> = {};
      for (const title of PROJECT_OVERVIEW_KPI_CARDS) {
        values[title] = await this.kpiCardValue(title);
      }
      return values;
    });
  }

  /**
   * Waits for the page to switch itself to a freshly created view and returns
   * its id.
   *
   * A copy's id only ever surfaces in the URL — `onCopyCreated` writes it to the
   * `dashboardId` param — so this is the only way a test can learn it, and it
   * has to learn it to register the view for teardown. Matched on the id being a
   * UUID rather than on "not the template id": the page rewrites the param more
   * than once while it resolves, and the template id is not the only non-UUID
   * value it can hold on the way.
   */
  async waitForCopyDashboardId(): Promise<string> {
    return test.step('Wait for the page to switch to the created copy', async () => {
      await this.page.waitForURL(
        (url) => UUID_PATTERN.test(url.searchParams.get('dashboardId') ?? ''),
        { timeout: 30_000 },
      );
      const id = this.currentDashboardId();
      expect(id, 'the created copy must leave its id in the URL').not.toBeNull();
      return id as string;
    });
  }

  /**
   * The toast's "Rename" action.
   *
   * Addressed page-wide and by name: Radix renders the toast into a portal at the
   * end of the document, outside this page's tree, and the only other thing on
   * screen carrying this label is the visually-hidden announcer — which renders
   * the action's `altText` as text, not as a button. Deliberately not scoped to
   * the toast element either, because the claim one spec makes is about this
   * action still being clickable after the page has moved to a different view.
   */
  get copyToastRenameAction(): Locator {
    return this.page.getByRole('button', { name: 'Rename', exact: true });
  }

  /**
   * The rename dialog the toast's action opens — `ProjectDashboardViewDialog` in
   * `edit` mode.
   *
   * Filtered on its title rather than taken as "the dialog": the widget config
   * dialog is also a `role="dialog"`, and a spec that renames right after adding
   * a widget would otherwise be able to satisfy this against the dialog it had
   * just closed.
   */
  get editViewDialog(): Locator {
    return this.page
      .getByRole('dialog')
      .filter({ has: this.page.getByText('Edit view', { exact: true }) });
  }

  /**
   * The name box in that dialog, which arrives prefilled with the name of the
   * view the dialog is about.
   *
   * That prefill is the assertion the rename spec exists for — the toast can
   * outlive the view it refers to, so the dialog has to open on the copy rather
   * than on whatever view the user has since switched to.
   */
  get editViewNameInput(): Locator {
    return this.editViewDialog.getByPlaceholder('View name');
  }

  /** Renames the view the open "Edit view" dialog is about. */
  async submitRename(newName: string): Promise<void> {
    await test.step(`Rename the view to "${newName}"`, async () => {
      await this.editViewNameInput.fill(newName);
      await this.editViewDialog.getByRole('button', { name: 'Rename view' }).click();
      await this.editViewDialog.waitFor({ state: 'hidden' });
    });
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
