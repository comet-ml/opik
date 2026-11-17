import { expect } from '@playwright/test';
import { test as baseTest } from './searched-thread-aggregate.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/** How long the `trace_threads` aggregation may take to catch up with the traces. */
const THREADS_VISIBLE_TIMEOUT_MS = 180_000;

/**
 * How long the workspace's environment registry may take to learn a name.
 *
 * The registration is a listener on the trace write, not part of it, so the name
 * a trace carries is filterable before the dropdown can offer it — and before an
 * inbound `environment` param carrying it survives `envIsValid`.
 */
const ENVIRONMENT_REGISTERED_TIMEOUT_MS = 120_000;

/**
 * Per-turn token usage for the split thread, as `[prompt, completion]`.
 *
 * Distinct subset sums again (110 / 220 / 440), for the reason
 * `searched-thread-aggregate.fixture.ts` gives at length: the three answers this
 * spec has to tell apart are "the whole conversation" (770), "the turns inside
 * the filter" (330) and "the one turn the search matched", and no two of them
 * may be reachable by a different subset.
 */
const SPLIT_USAGE: ReadonlyArray<readonly [number, number]> = [
  [80, 30],
  [160, 60],
  [320, 120],
];

/** Which slot each turn of the split thread is written under. */
const TURN_SLOT: ReadonlyArray<'included' | 'excluded'> = ['included', 'included', 'excluded'];

/** Per-turn usage for the decoy, which lives wholly inside the filter. */
const DECOY_USAGE: ReadonlyArray<readonly [number, number]> = [
  [15, 5],
  [25, 7],
];

export interface SplitThreadTurnRef {
  traceId: string;
  spanId: string;
  turn: number;
  /** The environment this turn's trace was written under. */
  environment: string;
  /** Whether an `environment = included` filter keeps this turn. */
  included: boolean;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
}

export interface EnvironmentSplitThreadRef {
  /** The conversation whose turns straddle the two environments. */
  threadId: string;
  turns: SplitThreadTurnRef[];
  /** A thread living wholly inside `includedEnvironment`, carrying neither token. */
  decoyThreadId: string;
  /** Both seeded thread ids, sorted — the answer an unfiltered, unsearched read gives. */
  allThreadIds: string[];

  /** The environment the spec filters TO. */
  includedEnvironment: string;
  /** The environment the spec filters AWAY, and which one turn of the thread is in. */
  excludedEnvironment: string;

  /**
   * A term carried by one INCLUDED turn's payload and by nothing else.
   *
   * Searching it under the filter must return the thread; this is the arm that
   * proves the composed read still works at all, so that the empty answer the
   * excluded term gives is a filter decision rather than a broken search.
   */
  includedTerm: string;
  /**
   * A term carried by the EXCLUDED turn's payload and by nothing else.
   *
   * Searching it under the filter must return NOTHING — the matching trace is
   * outside the filter — and searching it WITHOUT the filter must return the
   * whole thread. That pair is the behaviour opik#9304 added by moving
   * `<if(filters)> AND <filters>` inside the search subquery.
   */
  excludedTerm: string;

  /** 2 per turn — what the unfiltered row reports. */
  expectedMessageCountAll: number;
  /** The whole conversation's token total. */
  expectedTotalTokensAll: number;
  /** 2 per INCLUDED turn — what the filtered row reports. */
  expectedMessageCountIncluded: number;
  /** The included turns' token total. */
  expectedTotalTokensIncluded: number;
}

export interface EnvironmentSplitThreadFixtures {
  environmentSplitThread: EnvironmentSplitThreadRef;
}

