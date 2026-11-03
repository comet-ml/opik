import { expect } from '@playwright/test';
import { test as baseTest } from './thread-version-search.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/**
 * The term the searched reads in the spec are driven on.
 *
 * Its own marker again, for the reason `VERSION_MARKER` gives: a marker shared
 * with another fixture turns a cross-project leak into a passing answer.
 */
export const COST_ORDER_MARKER = 'pangolin';

/** Matching threads and decoys, and so the whole population. */
const MATCH_COUNT = 4;
const DECOY_COUNT = 3;

/** How long the `trace_threads` aggregation may take to catch up with the traces. */
const THREADS_VISIBLE_TIMEOUT_MS = 180_000;

/**
 * Token counts per thread, as a multiplier on a base usage.
 *
 * Cost is priced from `usage` against the model's own per-token rates, so a
 * strictly increasing token count gives a strictly increasing cost without the
 * fixture having to know any price. Spread wide (1x to 8x) rather than by ones:
 * `total_estimated_cost` is a float the API serialises at limited precision, and
 * adjacent costs that round to the same rendered value would make a DESC order
 * assertion depend on tie-breaking rather than on the sort.
 *
 * The decoys take multipliers INTERLEAVED with the matches' (see
 * `MULTIPLIER_PLAN`), so the searched set's cost order is not simply the whole
 * project's order with rows removed — a sort that ignored the search entirely
 * would answer a different sequence.
 */
const MATCH_MULTIPLIERS = [1, 8, 3, 6] as const;
const DECOY_MULTIPLIERS = [2, 7, 4] as const;

/** The base usage one multiplier of 1 corresponds to. */
const BASE_USAGE = { prompt_tokens: 400, completion_tokens: 160 } as const;

/**
 * The opening every thread's first message shares, matches and decoys alike.
 *
 * Load-bearing rather than filler prose: a `first_message CONTAINS` filter on it
 * answers the whole project, so composing it with the search shows the two
 * narrowing independently. Without a term both sets share, a filter that had
 * silently swallowed the search would answer the right rows anyway.
 */
const SHARED_FIRST_MESSAGE_TERM = 'tell me about';

export interface ThreadCostOrderRowRef {
  /** The producer-chosen thread id, which is also the table row's `data-row-id`. */
  threadId: string;
  traceId: string;
  spanId: string;
  /** Whether this thread carries the marker, and so must come back from a search. */
  matches: boolean;
  /** This thread's token multiplier, and so its rank in the cost order. */
  multiplier: number;
  /**
   * A token in this thread's trace INPUT, and so in the thread's
   * `first_message` — the column a `first_message CONTAINS` filter reads, which
   * is one of the two ways to reach the non-pushdown query copy.
   */
  firstMessageToken: string;
}

export interface ThreadCostOrderSearchRef {
  rows: ThreadCostOrderRowRef[];
  /** The term every searched read is driven on. */
  marker: string;
  /** Thread ids carrying the marker, sorted — the answer the search must give. */
  matchingThreadIds: string[];
  /** Every seeded thread id, sorted — the answer an unsearched read must give. */
  allThreadIds: string[];
  /**
   * The matching thread ids in STRICTLY DESCENDING cost order.
   *
   * Derived from the multipliers rather than read back from the API: an expected
   * order computed from the same read it is compared against would be
   * circular — it would pass against any order the server happened to answer.
   */
  matchingThreadIdsByCostDesc: string[];
  /** One matching thread whose `first_message` token narrows the search to it. */
  filterTarget: ThreadCostOrderRowRef;
  /**
   * A term EVERY thread's first message carries, matches and decoys alike.
   *
   * The negative control for the composed read: a `first_message` filter on
   * this answers the whole project on its own and the matching threads when the
   * search is added, so the two are shown to narrow independently rather than
   * one of them quietly swallowing the other.
   */
  sharedFirstMessageTerm: string;
}

export interface ThreadCostOrderSearchFixtures {
  threadCostOrderSearch: ThreadCostOrderSearchRef;
}

