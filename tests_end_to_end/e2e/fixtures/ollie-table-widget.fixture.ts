import { expect } from '@playwright/test';
import { test as baseTest } from './grouped-pass-rate-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { InsightsViewRef, ProjectRef } from '../core/backend';

/**
 * The columns the seeded table widget's spec declares, in the order it declares
 * them.
 *
 * `RowsTable` takes its column order from `spec.table.columns` rather than from
 * the rows, so this is also the order the headers and the `<colgroup>` render
 * in — which is what lets a width assertion pair a `<col>` with the header that
 * sits above it.
 */
export const OLLIE_TABLE_COLUMNS = ['model', 'cost', 'latency_ms', 'note'] as const;

/**
 * The five rows, in the order the widget stores them — deliberately NOT in
 * either sorted order.
 *
 * Every value here is doing a job, because the sort rule under test
 * (`RowsTable.sortRows`) ranks by KIND first and only then by value:
 *
 * - `cost` mixes a real number (10, 2), a NUMERIC STRING ('9.5'), a non-numeric
 *   string ('n/a') and a null. The numeric string is the point of the column:
 *   widget rows arrive from a query where numbers are often strings, and
 *   `coerceNumericRows` is what has to make 9.5 sort between 2 and 10 rather
 *   than lexically after both. 'n/a' must rank below every number and the null
 *   below that, in BOTH directions.
 * - the stored order (2, 10, n/a, 9.5, null) matches neither sort direction, so
 *   a first click that reorders nothing fails rather than passing against rows
 *   that happened to arrive sorted already.
 * - `note` carries one empty string, which is `isEmpty` to the table and so
 *   renders as the "-" placeholder, not as blank.
 * - `model` holds deliberately long values. Its content width (the widest value
 *   plus padding) has to exceed the width of the narrow widget below, or the
 *   width CAP a spec observes there would be the cap to the column's content
 *   rather than the cap to the widget. The spec asserts that relationship off
 *   the two widgets rather than trusting it.
 */
export const OLLIE_TABLE_ROWS: ReadonlyArray<Record<string, unknown>> = [
  { model: 'meta-llama/llama-3-70b-instruct-turbo', cost: 2, latency_ms: 2300, note: '' },
  {
    model: 'anthropic/claude-sonnet-4-5-20260101-preview',
    cost: 10,
    latency_ms: 1200,
    note: 'baseline',
  },
  { model: 'google/gemini-2-flash-lite-preview', cost: 'n/a', latency_ms: 300, note: 'free tier' },
  { model: 'openai/gpt-5-mini-20260218', cost: '9.5', latency_ms: 450, note: 'cheaper' },
  { model: 'mistralai/mistral-large-2411', cost: null, latency_ms: 980, note: 'pending' },
];

/** `cost` as the table renders it, in stored order — numbers formatted, empty as "-". */
export const OLLIE_TABLE_COST_AS_STORED = ['2', '10', 'n/a', '9.5', '-'] as const;
/** …descending: numbers high-to-low, then text, then the empty cell. */
export const OLLIE_TABLE_COST_DESCENDING = ['10', '9.5', '2', 'n/a', '-'] as const;
/** …ascending: numbers low-to-high, and text and empty STILL last. */
export const OLLIE_TABLE_COST_ASCENDING = ['2', '9.5', '10', 'n/a', '-'] as const;

/**
 * A column width saved by somebody else, far wider than any widget on screen.
 *
 * The scenario is a width dragged out on a wide monitor and then read by
 * someone whose widget is narrow: `columnsWidth` is part of the shared
 * dashboard config, so the number travels. 2000 is past every column's content
 * width and past the full-width widget too, so whichever cap applies, the
 * drawn width can never be this.
 */
export const OVERSIZED_SAVED_COLUMN_WIDTH = 2000;

/** The `version` Ollie stamps on the widget config, which a save must not drop. */
const SEEDED_WIDGET_VERSION = 7;
/** The chart's scope, as Ollie writes it — likewise part of the round trip. */
const SEEDED_WIDGET_SCOPE = 'project';
/** Ollie writes the chart's scope sentence here; the widget renders it as a subtitle. */
const SEEDED_WIDGET_DESCRIPTION = 'Spend and latency per model';

export interface OllieTableWidgetSeed {
  id: string;
  title: string;
  /**
   * The widget config exactly as it was POSTed.
   *
   * Carried so a spec can compare what the auto-save stored against it AS A
   * WHOLE, rather than checking only the key it expected to change. The failure
   * worth catching is a save that writes `sorting` correctly and drops `rows`
   * or `version` on the way, and only a whole-object comparison sees that.
   */
  config: Record<string, unknown>;
}

export interface OllieTableWidgetRef {
  /** The project-scoped insights view holding both widgets. */
  view: InsightsViewRef;
  /** The title of the one section they sit in. */
  sectionTitle: string;
  /** The project the view belongs to — the `project` fixture's own. */
  project: ProjectRef;
  /**
   * The full-width (6-of-6 columns) table widget, with no saved sort and no
   * saved column width: the one a spec sorts and resizes from the UI.
   */
  widget: OllieTableWidgetSeed;
  /**
   * A two-column-wide table widget whose `model` column already carries
   * `OVERSIZED_SAVED_COLUMN_WIDTH`.
   *
   * Its own widget rather than a mid-test PATCH of the first one: the claim is
   * about what a NARROW widget does with a width saved from a wide one, and two
   * widgets side by side in one view let a spec read the column's content width
   * off the wide one and the cap off the narrow one in the same render — which
   * is what proves the cap observed is the cap to the WIDGET and not the cap to
   * the content.
   */
  narrowWidget: OllieTableWidgetSeed;
}

