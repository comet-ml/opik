import { test as baseTest, expect } from './emptied-source-versions.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type InsightsViewRef, type ProjectRef } from '../core/backend';

/** The name the app gives the first copy of the "Project overview" template. */
const COPY_NAME = 'Project overview (custom)';

/**
 * Six traces, 100–600ms apart, in a project of their own.
 *
 * Six rather than one so the "At a glance" cards resolve to numbers a reader
 * would notice being wrong, and six *different* durations so the two latency
 * percentiles are genuinely two different aggregates — six identical ones would
 * also be what a p99 wired to the p50 query reported.
 */
const TRACE_DURATIONS_MS = [100, 200, 300, 400, 500, 600];

export interface ProjectOverviewTracesRef {
  /** The project the traces were written to — the `project` fixture's own. */
  project: ProjectRef;
  /** The trace ids, for a test that wants to name them. */
  traceIds: string[];
  /**
   * How many traces the project holds, as the "Traces" card must report it.
   *
   * Carried rather than left to the spec to recount: it is the positive control
   * for every other card reading, so it has to be the number the fixture
   * actually wrote and verified, not a literal the spec repeats.
   */
  traceCount: number;
}

export interface ProjectOverviewCopyFixtures {
  projectOverviewTraces: ProjectOverviewTracesRef;
  /**
   * A view already holding the name the first copy would be given.
   *
   * Lets a spec drive the de-duplication branch of `getCopyName` without first
   * having to create a copy through the UI: with this in the project, a widget
   * saved on the built-in template has to come back as
   * "Project overview (custom 2)".
   *
   * Deliberately NOT run-prefixed — the name has to collide with the one the
   * app generates, which is a fixed string. It is deleted here, and swept by
   * name if the create lost its response, because `global-teardown`'s prefix
   * sweep would never match it.
   */
  copyNameTakenView: InsightsViewRef;
  /**
   * A second view in the same project that no spec touches.
   *
   * The bystander for the rename spec: "the copy took the new name" would pass
   * just as well against a rename that had written the name to every view in the
   * project, or to the wrong one.
   */
  bystanderProjectView: InsightsViewRef;
  /**
   * Registers an insights view for deletion at teardown.
   *
   * A register-callback rather than a seed fixture because the views these specs
   * care about are created by the app, mid-test: a copy's id first exists when
   * the page writes it into the URL, so there is nothing a seed fixture could
   * know upfront.
   *
   * It is needed at all because nothing else would ever remove them. No project
   * delete cascades to an insights view, and `global-teardown`'s run-prefix
   * sweep only knows the workspace-scoped `/dashboards` collection — and the
   * names the app gives these copies carry no run prefix to match anyway.
   */
  registerInsightsViewCleanup: (id: string) => void;
}

/**
 * The seed shape the "built-in view becomes an editable copy" flow needs
 * (opik#9162, OPIK-8928): a project with enough traces for the template's stats
 * cards to show real numbers, somewhere to register the views the app creates,
 * and two views the specs use as controls.
 *
 * Seeded through the REST client rather than the UI for the usual reason, and
 * with each trace's id minted at its own start time: the stats behind the cards
 * window on the id, so a trace whose id disagreed with its timestamps could land
 * outside the dashboard's date range and leave the cards reading zero.
 */
