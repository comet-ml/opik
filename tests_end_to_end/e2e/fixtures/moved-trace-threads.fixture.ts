import { test as baseTest } from './open-ended-window-rows.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';

/**
 * The model a seeded trace stands for, and the one the backend has a published
 * price for. Pricing is not the subject here — the spec reads each trace's cost
 * back from the API and sums it — but the counts below are chosen so every
 * thread total clears the thread panel's `MIN_DISPLAYED_COST` of $0.01, under
 * which `formatCost` renders the literal "<$0.01" and the chip stops carrying a
 * number at all.
 */
const MODEL = 'gpt-4o-mini';
const PROVIDER = 'openai';

/** One seeded trace: when it ran, what it cost, and which thread owns it. */
interface TraceSeed {
  /** Stable key the ref exposes it under. */
  key: MovedTraceKey;
  /** ms after the scenario's base instant. */
  startOffsetMs: number;
  endOffsetMs: number;
  promptTokens: number;
  completionTokens: number;
}

export type MovedTraceKey = 'mover' | 'aEarly' | 'aLate' | 'bEarly' | 'bLate';

/**
 * The five traces, in ascending `start_time` order — which is also the order
 * they are written in, so their UUIDv7 ids are monotonic with their start
 * times.
 *
 * That alignment is deliberate housekeeping rather than part of the subject:
 * the thread panel renders its turns in trace-id order, not `start_time` order,
 * so a seeder whose ids and times disagree produces a panel that LOOKS
 * misordered for reasons that have nothing to do with the aggregate. Real SDK
 * traces get time-ordered UUIDv7s, so seeding the same way keeps the panel
 * faithful to what a user would see.
 *
 * The intervals are chosen so that, before the move, each thread's
 * earliest-STARTED trace and its latest-ENDED trace are different traces — a
 * thread whose single longest trace supplied both ends would be satisfied by an
 * aggregate that only ever looked at one row.
 *
 *   thread A   mover  [     0 ‥  60_000]   earliest start
 *              aEarly [10_000 ‥  40_000]
 *              aLate  [50_000 ‥ 120_000]   latest end
 *   thread B   bEarly [70_000 ‥ 100_000]   earliest start
 *              bLate  [80_000 ‥ 140_000]   latest end
 *
 * So moving `mover` out of A and into B changes the START of both threads and
 * the END of neither: A's window shrinks to aEarly's start, B's grows back to
 * the moved trace's. An aggregate that kept counting a trace it no longer owns
 * — the regression opik#8735 fixed — fails on A; one that ignored a trace it
 * just gained fails on B; and the untouched ends are the control that says the
 * aggregate recomputed a bound rather than replacing the whole window.
 *
 * The token counts are uneven in both axes, so a sum that double-counted, or
 * that summed prompt tokens into the completion bucket, cannot land on the
 * right total by symmetry.
 */
const TRACE_SEEDS: TraceSeed[] = [
  { key: 'mover', startOffsetMs: 0, endOffsetMs: 60_000, promptTokens: 400_000, completionTokens: 200_000 },
  { key: 'aEarly', startOffsetMs: 10_000, endOffsetMs: 40_000, promptTokens: 100_000, completionTokens: 100_000 },
  { key: 'aLate', startOffsetMs: 50_000, endOffsetMs: 120_000, promptTokens: 200_000, completionTokens: 100_000 },
  { key: 'bEarly', startOffsetMs: 70_000, endOffsetMs: 100_000, promptTokens: 100_000, completionTokens: 50_000 },
  { key: 'bLate', startOffsetMs: 80_000, endOffsetMs: 140_000, promptTokens: 100_000, completionTokens: 50_000 },
];

/** Which thread each trace is seeded into, before anything is moved. */
const THREAD_AT_SEED: Record<MovedTraceKey, 'a' | 'b'> = {
  mover: 'a',
  aEarly: 'a',
  aLate: 'a',
  bEarly: 'b',
  bLate: 'b',
};

/** Every trace this scenario seeded, by role. */
export interface MovedTraceRef {
  id: string;
  name: string;
  /** Absolute instants, as the backend stored them. */
  startTime: string;
  endTime: string;
  promptTokens: number;
  completionTokens: number;
}

/**
 * What one thread's aggregate must report at a given moment, for everything
 * derivable from the seed WITHOUT consulting a price table.
 *
 * Cost is deliberately absent: the spec reads each trace's own
 * `total_estimated_cost` back from the API and sums those, so the assertion
 * stays a statement about which traces the thread counted rather than about
 * what gpt-4o-mini costs this month.
 */
export interface ThreadAggregateExpectation {
  traceKeys: MovedTraceKey[];
  /** Two messages per trace — the turn's input and its output. */
  numberOfMessages: number;
  startTime: string;
  endTime: string;
  durationMs: number;
  usage: Record<string, number>;
}

export interface MovedTraceThreadsRef {
  projectId: string;
  projectName: string;
  threadA: string;
  threadB: string;
  traces: Record<MovedTraceKey, MovedTraceRef>;
  /** The aggregates before `mover` is re-pointed, keyed by thread id. */
  before: Record<string, ThreadAggregateExpectation>;
  /** …and after. The spec performs the move; the fixture only predicts it. */
  after: Record<string, ThreadAggregateExpectation>;
}

export interface MovedTraceThreadsFixtures {
  movedTraceThreads: MovedTraceThreadsRef;
}

