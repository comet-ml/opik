import {
  test,
  expect,
  WET_FILTER_COLUMN,
  WET_FILTER_OPERATOR,
  WET_MATCHING_TRACES,
} from '@e2e/fixtures';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * opik#9668 (OPIK-9303) replaced `isLooseEqual`'s fast-deep-equal with lodash.
 * Its only consumer is `areWidgetsEqual`, which is the `React.memo` comparator
 * on `DashboardWidgetGrid`:
 *
 *   prev.id === next.id && … && prev.generatedTitle === next.generatedTitle &&
 *   isLooseEqual(prev.config, next.config)
 *
 * The three specs already tagged `dashboards.configure-widget` only configure a
 * widget AT CREATION, where the section's widget array grows and
 * `areWidgetArraysEqual` rejects the props on `length` before `isLooseEqual` is
 * ever reached. So the comparison this release rewrote is reached by nothing,
 * and an over-equal one fails silently: the grid skips its re-render, the
 * widget never re-queries, and a stale chart stays on screen with no error
 * anywhere — on a page people read daily to decide whether something is wrong.
 *
 * The spec edits an EXISTING widget twice, and the two edits are not
 * interchangeable:
 *
 *  1. **The metric type.** The generated title is derived from the metric
 *     (`METRIC_LABELS`), so this edit changes `generatedTitle` as well and the
 *     comparator short-circuits before the deep compare. It is the positive
 *     control: it says the in-place edit path repaints at all, so a failure in
 *     step 2 is about the comparison rather than about the plumbing.
 *  2. **One filter value, and nothing else.** Metric, title, project, type and
 *     every other field are identical; the two configs differ only inside
 *     `traceFilters[0].value`, a nested object inside an array. This is the one
 *     edit that actually reaches `isLooseEqual`, and the one the fix is about.
 *
 * **Why the assertion is the DOM and not the metrics request.** The obvious
 * observation — "a new `POST /projects/{id}/metrics` fired carrying the new
 * config" — is not sound here, in BOTH directions. `WidgetConfigPreview`
 * renders a live `ProjectMetricsWidget preview` inside the open dialog, so the
 * request for the new configuration is issued while the dialog is still up,
 * before the grid has been handed anything: seeing it proves nothing about the
 * grid. And `useProjectMetric` keys its query on the params object, so by the
 * time the saved widget re-renders, react-query already holds that exact key
 * and serves it from cache WITHOUT a network call — so not seeing a request
 * after the save would be a false failure. What the widget RENDERS is immune to
 * both: a cache hit still re-renders the chart, and a memoized-away widget
 * still shows the old one.
 *
 * So each edit is asserted on what a user sees, with no reload — a reload would
 * rebuild the grid from scratch and mask exactly the memo bug under test — and
 * then corroborated against the persisted dashboard over the API, which rules
 * out the opposite failure of a repaint that never saved.
 *
 * The bystander widget earns its place twice: it shares the grid and the
 * comparator with the edited one, so it is what says the repaint was targeted
 * rather than the whole page being rebuilt, and it keeps its own data over the
 * same project, so a blanket invalidation would show up as its chart going
 * empty too. It is given a metric of its own so that every locator here
 * addresses a widget by a title only one widget ever holds — a grid is a
 * repeated, unlabelled container, and `.first()`/`.last()` over two widgets
 * sharing a title would be positional addressing of exactly the kind that goes
 * silently wrong.
 */