export const test = baseTest.extend<ThreadCostOrderSearchFixtures>({
  /**
   * Seven single-trace threads — 4 carrying a marker, 3 decoys — whose costs
   * form a strict total order, and whose first messages each carry a token of
   * their own.
   *
   * Shaped for the half of opik#9264 that no existing spec reaches. The PR
   * rewrote the search clause in four copies of the thread queries, and
   * `ThreadDAO.isPagePushdownEligible` decides which pair a read goes through:
   * a page is resolved from a narrow id-only pre-pass unless a sort or filter
   * needs a column that scan cannot compute. `total_estimated_cost` is not in
   * `PAGE_PUSHDOWN_SORT_FIELDS` and `first_message` IS in
   * `PAGE_PUSHDOWN_EXCLUDED_THREAD_FILTERS`, so those two parameters are the
   * only ways in from outside — and `thread-free-text-search.spec.ts` drives
   * neither, so it has only ever exercised the pushdown copy. The taxonomy note
   * for `threads.free-text-search` already names search-plus-a-filter-chip as
   * uncovered.
   *
   * Three choices make a divergence between the copies visible:
   *
   *  - **Decoy multipliers interleave with the matches'.** The searched set's
   *    cost order is therefore NOT the project's order with rows deleted, so a
   *    sort that ran before the search — or over the wrong population — answers
   *    a different sequence rather than the same one shorter.
   *  - **Costs are spread 1x to 8x, not by ones.** `total_estimated_cost` comes
   *    back as a float; adjacent costs that rendered identically would make the
   *    order assertion a test of tie-breaking.
   *  - **Each thread's first message carries a unique token.** That is what lets
   *    a `first_message CONTAINS` filter compose with the search and narrow to
   *    exactly one known row — the intersection being strictly smaller than
   *    either side, so neither side alone can satisfy it.
   *
   * One trace per thread: the subject is which threads come back and in what
   * order, and a multi-turn thread would additionally drag in the aggregate
   * narrowing the exploration filed separately as a pre-existing defect.
   *
   * Teardown deletes the traces, which takes their spans and collapses the
   * `trace_threads` rows with them.
   */
  threadCostOrderSearch: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    try {
      const marker = COST_ORDER_MARKER.toLowerCase();

      const plan: Array<{ matches: boolean; multiplier: number }> = [
        ...MATCH_MULTIPLIERS.map((multiplier) => ({ matches: true, multiplier })),
        ...DECOY_MULTIPLIERS.map((multiplier) => ({ matches: false, multiplier })),
      ];
      expect(plan.length, 'the plan covers every seeded thread').toBe(
        MATCH_COUNT + DECOY_COUNT,
      );

      const rows: ThreadCostOrderRowRef[] = [];
      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];
      const base = new Date();

      plan.forEach((seed, position) => {
        const ordinal = String(position).padStart(2, '0');
        const label = seed.matches ? 'match' : 'decoy';
        const threadId = `${testNamespace}-thread-${label}-${ordinal}`;
        const traceId = uuid7();
        const spanId = uuid7();
        const firstMessageToken = `${testNamespace}-fm-${ordinal}`;

        // The decoys' prose mirrors the matches' sentence structure and differs
        // only by the marker, so the two sets cannot be told apart by anything
        // else a search could key on.
        const subject = seed.matches ? marker : 'the other animal';
        const input = {
          prompt: `${SHARED_FIRST_MESSAGE_TERM} ${subject} please [${firstMessageToken}]`,
        };
        const output = { answer: `${subject} is a scaly creature of the dry woodland` };

        const start = new Date(base.getTime() + position * 20);
        traces.push({
          id: traceId,
          name: `${testNamespace}-trace-${label}-${ordinal}`,
          input,
          output,
          threadId,
          startTime: start,
          endTime: new Date(start.getTime() + 1_500),
        });
        spans.push({
          id: spanId,
          traceId,
          name: `${testNamespace}-span-${label}-${ordinal}`,
          type: 'llm',
          model: 'gpt-4o-mini',
          provider: 'openai',
          usage: {
            prompt_tokens: BASE_USAGE.prompt_tokens * seed.multiplier,
            completion_tokens: BASE_USAGE.completion_tokens * seed.multiplier,
            total_tokens:
              (BASE_USAGE.prompt_tokens + BASE_USAGE.completion_tokens) * seed.multiplier,
          },
          input,
          output,
          startTime: start,
          endTime: new Date(start.getTime() + 1_400),
        });
        rows.push({
          threadId,
          traceId,
          spanId,
          matches: seed.matches,
          multiplier: seed.multiplier,
          firstMessageToken,
        });
      });

      // ---- the fixture proves its own premises, before anything is written ----

      // The clause matches `thread_id` and `id` as well as the payload, and the
      // ids are built from the run prefix and the test's own title, so a marker
      // occurring in one would make every row match for the wrong reason.
      for (const row of rows) {
        expect(
          row.threadId.toLowerCase().includes(marker),
          `thread id '${row.threadId}' must not contain '${marker}'`,
        ).toBe(false);
        expect(
          row.firstMessageToken.toLowerCase().includes(marker),
          `the first-message token '${row.firstMessageToken}' must not contain '${marker}'`,
        ).toBe(false);
      }

      // Assembled from the SAME objects that go on the wire, folding in only the
      // columns the clause reads.
      const searchableText = (position: number): string =>
        JSON.stringify([
          traces[position].input,
          traces[position].output,
          traces[position].threadId,
        ]).toLowerCase();

      rows.forEach((row, position) => {
        expect(
          searchableText(position).includes(marker),
          `thread '${row.threadId}' must ${row.matches ? '' : 'not '}carry the marker`,
        ).toBe(row.matches);
      });

      // Every multiplier distinct, over the WHOLE population: a decoy sharing a
      // matching thread's cost would let a sort that ignored the search answer
      // the right sequence by coincidence.
      const multipliers = rows.map((r) => r.multiplier);
      expect(
        new Set(multipliers).size,
        'every seeded thread has a distinct cost multiplier, so the cost order is strict',
      ).toBe(multipliers.length);

      // And the interleave: the matches must not occupy a contiguous block of
      // the project's cost order, or "the searched order" and "the project's
      // order, shortened" are the same sequence.
      const projectOrderDesc = [...rows].sort((a, b) => b.multiplier - a.multiplier);
      const matchPositions = projectOrderDesc
        .map((row, index) => ({ row, index }))
        .filter(({ row }) => row.matches)
        .map(({ index }) => index);
      expect(
        Math.max(...matchPositions) - Math.min(...matchPositions) + 1,
        'the matching threads must be interleaved with decoys in the project cost order',
      ).toBeGreaterThan(MATCH_COUNT);

      // The shared opener must reach EVERY thread, and must not be a second
      // spelling of the marker — otherwise the composed read's negative control
      // is asserting the same thing twice.
      expect(
        SHARED_FIRST_MESSAGE_TERM.toLowerCase().includes(marker),
        `the shared opener must not contain '${marker}'`,
      ).toBe(false);
      rows.forEach((row, position) => {
        expect(
          String(
            (traces[position].input as { prompt?: string }).prompt ?? '',
          ).toLowerCase().includes(SHARED_FIRST_MESSAGE_TERM),
          `thread '${row.threadId}' must carry the shared opener in its first message`,
        ).toBe(true);
      });

      // Each first-message token must reach exactly one thread, or a
      // `first_message CONTAINS` filter on it has the wrong expected answer.
      for (const row of rows) {
        const carriers = rows
          .filter((_, position) =>
            searchableText(position).includes(row.firstMessageToken.toLowerCase()),
          )
          .map((carrier) => carrier.threadId);
        expect(
          carriers,
          `first-message token '${row.firstMessageToken}' must occur in exactly one thread`,
        ).toEqual([row.threadId]);
      }

      const ids = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      await expect
        .poll(
          async () => (await backendClient.listThreads({ projectId: project.id, size: 1 })).total,
          {
            message: 'every seeded thread must be aggregated before the spec reads anything',
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(rows.length);

      const sorted = (values: string[]) => [...values].sort();
      const matching = rows.filter((row) => row.matches);

      const ref: ThreadCostOrderSearchRef = {
        rows,
        marker,
        matchingThreadIds: sorted(matching.map((r) => r.threadId)),
        allThreadIds: sorted(rows.map((r) => r.threadId)),
        matchingThreadIdsByCostDesc: [...matching]
          .sort((a, b) => b.multiplier - a.multiplier)
          .map((r) => r.threadId),
        // The middle of the cost order rather than either end, so a filtered
        // read that silently kept the unfiltered page's first or last row
        // cannot land on the right answer.
        filterTarget: matching.find((r) => r.multiplier === 3)!,
        sharedFirstMessageTerm: SHARED_FIRST_MESSAGE_TERM,
      };

      await testInfo.attach('opik.threadCostOrderSearch', {
        body: JSON.stringify(
          {
            projectId: project.id,
            projectName: project.name,
            marker: ref.marker,
            rows: ref.rows,
            matchingByCostDesc: ref.matchingThreadIdsByCostDesc,
            filterTarget: ref.filterTarget,
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
          console.warn('[threadCostOrderSearch fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './thread-version-search.fixture';