export const test = baseTest.extend<ProjectOverviewCopyFixtures>({
  projectOverviewTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];
    try {
      for (const [i, durationMs] of TRACE_DURATIONS_MS.entries()) {
        const startTime = new Date(Date.now() - (i + 1) * 60_000);
        const id = uuid7(startTime);
        await backendClient.createTraceWithSource({
          id,
          projectName: project.name,
          name: `${testNamespace}-trace-${i}`,
          // The cards read `/traces/stats` with a `source = sdk` filter, as the
          // Logs page does; a trace written under any other source is invisible
          // to them and the whole seed would read as zero.
          source: 'sdk',
          input: `${testNamespace} in ${i}`,
          output: `${testNamespace} out ${i}`,
          startTime,
          endTime: new Date(startTime.getTime() + durationMs),
        });
        traceIds.push(id);
      }

      // The seed has to be queryable before the browser opens, or a card
      // reading "0" would be indistinguishable from the regression this spec is
      // looking for. Polled because trace ingestion is eventually consistent.
      await expect
        .poll(async () => (await backendClient.listTraceIds({ projectId: project.id })).length, {
          message: `the ${TRACE_DURATIONS_MS.length} seeded traces must be queryable before the dashboard is read`,
          timeout: 60_000,
        })
        .toBe(TRACE_DURATIONS_MS.length);

      const ref: ProjectOverviewTracesRef = {
        project,
        traceIds: [...traceIds],
        traceCount: TRACE_DURATIONS_MS.length,
      };
      await testInfo.attach('opik.projectOverviewTraces', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });
      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        try {
          // Explicit: deleting the project does not take its traces, and the
          // run-prefix sweep in global-teardown does not know about them.
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn('[projectOverviewTraces fixture] trace delete warning:', err);
        }
      }
    }
  },

  copyNameTakenView: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    let view: InsightsViewRef | null = null;
    try {
      view = await backendClient.createInsightsView({
        name: COPY_NAME,
        projectId: project.id,
        sections: [
          { id: `${testNamespace}-taken-section-1`, title: `${testNamespace}-taken`, widgets: [] },
        ],
      });
      await use(view);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        await deleteViewOrSweepByName(backendClient, project.id, view, COPY_NAME, 'copyNameTakenView');
      }
    }
  },

  bystanderProjectView: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-bystander-view`;
    let view: InsightsViewRef | null = null;
    try {
      view = await backendClient.createInsightsView({
        name,
        projectId: project.id,
        sections: [
          {
            id: `${testNamespace}-bystander-section-1`,
            title: `${testNamespace}-bystander-section`,
            widgets: [],
          },
        ],
      });
      await use(view);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        await deleteViewOrSweepByName(
          backendClient,
          project.id,
          view,
          name,
          'bystanderProjectView',
        );
      }
    }
  },

  registerInsightsViewCleanup: async ({ backendClient }, use, testInfo) => {
    const ids: string[] = [];
    await use((id: string) => {
      ids.push(id);
    });

    if (shouldLeaveArtifacts(testInfo)) {
      if (ids.length > 0) {
        console.warn(`[registerInsightsViewCleanup] leaving ${ids.join(', ')} for debugging`);
      }
      return;
    }

    for (const id of ids) {
      try {
        await backendClient.deleteInsightsView(id);
      } catch (err) {
        // Keep going: one undeletable view must not orphan the rest, and a
        // cleanup failure must not replace the test's own error.
        console.warn(`[registerInsightsViewCleanup] delete warning for ${id}:`, err);
      }
    }
  },
});

/**
 * Deletes a seeded view, or — when the create never handed back an id — finds it
 * by name in its project and deletes that.
 *
 * The second half is not defensive padding. `insights-views` stamps its own id
 * server-side, so a POST that commits and then loses its response leaves a view
 * that nothing else can find: no cascade reaches it from the project it names,
 * and the run-prefix sweep does not know the collection. The leak would be
 * permanent. (This is the same shape `projectScopedDashboard` uses.)
 */
async function deleteViewOrSweepByName(
  backendClient: {
    deleteInsightsView: (id: string) => Promise<void>;
    listInsightsViews: (args: { projectId?: string }) => Promise<InsightsViewRef[]>;
  },
  projectId: string,
  view: InsightsViewRef | null,
  name: string,
  label: string,
): Promise<void> {
  try {
    if (view !== null) {
      await backendClient.deleteInsightsView(view.id);
      return;
    }
    const orphans = (await backendClient.listInsightsViews({ projectId })).filter(
      (candidate) => candidate.name === name,
    );
    for (const orphan of orphans) {
      await backendClient.deleteInsightsView(orphan.id);
    }
  } catch (err) {
    console.warn(`[${label} fixture] view delete warning:`, err);
  }
}

export { expect } from './emptied-source-versions.fixture';
