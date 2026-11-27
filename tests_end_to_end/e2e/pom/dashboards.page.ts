import type { Page, Locator } from '@playwright/test';
import { expect, test } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { openWidgetConfigDialog } from './add-widget-control';

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

  /**
   * Opens the list already narrowed to the dashboards whose name contains
   * `nameQuery`.
   *
   * Through the `search` query param, which `DashboardsPage` binds with
   * `useQueryParam` and passes to the list request as `search`, rather than
   * through the Filters popover: the param is a one-shot navigation with no
   * debounce and no popover to dismiss, and the narrowing is a PRECONDITION
   * here, not the subject. The workspace this runs against is shared and holds
   * other runs' dashboards, so an unfiltered list cannot promise the two
   * seeded rows are even on the first page.
   */
  async gotoSearchedByName(nameQuery: string): Promise<void> {
    return test.step(`Open the Dashboards page searched for "${nameQuery}"`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/dashboards?search=${encodeURIComponent(nameQuery)}`,
      );
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
      // `.first()` because the dialog's own submit button carries the same name;
      // only the header control exists before it opens. Whether that control is
      // a plain button or a dropdown trigger depends on whether Ollie is
      // available on the target, so the branch lives in one shared helper.
      const dialog = await openWidgetConfigDialog(
        this.page,
        this.page.getByRole('button', { name: 'Add widget' }).first(),
      );

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
   * the caller none the wiser. See `scopeWidgetToProject` and
   * `selectWidgetMetric` for how each is read back.
   */
  async addProjectSpanTokenUsageWidget(projectName: string): Promise<void> {
    await test.step(`Add a Span token usage widget scoped to "${projectName}"`, async () => {
      const dialog = await this.openWidgetDialog();
      await this.scopeWidgetToProject(dialog, projectName);
      await this.selectWidgetMetric('Span token usage');
      await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * Opens the widget config dialog from a section header's Add widget control.
   *
   * `.first()` because the dialog's own submit button carries the same name;
   * only the header control exists before it opens. Whether that control is a
   * plain button or a dropdown trigger depends on whether Ollie is available on
   * the target, so the branch lives in one shared helper.
   */
  async openWidgetDialog(): Promise<Locator> {
    return openWidgetConfigDialog(
      this.page,
      this.page.getByRole('button', { name: 'Add widget' }).first(),
    );
  }

  /**
   * Picks exactly one project in the open widget dialog's project selector.
   *
   * Extracted from `addProjectSpanTokenUsageWidget` rather than copied: every
   * comment below records a way this step failed under parallel workers, and two
   * copies of it is how the callers quietly stop agreeing about which of those
   * are still guarded.
   *
   * The dropdown's search filters options client-side over the first page the
   * workspace returns, so this types the FULL project name: on a shared
   * workspace holding thousands of projects, scrolling for the row is neither
   * fast nor deterministic.
   */
  async scopeWidgetToProject(dialog: Locator, projectName: string): Promise<void> {
    await test.step(`Scope the widget to "${projectName}"`, async () => {
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
      // Exactly one option in the list — not merely one *matching* option.
      //
      // Both halves earn their place. It rules out a project whose name merely
      // contains this one, which `.first()` would otherwise pick between
      // silently. And counting the whole list rather than the match is what
      // waits for the dropdown's client-side filter to settle: the search is
      // debounced, so for a beat after the text lands the list is still the
      // full unfiltered set. A click dispatched across that re-render toggles
      // nothing while still counting as a successful click, and the only
      // symptom is the trigger below still reading "Select projects" — which
      // is how this failed, reproducibly, under parallel workers.
      await expect(projectPopover.getByRole('option')).toHaveCount(1);
      await expect(option).toHaveCount(1);
      await option.click();
      // The option's own record of the toggle, and the earliest thing that can
      // be read back: the trigger only re-renders once the editor's state has
      // flowed back down, so asserting there first would blame the wrong step.
      await expect(option, 'the project option registered as selected').toHaveAttribute(
        'aria-selected',
        'true',
      );
      // Multiselect keeps the popover open; dismiss it before reaching the
      // metric control underneath.
      await this.page.keyboard.press('Escape');
      await projectPopover.waitFor({ state: 'hidden' });
      await expect(
        dialog.getByRole('button', { name: projectName, exact: true }),
        'the widget is scoped to the seeded project',
      ).toBeVisible();
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
   * The open widget dialog's "Group by" accordion trigger.
   *
   * `AccordionTrigger` renders a real button whose accessible name is the
   * section label, so this needs no test id. Its only caller today is
   * `selectWidgetGroupBy` below, which has to read the trigger's
   * `aria-expanded` before deciding whether to click it; it is exposed for
   * symmetry with `filtersSection`, which a spec does address directly.
   */
  groupBySection(): Locator {
    return this.page.getByRole('dialog').getByRole('button', { name: 'Group by', exact: true });
  }

  /** The open widget dialog's "Filters" accordion trigger — the control the info button follows in the tab order. */
  filtersSection(): Locator {
    return this.page.getByRole('dialog').getByRole('button', { name: /^Filters/ });
  }

  /**
   * Expands "Group by" and picks a field, then asserts the control holds it.
   *
   * The field control is a Radix `Select`, so its trigger carries
   * `role="combobox"` and its items `role="option"` — unlike the metric
   * selector above, which is a popover of divs. There is exactly one combobox
   * inside the expanded section (the metadata-key autocomplete appears only for
   * the Metadata field and is a textbox), so it needs no further anchoring.
   *
   * Idempotent on the accordion: the section is only clicked open when its
   * content is not already showing, because the trigger TOGGLES and a second
   * click would collapse what the caller just opened.
   */
  async selectWidgetGroupBy(fieldLabel: string): Promise<void> {
    await test.step(`Group the widget by "${fieldLabel}"`, async () => {
      const section = this.groupBySection();
      const control = this.page.getByRole('dialog').getByRole('combobox');
      if ((await section.getAttribute('aria-expanded')) !== 'true') {
        await section.click();
      }
      await expect(control, 'the Group by field control is showing').toHaveCount(1);
      await control.click();
      const options = this.page.getByRole('option', { name: fieldLabel, exact: true });
      await expect(options, `exactly one "${fieldLabel}" group-by option`).toHaveCount(1);
      await options.click();
      await expect(control, `the group-by control holds "${fieldLabel}"`).toContainText(fieldLabel);
    });
  }

  /**
   * Picks the Group-by aggregation mode.
   *
   * The two choices are `ToggleGroupItem`s carrying `aria-label="Total"` /
   * `aria-label="Time-based"`, which makes them radio-role buttons with those
   * accessible names.
   *
   * Clicked only when it is not already on, and asserted on either way. Two
   * reasons, both load-bearing: the group is DISABLED until a group-by field is
   * chosen, so a click before that is a silent no-op; and a Radix single-value
   * toggle group treats a click on the ACTIVE item as a deselect, which this
   * component's handler swallows (`if (value)`) — so clicking unconditionally
   * would be a no-op that looks like a successful set. `aggregateTotal`
   * defaults to false, i.e. Time-based, and the assertion is what makes this
   * spec notice if that default ever flips: Total collapses the read to
   * `interval: TOTAL`, one point per group, and every per-bucket expectation
   * below would be about a request the widget no longer makes.
   */
  async selectWidgetAggregationMode(mode: 'Total' | 'Time-based'): Promise<void> {
    await test.step(`Set the widget aggregation mode to "${mode}"`, async () => {
      const item = this.page.getByRole('dialog').getByRole('radio', { name: mode, exact: true });
      await expect(item, `exactly one "${mode}" aggregation control`).toHaveCount(1);
      if ((await item.getAttribute('data-state')) !== 'on') {
        await item.click();
      }
      await expect(item, `the "${mode}" aggregation mode is selected`).toHaveAttribute(
        'data-state',
        'on',
      );
    });
  }

  /**
   * The tag-cost overlap description rendered under the Group-by control.
   *
   * Matched on its exact text rather than through a test id, which this
   * component does not carry. The text is the assertion — it is what stops a
   * user reading an overlapping tag breakdown as a billing discrepancy — so
   * locating by it cannot pass against a different string, and a `data-testid`
   * would add nothing a reviewer could check.
   */
  tagCostDescription(text: string): Locator {
    return this.page.getByRole('dialog').getByText(text, { exact: true });
  }

  /**
   * The "Widget information" button, inside the dialog's preview header or on a
   * saved widget's own header.
   *
   * One accessible name for both, because opik#9001 made it the same focusable
   * `Button` component in both places — which is the thing worth asserting:
   * before that change the preview header's copy was an icon slot inside the
   * editable title, so activating it put the title into edit mode.
   */
  widgetInfoButton(scope: Locator): Locator {
    return scope.getByRole('button', { name: 'Widget information', exact: true });
  }

  /**
   * The input that appears when a widget title goes into edit mode.
   *
   * Addressed by the placeholder `InlineEditableText` puts on it — the widget's
   * own generated title — rather than by "a textbox in the dialog": the widget
   * editor holds several other inputs, and a bare textbox count would be
   * satisfied by any of them. This is the control that must NOT appear when the
   * info button is activated, so a locator that could match something else
   * would make the negative assertion worthless.
   */
  widgetTitleEditor(generatedTitle: string): Locator {
    return this.page.getByRole('dialog').getByPlaceholder(generatedTitle, { exact: true });
  }

  /**
   * The non-editing widget title in the dialog's preview header.
   *
   * `InlineEditableText` renders it as a `div role="button" tabIndex=0`, so it
   * is reachable by role with the title as its accessible name — and clicking
   * it is the positive control for the info button's negative assertion.
   */
  previewWidgetTitle(title: string): Locator {
    return this.page.getByRole('dialog').getByRole('button', { name: title, exact: true });
  }

  /**
   * Tabs forward from `from` until `target` holds focus, up to `maxTabs`.
   *
   * A bounded search rather than a fixed number of presses. The property worth
   * asserting is that the control is REACHABLE from the keyboard — opik#9001
   * turned the info icon from a bare `<Info/>` glyph into a focusable button —
   * and pinning the exact count would couple the test to how many controls the
   * editor happens to render between the two, which every later form change
   * would break without anything being wrong.
   */
  async tabUntilFocused(from: Locator, target: Locator, maxTabs = 15): Promise<number> {
    return test.step('Tab forward until the target control holds focus', async () => {
      await from.focus();
      await expect(from, 'the tab walk starts from the named control').toBeFocused();
      for (let presses = 1; presses <= maxTabs; presses++) {
        await this.page.keyboard.press('Tab');
        if (await target.evaluate((el) => el === document.activeElement).catch(() => false)) {
          return presses;
        }
      }
      throw new Error(
        `DashboardsPage.tabUntilFocused: the target never took focus within ${maxTabs} Tab presses`,
      );
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
   * The actions-menu trigger on a SAVED widget's header.
   *
   * Addressed by `aria-haspopup="menu"`, which Radix's `DropdownMenuTrigger`
   * puts on whatever it wraps. The button itself is icon-only — a bare
   * `MoreHorizontal` with no `aria-label` and no text — so it has no accessible
   * name to match on, and it is the only dropdown trigger inside a widget.
   * `online-evaluation.page.ts` reaches its row menu the same way.
   *
   * It would be better as a `data-testid` on the FE component, which is what
   * this estate's conventions ask for. This branch may only touch
   * `tests_end_to_end/`, so the attribute cannot be added here — worth adding
   * next time `DashboardWidgetActionsMenu.tsx` is open.
   *
   * The header reveals its actions on hover (`opacity-0
   * group-hover/widget:opacity-100`), which does not affect hit-testing:
   * `opacity: 0` still has a box and Playwright still considers it visible and
   * clickable, so no explicit hover is needed.
   */
  widgetActionsTrigger(title: string): Locator {
    return this.widget(title).locator('button[aria-haspopup="menu"]');
  }

  /**
   * Opens a saved widget's configuration dialog through its own Edit action,
   * and answers the dialog.
   *
   * This is the IN-PLACE edit path, which is a different thing from
   * `openWidgetDialog`: that one adds a widget, where the section's widget
   * array grows and `DashboardWidgetGrid`'s `React.memo` comparator rejects the
   * props on length alone. An edit leaves the length unchanged, so the
   * comparator has to actually compare the widget — which is the path
   * `areWidgetsEqual` -> `isLooseEqual` sits on.
   *
   * The dialog is identified by its own title ("Edit widget" rather than "Add
   * widget"), so a click that somehow landed on the section's Add control
   * fails here rather than quietly configuring a second widget.
   */
  async editWidget(title: string): Promise<Locator> {
    return test.step(`Edit the "${title}" widget in place`, async () => {
      const trigger = this.widgetActionsTrigger(title);
      await expect(trigger, `exactly one actions menu on the "${title}" widget`).toHaveCount(1);
      await trigger.click();
      const edit = this.page.getByRole('menuitem', { name: 'Edit widget' });
      await expect(edit, 'the menu offers exactly one Edit widget action').toHaveCount(1);
      await edit.click();
      const dialog = this.page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });
      await expect(
        dialog.getByRole('heading', { name: 'Edit widget', exact: true }),
        'the dialog opened in edit mode, not add mode',
      ).toBeVisible();
      return dialog;
    });
  }

  /**
   * Submits an open EDIT dialog.
   *
   * "Save changes" rather than "Add widget": `WidgetConfigDialog` labels its
   * submit from `isEditMode`, so the label is also the assertion that the
   * dialog really was editing an existing widget.
   */
  async saveWidgetChanges(dialog: Locator): Promise<void> {
    await test.step('Save the widget changes', async () => {
      await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * The filter rows inside the open widget dialog's Filters section.
   *
   * `FiltersAccordionSection` renders `FiltersContent`, the same component the
   * rule editor uses, so the row carries the shared
   * `data-testid="filter-column"` — see `online-evaluation.page.ts`'s
   * `filterRows`, which this mirrors deliberately rather than inventing a
   * second way to address the same markup.
   */
  widgetFilterRows(dialog: Locator): Locator {
    return dialog.locator('tr').filter({ has: this.page.locator('[data-testid="filter-column"]') });
  }

  /**
   * Expands the dialog's Filters accordion, if it is not already open.
   *
   * Idempotent, and it has to be: `AccordionTrigger` TOGGLES, so a second click
   * collapses what the caller just opened and every control below becomes
   * unreachable.
   */
  async expandWidgetFilters(dialog: Locator): Promise<void> {
    await test.step('Expand the widget Filters section', async () => {
      const section = dialog.getByRole('button', { name: /^Filters/ });
      await expect(section, 'exactly one Filters accordion trigger').toHaveCount(1);
      if ((await section.getAttribute('aria-expanded')) !== 'true') {
        await section.click();
      }
      await expect(section, 'the Filters section is expanded').toHaveAttribute(
        'aria-expanded',
        'true',
      );
    });
  }

  /**
   * Adds one filter row and sets its column and value.
   *
   * The operator is left at the column type's own default and ASSERTED rather
   * than selected: a string column defaults to `contains`
   * (`DEFAULT_OPERATORS[COLUMN_TYPE.string]`), and asserting it is what stops a
   * change of default silently turning a substring filter into an equality one
   * — which would select nothing and make every "the chart narrowed" assertion
   * pass for the wrong reason.
   *
   * Selecting the column resets the row's operator and value, so the column is
   * set first.
   */
  async addWidgetFilter(
    dialog: Locator,
    args: { column: string; operator: string; value: string },
  ): Promise<void> {
    await test.step(`Add a widget filter: ${args.column} ${args.operator} "${args.value}"`, async () => {
      await this.expandWidgetFilters(dialog);
      const before = await this.widgetFilterRows(dialog).count();
      await dialog.getByRole('button', { name: 'Add filter', exact: true }).click();
      await expect(
        this.widgetFilterRows(dialog),
        'the filter row count after Add filter',
      ).toHaveCount(before + 1);

      const row = this.widgetFilterRows(dialog).nth(before);
      await row.locator('button[role="combobox"]:has([data-testid="filter-column"])').click();
      const option = this.page.getByRole('option', { name: args.column, exact: true });
      await expect(option, `exactly one "${args.column}" filter column option`).toHaveCount(1);
      await option.click();

      const operator = row.locator(
        'button[role="combobox"]:has([data-testid="filter-operator"])',
      );
      await expect(
        operator,
        `the filter operator defaults to "${args.operator}" for this column`,
      ).toContainText(args.operator);

      await this.setWidgetFilterValue(dialog, before, args.value);
    });
  }

  /**
   * Rewrites one existing filter row's VALUE and nothing else.
   *
   * The whole point of the in-place-edit spec: this is the only field that
   * changes between two saves, so the widget's config differs from its previous
   * state only inside a nested array of objects — the case the `React.memo`
   * comparator's deep equality has to catch, with the generated title, the
   * metric and the project all identical.
   *
   * `DebounceInput` commits on a timer, so the blur is explicit and the value
   * is read back before returning — the same shape
   * `online-evaluation.page.ts`'s `setFilterValue` uses.
   */
  async setWidgetFilterValue(dialog: Locator, index: number, value: string): Promise<void> {
    await test.step(`Set widget filter ${index + 1}'s value to "${value}"`, async () => {
      const input = this.widgetFilterRows(dialog)
        .nth(index)
        .locator('[data-testid="filter-number-input"], [data-testid="filter-string-input"]');
      await expect(input, `exactly one value box on filter ${index + 1}`).toHaveCount(1);
      await input.fill(value);
      await input.blur();
      await expect(input, `filter ${index + 1}'s value box`).toHaveValue(value);
    });
  }

  /**
   * The "no data" message a metrics widget renders in place of its chart.
   *
   * `MetricChartContainer` falls back to `NoData` with this message when every
   * point of every line is nil or zero, and `ProjectMetricsWidget` supplies no
   * custom empty state for the count metrics. It is therefore the user-visible
   * difference between a filter that selects rows and one that selects none —
   * and the thing a stale, memoized-away chart would NOT show.
   */
  widgetNoDataMessage(title: string): Locator {
    return this.widget(title).getByText('No data to show', { exact: true });
  }

  /**
   * Picks a widget type in the open widget-config dialog's type selector.
   *
   * The selector renders each type as a `CardSelector` button whose accessible
   * name is the type's own title ("Time series", "Markdown", "Single metric",
   * …), so this is a plain `getByRole` — no test id needed. `exact: true`
   * because "Metrics" is a prefix of nothing here but "Markdown" and "Metrics"
   * both sit in the same list and a loose match on a future "Markdown table"
   * would silently take the wrong card.
   *
   * The count is asserted before the click so a renamed or permission-hidden
   * type fails saying the card was not there, rather than timing out on the
   * editor that was never going to mount. Selecting a type REPLACES the
   * dialog's preview widget, which is what swaps in that type's own editor and
   * its own zod schema.
   */
  async selectWidgetType(dialog: Locator, typeTitle: string): Promise<void> {
    await test.step(`Select the "${typeTitle}" widget type`, async () => {
      const card = dialog.getByRole('button', { name: typeTitle, exact: true });
      await expect(card, `exactly one "${typeTitle}" widget type card`).toHaveCount(1);
      await card.click();
      // The editor for the chosen type mounts after the preview widget is
      // replaced; the heading the dialog renders above it is the first thing
      // that says the swap happened.
      await dialog
        .getByRole('heading', { name: typeTitle, exact: true })
        .waitFor({ state: 'visible' });
    });
  }

  /**
   * The Markdown widget editor's CodeMirror content node.
   *
   * `.cm-content` is how the estate addresses every CodeMirror in the app
   * (`online-evaluation.page.ts`, `optimization-studio.page.ts`,
   * `test-suite-items.page.ts`): the editor is rendered by `@uiw/react-codemirror`,
   * which exposes no role and takes no props this page could hang a test id on.
   * Scoped to the dialog so it cannot match an editor elsewhere on the page.
   */
  markdownEditor(dialog: Locator): Locator {
    return dialog.locator('.cm-content').first();
  }

  /** The markdown content the editor currently holds, placeholder stripped. */
  async markdownEditorContent(dialog: Locator): Promise<string> {
    return test.step('Read the Markdown editor content', async () => {
      return this.markdownEditor(dialog).evaluate((editor) => {
        const clone = editor.cloneNode(true) as HTMLElement;
        clone.querySelectorAll('.cm-placeholder').forEach((node) => node.remove());
        return (clone.textContent ?? '').trim();
      });
    });
  }

  /**
   * Types markdown into the editor and settles once it really holds the text.
   *
   * Click-then-type rather than `fill()`: `fill()` on a CodeMirror editor does
   * not reliably reach the editor's own state, and the content here is read
   * back out of that state by the form AND pushed into the dashboard store on
   * every change. Resolving on the content rather than on the keystrokes is
   * what makes the assertions that follow about the form, not about typing.
   *
   * Single-line content on purpose at the call sites: CodeMirror auto-indents,
   * so a multi-line string typed key by key is not necessarily the string that
   * ends up stored.
   */
  async fillMarkdownContent(dialog: Locator, content: string): Promise<void> {
    await test.step('Type markdown into the widget editor', async () => {
      const editor = this.markdownEditor(dialog);
      await editor.click();
      await editor.press('ControlOrMeta+A');
      await this.page.keyboard.type(content);
      await expect
        .poll(async () => this.markdownEditorContent(dialog), { timeout: 15_000 })
        .toBe(content);
    });
  }

  /**
   * A validation message rendered inside the open widget dialog.
   *
   * `FormMessage` renders the zod issue's message as plain text in an
   * unlabelled div, so text is the only handle — and that is fine here, because
   * the message string IS what is under test: the assertion is that the
   * resolver mapped the issue to this field's message and the form rendered it.
   */
  widgetValidationMessage(dialog: Locator, message: string): Locator {
    return dialog.getByText(message, { exact: true });
  }

  /**
   * The dashboard's autosave indicator once it reports a completed save.
   *
   * A dashboard page keeps its widgets in a client store and persists them
   * asynchronously, so a widget can render from memory before (or without) the
   * write landing. A caller about to reload the page, or to read the dashboard
   * back over the API, has to wait on this first or it races the save.
   */
  savedIndicator(): Locator {
    return this.page.getByText('Saved', { exact: true });
  }

  /**
   * One label in a widget's chart legend.
   *
   * `toHaveCount(1)` rather than `toBeVisible()` is what a caller wants here:
   * `ChartHorizontalLegend` wraps its items in a `max-h-20 overflow-y-auto`
   * box, so an eleven-series breakdown legend renders every item but scrolls
   * the later ones out of view. Visibility would then fail for a label that is
   * genuinely present, which is not the question being asked.
   *
   * `exact: true` matters for the group names a cost breakdown produces: a
   * substring match on `…-grp-01` also matches `…-grp-010` were one to exist,
   * and the overflow group's display label `Others` is a word that appears in
   * ordinary prose elsewhere on a dashboard.
   */
  widgetLegendLabel(title: string, label: string): Locator {
    return this.widget(title).getByText(label, { exact: true });
  }

  /** Waits for a widget's chart to be drawn rather than still loading. */
  async waitForWidgetChart(title: string): Promise<void> {
    await test.step(`Wait for the "${title}" widget's chart`, async () => {
      await this.widget(title).locator('svg').first().waitFor({ state: 'visible' });
    });
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
   * A row in the dashboards list, addressed by the dashboard's **id**.
   *
   * `DataTable` stamps `data-row-id` from the page's `getRowId`, which for
   * dashboards is the entity id. Preferred over {@link row} wherever the test
   * knows the id: a name cell is matched by rendered text, and this page's
   * column order is user-configurable and persisted, so nothing about a name
   * lookup is anchored to the row's identity. It also makes "exactly this
   * dashboard went" expressible, which is the whole verdict of a delete.
   */
  rowById(dashboardId: string): Locator {
    return this.page.locator(`tr[data-row-id="${dashboardId}"]`);
  }

  /**
   * Opens one row's actions menu and returns the open menu.
   *
   * The trigger carries no `data-testid`; it is an icon-only button whose only
   * handle is the `sr-only` accessible name "Actions menu" that
   * `DashboardRowActionsCell` renders inside it. Scoped to the row so the
   * lookup cannot drift to a sibling row's trigger, and asserted to resolve to
   * exactly one element rather than taking `.first()`, so an ambiguous match
   * fails loudly instead of silently operating on the wrong row.
   *
   * Does NOT assert anything about navigation — opik#9832 rewrapped this cell
   * so a click here stops propagating to the row's own `onRowClick`, and
   * whether the URL stayed put is the caller's claim to make.
   */
  async openRowActionsMenu(dashboardId: string): Promise<Locator> {
    return test.step(`open the actions menu on dashboard ${dashboardId}`, async () => {
      const trigger = this.rowById(dashboardId).getByRole('button', { name: 'Actions menu' });
      await expect(
        trigger,
        `exactly one Actions menu trigger in the row for dashboard ${dashboardId}`,
      ).toHaveCount(1);
      await trigger.click();
      const menu = this.page.getByRole('menu');
      await menu.waitFor({ state: 'visible' });
      return menu;
    });
  }

  /**
   * Completes a delete from a row's actions menu: open the menu, choose
   * Delete, confirm in the dialog.
   *
   * Waits for the delete request to be answered rather than for the row to go,
   * and leaves every assertion to the caller. The list invalidates its query
   * optimistically, so a row can leave the table before — or without — the
   * server having deleted anything; returning once the response has landed is
   * what lets the caller's UI and server-side assertions mean two different
   * things.
   *
   * The request is a `POST .../dashboards/delete-batch` carrying an `ids`
   * array, not a `DELETE` — `useDashboardBatchDeleteMutation` is the same
   * mutation the multi-select toolbar uses, which is exactly why a bystander
   * matters.
   */
  async deleteFromRowActionsMenu(dashboardId: string): Promise<void> {
    return test.step(`delete dashboard ${dashboardId} from its row-actions menu`, async () => {
      const menu = await this.openRowActionsMenu(dashboardId);

      await menu.getByRole('menuitem', { name: 'Delete', exact: true }).click();

      // `ConfirmDialog`'s confirm button is labelled "Delete dashboard", which
      // is also the label of the list header's create button — hence the scope
      // to the dialog rather than to the page.
      const dialog = this.page.getByRole('dialog').filter({ hasText: 'Delete dashboard' });
      await dialog.waitFor({ state: 'visible' });

      const deleted = this.page.waitForResponse(
        (res) =>
          res.url().includes('/v1/private/dashboards/delete-batch') &&
          res.request().method() === 'POST',
      );
      await dialog.getByRole('button', { name: 'Delete dashboard', exact: true }).click();
      await deleted;
      await dialog.waitFor({ state: 'hidden' });
    });
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