test.describe(
  'Dashboards — a widget repaints after its configuration is edited in place',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    /** `METRIC_LABELS[SPAN_COUNT]` — the bystander's metric, and its generated title. */
    const BYSTANDER_LABEL = 'Number of spans';
    /**
     * `METRIC_LABELS[THREAD_COUNT]` — where the widget under test starts.
     *
     * A metric the seeded project has no data for, on purpose: the first edit
     * is then a change of rendered STATE as well as of title, from the
     * thread-count empty state to a drawn chart.
     */
    const START_LABEL = 'Number of threads';
    /** `METRIC_LABELS[TRACE_COUNT]` — where the first edit takes it. */
    const EDITED_LABEL = 'Number of traces';
    /** `METRIC_NAME_TYPE.TRACE_COUNT`, as the persisted config spells it. */
    const EDITED_METRIC = 'TRACE_COUNT';

    test(
      'editing the metric type and then one nested filter value both repaint the chart in place',
      { tag: ['@cap:dashboards.configure-widget'] },
      async ({ widgetEditTraces, backendClient, registerDashboardCleanup, page }) => {
        const dashboards = new DashboardsPage(page);
        const { projectName, matchingMarker, unmatchedMarker } = widgetEditTraces;

        const dashboardId = await test.step('Create a dashboard', async () => {
          await dashboards.goto();
          await dashboards.waitForReady();
          const id = await dashboards.createDashboard(`${projectName}-dash`);
          registerDashboardCleanup(id);
          return id;
        });

        await test.step(`Add the bystander widget: ${BYSTANDER_LABEL} on the project`, async () => {
          const dialog = await dashboards.openWidgetDialog();
          await dashboards.scopeWidgetToProject(dialog, projectName);
          await dashboards.selectWidgetMetric(BYSTANDER_LABEL);
          await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
          await dialog.waitFor({ state: 'hidden' });
          await dashboards.waitForWidgetChart(BYSTANDER_LABEL);
        });

        await test.step(`Add the widget under test: ${START_LABEL} on the same project`, async () => {
          const dialog = await dashboards.openWidgetDialog();
          await dashboards.scopeWidgetToProject(dialog, projectName);
          await dashboards.selectWidgetMetric(START_LABEL);
          await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
          await dialog.waitFor({ state: 'hidden' });
        });

        await test.step('Both widgets are on the grid, each under its own title', async () => {
          await expect(
            dashboards.widget(BYSTANDER_LABEL),
            'exactly one bystander widget',
          ).toHaveCount(1);
          await expect(
            dashboards.widget(START_LABEL),
            'exactly one widget under test',
          ).toHaveCount(1);
          // The bystander has data and is NOT empty. Both halves matter: it is
          // the control for the "did not narrow with its neighbour" assertion
          // at the end, which an already-empty widget would satisfy for free.
          await expect(
            dashboards.widget(BYSTANDER_LABEL).locator('svg').first(),
            "the bystander's chart is drawn",
          ).toBeVisible();
          await expect(
            dashboards.widgetNoDataMessage(BYSTANDER_LABEL),
            'and it is not empty — the project really holds the seeded spans',
          ).toHaveCount(0);
        });

        // ---- Edit 1: the metric type, which carries the generated title with
        // it. The control for the edit path itself.

        await test.step(`Edit the widget in place: metric to ${EDITED_LABEL}, with a ${WET_FILTER_COLUMN} filter`, async () => {
          const dialog = await dashboards.editWidget(START_LABEL);
          // The metric FIRST: changing it clears the editor's filters
          // (`form.setValue("traceFilters", [])`), so a filter added before it
          // would be discarded and the second edit would have no value to
          // change.
          await dashboards.selectWidgetMetric(EDITED_LABEL);
          await dashboards.addWidgetFilter(dialog, {
            column: WET_FILTER_COLUMN,
            operator: WET_FILTER_OPERATOR,
            value: matchingMarker,
          });
          await dashboards.saveWidgetChanges(dialog);
        });

        await test.step('It repainted under the new title, without a reload', async () => {
          await expect(
            dashboards.widget(EDITED_LABEL),
            'the widget now renders the trace-count title',
          ).toHaveCount(1);
          await expect(
            dashboards.widget(START_LABEL),
            'and no longer its old generated title',
          ).toHaveCount(0);
          await expect(
            dashboards.widget(EDITED_LABEL).locator('svg').first(),
            'a chart is drawn where the thread-count empty state was',
          ).toBeVisible();
          await expect(
            dashboards.widgetNoDataMessage(EDITED_LABEL),
            `and it is not empty: the filter still selects ${WET_MATCHING_TRACES} of the seeded traces`,
          ).toHaveCount(0);
        });

        // ---- Edit 2: one nested filter value and nothing else, so the config
        // differs only inside traceFilters[0].value. This is the edit that
        // reaches isLooseEqual.

        await test.step(`Edit it again, changing only the filter value to "${unmatchedMarker}"`, async () => {
          const dialog = await dashboards.editWidget(EDITED_LABEL);
          // The premise of this step, asserted before anything is changed: the
          // editor is holding the widget that was just saved, with its metric
          // and its one filter. If it had opened on a widget whose filter was
          // lost, the change below would not be "one nested value" and the
          // comparator would have an easier job than the one under test.
          await expect(
            dialog.getByRole('button', { name: 'Metric type' }),
            'the editor opened on the trace-count metric, which this edit leaves alone',
          ).toContainText(EDITED_LABEL);
          await dashboards.expandWidgetFilters(dialog);
          await expect(
            dashboards.widgetFilterRows(dialog),
            'it holds exactly the one filter the previous save left',
          ).toHaveCount(1);

          await dashboards.setWidgetFilterValue(dialog, 0, unmatchedMarker);
          await dashboards.saveWidgetChanges(dialog);
        });

        await test.step('The chart it draws narrowed to the new, empty result set', async () => {
          // THE assertion of this spec. Every other field of the widget is
          // identical to its previous state, so if `isLooseEqual` calls the two
          // configs equal, `DashboardWidgetGrid` skips its re-render,
          // `ProjectMetricsWidget` never re-renders, and the chart drawn for
          // the OLD filter stays on screen.
          await expect(
            dashboards.widgetNoDataMessage(EDITED_LABEL),
            'the edited widget replaced its chart with the empty state, so the repaint reached the DOM',
          ).toBeVisible();
          await expect(
            dashboards.widget(EDITED_LABEL),
            'and it is still the one widget under that title',
          ).toHaveCount(1);
        });

        await test.step('The bystander widget is untouched', async () => {
          await expect(
            dashboards.widget(BYSTANDER_LABEL).locator('svg').first(),
            "the first widget's chart is still drawn",
          ).toBeVisible();
          await expect(
            dashboards.widgetNoDataMessage(BYSTANDER_LABEL),
            'and it did not narrow with its neighbour — the repaint was targeted, not a page rebuild',
          ).toHaveCount(0);
        });

        await test.step('The dashboard persisted exactly the configuration that was rendered', async () => {
          // The other direction of the same question. The step above rules out
          // "saved but not repainted", which is the memo bug; this rules out
          // "repainted but not saved", where the chart a user is looking at is
          // not the one the dashboard will show them next time.
          //
          // Polled rather than read once, and polled rather than gated on the
          // "Saved" indicator: a dashboard page keeps its widgets in a client
          // store and persists them asynchronously, and the indicator's text
          // is also left over from the earlier saves this test already made —
          // so it would be satisfied before the edits had landed anywhere.
          // Measured here, the write took a few seconds.
          await expect
            .poll(
              async () =>
                (await backendClient.getDashboardWidgets(dashboardId)).filter(
                  (w) => w.config.metricType === EDITED_METRIC,
                ).length,
              {
                message: `the stored dashboard must carry one ${EDITED_METRIC} widget`,
                timeout: 30_000,
              },
            )
            .toBe(1);

          const widgets = await backendClient.getDashboardWidgets(dashboardId);
          expect(widgets.length, 'the dashboard holds both widgets').toBe(2);

          const edited = widgets.filter((w) => w.config.metricType === EDITED_METRIC);
          expect(
            edited.length,
            `exactly one stored widget carries the ${EDITED_METRIC} metric. Stored: ${JSON.stringify(widgets)}`,
          ).toBe(1);
          const traceFilters = edited[0].config.traceFilters;
          expect(
            Array.isArray(traceFilters) ? traceFilters.length : null,
            'it stored exactly one trace filter',
          ).toBe(1);
          expect(
            (traceFilters as Array<Record<string, unknown>>)[0].value,
            'and that filter carries the value the second edit set, not the first',
          ).toBe(unmatchedMarker);
        });
      },
    );
  },
);