export interface OllieTableWidgetFixtures {
  ollieTableWidget: OllieTableWidgetRef;
}

const widgetConfig = (extra: Record<string, unknown> = {}): Record<string, unknown> => ({
  // No `query.id`: without one the widget is not "live", so it draws these
  // snapshot rows and never calls the dashboard-query endpoint. That is what
  // makes the whole flow reachable on OSS with no Ollie and no provider keys,
  // and what makes it deterministic — the rows are the seed, not a query result.
  spec: { table: { columns: [...OLLIE_TABLE_COLUMNS] } },
  rows: OLLIE_TABLE_ROWS.map((row) => ({ ...row })),
  scope: SEEDED_WIDGET_SCOPE,
  description: SEEDED_WIDGET_DESCRIPTION,
  version: SEEDED_WIDGET_VERSION,
  ...extra,
});

/**
 * A project-scoped dashboard holding two Ollie TABLE widgets — one full width,
 * one narrow with an oversized column width already saved into it.
 *
 * Seeded through the API rather than built in the UI because there is no UI
 * that builds one: an `ollie_chart` widget is created by Ollie, which needs a
 * cloud Custom-Charts workspace. The widget itself does not: it reads
 * `OLLIE_ENABLED` only for its Edit/Duplicate-in-Ollie menu actions, so a
 * widget seeded with a snapshot `rows` array and no `query.id` renders its
 * table on a self-hosted install exactly as it does on cloud.
 *
 * In a fixture rather than in the tests because nothing cascades to an insights
 * view — it outlives the project it names, and `global-teardown`'s run-prefix
 * sweep knows the workspace `/dashboards` collection but not `insights-views` —
 * so a view created inside a test body leaks on exactly the failures that
 * matter. The seed is also verified here, before any browser opens: every
 * assertion a spec makes about a width cap is meaningless if the oversized
 * width never reached the server, and a UI assertion over a seed that silently
 * failed is a test that cannot fail.
 */
export const test = baseTest.extend<OllieTableWidgetFixtures>({
  ollieTableWidget: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const viewName = `${testNamespace}-ollie-tbl-view`;
    const sectionTitle = `${testNamespace}-ollie-tbl-section`;

    const widget: OllieTableWidgetSeed = {
      id: `${testNamespace}-widget-wide`,
      title: `${testNamespace} spend by model`,
      config: widgetConfig(),
    };
    const narrowWidget: OllieTableWidgetSeed = {
      id: `${testNamespace}-widget-narrow`,
      title: `${testNamespace} spend by model, narrow`,
      config: widgetConfig({ columnsWidth: { model: OVERSIZED_SAVED_COLUMN_WIDTH } }),
    };

    let view: InsightsViewRef | null = null;
    try {
      view = await backendClient.createInsightsView({
        name: viewName,
        projectId: project.id,
        sections: [
          {
            id: `${testNamespace}-section-1`,
            title: sectionTitle,
            // `minW`/`minH` match `getWidgetSizeConfig(OLLIE_CHART)` and the
            // x/y/w/h already satisfy them, so the grid's own
            // `normalizeLayout` on mount reproduces this layout exactly. A
            // layout the grid would have to correct is a config change, and a
            // config change is an auto-save — one that would fire on load and
            // race the save a spec is waiting for.
            layout: [
              { i: widget.id, x: 0, y: 0, w: 6, h: 5, minW: 2, minH: 3, maxW: 6, maxH: 12 },
              { i: narrowWidget.id, x: 0, y: 5, w: 2, h: 5, minW: 2, minH: 3, maxW: 6, maxH: 12 },
            ],
            widgets: [
              { id: widget.id, type: 'ollie_chart', title: widget.title, config: widget.config },
              {
                id: narrowWidget.id,
                type: 'ollie_chart',
                title: narrowWidget.title,
                config: narrowWidget.config,
              },
            ],
          },
        ],
      });

      const stored = await backendClient.listInsightsViewWidgets(view.id);
      expect(stored, 'the seeded view must be readable back by id').not.toBeNull();
      // Compared as a whole list, not searched: the specs read "the widget at
      // this id" out of this view, and a seed that stored a third widget, or
      // stored one of these twice, would make every later count and comparison
      // mean something else.
      expect(stored, 'both table widgets are stored, in order, with their configs intact').toEqual([
        { id: widget.id, type: 'ollie_chart', title: widget.title, config: widget.config },
        {
          id: narrowWidget.id,
          type: 'ollie_chart',
          title: narrowWidget.title,
          config: narrowWidget.config,
        },
      ]);

      const ref: OllieTableWidgetRef = {
        view,
        sectionTitle,
        project,
        widget,
        narrowWidget,
      };

      await testInfo.attach('opik.ollieTableWidget', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        if (view !== null) {
          try {
            await backendClient.deleteInsightsView(view.id);
          } catch (err) {
            console.warn('[ollieTableWidget fixture] view delete warning:', err);
          }
        } else {
          // No id came back, which is not the same as nothing having been
          // created: `insights-views` stamps its own id server-side, so a POST
          // that commits and then loses its response can only be found again by
          // name. Nothing else would find it — no cascade reaches a view from
          // the project it names, and the run-prefix sweep in `global-teardown`
          // does not know this collection — so the leak would be permanent.
          try {
            const orphans = (
              await backendClient.listInsightsViews({ projectId: project.id })
            ).filter((d) => d.name === viewName);
            for (const orphan of orphans) {
              await backendClient.deleteInsightsView(orphan.id);
            }
          } catch (err) {
            console.warn('[ollieTableWidget fixture] orphan view sweep warning:', err);
          }
        }
      }
    }
  },
});

export { expect } from './grouped-pass-rate-experiments.fixture';
