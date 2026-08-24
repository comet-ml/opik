import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface WideTracesRef {
  /** Every seeded trace id, newest first — the order the Logs table renders. */
  traceIds: string[];
  /** How many traces were seeded. */
  traceCount: number;
  /** Distinct feedback score names, one dynamic column each. */
  scoreNames: string[];
  /** A term that matches exactly `searchMatchIds` by trace name. */
  searchTerm: string;
  /** The trace ids the search term matches, newest first. */
  searchMatchIds: string[];
}

export interface WideTracesFixtures {
  wideTraces: WideTracesRef;
}

/**
 * Sized against the Logs table's virtualization budget (`columns × rows > 3000`,
 * see `getVirtualizationConfig` in `shared/DataTable/utils.tsx`), so one project
 * sits on both sides of it:
 *
 *   default column selection (10 static + one per score name, + the pinned
 *   select column) x 100 rows  -> under the budget, nothing is windowed
 *   every column selected                x 100 rows  -> over it, rows and columns window
 *
 * `SCORE_NAME_COUNT` is what holds that gap open, so the specs assert both sides
 * from the counts the Columns control actually reports rather than trusting the
 * arithmetic here — if the table's static column set drifts far enough to close
 * the gap, they fail saying so instead of quietly stopping exercising windowing.
 */
const TRACE_COUNT = 100;
const SCORE_NAME_COUNT = 14;
const WRITE_CHUNK = 50;

/** Traces are named `<ns>-b<bucket>-<index>`; buckets hold ten traces each. */
const SEARCH_BUCKET = 1;
const BUCKET_SIZE = 10;

/**
 * `TRACE_COUNT` traces in one project, wide enough to window the Logs table once
 * every column is turned on.
 *
 * Ids are minted with an explicit, strictly increasing timestamp rather than
 * left to the server: the table orders on the id's embedded UUIDv7 instant, and
 * a batch write stamps every trace in the same millisecond, which would leave
 * the row order down to the ids' random tails. These specs sweep the table and
 * assert the window advances without skipping a row, so they need to know the
 * order up front.
 */
export const test = baseTest.extend<WideTracesFixtures>({
  wideTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Backdated so the seed sits inside the Logs page's default date window with
    // room to spare, and far from anything else the run creates.
    const firstStamp = Date.now() - 60 * 60 * 1000;

    const seeds = Array.from({ length: TRACE_COUNT }, (_, i) => {
      const index = String(i).padStart(3, '0');
      const startTime = new Date(firstStamp + i * 1000);
      return {
        id: uuid7(startTime),
        projectName: project.name,
        name: `${testNamespace}-b${Math.floor(i / BUCKET_SIZE)}-${index}`,
        startTime,
        endTime: new Date(startTime.getTime() + 500),
        input: { question: `question number ${index}` },
        output: { answer: `answer number ${index}` },
      };
    });

    for (let i = 0; i < seeds.length; i += WRITE_CHUNK) {
      await backendClient.createTracesBatch(seeds.slice(i, i + WRITE_CHUNK));
    }

    const scoreNames = Array.from(
      { length: SCORE_NAME_COUNT },
      (_, i) => `score_${String(i).padStart(2, '0')}`,
    );
    // One trace carries all of them: a score *name* existing in the project is
    // what grows the column, and which rows hold a value for it is irrelevant to
    // how wide the table gets.
    await backendClient.scoreTracesBatch(
      scoreNames.map((name) => ({
        traceId: seeds[0].id,
        projectName: project.name,
        name,
        value: 0.5,
      })),
    );

    // The batch write answers 204 before ClickHouse can serve the rows back, and
    // a UI assertion over a project that is still half-seeded is a test that
    // cannot fail. Block until the API agrees all of them are readable.
    const expectedIds = new Set(seeds.map((s) => s.id));
    const deadline = Date.now() + 60_000;
    for (;;) {
      const listed = await backendClient.listTraceIds({
        projectId: project.id,
        size: TRACE_COUNT + 10,
      });
      if (listed.length === TRACE_COUNT && listed.every((id) => expectedIds.has(id))) break;
      if (Date.now() > deadline) {
        throw new Error(
          `[wideTraces fixture] seeded ${TRACE_COUNT} traces but the API lists ${listed.length} ` +
            `for project ${project.name} after 60s`,
        );
      }
      await new Promise((r) => setTimeout(r, 1000));
    }

    const searchTerm = `-b${SEARCH_BUCKET}-`;
    const matches = seeds.filter((s) => s.name.includes(searchTerm));
    if (matches.length !== BUCKET_SIZE) {
      throw new Error(
        `[wideTraces fixture] search term "${searchTerm}" matches ${matches.length} of the ` +
          `seeded names, expected ${BUCKET_SIZE} — the namespace or the naming scheme changed`,
      );
    }

    const ref: WideTracesRef = {
      // Newest first, which is how the table sorts by default.
      traceIds: seeds.map((s) => s.id).reverse(),
      traceCount: TRACE_COUNT,
      scoreNames,
      searchTerm,
      searchMatchIds: matches.map((s) => s.id).reverse(),
    };
    await testInfo.attach('opik.wideTraces', {
      body: JSON.stringify({ ...ref, projectId: project.id }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      // Traces do not go with the project, and the run-prefix sweep in
      // global-teardown only knows about experiments, datasets and projects.
      try {
        await backendClient.deleteTraces(seeds.map((s) => s.id));
      } catch (err) {
        console.warn(`[wideTraces fixture] trace delete warning for ${project.name}:`, err);
      }
    }
  },
});

export { expect } from './automation-rules.fixture';
