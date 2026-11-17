import { expect } from '@playwright/test';
import { test as baseTest } from './pass-criteria-suite.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One of the three projects, and the span population it holds. */
export interface SpansEmptyStateProjectRef {
  id: string;
  name: string;
  /** The span this project holds, or `null` when it holds none. */
  spanId: string | null;
}

export interface SpansEmptyStateProjectsRef {
  /** Holds one `source=sdk` LLM span — the only one of the three a Logs read shows. */
  sdkLogged: SpansEmptyStateProjectRef;
  /** Holds nothing at all. */
  empty: SpansEmptyStateProjectRef;
  /** Holds one `source=experiment` LLM span and no sdk span. */
  nonSdkOnly: SpansEmptyStateProjectRef;
  /** The Logs date-range preset the seeded spans are guaranteed to sit inside. */
  timeRangePreset: string;
  /**
   * A search term that matches nothing in `sdkLogged`, for the one state the
   * other three projects cannot reach: a project that HAS spans whose current
   * read returned none.
   */
  unmatchableSearch: string;
}

export interface SpansEmptyStateProjectsFixtures {
  spansEmptyStateProjects: SpansEmptyStateProjectsRef;
}

/**
 * The window the spec opens the Spans tab with.
 *
 * Stated rather than inherited, for the reason `gotoSpans` documents: the range
 * is persisted per project in localStorage as well as in the URL. A week
 * comfortably contains a population seeded at `now` and cannot roll over
 * mid-test.
 */
const TIME_RANGE_PRESET = 'past7days';

/** How long a seeded span may take to become queryable. */
const QUERYABLE_TIMEOUT_MS = 90_000;

/**
 * Three projects that put the Logs Spans tab into each of its distinct
 * empty-state answers — the seed for opik#9599 (OPIK-9219).
 *
 * 9599 bounded `GET /v1/private/spans/exists` to the weeks its project
 * actually holds rows in, and that probe is the ONLY input to the Spans tab's
 * choice between two visually different empty states: `showEmptyState` is
 * `!hasProjectData && rows.length === 0`, where `hasProjectData` is this probe
 * scoped to `source=sdk`. A probe that wrongly answers `false` therefore shows
 * the SDK onboarding panel — "No spans yet", Quickstart guide — to someone
 * whose project is full of spans, which reads as data loss rather than as a
 * read that matched nothing.
 *
 * ## Why three projects and not one
 *
 * Each is a different ANSWER, and no two of them can be expressed by the same
 * project:
 *
 *  - `sdkLogged` — one `source=sdk` span. The probe must say yes, and the
 *    table must render the row. This is the control for the other two: without
 *    it, "the empty state appeared" is equally true of a build whose Spans read
 *    is broken for every project.
 *  - `empty` — nothing. The probe must say no, and the onboarding panel is the
 *    CORRECT answer here. This is the state the other two must not be confused
 *    with.
 *  - `nonSdkOnly` — one `source=experiment` span and no sdk span. The
 *    discriminating case, and the reason the probe takes a `source` at all:
 *    `source=sdk` must say no while an unscoped probe says yes, so the two
 *    questions are observably different over one project. The page is then
 *    expected to show the onboarding panel, which `TracesSpansTab`'s own
 *    comment says is deliberate — its listing is `logsSource: LOGS_SOURCE.sdk`,
 *    so a project whose only data is experiment/playground/optimization/
 *    evaluator traffic has nothing to show a user who has not logged anything
 *    themselves.
 *
 * `sdkLogged` is the `project` fixture and `empty` is `secondProject`, reused
 * rather than re-seeded; only the third is new here, because nothing in
 * `fixtures/` seeds a project whose only span is non-sdk. Note the spans must
 * go to DIFFERENT projects, so a single-project fixture cannot express this at
 * all: the probe is per project.
 *
 * ## Why the spans are written over REST
 *
 * `source` is the variable, and the bridge cannot set it — the Python SDK
 * always emits `source=sdk`. The same reason `evaluatorSourceTraces` seeds that
 * way. Seeding a real experiment instead would work but drags in a dataset, an
 * experiment and its items for a fact about one column.
 *
 * ## What the fixture proves before the browser opens
 *
 * That each project really is in the state its name claims, read back through
 * the probe itself. A fixture whose `nonSdkOnly` span had silently landed as
 * `sdk` would make the UI assertion over it fail for a reason that has nothing
 * to do with the page; one whose span had not landed at all would make it PASS
 * while testing nothing, because an empty project shows the onboarding panel
 * too. That second failure mode is the one worth guarding: it reads as
 * coverage forever.
 *
 * Teardown deletes the third project and its trace. The other two are owned by
 * the fixtures they come from, and `global-teardown.ts`'s prefix sweep knows
 * about projects but not about traces.
 */