function expectationFor(
  keys: MovedTraceKey[],
  traces: Record<MovedTraceKey, MovedTraceRef>,
): ThreadAggregateExpectation {
  const members = keys.map((k) => traces[k]);
  const starts = members.map((t) => Date.parse(t.startTime));
  const ends = members.map((t) => Date.parse(t.endTime));
  const start = Math.min(...starts);
  const end = Math.max(...ends);
  const promptTokens = members.reduce((acc, t) => acc + t.promptTokens, 0);
  const completionTokens = members.reduce((acc, t) => acc + t.completionTokens, 0);

  return {
    traceKeys: keys,
    numberOfMessages: members.length * 2,
    startTime: new Date(start).toISOString(),
    endTime: new Date(end).toISOString(),
    durationMs: end - start,
    usage: {
      prompt_tokens: promptTokens,
      completion_tokens: completionTokens,
      total_tokens: promptTokens + completionTokens,
    },
  };
}

/**
 * Two conversations in one project, each trace carrying one priced LLM span,
 * laid out so that moving a single trace between them changes both aggregates.
 *
 * Seeded through `POST /v1/private/traces` and `POST /v1/private/spans` rather
 * than the SDK bridge for two reasons, both load-bearing here: the bridge
 * stamps `start_time`/`end_time` from its own clock, so neither thread's window
 * could be chosen, and it normalises `usage`, so the token counts the cost is
 * derived from would not be the ones seeded.
 *
 * The fixture does NOT perform the move. The move is the act under test, so it
 * belongs in a `test.step` where its failure is legible, and the fixture's job
 * is to state what each thread must report on either side of it.
 *
 * Teardown deletes the traces explicitly: deleting a project does not take its
 * traces with it, `global-teardown`'s run-prefix sweep does not know about
 * traces at all, and the spans cascade with their trace.
 */
export const test = baseTest.extend<MovedTraceThreadsFixtures>({
  movedTraceThreads: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Anchored so the whole scenario sits in the recent past: every default
    // Logs window reaches back far enough to hold it, and nothing lands in the
    // future, where a trace is outside every preset.
    const span = Math.max(...TRACE_SEEDS.map((s) => s.endOffsetMs));
    const base = Date.now() - span - 10_000;

    const threadA = `${testNamespace}-thread-a`;
    const threadB = `${testNamespace}-thread-b`;
    const traceIds: string[] = [];

    try {
      const traces = {} as Record<MovedTraceKey, MovedTraceRef>;

      for (const seed of TRACE_SEEDS) {
        const traceId = uuid7();
        const startTime = new Date(base + seed.startOffsetMs);
        const endTime = new Date(base + seed.endOffsetMs);
        const name = `${testNamespace}-${seed.key}`;

        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name,
          source: 'sdk',
          threadId: THREAD_AT_SEED[seed.key] === 'a' ? threadA : threadB,
          input: `${name} input`,
          output: `${name} output`,
          startTime,
          // Never omitted: a trace with no end_time contributes no end to the
          // thread's `max(end_time)`, and the aggregate answers a null
          // duration — which no assertion below could tell apart from a bug.
          endTime,
        });
        traceIds.push(traceId);

        await seedPricedSpan(backendClient, {
          traceId,
          projectName: project.name,
          name: `${name}-llm`,
          startTime,
          endTime,
          promptTokens: seed.promptTokens,
          completionTokens: seed.completionTokens,
        });

        traces[seed.key] = {
          id: traceId,
          name,
          startTime: startTime.toISOString(),
          endTime: endTime.toISOString(),
          promptTokens: seed.promptTokens,
          completionTokens: seed.completionTokens,
        };
      }

      const ref: MovedTraceThreadsRef = {
        projectId: project.id,
        projectName: project.name,
        threadA,
        threadB,
        traces,
        before: {
          [threadA]: expectationFor(['mover', 'aEarly', 'aLate'], traces),
          [threadB]: expectationFor(['bEarly', 'bLate'], traces),
        },
        after: {
          [threadA]: expectationFor(['aEarly', 'aLate'], traces),
          [threadB]: expectationFor(['mover', 'bEarly', 'bLate'], traces),
        },
      };

      await testInfo.attach('opik.movedTraceThreads', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // In a `finally`, and driven by what was actually written: a failure
      // part-way through the seed must still take the traces already created
      // with it, or the next run's thread list starts with a stranger in it.
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        try {
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn('[movedTraceThreads fixture] trace delete warning:', err);
        }
      }
    }
  },
});

/** One LLM span under a trace, priced server-side from the usage it carries. */
async function seedPricedSpan(
  backendClient: BackendClient,
  args: {
    traceId: string;
    projectName: string;
    name: string;
    startTime: Date;
    endTime: Date;
    promptTokens: number;
    completionTokens: number;
  },
): Promise<void> {
  await backendClient.createSpan({
    id: uuid7(),
    traceId: args.traceId,
    projectName: args.projectName,
    name: args.name,
    source: 'sdk',
    type: 'llm',
    model: MODEL,
    provider: PROVIDER,
    startTime: args.startTime,
    endTime: args.endTime,
    // No `total_cost`: supplying one would skip server-side pricing, and the
    // cost this scenario attributes to a thread has to be one the backend
    // derived, not one the seed asserted into existence.
    usage: {
      prompt_tokens: args.promptTokens,
      completion_tokens: args.completionTokens,
      total_tokens: args.promptTokens + args.completionTokens,
    },
  });
}

export { expect } from './open-ended-window-rows.fixture';