/**
 * A 3-turn conversation whose turns straddle two environments, plus a decoy
 * living wholly in one of them — the second, independent behaviour change in
 * opik#9304 (OPIK-9051), which no test reaches at any layer.
 *
 * 9304 added `<if(filters)> AND <filters>` INSIDE the search subquery in all
 * four thread query templates, so the rule became "a thread matches when one of
 * its traces *that passes the filters* matches the search". Reaching that clause
 * needs a `FilterStrategy.TRACE` filter, and on the Threads tab exactly one
 * control produces one: the **Environment select**. Every chip the tab offers —
 * tags, duration, message count, first/last message, thread id, annotation
 * queue, feedback scores — is `TRACE_THREAD`-strategy or an aggregation
 * strategy and is applied by the OUTER query instead, so a chip-based seed
 * would not exercise this at all.
 *
 * What the shape buys:
 *
 *  - **Two turns in, one out.** So "the whole conversation" (6 messages) and
 *    "the turns inside the filter" (4) are different numbers, and the filtered
 *    row is a narrowed aggregate the spec can pin rather than the unfiltered one.
 *  - **A term on an INCLUDED turn and a term on the EXCLUDED one.** The two
 *    searches differ only in which side of the filter their matching trace sits
 *    on, so "returns the thread" and "returns nothing" cannot both be explained
 *    by the search or the filter alone.
 *  - **The excluded turn is the LAST one.** A thread that lost its excluded
 *    turn therefore differs in `last_message` and `end_time` as well as in the
 *    numbers.
 *  - **A decoy wholly inside the filter.** It is what makes the filter's own
 *    answer bigger than the composed read's: `environment = included` alone
 *    keeps two threads, and adding the search keeps one. Without it, "the
 *    filter plus the search" and "the filter" would be the same single row.
 *
 * Environment names are REGISTERED by seeding traces that carry them rather
 * than through `POST /environments`, because that is the path a user's data
 * takes — and the registry is what decides whether an inbound `environment`
 * param survives `envIsValid`, so the UI half cannot deep-link until the names
 * are there. The registration is asynchronous, hence the poll.
 *
 * Teardown deletes the traces (which collapses the `trace_threads` rows with
 * them) and then the two environments. Environments are workspace-scoped and
 * cascade with neither the project nor the run-prefix sweep in
 * `global-teardown.ts`, and they are deleted BY ID resolved from this fixture's
 * own two names — the workspace is shared, and anything looser would take an
 * environment someone else is relying on.
 */
