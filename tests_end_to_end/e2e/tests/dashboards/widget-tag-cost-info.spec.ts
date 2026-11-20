import { test, expect } from '@e2e/fixtures';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * The tag-cost overlap warning, and the widget information button it is served
 * through (opik#9001, issue-8132).
 *
 * A tag breakdown of a cost metric legitimately sums PAST real spend: the group
 * expression is `arrayJoin(...)` over the tag array, so a span carrying two
 * matching tags is counted in full under both. `project-cost-breakdown-others.spec.ts`
 * asserts that arithmetic. This spec asserts the sentence that stops a user
 * reading it as a billing discrepancy — and the three things opik#9001 had to
 * get right to show it:
 *
 *  - the text appears for a cost metric grouped by Tags and NOT otherwise. The
 *    negative half is what makes this an assertion rather than a presence
 *    check: a description rendered unconditionally would pass a test that only
 *    looked at the Tags case, and would then tell every user of every widget
 *    that their numbers might exceed real spend.
 *  - the same text is reachable from the saved widget, where there is no form
 *    to read it off — through an `aria-label="Widget information"` button on
 *    the widget header.
 *  - that button is a focusable `Button` SEPARATE from the editable title. It
 *    used to be a `rightIcon` slot inside `InlineEditableText`, whose wrapper
 *    is itself a `role="button"` with an Enter/Space handler that opens the
 *    title editor — so activating the info icon renamed-moded the widget. The
 *    `rightIcon` prop was removed in the same PR, and the only coverage of
 *    either header is vitest.
 *
 * SCOPE — UI only, and deliberately so: every claim here is about what a
 * browser renders and what the keyboard reaches. There is no payload behind the
 * warning to assert at the API level, and `isCostMetricType` + the breakdown
 * compatibility matrix that gate it are front-end code.
 */

/**
 * The warning, verbatim. `TAG_COST_BREAKDOWN_DESCRIPTION` in
 * `ProjectMetricsWidget/breakdown.ts`.
 *
 * Pinned as a literal rather than loosened to a regex: the whole value of this
 * spec is that the sentence a user reads is the one that explains the overlap.
 * A text change should fail here and be re-read by a human, which is exactly
 * what a tolerant match would prevent.
 */
const TAG_COST_WARNING =
  'Costs are counted in full under every matching tag. Tag groups overlap, so adding them can exceed actual spend.';

/** `METRIC_LABELS[SPAN_COST]`, which is also the widget's generated title — a cost metric takes no sub-metric. */
const COST_METRIC_LABEL = 'Span estimated cost';

test.describe(
  'Dashboards — tag-cost warning and the widget information button',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    test(
      'the overlap warning and the information button appear for a cost metric grouped by Tags, and for no other grouping',
      { tag: ['@cap:dashboards.configure-widget'] },
      async ({ project, registerDashboardCleanup, page }) => {
        const dashboards = new DashboardsPage(page);

        await test.step('Open Dashboards and create one', async () => {
          await dashboards.goto();
          await dashboards.waitForReady();
          registerDashboardCleanup(await dashboards.createDashboard(`${project.name}-dash`));
        });

        const dialog = await test.step('Configure a Span estimated cost widget on the project', async () => {
          const opened = await dashboards.openWidgetDialog();
          await dashboards.scopeWidgetToProject(opened, project.name);
          await dashboards.selectWidgetMetric(COST_METRIC_LABEL);
          return opened;
        });

        await test.step('Grouped by Provider: no warning and no information button', async () => {
          await dashboards.selectWidgetGroupBy('Provider');
          // The negative control, and the reason the positive assertion below
          // means anything. Provider is a perfectly valid grouping of the same
          // cost metric and it does NOT overlap, so the warning must be absent
          // — and with it the info button, which exists only to carry it.
          await expect(
            dashboards.tagCostDescription(TAG_COST_WARNING),
            'the overlap warning is absent for a non-overlapping grouping',
          ).toHaveCount(0);
          await expect(
            dashboards.widgetInfoButton(dialog),
            'the widget information button is absent when there is nothing to say',
          ).toHaveCount(0);
        });

        await test.step('Grouped by Tags: the warning renders, word for word', async () => {
          await dashboards.selectWidgetGroupBy('Tags');
          await expect(
            dashboards.tagCostDescription(TAG_COST_WARNING),
            'the overlap warning under the Group by control',
          ).toHaveCount(1);
          await expect(
            dashboards.widgetInfoButton(dialog),
            'the preview header carries exactly one information button',
          ).toHaveCount(1);
        });
      },
    );

    test(
      'the information button is keyboard-reachable, shows the warning on focus, and never opens the widget title editor',
      { tag: ['@cap:dashboards.configure-widget'] },
      async ({ project, registerDashboardCleanup, page }) => {
        const dashboards = new DashboardsPage(page);

        await test.step('Open Dashboards and create one', async () => {
          await dashboards.goto();
          await dashboards.waitForReady();
          registerDashboardCleanup(await dashboards.createDashboard(`${project.name}-dash`));
        });

        const dialog = await test.step('Configure a Span estimated cost widget grouped by Tags', async () => {
          const opened = await dashboards.openWidgetDialog();
          await dashboards.scopeWidgetToProject(opened, project.name);
          await dashboards.selectWidgetMetric(COST_METRIC_LABEL);
          await dashboards.selectWidgetGroupBy('Tags');
          return opened;
        });

        const infoButton = dashboards.widgetInfoButton(dialog);
        const titleEditor = dashboards.widgetTitleEditor(COST_METRIC_LABEL);
        // Radix renders every tooltip and popover into a portal under this
        // wrapper — the selector the rest of this page object already uses for
        // popovers. Nothing else is open at this point in the test, so its
        // text is the tooltip's text.
        const tooltip = page.locator('[data-radix-popper-content-wrapper]');

        await test.step('Tabbing forward from Filters reaches the information button', async () => {
          // The property is REACHABILITY, not a press count: before opik#9001
          // the icon was a bare `<Info/>` glyph with no tab stop at all, so a
          // keyboard user could not read the warning on a saved widget.
          await expect(dashboards.filtersSection(), 'the Filters control to tab from').toHaveCount(1);
          await dashboards.tabUntilFocused(dashboards.filtersSection(), infoButton);
          await expect(infoButton, 'the information button holds focus').toBeFocused();
        });

        await test.step('The tooltip opens on focus carrying the same warning', async () => {
          await expect(
            tooltip,
            'focusing the button opens its tooltip with the overlap warning',
          ).toContainText(TAG_COST_WARNING);
        });

        await test.step('Enter and Space on it leave the title alone', async () => {
          // The untangling opik#9001 shipped. `InlineEditableText`'s wrapper is
          // a `role="button"` with its own Enter/Space handler, so while the
          // info icon lived inside it as `rightIcon`, keyboard-activating the
          // icon put the title into edit mode.
          await infoButton.press('Enter');
          await expect(
            titleEditor,
            'Enter on the information button does not open the title editor',
          ).toHaveCount(0);
          await infoButton.press(' ');
          await expect(
            titleEditor,
            'Space on the information button does not open the title editor',
          ).toHaveCount(0);
        });

        await test.step('Clicking the title DOES open the editor — the control', async () => {
          // Without this, "no editor appeared" would be satisfied just as well
          // by a preview header whose title is not editable at all, or by a
          // placeholder that never matches. This is what proves the negative
          // assertions above were looking at a live control.
          const title = dashboards.previewWidgetTitle(COST_METRIC_LABEL);
          await expect(title, 'exactly one editable widget title in the dialog').toHaveCount(1);
          await title.click();
          await expect(titleEditor, 'clicking the title opens the editor').toHaveCount(1);
        });
      },
    );

    test(
      'the saved widget header carries the same information button and the same warning',
      { tag: ['@cap:dashboards.add-widget', '@cap:dashboards.configure-widget'] },
      async ({ project, registerDashboardCleanup, page }) => {
        const dashboards = new DashboardsPage(page);

        await test.step('Open Dashboards and create one', async () => {
          await dashboards.goto();
          await dashboards.waitForReady();
          registerDashboardCleanup(await dashboards.createDashboard(`${project.name}-dash`));
        });

        await test.step('Add a Span estimated cost widget grouped by Tags', async () => {
          const dialog = await dashboards.openWidgetDialog();
          await dashboards.scopeWidgetToProject(dialog, project.name);
          await dashboards.selectWidgetMetric(COST_METRIC_LABEL);
          await dashboards.selectWidgetGroupBy('Tags');
          await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
          await dialog.waitFor({ state: 'hidden' });
        });

        await test.step('The saved widget exposes the warning through its own header button', async () => {
          // A different component from the one above — `DashboardWidgetHeader`,
          // not `DashboardWidgetPreviewHeader` — and the one that matters most:
          // the dialog at least shows the warning as text, while a saved widget
          // has no form to read it off. If only one of the two got the button, a
          // user would see an overlapping tag chart with no explanation anywhere.
          const widget = dashboards.widget(COST_METRIC_LABEL);
          await expect(widget, 'exactly one widget carrying the generated title').toHaveCount(1);
          const infoButton = dashboards.widgetInfoButton(widget);
          await expect(infoButton, 'the saved widget header has one information button').toHaveCount(
            1,
          );
          await infoButton.focus();
          await expect(
            page.locator('[data-radix-popper-content-wrapper]'),
            'the saved widget’s tooltip carries the overlap warning',
          ).toContainText(TAG_COST_WARNING);
        });
      },
    );
  },
);
