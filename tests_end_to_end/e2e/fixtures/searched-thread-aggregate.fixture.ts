import { expect } from '@playwright/test';
import { test as baseTest } from './numeric-assertion-suite.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/**
 * The term the searched reads are driven on, carried by the MIDDLE turn of the
 * subject thread and by nothing else in the project.
 *
 * Its own marker rather than one shared with `thread-search-population`,
 * `thread-version-search` or `thread-cost-order-search`: the workspace is
 * shared, and a marker two fixtures both seed turns a cross-project leak into a
 * passing answer.
 */
export const AGGREGATE_MARKER = 'quagga';

/** How long the `trace_threads` aggregation may take to catch up with the traces. */
const THREADS_VISIBLE_TIMEOUT_MS = 180_000;

/**
 * Per-turn token usage for the subject thread, as `[prompt, completion]`.
 *
 * Chosen so every SUBSET of the three turns sums to a different number — 110,
 * 220, 440, 330, 550, 660, 770 — on the total, the prompt and the completion
 * column alike. That is the whole discriminating property of this fixture: the
 * pre-fix answer for a searched row was "the matching trace's aggregate" and
 * the post-fix answer is "the whole conversation's", so the two must not be
 * able to coincide. With a flat 100/100/100 both answers would be plausible
 * numbers and only the message count would separate them; with 110/220/330 the
 * first two turns and the third would sum alike, so a clause that kept the
 * wrong two traces could still answer 330.
 */
const SUBJECT_USAGE: ReadonlyArray<readonly [number, number]> = [
  [80, 30],
  [160, 60],
  [320, 120],
];

/** Which turn of the subject thread carries the marker (0-based). */
const MARKED_TURN = 1;

/** Per-turn usage for the decoy, whose own aggregate must never be the answer. */
const DECOY_USAGE: ReadonlyArray<readonly [number, number]> = [
  [15, 5],
  [25, 7],
];

/** One turn of a seeded conversation, and the usage its llm span carries. */
export interface SearchedThreadTurnRef {
  traceId: string;
  spanId: string;
  /** 0-based position in the conversation. */
  turn: number;
  /** Whether this turn's input carries {@link AGGREGATE_MARKER}. */
  carriesMarker: boolean;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  /** The exact `input` object written for this turn. */
  input: Record<string, unknown>;
  /** The exact `output` object written for this turn. */
  output: Record<string, unknown>;
  startTime: Date;
  endTime: Date;
}

export interface SearchedThreadRef {
  threadId: string;
  turns: SearchedThreadTurnRef[];
  /** 2 per turn — the Threads view counts an input and an output message each. */
  expectedMessageCount: number;
  /** The sum of every turn's `total_tokens`, which is the row's own answer. */
  expectedTotalTokens: number;
  expectedPromptTokens: number;
  expectedCompletionTokens: number;
}

export interface SearchedThreadAggregateRef {
  /** The 3-turn conversation whose middle turn alone carries the marker. */
  subject: SearchedThreadRef;
  /** A multi-turn conversation carrying the marker nowhere. */
  decoy: SearchedThreadRef;
  /** The term every searched read below is driven on. */
  marker: string;
  /** Both seeded thread ids, sorted — the answer an unsearched read must give. */
  allThreadIds: string[];
  /**
   * The aggregate the MATCHING TRACE alone would produce, which is the answer
   * the pre-fix query gave.
   *
   * On the ref so the spec can assert the row is NOT this, by name. Asserting
   * only "the row equals the whole thread" would also pass if the two happened
   * to agree; stating the rejected answer explicitly is what makes the seed's
   * distinct-subset-sums property load-bearing rather than decorative.
   */
  matchingTraceOnly: {
    messageCount: number;
    totalTokens: number;
  };
}

export interface SearchedThreadAggregateFixtures {
  searchedThreadAggregate: SearchedThreadAggregateRef;
}