export const test = baseTest.extend<EnvironmentSplitThreadFixtures>({
  environmentSplitThread: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const includedEnvironment = `${testNamespace}-env-in`;
    const excludedEnvironment = `${testNamespace}-env-out`;
    const includedTerm = `${testNamespace}-tok-in`;
    const excludedTerm = `${testNamespace}-tok-out`;

    const traceIds: string[] = [];

    try {
      const threadId = `${testNamespace}-thread-split`;
      const decoyThreadId = `${testNamespace}-thread-decoy`;

      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];
      const base = new Date();

      const turns: SplitThreadTurnRef[] = SPLIT_USAGE.map(
        ([promptTokens, completionTokens], turn) => {
          const included = TURN_SLOT[turn] === 'included';
          // The marker sits on the FIRST included turn and on the excluded one,
          // so the middle included turn carries neither — a search on either
          // term therefore matches exactly one trace, and "which trace matched"
          // is the only thing that differs between the two arms.
          const term = turn === 0 ? includedTerm : turn === SPLIT_USAGE.length - 1 ? excludedTerm : '';
          const startTime = new Date(base.getTime() + turn * 4_000);
          const endTime = new Date(startTime.getTime() + 1_500);
          const input = {
            prompt: `turn ${turn + 1}: what is the weather${term ? ` [${term}]` : ''}`,
          };
          const output = { answer: `turn ${turn + 1}: it is bright and dry` };

          traces.push({
            id: uuid7(),
            name: `${threadId}-turn-${turn + 1}`,
            input,
            output,
            threadId,
            startTime,
            endTime,
            environment: included ? includedEnvironment : excludedEnvironment,
          });
          spans.push({
            id: uuid7(),
            traceId: traces[traces.length - 1].id,
            name: `${threadId}-span-${turn + 1}`,
            type: 'llm',
            model: 'gpt-4o-mini',
            provider: 'openai',
            usage: {
              prompt_tokens: promptTokens,
              completion_tokens: completionTokens,
              total_tokens: promptTokens + completionTokens,
            },
            input,
            output,
            startTime,
            endTime: new Date(endTime.getTime() - 100),
          });

          return {
            traceId: traces[traces.length - 1].id,
            spanId: spans[spans.length - 1].id,
            turn,
            environment: included ? includedEnvironment : excludedEnvironment,
            included,
            promptTokens,
            completionTokens,
            totalTokens: promptTokens + completionTokens,
            input,
            output,
          };
        },
      );

      DECOY_USAGE.forEach(([promptTokens, completionTokens], turn) => {
        const startTime = new Date(base.getTime() + 1_000 + turn * 4_000);
        const endTime = new Date(startTime.getTime() + 1_200);
        const input = { prompt: `decoy turn ${turn + 1}: what is the weather` };
        const output = { answer: `decoy turn ${turn + 1}: it is bright and dry` };
        const traceId = uuid7();
        traces.push({
          id: traceId,
          name: `${decoyThreadId}-turn-${turn + 1}`,
          input,
          output,
          threadId: decoyThreadId,
          startTime,
          endTime,
          environment: includedEnvironment,
        });
        spans.push({
          id: uuid7(),
          traceId,
          name: `${decoyThreadId}-span-${turn + 1}`,
          type: 'llm',
          model: 'gpt-4o-mini',
          provider: 'openai',
          usage: {
            prompt_tokens: promptTokens,
            completion_tokens: completionTokens,
            total_tokens: promptTokens + completionTokens,
          },
          input,
          output,
          startTime,
          endTime: new Date(endTime.getTime() - 100),
        });
      });

      const includedTurns = turns.filter((t) => t.included);
      const excludedTurns = turns.filter((t) => !t.included);

      // ---- the fixture proves its own premises, before anything is written ----

      expect(
        new Set([includedEnvironment, excludedEnvironment]).size,
        'the two environment names are distinct',
      ).toBe(2);
      for (const name of [includedEnvironment, excludedEnvironment]) {
        // `Environment.NAME_PATTERN` is `^[A-Za-z0-9_-]+$` with a 150-character
        // cap. `testNamespace` is built from the run prefix and the test title,
        // neither of which this fixture controls — so a title carrying anything
        // else would make the seed fail as a 400 on the trace write, which from
        // inside the spec reads as the filter being broken.
        expect(name, `'${name}' is a name the backend will accept`).toMatch(
          /^[A-Za-z0-9_-]{1,150}$/,
        );
      }

      expect(includedTurns.length, 'at least two turns are inside the filter').toBeGreaterThan(1);
      expect(excludedTurns.length, 'and at least one is outside it').toBeGreaterThan(0);
      expect(
        turns[turns.length - 1].included,
        'the excluded turn is the LAST one, so a narrowed aggregate differs in ' +
          'last_message and end_time as well as in its numbers',
      ).toBe(false);

      // Each term reaches exactly one turn, and that turn is on the side the
      // ref claims. Assembled from the SAME objects that go on the wire,
      // folding in only the columns `ThreadDAO`'s clause reads.
      const searchableTurnText = (turn: SplitThreadTurnRef): string =>
        JSON.stringify([turn.input, turn.output]).toLowerCase();
      for (const [term, expectIncluded] of [
        [includedTerm, true],
        [excludedTerm, false],
      ] as const) {
        const carriers = turns.filter((t) => searchableTurnText(t).includes(term.toLowerCase()));
        expect(
          carriers.map((t) => t.turn),
          `'${term}' must occur in exactly one turn of the split thread`,
        ).toHaveLength(1);
        expect(
          carriers[0].included,
          `and that turn must be ${expectIncluded ? 'inside' : 'outside'} the filter`,
        ).toBe(expectIncluded);
      }
      // And nowhere in the decoy, whose traces are written above from their own
      // prose: a term the decoy shared would make the composed read's answer
      // two rows and every count assertion below wrong.
      for (const trace of traces.filter((t) => t.threadId === decoyThreadId)) {
        const text = JSON.stringify([trace.input, trace.output]).toLowerCase();
        for (const term of [includedTerm, excludedTerm]) {
          expect(
            text.includes(term.toLowerCase()),
            `the decoy must not carry '${term}'`,
          ).toBe(false);
        }
      }
      // The clause `ilike`s the thread's own id too, and the ids share this
      // fixture's namespace with the terms — so a term that was a substring of
      // an id would match for a reason that is not the payload.
      for (const id of [threadId, decoyThreadId]) {
        for (const term of [includedTerm, excludedTerm]) {
          expect(
            id.toLowerCase().includes(term.toLowerCase()),
            `thread id '${id}' must not contain '${term}'`,
          ).toBe(false);
        }
      }

      // Distinct subset sums, so no wrong subset of turns can answer either of
      // the two totals this spec pins.
      const subsetSums = (values: number[]): number[] =>
        values.reduce<number[]>((sums, value) => [...sums, ...sums.map((s) => s + value)], [0]);
      const sums = subsetSums(turns.map((t) => t.totalTokens));
      expect(
        new Set(sums).size,
        "every subset of the split thread's turns sums to a different token count",
      ).toBe(sums.length);

      const ids = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      const expectedMessageCountAll = turns.length * 2;
      const expectedMessageCountIncluded = includedTurns.length * 2;

      // Both threads aggregated, and the split one carrying every one of its
      // turns. Polled on the message counts rather than on the row count alone:
      // an aggregate still catching up would make the spec's filtered reads
      // short for a reason that is not the filter's.
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
              'both threads must be aggregated, the split one over all three of its turns, ' +
              'before the spec asks a filtered question about them',
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(
          [
            `${threadId}=${expectedMessageCountAll}`,
            `${decoyThreadId}=${DECOY_USAGE.length * 2}`,
          ]
            .sort()
            .join(','),
        );

      // The seed really carries the environments it claims — read at the TRACE
      // level, which is the column the filter matches, and asserted as the whole
      // answer rather than as membership. Without this the spec's empty filtered
      // read could be passing over a seed whose `environment` never landed.
      for (const [name, expected] of [
        [
          includedEnvironment,
          [...includedTurns.map((t) => t.traceId), ...traces
            .filter((t) => t.threadId === decoyThreadId)
            .map((t) => t.id)],
        ],
        [excludedEnvironment, excludedTurns.map((t) => t.traceId)],
      ] as const) {
        const matching = await backendClient.listTraceIds({
          projectId: project.id,
          filters: [{ field: 'environment', operator: '=', value: name, type: 'string' }],
        });
        expect(
          [...matching].sort(),
          `exactly the intended traces carry environment '${name}'`,
        ).toEqual([...expected].sort());
      }

      // The dropdown and `envIsValid` both read the workspace registry, not the
      // traces, so the names have to be registered before the UI half can
      // deep-link to one.
      await expect
        .poll(
          async () => {
            const registered = new Set(
              (await backendClient.listEnvironments()).map((e) => e.name),
            );
            return (
              registered.has(includedEnvironment) && registered.has(excludedEnvironment)
            );
          },
          {
            message:
              'both seeded environments must reach the workspace registry — the dropdown is ' +
              'built from it, and an inbound param naming an unknown environment is dropped',
            timeout: ENVIRONMENT_REGISTERED_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(true);

      const ref: EnvironmentSplitThreadRef = {
        threadId,
        turns,
        decoyThreadId,
        allThreadIds: [threadId, decoyThreadId].sort(),
        includedEnvironment,
        excludedEnvironment,
        includedTerm: includedTerm.toLowerCase(),
        excludedTerm: excludedTerm.toLowerCase(),
        expectedMessageCountAll,
        expectedTotalTokensAll: turns.reduce((sum, t) => sum + t.totalTokens, 0),
        expectedMessageCountIncluded,
        expectedTotalTokensIncluded: includedTurns.reduce((sum, t) => sum + t.totalTokens, 0),
      };

      await testInfo.attach('opik.environmentSplitThread', {
        body: JSON.stringify({ projectId: project.id, projectName: project.name, ...ref }, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        try {
          if (traceIds.length) await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn('[environmentSplitThread fixture] trace delete warning:', err);
        }
        try {
          const mine = (await backendClient.listEnvironments()).filter(
            (e) => e.name === includedEnvironment || e.name === excludedEnvironment,
          );
          await backendClient.deleteEnvironments(mine.map((e) => e.id));
        } catch (err) {
          console.warn('[environmentSplitThread fixture] environment delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './searched-thread-aggregate.fixture';
