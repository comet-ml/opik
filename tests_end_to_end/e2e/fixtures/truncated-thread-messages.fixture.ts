import { expect } from '@playwright/test';
import { test as baseTest } from './all-dataset-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/**
 * The `truncation_threshold` the traces table defaults to, in characters of the
 * stored JSON.
 *
 * `ResponseFormattingConfig.truncationSize = 10001`, and the column is
 * `MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1,
 * truncation_threshold), input)` — so the comparison is `>=` and a payload AT
 * the threshold is already truncated, to exactly this many characters.
 *
 * Exported so the spec can assert the cut landed here rather than merely that
 * something was shortened: a build that truncated to some other length would
 * still satisfy "shorter than what was written".
 */
export const THREAD_TRUNCATION_THRESHOLD = 10001;

/** Which turn of each conversation is the first and which the last. */
const TURNS_PER_THREAD = 3;

/**
 * How far past the threshold an over-long payload is seeded.
 *
 * Comfortably clear of it rather than exactly on it: the stored string is the
 * serialised JSON, so its length includes the keys and the quoting, and a seed
 * pinned to the threshold by arithmetic would flip to the untruncated branch on
 * any change to the payload's shape. The fixture asserts the length it actually
 * produced, so the margin costs nothing.
 */
const OVERSIZE_MARGIN = 2_000;

/**
 * Markers naming the thread, the turn and the side each payload belongs to.
 *
 * The whole point of the fixture: `first_message` must be turn 1's INPUT and
 * `last_message` turn 3's OUTPUT, so every one of the twelve payloads a
 * three-turn conversation carries has to be individually identifiable. A cell
 * taken from the wrong turn then names the turn it came from instead of merely
 * being "not what we expected".
 *
 * No two markers are a prefix or a substring of another (the turn index is
 * always followed by `-`), so `not.toContain` means what it says.
 */
const marker = (thread: string, turn: number, side: 'IN' | 'OUT'): string =>
  `TRUNCFL-${thread}-T${turn}-${side}`;

/** One turn of a seeded conversation. */
export interface TruncatedThreadTurnRef {
  /** 1-based, oldest first — the number the markers carry. */
  turn: number;
  traceId: string;
  spanId: string;
  inputMarker: string;
  outputMarker: string;
  /** `length()` of the stored `input` JSON, i.e. what `input_length` will be. */
  inputLength: number;
  /** `length()` of the stored `output` JSON, i.e. what `output_length` will be. */
  outputLength: number;
  startTime: Date;
  endTime: Date;
}

export interface TruncatedThreadRef {
  /** Stable handle the spec looks this conversation up by. */
  key: 'first-oversize' | 'last-oversize' | 'all-short';
  threadId: string;
  turns: TruncatedThreadTurnRef[];
  /** Whether turn 1's input is at or over the threshold. */
  firstMessageTruncated: boolean;
  /** Whether turn 3's output is at or over the threshold. */
  lastMessageTruncated: boolean;
  /** The sum of the conversation's span costs — the cost-sorted read's key. */
  totalEstimatedCost: number;
}

export interface TruncatedThreadMessagesRef {
  projectId: string;
  projectName: string;
  threads: TruncatedThreadRef[];
  /** Every seeded thread id, sorted — the answer an unfiltered read must give. */
  allThreadIds: string[];
  threshold: number;
}

export interface TruncatedThreadMessagesFixtures {
  truncatedThreadMessages: TruncatedThreadMessagesRef;
}

/** How long the `trace_threads` aggregation may take to catch up. */
const THREADS_VISIBLE_TIMEOUT_MS = 180_000;

