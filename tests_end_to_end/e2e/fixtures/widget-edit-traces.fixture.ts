import { test as baseTest } from './numpy-payload-traces.fixture';
import type { BackendFilter } from '../core/backend';

/**
 * The filter column and operator the widget editor is driven with. `Name` is a
 * string column, so `contains` is its own default operator — see
 * `DashboardsPage.addWidgetFilter`, which asserts that rather than selecting it.
 */
export const WET_FILTER_COLUMN = 'Name';
export const WET_FILTER_OPERATOR = 'contains';

/** How many of the seeded traces carry the matching marker, and how many exist. */
export const WET_MATCHING_TRACES = 2;
export const WET_TOTAL_TRACES = 3;

export interface WidgetEditTracesRef {
  projectId: string;
  projectName: string;
  /**
   * The substring the FIRST filter value matches — on two of the three traces.
   *
   * Two rather than all three on purpose: a filter that happened to match
   * everything is indistinguishable from no filter at all, so the chart it
   * draws would be the same one the unfiltered widget drew and "the series
   * changed" could never fail.
   */
  matchingMarker: string;
  /**
   * The substring the SECOND filter value matches — on nothing.
   *
   * Narrowing to zero is what makes the second repaint unmistakable in the DOM:
   * the widget swaps its chart for "No data to show". A memoized-away widget
   * keeps drawing the previous line, which is exactly the regression an
   * over-equal comparator would cause.
   */
  unmatchedMarker: string;
  /** Names of the traces carrying `matchingMarker`, in seed order. */
  matchingTraceNames: string[];
  /** The one trace that carries neither marker — the decoy the first filter excludes. */
  excludedTraceName: string;
}

export interface WidgetEditTracesFixtures {
  widgetEditTraces: WidgetEditTracesRef;
}

/** A `name contains <marker>` filter, as the REST layer serialises it. */
const nameContains = (value: string): BackendFilter => ({
  field: 'name',
  operator: 'contains',
  value,
  type: 'string',
});

/**
 * A project holding three traces, two of which share a name marker.
 *
 * Seeded through the SDK, not the UI: what the spec using this is about is how
 * a dashboard widget re-reads its metric after its configuration changes, not
 * how traces get logged.
 *
 * The fixture proves the seed can DISCRIMINATE before any browser opens. Both
 * markers are checked over the API — the matching one selects exactly two of
 * the three traces, the unmatched one selects none — because every UI assertion
 * downstream is of the form "the chart narrowed", and over a seed whose filter
 * silently matched everything (or nothing from the start) those assertions
 * cannot fail. That is the test-that-cannot-fail shape, and it reads as
 * coverage forever.
 *
 * `deleteProject` does not cascade to traces and `global-teardown`'s
 * run-prefix sweep does not know about them, so the project is registered with
 * `registerProjectTracesCleanup`.
 */
export const test = baseTest.extend<WidgetEditTracesFixtures>({
  widgetEditTraces: async (
    { sdkClient, backendClient, project, testNamespace, registerProjectTracesCleanup },
    use,
    testInfo,
  ) => {
    registerProjectTracesCleanup(project.id);

    const matchingMarker = 'kept';
    const unmatchedMarker = 'absent';
    const matchingTraceNames = [
      `${testNamespace}-${matchingMarker}-1`,
      `${testNamespace}-${matchingMarker}-2`,
    ];
    const excludedTraceName = `${testNamespace}-decoy-1`;

    for (const name of [...matchingTraceNames, excludedTraceName]) {
      await sdkClient.python.createTrace({
        project_name: project.name,
        name,
        input: `input for ${name}`,
        output: `output for ${name}`,
      });
    }

    const all = await backendClient.searchTraceIdsPage({ projectId: project.id });
    if (all.total !== WET_TOTAL_TRACES) {
      throw new Error(
        `[widgetEditTraces fixture] project '${project.name}' holds ${all.total} trace(s), ` +
          `expected ${WET_TOTAL_TRACES} — the filter assertions downstream compare counts`,
      );
    }

    const matching = await backendClient.searchTraceIdsPage({
      projectId: project.id,
      filters: [nameContains(matchingMarker)],
    });
    if (matching.total !== WET_MATCHING_TRACES) {
      throw new Error(
        `[widgetEditTraces fixture] 'name contains ${matchingMarker}' selects ` +
          `${matching.total} trace(s), expected ${WET_MATCHING_TRACES} — a marker that ` +
          `matched every trace would make "the chart narrowed" unfalsifiable`,
      );
    }

    const unmatched = await backendClient.searchTraceIdsPage({
      projectId: project.id,
      filters: [nameContains(unmatchedMarker)],
    });
    if (unmatched.total !== 0) {
      throw new Error(
        `[widgetEditTraces fixture] 'name contains ${unmatchedMarker}' selects ` +
          `${unmatched.total} trace(s), expected none — the second edit narrows to empty`,
      );
    }

    const ref: WidgetEditTracesRef = {
      projectId: project.id,
      projectName: project.name,
      matchingMarker,
      unmatchedMarker,
      matchingTraceNames,
      excludedTraceName,
    };
    await testInfo.attach('opik.widgetEditTraces', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './numpy-payload-traces.fixture';
