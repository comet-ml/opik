import { expect } from '@playwright/test';
import { test as baseTest } from './orphan-span-tree.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/**
 * The token every matching thread carries in its trace's INPUT and nowhere else.
 *
 * `ThreadDAO`'s search clause is `ilike` over `thread_id`, `id`, `input` and
 * `output` — four columns, two of which are the payload. An input-only marker is
 * what separates "the input column is searched" from "something matched"; see
 * `OUTPUT_MARKER` for the mirror.
 */
export const THREAD_INPUT_MARKER = 'zephyr';

/** The token every matching thread carries in its trace's OUTPUT and nowhere else. */
export const THREAD_OUTPUT_MARKER = 'quetzal';

/** Matching threads, decoys, and so the whole population. */
const MATCH_COUNT = 7;
const DECOY_COUNT = 5;

/** How long the `trace_threads` aggregation may take to catch up with the traces. */
const THREADS_VISIBLE_TIMEOUT_MS = 180_000;

/** Usage on each trace's one llm span, so the thread aggregates something real. */
const SPAN_USAGE = { prompt_tokens: 120, completion_tokens: 45, total_tokens: 165 } as const;

/** One seeded thread: its single trace, and what the thread is for. */
export interface ThreadSearchRowRef {
  /** The producer-chosen thread id, which is also the table row's `data-row-id`. */
  threadId: string;
  traceId: string;
  spanId: string;
  /** The trace's name. Deliberately NOT searchable — see `traceNameOfFirstMatch`. */
  traceName: string;
  /** Whether this thread carries the markers, and so must come back from a search. */
  matches: boolean;
  /** A token in this thread's INPUT alone; resolves to exactly this one thread. */
  inputToken: string;
  /** A token in this thread's OUTPUT alone; resolves to exactly this one thread. */
  outputToken: string;
}

export interface ThreadSearchPopulationRef {
  rows: ThreadSearchRowRef[];
  inputMarker: string;
  outputMarker: string;
  /** Thread ids carrying the markers, sorted — the answer either marker must give. */
  matchingThreadIds: string[];
  /** Every seeded thread id, sorted — the answer an unsearched read must give. */
  allThreadIds: string[];
  /**
   * The full trace name of one matching thread.
   *
   * On the ref as a NEGATIVE control: a trace's `name` is not in the thread
   * search clause, so searching for it must return nothing. That is the cheapest
   * available proof that the matches above came from the payload columns rather
   * than from the search having silently widened to everything the seed wrote.
   */
  traceNameOfFirstMatch: string;
}

export interface ThreadSearchPopulationFixtures {
  threadSearchPopulation: ThreadSearchPopulationRef;
}