/**
 * Three three-turn conversations on one page, differing only in WHICH of their
 * endpoint payloads is over the truncation threshold — the shape opik#9314
 * (OPIK-9058) rewrote the threads-list query for and the one no spec reaches.
 *
 * ## What changed, and why the truncated branch is the gap
 *
 * Before 9314 the threads list read `input`/`output` (and their `truncated_*`,
 * `*_length` and `truncation_threshold` siblings) under FINAL for every trace of
 * the page's threads, and took the first input and last output with
 * `argMin`/`argMax`. On a page-pushdown read 9314 stops reading any payload in
 * `traces_final`: `thread_message_traces` picks each thread's first and last
 * TRACE from the narrow columns, and `thread_messages` then reads the payload of
 * only those two traces, outside FINAL, keeping each id's latest version. That
 * is a wholesale rewrite of where these two columns come from, including the
 * three truncation columns that decide the SHAPE of the answer.
 *
 * `thread-logging-smoke.spec.ts` and `thread-search-multi-turn-aggregate.spec.ts`
 * both assert the First/Last message cells and both hit the changed branch — but
 * both seed short messages, so neither has ever seen the truncated columns.
 * `thread-moved-trace-aggregate.spec.ts` does drive both truncate paths, through
 * `POST /threads/retrieve`, which is a different query that 9314 did not touch.
 *
 * ## Why these three conversations
 *
 *  - **One with an over-long turn-1 INPUT and a short turn-3 output**, and one
 *    the other way round. The pick is per-column — `truncated_first_message`
 *    comes from the first trace and `truncated_last_message` from the last — so
 *    a read that took both from the same trace, or that paired the first trace's
 *    payload with the last trace's truncation flag, answers one of these two
 *    correctly and the other not. One thread could not tell those apart.
 *  - **A third with nothing over the threshold.** The control. Every assertion
 *    about truncation above is about a payload that WAS cut; without a row that
 *    was not, a build that truncated everything would pass them all.
 *  - **All three on one page, in one project.** The rewrite's new CTEs group by
 *    `thread_id` and join back on it, so a mix of threads on one page is the
 *    only way a cross-thread mix-up is observable at all.
 *  - **The over-long payload is on turn 1 / turn 3, never turn 2.** The middle
 *    turn's payloads are short and distinctly marked, so "the cell came from the
 *    middle turn" is a named, failing answer rather than an absence.
 *
 * ## Why the markers lead the payload
 *
 * A truncated value comes back cut to the first 10001 characters, so a marker
 * placed after the filler would be gone from exactly the rows this fixture
 * exists to assert on. Each payload is `{"messages":[{"role":…,"content":"<marker>
 * <filler>"}]}`, so the marker survives both the server-side cut and
 * `PrettyCell`'s own 10000-character display cut.
 *
 * The filler is a run of `x`: `ImageUtils.IMAGE_TRUNCATION_REGEX` rewrites any
 * quoted base64-looking run into `"[image]"` when `truncate=true`, which would
 * change the length the spec asserts. `x…` matches none of its type prefixes, and
 * the marker's `-` separators are outside its character class.
 *
 * ## Seeding and teardown
 *
 * Two batch writes (`/traces/batch` then `/spans/batch`) rather than the SDK, for
 * `searched-thread-aggregate`'s reasons: the turn times have to be pinned so each
 * thread's first and last trace are unambiguous, and the payloads have to be
 * exact. The spans carry distinct explicit costs so the cost-sorted read — the
 * full-scan copy of the query, which 9314 deliberately left alone — has a real
 * ordering key rather than three nulls.
 *
 * Teardown deletes the traces, which takes their spans and collapses the
 * `trace_threads` rows with them. Nothing else is created.
 */