export const test = baseTest.extend<SpansEmptyStateProjectsFixtures>({
  spansEmptyStateProjects: async (
    { sdkClient, backendClient, project, secondProject, testNamespace },
    use,
    testInfo,
  ) => {
    const nonSdkName = `${testNamespace}-proj-nonsdk`;
    let nonSdkProjectId: string | null = null;
    const traceIds: string[] = [];

    try {
      const sdkTraceId = uuid7();
      const sdkSpanId = uuid7();
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: sdkTraceId,
        projectName: project.name,
        name: `${testNamespace}-sdk-trace`,
        source: 'sdk',
        input: { question: 'sdk-logged question' },
        output: { answer: 'sdk-logged answer' },
        startTime: now,
        endTime: now,
      });
      traceIds.push(sdkTraceId);
      await backendClient.createSpansBatch({
        projectName: project.name,
        spans: [
          {
            id: sdkSpanId,
            traceId: sdkTraceId,
            name: `${testNamespace}-sdk-span`,
            type: 'llm',
            source: 'sdk',
            startTime: now,
            endTime: now,
          },
        ],
      });

      const nonSdk = await sdkClient.python.createProject({ name: nonSdkName });
      nonSdkProjectId = nonSdk.id;
      const nonSdkTraceId = uuid7();
      const nonSdkSpanId = uuid7();
      await backendClient.createTraceWithSource({
        id: nonSdkTraceId,
        projectName: nonSdkName,
        name: `${testNamespace}-experiment-trace`,
        source: 'experiment',
        input: { question: 'experiment question' },
        output: { answer: 'experiment answer' },
        startTime: now,
        endTime: now,
      });
      traceIds.push(nonSdkTraceId);
      await backendClient.createSpansBatch({
        projectName: nonSdkName,
        spans: [
          {
            id: nonSdkSpanId,
            traceId: nonSdkTraceId,
            name: `${testNamespace}-experiment-span`,
            type: 'llm',
            source: 'experiment',
            startTime: now,
            endTime: now,
          },
        ],
      });

      // Each project is in the state its name claims, read through the probe
      // the page itself reads. Polled because ingestion is eventually
      // consistent; the `false` expectations are polled too, so a seed that
      // merely had not landed yet cannot satisfy them by arriving late.
      await expect
        .poll(() => backendClient.spansExist({ projectId: project.id, source: 'sdk' }), {
          message: 'the sdk-logged project must report an sdk span before anything is asserted',
          timeout: QUERYABLE_TIMEOUT_MS,
          intervals: [1_000, 2_000, 5_000],
        })
        .toBe(true);
      await expect
        .poll(() => backendClient.spansExist({ projectId: nonSdk.id }), {
          message: 'the non-sdk project must report a span under SOME source',
          timeout: QUERYABLE_TIMEOUT_MS,
          intervals: [1_000, 2_000, 5_000],
        })
        .toBe(true);

      expect(
        await backendClient.spansExist({ projectId: nonSdk.id, source: 'sdk' }),
        `project '${nonSdkName}' holds one source=experiment span and must report NO sdk span — ` +
          'if this is true the seed landed as sdk and the spec below would assert nothing',
      ).toBe(false);
      expect(
        await backendClient.spansExist({ projectId: secondProject.id }),
        `project '${secondProject.name}' is seeded with nothing and must report no span under ` +
          'any source',
      ).toBe(false);

      const ref: SpansEmptyStateProjectsRef = {
        sdkLogged: { id: project.id, name: project.name, spanId: sdkSpanId },
        empty: { id: secondProject.id, name: secondProject.name, spanId: null },
        nonSdkOnly: { id: nonSdk.id, name: nonSdkName, spanId: nonSdkSpanId },
        timeRangePreset: TIME_RANGE_PRESET,
        // Built from the run namespace so it cannot collide with anything in
        // the shared workspace, and suffixed so it cannot match this run's own
        // seed either.
        unmatchableSearch: `${testNamespace}-no-span-matches-this`,
      };

      await testInfo.attach('opik.spansEmptyStateProjects', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        for (const traceId of traceIds) {
          try {
            await backendClient.deleteTraces([traceId]);
          } catch (err) {
            // Never rethrow from teardown: a cleanup failure must not replace
            // the test's own error.
            console.warn(`[spansEmptyStateProjects fixture] could not delete trace ${traceId}:`, err);
          }
        }
        if (nonSdkProjectId) {
          try {
            await backendClient.deleteProject(nonSdkProjectId);
          } catch (err) {
            console.warn(
              `[spansEmptyStateProjects fixture] delete warning for ${nonSdkName}:`,
              err,
            );
          }
        }
      }
    }
  },
});

export { expect } from './pass-criteria-suite.fixture';