/**
 * A 3-turn conversation whose MIDDLE turn alone carries a search marker, plus a
 * 2-turn decoy carrying it nowhere — the shape opik#9304 (OPIK-9051) fixed and
 * the one no spec in the estate can reach.
 *
 * `thread-free-text-search.spec.ts` has a step named "Every searched row still
 * carries its aggregates", but its fixture seeds ONE trace per thread in as many
 * words, so the matching trace IS the whole thread and the pre- and post-fix
 * queries answer identically. The bug only exists where SOME of a thread's
 * traces match: before 9304 `ThreadDAO`'s search selected trace ids and the
 * aggregate was then computed over that subset, so a searched row reported one
 * turn's cost, tokens, duration and endpoint messages.
 *
 * Three properties make the row's aggregate a finding rather than a tautology:
 *
 *  - **The marker is on the middle turn only.** Not the first and not the last,
 *    so a row that collapsed to the matching trace differs from the whole
 *    thread's row in `first_message` AND `last_message` — the two columns a
 *    reader would notice last, and the ones a narrowed aggregate changes most
 *    visibly.
 *  - **Every subset of the turns sums to a different token count.** So "the
 *    matching trace", "the matching trace and its neighbour" and "the whole
 *    conversation" are three different numbers, and no wrong subset can answer
 *    the right total by arithmetic coincidence.
 *  - **The decoy is multi-turn too.** A single-trace decoy would make "the
 *    search narrowed to one thread" and "the search kept whole threads" the
 *    same observation; this way the unsearched baseline has two rows whose
 *    aggregates differ, and the searched read has to pick one of them WHOLE.
 *
 * Seeded through two batch writes (`/traces/batch`, then `/spans/batch`) rather
 * than the SDK: the subject needs its turns' start and end times pinned so the
 * thread's `duration`, `start_time` and `end_time` are predictable, and the
 * marker has to sit on exactly one turn's payload.
 *
 * Teardown deletes the traces, which takes their spans and collapses the
 * `trace_threads` rows with them. Nothing else is created — no environment, no
 * dataset — so there is nothing the run-prefix sweep in `global-teardown.ts`
 * could miss.
 */