export const test = baseTest.extend<TruncatedThreadMessagesFixtures>({
  truncatedThreadMessages: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    try {
      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];
      const base = new Date();

      /**
       * `{"messages":[{"role":"user","content":"<marker> <filler>"}]}` — a chat
       * shape, so the Threads table's `PrettyCell` renders the content rather
       * than the raw JSON for the rows that were NOT truncated, which is what a
       * user actually reads.
       */
      const chatInput = (text: string) => ({
        messages: [{ role: 'user', content: text }],
      });
      const chatOutput = (text: string) => ({
        choices: [{ message: { role: 'assistant', content: text } }],
      });

      /**
       * `<marker> <x…>`, padded until the SERIALISED payload is `target`
       * characters long — measured, not computed, so the padding stays right
       * whatever the wrapper shape above is.
       */
      const payloadOfLength = (
        wrap: (text: string) => unknown,
        text: string,
        target: number,
      ): { value: unknown; length: number } => {
        const overhead = JSON.stringify(wrap(text)).length;
        const padding = Math.max(0, target - overhead);
        const value = wrap(padding > 0 ? `${text} ${'x'.repeat(padding - 1)}` : text);
        return { value, length: JSON.stringify(value).length };
      };

      const oversizeTarget = THREAD_TRUNCATION_THRESHOLD + OVERSIZE_MARGIN;

      const buildThread = (
        key: TruncatedThreadRef['key'],
        label: string,
        oversize: { firstInput: boolean; lastOutput: boolean },
        offsetMs: number,
        costPerTurn: number,
      ): TruncatedThreadRef => {
        const threadId = `${testNamespace}-thread-${label}`;
        const turns: TruncatedThreadTurnRef[] = [];

        for (let turn = 1; turn <= TURNS_PER_THREAD; turn++) {
          // 4s apart and 1.5s long, so no two turns of a thread share a
          // `start_time` or an `end_time`: the new picks break ties on the trace
          // id, and a tie would make this fixture assert the tie-break rather
          // than the ordering.
          const startTime = new Date(base.getTime() + offsetMs + (turn - 1) * 4_000);
          const endTime = new Date(startTime.getTime() + 1_500);
          const inputMarker = marker(label, turn, 'IN');
          const outputMarker = marker(label, turn, 'OUT');
          const wantsBigInput = oversize.firstInput && turn === 1;
          const wantsBigOutput = oversize.lastOutput && turn === TURNS_PER_THREAD;

          const input = payloadOfLength(
            chatInput,
            inputMarker,
            wantsBigInput ? oversizeTarget : 0,
          );
          const output = payloadOfLength(
            chatOutput,
            outputMarker,
            wantsBigOutput ? oversizeTarget : 0,
          );

          const traceId = uuid7();
          const spanId = uuid7();
          traces.push({
            id: traceId,
            name: `${threadId}-turn-${turn}`,
            input: input.value as Record<string, unknown>,
            output: output.value as Record<string, unknown>,
            threadId,
            startTime,
            endTime,
          });
          spans.push({
            id: spanId,
            traceId,
            name: `${threadId}-span-${turn}`,
            type: 'llm',
            model: 'gpt-4o-mini',
            provider: 'openai',
            usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
            totalEstimatedCost: costPerTurn,
            startTime,
            endTime: new Date(endTime.getTime() - 100),
          });

          turns.push({
            turn,
            traceId,
            spanId,
            inputMarker,
            outputMarker,
            inputLength: input.length,
            outputLength: output.length,
            startTime,
            endTime,
          });
        }

        return {
          key,
          threadId,
          turns,
          firstMessageTruncated: turns[0].inputLength >= THREAD_TRUNCATION_THRESHOLD,
          lastMessageTruncated:
            turns[TURNS_PER_THREAD - 1].outputLength >= THREAD_TRUNCATION_THRESHOLD,
          totalEstimatedCost: costPerTurn * TURNS_PER_THREAD,
        };
      };

      // Distinct costs, and distinct offsets so no two threads share a window: a
      // read that aggregated across threads would then report a span neither
      // thread has.
      const threads: TruncatedThreadRef[] = [
        buildThread('first-oversize', 'bigfirst', { firstInput: true, lastOutput: false }, 0, 0.03),
        buildThread('last-oversize', 'biglast', { firstInput: false, lastOutput: true }, 1_000, 0.02),
        buildThread('all-short', 'short', { firstInput: false, lastOutput: false }, 2_000, 0.01),
      ];

      // ---- the fixture proves its own premises, before anything is written ----

      // The whole discriminating property: exactly one thread's FIRST message is
      // over the threshold, exactly one thread's LAST message is, and they are
      // different threads. Asserted over the measured lengths rather than over
      // the intent above, so an edit to the payload shape that pushed a "short"
      // payload over the line — or a margin that stopped clearing it — fails
      // here instead of making a truncation assertion vacuous.
      expect(
        threads.filter((t) => t.firstMessageTruncated).map((t) => t.key),
        'exactly the first-oversize thread has an over-threshold FIRST message',
      ).toEqual(['first-oversize']);
      expect(
        threads.filter((t) => t.lastMessageTruncated).map((t) => t.key),
        'exactly the last-oversize thread has an over-threshold LAST message',
      ).toEqual(['last-oversize']);

      for (const thread of threads) {
        // Every payload that is NOT meant to be truncated has to be clear of the
        // threshold, not merely smaller than the oversize one: a short payload
        // that had crept over it would be served as a JSON string and the
        // control row would stop being a control.
        for (const turn of thread.turns) {
          const oversizeSides = [
            ...(thread.firstMessageTruncated && turn.turn === 1 ? (['input'] as const) : []),
            ...(thread.lastMessageTruncated && turn.turn === TURNS_PER_THREAD
              ? (['output'] as const)
              : []),
          ];
          for (const [side, length] of [
            ['input', turn.inputLength],
            ['output', turn.outputLength],
          ] as const) {
            if (oversizeSides.includes(side)) {
              expect(
                length,
                `${thread.key} turn ${turn.turn}'s ${side} must be over the ${THREAD_TRUNCATION_THRESHOLD}-character threshold`,
              ).toBeGreaterThan(THREAD_TRUNCATION_THRESHOLD);
            } else {
              expect(
                length,
                `${thread.key} turn ${turn.turn}'s ${side} must stay under the threshold`,
              ).toBeLessThan(THREAD_TRUNCATION_THRESHOLD);
            }
          }
        }

        // The marker of each turn must appear in that turn's payload and nowhere
        // else in the conversation, or `not.toContain('…T2-IN')` proves nothing.
        const everyPayload = JSON.stringify(
          traces.filter((t) => t.threadId === thread.threadId).map((t) => [t.input, t.output]),
        );
        for (const turn of thread.turns) {
          for (const m of [turn.inputMarker, turn.outputMarker]) {
            expect(
              everyPayload.split(m).length - 1,
              `marker '${m}' appears exactly once across ${thread.key}`,
            ).toBe(1);
          }
        }
      }

      const ids = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);
      // Distinct thread costs, so the cost-sorted read has a total order and a
      // row answered for the wrong thread is visible in the ordering too.
      expect(
        new Set(threads.map((t) => t.totalEstimatedCost)).size,
        'each conversation sums to its own cost',
      ).toBe(threads.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      // Threads are materialised from the traces that share a `thread_id`, on a
      // separate eventually-consistent path. Polled on each thread's own message
      // count so a conversation still mid-aggregation fails HERE — a thread
      // holding two of its three turns has a different first-and-last pair, and
      // the spec would report that as the product's fault.
      const expectedShape = threads
        .map((t) => `${t.threadId}=${t.turns.length * 2}`)
        .sort()
        .join(',');
      await expect
        .poll(
          async () => {
            const answer = await backendClient.listThreads({ projectId: project.id, size: 50 });
            return answer.threads
              .map((row) => `${row.id}=${row.numberOfMessages}`)
              .sort()
              .join(',');
          },
          {
            message:
              'all three conversations must be aggregated, each reporting every one of its ' +
              'own turns, before the spec reads anything',
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(expectedShape);

      const ref: TruncatedThreadMessagesRef = {
        projectId: project.id,
        projectName: project.name,
        threads,
        allThreadIds: threads.map((t) => t.threadId).sort(),
        threshold: THREAD_TRUNCATION_THRESHOLD,
      };

      await testInfo.attach('opik.truncatedThreadMessages', {
        body: JSON.stringify(
          {
            projectId: ref.projectId,
            projectName: ref.projectName,
            threshold: ref.threshold,
            threads: ref.threads.map((t) => ({
              key: t.key,
              threadId: t.threadId,
              firstMessageTruncated: t.firstMessageTruncated,
              lastMessageTruncated: t.lastMessageTruncated,
              totalEstimatedCost: t.totalEstimatedCost,
              turns: t.turns.map((turn) => ({
                turn: turn.turn,
                traceId: turn.traceId,
                inputMarker: turn.inputMarker,
                outputMarker: turn.outputMarker,
                inputLength: turn.inputLength,
                outputLength: turn.outputLength,
              })),
            })),
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
          console.warn('[truncatedThreadMessages fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './all-dataset-experiments.fixture';
