import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';

/**
 * Enough traces that the row window is unambiguously smaller than the data.
 *
 * The Logs table windows rows once there are more than 25 of them, and renders
 * roughly a viewport's worth plus overscan — a little under 40 rows at the
 * suite's 1280x720 viewport. Sixty leaves ~20 rows of headroom, so "fewer rows
 * are in the DOM than were seeded" stays true rather than depending on how tall
 * the runner's viewport happens to be.
 */
const TRACE_COUNT = 60;

/**
 * Distinct feedback-score names, each of which the Logs table offers as its own
 * column. Forty-five puts the table past sixty columns in total, well beyond
 * the fifty-column threshold at which the column window engages.
 */
const SCORE_NAME_COUNT = 45;

/** One second between seeded traces, so their order is total and known. */
const TRACE_SPACING_MS = 1_000;

/** How long a seeded write may take to become readable before we give up. */
const INGEST_TIMEOUT_MS = 60_000;
const INGEST_POLL_MS = 500;

export interface WindowedTracesRef {
  /**
   * Every seeded trace id, in the order `GET /v1/private/traces` answers for
   * this project — the same query, and therefore the same order, the Logs table
   * renders from. Read back from the API rather than assumed from the seed
   * order, so the expectation is the app's own answer and not a guess about how
   * it sorts.
   */
  orderedIds: string[];
  /** Each seeded trace's rendered name, keyed by id. */
  nameById: Record<string, string>;
  /** How many traces were seeded — `orderedIds.length`, named for legibility. */
  count: number;
}

export interface WideTracesTableRef {
  /** The distinct feedback-score names every seeded trace carries. */
  scoreNames: string[];
}

export interface WindowedTracesFixtures {
  windowedTraces: WindowedTracesRef;
  wideTracesTable: WideTracesTableRef;
}

/**
 * Poll until the project lists exactly `expectedIds` and nothing else,
 * returning them in the API's order.
 *
 * Both halves matter. Waiting only for a count would accept a partially-landed
 * batch topped up by a stray trace, and the specs built on this fixture assert
 * that the table's rows are a contiguous slice of *this* list — an extra id in
 * it makes every one of those assertions meaningless.
 */
async function waitForTracesListed(
  backendClient: BackendClient,
  projectId: string,
  expectedIds: string[],
): Promise<string[]> {
  const expected = new Set(expectedIds);
  const deadline = Date.now() + INGEST_TIMEOUT_MS;
  let listed: string[] = [];

  while (Date.now() < deadline) {
    listed = await backendClient.listTraceIds({ projectId, size: expectedIds.length + 50 });
    if (listed.length === expected.size && listed.every((id) => expected.has(id))) {
      return listed;
    }
    await new Promise((resolve) => setTimeout(resolve, INGEST_POLL_MS));
  }

  throw new Error(
    `[windowedTraces fixture] project ${projectId} listed ${listed.length} traces after ` +
      `${INGEST_TIMEOUT_MS}ms, expected exactly the ${expected.size} seeded ones`,
  );
}

/** Poll until `traceId` carries all `expected` feedback-score names. */
async function waitForScoresLanded(
  backendClient: BackendClient,
  traceId: string,
  expected: string[],
): Promise<void> {
  const deadline = Date.now() + INGEST_TIMEOUT_MS;
  let seen = 0;

  while (Date.now() < deadline) {
    const trace = await backendClient.getTrace(traceId);
    const names = new Set((trace?.feedbackScores ?? []).map((score) => score.name));
    seen = names.size;
    if (expected.every((name) => names.has(name))) return;
    await new Promise((resolve) => setTimeout(resolve, INGEST_POLL_MS));
  }

  throw new Error(
    `[wideTracesTable fixture] trace ${traceId} carries ${seen} feedback-score names after ` +
      `${INGEST_TIMEOUT_MS}ms, expected ${expected.length}`,
  );
}

export const test = baseTest.extend<WindowedTracesFixtures>({
  /**
   * Sixty traces in the fixture project, seeded in one batch write with
   * caller-minted UUIDv7 ids one second apart.
   *
   * Minting the ids is what makes the seed assertable: the batch write answers
   * 204 with no body, and the backend reads a trace's creation instant out of
   * its id, so spacing the ids is what gives the set a total, known order
   * instead of sixty traces sharing one millisecond.
   *
   * Teardown deletes the traces explicitly. Deleting a project does not cascade
   * to its traces, and `global-teardown`'s run-prefix sweep only knows about
   * experiments, datasets and projects — so without this the run leaves sixty
   * traces behind in the workspace.
   */
  windowedTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const oldest = Date.now() - TRACE_COUNT * TRACE_SPACING_MS;
    const seeds = Array.from({ length: TRACE_COUNT }, (_, index) => {
      const moment = new Date(oldest + index * TRACE_SPACING_MS);
      return {
        id: uuid7(moment),
        name: `${testNamespace}-row-${String(index + 1).padStart(2, '0')}`,
        startTime: moment,
        input: { question: `question ${index + 1}` },
        output: { answer: `answer ${index + 1}` },
      };
    });

    await backendClient.createTracesBatch({ projectName: project.name, traces: seeds });

    const orderedIds = await waitForTracesListed(
      backendClient,
      project.id,
      seeds.map((seed) => seed.id),
    );

    const ref: WindowedTracesRef = {
      orderedIds,
      nameById: Object.fromEntries(seeds.map((seed) => [seed.id, seed.name])),
      count: orderedIds.length,
    };

    await testInfo.attach('opik.windowedTraces', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(orderedIds);
      } catch (err) {
        console.warn(`[windowedTraces fixture] delete warning for ${project.name}:`, err);
      }
    }
  },

  /**
   * The same sixty traces, each carrying forty-five distinct feedback scores.
   *
   * Every distinct score name in a project becomes its own column in the Logs
   * table, so this is how the table is widened past the point where the column
   * window engages — the state the alignment assertions need in order to be
   * testing anything at all.
   *
   * No teardown of its own: feedback scores live on the traces, which the
   * `windowedTraces` fixture this chains from deletes.
   */
  wideTracesTable: async ({ backendClient, project, windowedTraces }, use) => {
    const scoreNames = Array.from(
      { length: SCORE_NAME_COUNT },
      (_, index) => `qa-score-${String(index + 1).padStart(2, '0')}`,
    );

    await backendClient.scoreTracesBatch({
      projectName: project.name,
      scores: windowedTraces.orderedIds.flatMap((traceId) =>
        scoreNames.map((name, index) => ({ traceId, name, value: (index % 10) / 10 })),
      ),
    });

    // Both ends of the batch: the write is chunked, so checking only the first
    // trace would accept a run where a later chunk never landed.
    await waitForScoresLanded(backendClient, windowedTraces.orderedIds[0], scoreNames);
    await waitForScoresLanded(
      backendClient,
      windowedTraces.orderedIds[windowedTraces.count - 1],
      scoreNames,
    );

    await use({ scoreNames });
  },
});

export { expect } from './automation-rules.fixture';