export const test = baseTest.extend<SearchedThreadAggregateFixtures>({
  searchedThreadAggregate: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    try {
      const marker = AGGREGATE_MARKER.toLowerCase();
      const subjectThreadId = `${testNamespace}-thread-subject`;
      const decoyThreadId = `${testNamespace}-thread-decoy`;

      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];
      const base = new Date();

      /**
       * Build one conversation. Turns are spaced 4 seconds apart and each runs
       * for 1.5s, so the thread's `duration` is the span from the first turn's
       * start to the last turn's end and no two turns' windows overlap — which
       * is what makes "the whole thread's duration" and "one turn's duration"
       * different numbers rather than nearly-equal floats.
       */
      const buildThread = (
        threadId: string,
        usage: ReadonlyArray<readonly [number, number]>,
        markedTurn: number | null,
        offsetMs: number,
      ): SearchedThreadRef => {
        const turns: SearchedThreadTurnRef[] = usage.map(
          ([promptTokens, completionTokens], turn) => {
            const carriesMarker = markedTurn === turn;
            const startTime = new Date(base.getTime() + offsetMs + turn * 4_000);
            const endTime = new Date(startTime.getTime() + 1_500);
            // The unmarked turns mirror the marked one's sentence structure and
            // differ only by the marker, so the two cannot be told apart by
            // anything else a free-text clause could key on.
            const subject = carriesMarker ? marker : 'the other animal';
            return {
              traceId: uuid7(),
              spanId: uuid7(),
              turn,
              carriesMarker,
              promptTokens,
              completionTokens,
              totalTokens: promptTokens + completionTokens,
              input: { prompt: `turn ${turn + 1}: tell me about ${subject} please` },
              output: { answer: `turn ${turn + 1}: ${subject} lives on the open grassland` },
              startTime,
              endTime,
            };
          },
        );

        for (const turn of turns) {
          traces.push({
            id: turn.traceId,
            name: `${threadId}-turn-${turn.turn + 1}`,
            input: turn.input,
            output: turn.output,
            threadId,
            startTime: turn.startTime,
            endTime: turn.endTime,
          });
          spans.push({
            id: turn.spanId,
            traceId: turn.traceId,
            name: `${threadId}-span-${turn.turn + 1}`,
            type: 'llm',
            model: 'gpt-4o-mini',
            provider: 'openai',
            usage: {
              prompt_tokens: turn.promptTokens,
              completion_tokens: turn.completionTokens,
              total_tokens: turn.totalTokens,
            },
            input: turn.input,
            output: turn.output,
            startTime: turn.startTime,
            endTime: new Date(turn.endTime.getTime() - 100),
          });
        }

        return {
          threadId,
          turns,
          expectedMessageCount: turns.length * 2,
          expectedTotalTokens: turns.reduce((sum, t) => sum + t.totalTokens, 0),
          expectedPromptTokens: turns.reduce((sum, t) => sum + t.promptTokens, 0),
          expectedCompletionTokens: turns.reduce((sum, t) => sum + t.completionTokens, 0),
        };
      };

      const subject = buildThread(subjectThreadId, SUBJECT_USAGE, MARKED_TURN, 0);
      // Offset so the decoy's turns interleave with neither the subject's
      // start nor its end: a row that aggregated across threads would then
      // report a window neither thread has.
      const decoy = buildThread(decoyThreadId, DECOY_USAGE, null, 1_000);

      // ---- the fixture proves its own premises, before anything is written ----

      // `ThreadDAO`'s clause `ilike`s the thread's `thread_id` and `id` as well
      // as its traces' payloads, and the ids are built from the run prefix and
      // the test's own title — so a marker occurring in one would make the
      // thread match for a reason that has nothing to do with the payload, and
      // the whole spec would pass over a broken clause.
      for (const threadId of [subjectThreadId, decoyThreadId]) {
        expect(
          threadId.toLowerCase().includes(marker),
          `thread id '${threadId}' must not contain '${marker}'`,
        ).toBe(false);
      }

      // Assembled from the SAME objects that go on the wire, folding in only
      // the columns the clause reads.
      const searchableTurnText = (turn: SearchedThreadTurnRef): string =>
        JSON.stringify([turn.input, turn.output]).toLowerCase();

      expect(
        subject.turns.filter((t) => searchableTurnText(t).includes(marker)).map((t) => t.turn),
        `exactly turn ${MARKED_TURN + 1} of the subject thread carries '${marker}'`,
      ).toEqual([MARKED_TURN]);
      expect(
        subject.turns.length,
        'the marked turn is neither the first nor the last, so a narrowed aggregate ' +
          'differs in first_message and last_message too',
      ).toBeGreaterThan(MARKED_TURN + 1);
      expect(MARKED_TURN, 'and the marked turn is not the first').toBeGreaterThan(0);
      expect(
        decoy.turns.filter((t) => searchableTurnText(t).includes(marker)),
        'no turn of the decoy carries the marker',
      ).toEqual([]);
      expect(
        decoy.turns.length,
        'the decoy is multi-turn too, so it has an aggregate of its own to be confused with',
      ).toBeGreaterThan(1);

      // Distinct subset sums, asserted rather than trusted to the constants
      // above: this is the property that makes a wrong subset of turns
      // unable to answer the right total.
      const subsetSums = (values: number[]): number[] =>
        values.reduce<number[]>((sums, value) => [...sums, ...sums.map((s) => s + value)], [0]);
      for (const [column, values] of [
        ['total_tokens', subject.turns.map((t) => t.totalTokens)],
        ['prompt_tokens', subject.turns.map((t) => t.promptTokens)],
        ['completion_tokens', subject.turns.map((t) => t.completionTokens)],
      ] as const) {
        const sums = subsetSums([...values]);
        expect(
          new Set(sums).size,
          `every subset of the subject's turns sums to a different ${column}, so no ` +
            'wrong subset can answer the whole thread\'s number',
        ).toBe(sums.length);
      }
      expect(
        subject.expectedTotalTokens,
        'and the whole thread differs from the matching turn alone',
      ).not.toBe(subject.turns[MARKED_TURN].totalTokens);
      expect(
        subject.expectedMessageCount,
        'as does its message count',
      ).not.toBe(2);

      const ids = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      // Threads are materialised from the traces that share a `thread_id`, on a
      // separate eventually-consistent path. Polled on the UNSEARCHED total so a
      // seed that lost a thread fails here, rather than later as a short
      // searched answer the spec would report as the product's fault.
      await expect
        .poll(
          async () => (await backendClient.listThreads({ projectId: project.id, size: 10 })).total,
          {
            message: 'both seeded threads must be aggregated before the spec reads anything',
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(2);

      // And that each thread aggregated ALL of its turns before any search is
      // asked about. Without this, a subject row that was still mid-aggregation
      // would make the spec's "the search did not narrow the aggregate" step
      // fail for a reason that is not the product's.
      await expect
        .poll(
          async () => {
            const answer = await backendClient.listThreads({ projectId: project.id, size: 10 });
            return answer.threads
              .map((row) => `${row.id}=${row.numberOfMessages}`)
              .sort()
              .join(',');
          },
          {
            message:
              'each thread must report every one of its own turns before the spec reads it — ' +
              'an aggregate still catching up is not the narrowing this spec is about',
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(
          [
            `${decoy.threadId}=${decoy.expectedMessageCount}`,
            `${subject.threadId}=${subject.expectedMessageCount}`,
          ]
            .sort()
            .join(','),
        );

      const ref: SearchedThreadAggregateRef = {
        subject,
        decoy,
        marker,
        allThreadIds: [subject.threadId, decoy.threadId].sort(),
        matchingTraceOnly: {
          messageCount: 2,
          totalTokens: subject.turns[MARKED_TURN].totalTokens,
        },
      };

      await testInfo.attach('opik.searchedThreadAggregate', {
        body: JSON.stringify(
          {
            projectId: project.id,
            projectName: project.name,
            marker: ref.marker,
            subject: ref.subject,
            decoy: ref.decoy,
            matchingTraceOnly: ref.matchingTraceOnly,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        try {
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn('[searchedThreadAggregate fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './numeric-assertion-suite.fixture';