export const test = baseTest.extend<ThreadSearchPopulationFixtures>({
  /**
   * 12 threads of one trace each: 7 carrying a marker in their input and a
   * different marker in their output, and 5 decoys carrying neither.
   *
   * Shaped for the thread half of free-text search, which `ThreadDAO` implements
   * with its own clause and which `traces.free-text-search` explicitly does not
   * cover. Four choices make the question answerable:
   *
   *  - **The two markers are on different columns.** A single marker on both
   *    would pass against a clause that had lost either `input` or `output`; one
   *    marker per column means each of the two is asserted separately.
   *  - **Decoys outnumber nothing.** 5 against 7 means neither "returned
   *    everything" nor "returned nothing" can pass as the right answer.
   *  - **Each thread has a token of its own, on each column.** A search for one
   *    is the narrowest real question a user asks — "find the conversation I
   *    know exists" — and its answer is exactly one row, so a scan bounded to
   *    the wrong weeks shows up as an empty table rather than as a short one.
   *  - **The thread ids are asserted to contain neither marker.** The clause
   *    matches `thread_id` and `id` as well as the payload, and the ids are built
   *    from the run prefix and the test's own title — so without this the markers
   *    could match every row for a reason that has nothing to do with the
   *    payload, and the whole seed would prove nothing while passing.
   *
   * One trace per thread rather than several: the subject is which threads come
   * back, and a multi-turn thread would make "7 threads" and "7 traces" two
   * different numbers for no gain. Each trace carries one llm span with usage so
   * the row's aggregates (`number_of_messages`, `duration`,
   * `total_estimated_cost`, `usage`) are all non-trivial and can be asserted
   * present — a searched listing that returned bare rows is as wrong as one that
   * returned the wrong rows.
   *
   * Seeded in two batch writes over REST: 24 sequential writes is the quickest
   * route to the workspace ingestion rate limit, and a rate-limited seed lands
   * partially — which from inside the spec is indistinguishable from a search
   * that lost rows.
   *
   * Teardown deletes the traces, which takes their spans and collapses the
   * `trace_threads` rows with them.
   */
  threadSearchPopulation: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    try {
      const rows: ThreadSearchRowRef[] = [];
      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];
      const base = new Date();

      const plan: Array<{ matches: boolean; index: number }> = [];
      for (let i = 0; i < MATCH_COUNT; i++) plan.push({ matches: true, index: i });
      for (let i = 0; i < DECOY_COUNT; i++) plan.push({ matches: false, index: i });

      plan.forEach((seed, position) => {
        const ordinal = String(position).padStart(2, '0');
        const label = seed.matches ? 'match' : 'decoy';
        const threadId = `${testNamespace}-thread-${label}-${ordinal}`;
        const traceId = uuid7();
        const spanId = uuid7();
        const traceName = `${testNamespace}-trace-${label}-${ordinal}`;
        // Per-thread tokens are built on the shared markers, so a search for a
        // marker is a strict superset of a search for any one token. That is the
        // relationship the spec asserts: the broad term finds all 7, each narrow
        // term finds exactly its 1.
        const inputToken = `${THREAD_INPUT_MARKER}-in-${ordinal}`;
        const outputToken = `${THREAD_OUTPUT_MARKER}-out-${ordinal}`;

        // The decoys' prose deliberately mirrors the matches' sentence structure
        // and differs only by the marker, so the two sets cannot be told apart
        // by anything else a search could key on.
        const input = seed.matches
          ? { prompt: `tell me about the ${inputToken} bird please` }
          : { prompt: `tell me about the other bird please` };
        const output = seed.matches
          ? { answer: `the ${outputToken} is a bird of the cloud forest` }
          : { answer: `the other one is a bird of the cloud forest` };

        const start = new Date(base.getTime() + position * 20);
        traces.push({
          id: traceId,
          name: traceName,
          input,
          output,
          threadId,
          startTime: start,
          // A non-zero span so the thread's `duration` aggregate is a real
          // number rather than null, which is what the spec asserts it is.
          endTime: new Date(start.getTime() + 1_500),
        });
        spans.push({
          id: spanId,
          traceId,
          name: `${traceName}-span`,
          type: 'llm',
          model: 'gpt-4o-mini',
          provider: 'openai',
          usage: { ...SPAN_USAGE },
          input,
          output,
          startTime: start,
          endTime: new Date(start.getTime() + 1_400),
        });
        rows.push({
          threadId,
          traceId,
          spanId,
          traceName,
          matches: seed.matches,
          inputToken,
          outputToken,
        });
      });

      // ---- the fixture proves its own premises, before anything is written ----

      const inputMarker = THREAD_INPUT_MARKER.toLowerCase();
      const outputMarker = THREAD_OUTPUT_MARKER.toLowerCase();

      // The load-bearing assertion. `ilike(thread_id, …)` and `ilike(id, …)` mean
      // the thread id is searched text too, and it is built from the run prefix
      // and the test's own title — neither of which this fixture controls. A
      // marker occurring in one would make every row match for a reason that has
      // nothing to do with the payload.
      for (const row of rows) {
        const id = row.threadId.toLowerCase();
        expect(
          id.includes(inputMarker),
          `thread id '${row.threadId}' must not contain '${THREAD_INPUT_MARKER}' — the search ` +
            'clause matches the thread id, so it would match for the wrong reason',
        ).toBe(false);
        expect(
          id.includes(outputMarker),
          `thread id '${row.threadId}' must not contain '${THREAD_OUTPUT_MARKER}'`,
        ).toBe(false);
      }

      // Assembled from the SAME objects that go on the wire, so it cannot drift
      // from what was actually seeded. Only the two payload columns and the
      // thread id are folded in, because those are the columns the clause reads —
      // a check that also folded in the trace name would be asserting about text
      // the server never looks at.
      const searchableText = (position: number): string =>
        JSON.stringify([
          traces[position].input,
          traces[position].output,
          traces[position].threadId,
        ]).toLowerCase();

      rows.forEach((row, position) => {
        const text = searchableText(position);
        if (row.matches) {
          expect(
            text.includes(inputMarker),
            `matching thread '${row.threadId}' carries the input marker`,
          ).toBe(true);
          expect(
            text.includes(outputMarker),
            `matching thread '${row.threadId}' carries the output marker`,
          ).toBe(true);
          expect(
            JSON.stringify(traces[position].output).toLowerCase().includes(inputMarker),
            `matching thread '${row.threadId}' must carry the input marker in its INPUT only, ` +
              'or the two markers do not test two columns',
          ).toBe(false);
          expect(
            JSON.stringify(traces[position].input).toLowerCase().includes(outputMarker),
            `matching thread '${row.threadId}' must carry the output marker in its OUTPUT only`,
          ).toBe(false);
        } else {
          expect(
            text.includes(inputMarker) || text.includes(outputMarker),
            `decoy thread '${row.threadId}' must carry neither marker anywhere the clause looks`,
          ).toBe(false);
        }
      });

      // Each per-thread token must be unique across the whole seed, or "exactly
      // one thread" is the wrong expected answer for it.
      for (const row of rows.filter((r) => r.matches)) {
        for (const token of [row.inputToken, row.outputToken]) {
          const carriers = rows
            .filter((_, position) => searchableText(position).includes(token.toLowerCase()))
            .map((carrier) => carrier.threadId);
          expect(
            carriers,
            `token '${token}' must occur in exactly one thread's searchable text, or ` +
              '"exactly one thread" is the wrong expected answer for a search on it',
          ).toEqual([row.threadId]);
        }
      }

      const ids = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);
      expect(
        new Set(rows.map((r) => r.threadId)).size,
        'every seeded thread id is distinct',
      ).toBe(rows.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      // Threads are materialised from the traces that share a `thread_id`, on a
      // separate eventually-consistent path from the traces themselves — so the
      // wait is on the THREAD listing, not on the trace one. Polled on the
      // unsearched total: a poll on a searched read would keep retrying the very
      // shortfall this seed exists to detect and then fail as a timeout rather
      // than as a missing thread.
      await expect
        .poll(
          async () =>
            (await backendClient.listThreads({ projectId: project.id, size: 1 })).total,
          {
            message: 'every seeded thread must be aggregated before the spec reads anything',
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(rows.length);

      const sorted = (values: string[]) => [...values].sort();
      const matching = rows.filter((row) => row.matches);

      const ref: ThreadSearchPopulationRef = {
        rows,
        inputMarker: THREAD_INPUT_MARKER,
        outputMarker: THREAD_OUTPUT_MARKER,
        matchingThreadIds: sorted(matching.map((r) => r.threadId)),
        allThreadIds: sorted(rows.map((r) => r.threadId)),
        traceNameOfFirstMatch: matching[0].traceName,
      };

      await testInfo.attach('opik.threadSearchPopulation', {
        body: JSON.stringify(
          {
            projectId: project.id,
            projectName: project.name,
            inputMarker: ref.inputMarker,
            outputMarker: ref.outputMarker,
            counts: { all: ref.allThreadIds.length, matching: ref.matchingThreadIds.length },
            rows: ref.rows,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        try {
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          // Never rethrow from teardown: a cleanup failure must not replace the
          // test's own error.
          console.warn('[threadSearchPopulation fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './orphan-span-tree.fixture';
