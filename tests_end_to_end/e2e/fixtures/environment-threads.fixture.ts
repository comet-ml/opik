import { expect } from '@playwright/test';
import { test as baseTest } from './gemini-thinking-stub.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/**
 * One seeded conversation and the `environment` its trace carries.
 *
 * `environment` is `null` for the untagged thread — the case the filter has a
 * separate `is_empty` branch for — and never `''`: a trace written with no
 * environment and one written with an empty one are different seeds, and the
 * ref must not collapse them.
 */
export interface EnvironmentThreadRef {
  threadId: string;
  traceId: string;
  environment: string | null;
}

export interface EnvironmentThreadsRef {
  projectId: string;
  projectName: string;
  /** The two environment names this fixture caused the workspace to register. */
  environmentA: string;
  environmentB: string;
  threads: EnvironmentThreadRef[];
  /** Thread ids under `environmentA`, sorted — the answer a filter on A must give. */
  threadIdsInA: string[];
  /** Thread ids under `environmentB`, sorted. */
  threadIdsInB: string[];
  /** Thread ids carrying no environment at all, sorted. */
  untaggedThreadIds: string[];
  /** Every seeded thread id, sorted — the answer an unfiltered read must give. */
  allThreadIds: string[];
  /**
   * A name of the right SHAPE that the workspace registry does not hold —
   * asserted absent below.
   *
   * On the ref because an inbound `environment` the registry does not know is
   * its own branch (`envIsValid === false`): the tab must drop the param and
   * reset to page 1 rather than filter the table down to nothing. A name the
   * registry happened to hold would test the ordinary filter instead.
   */
  unknownEnvironment: string;
}

export interface EnvironmentThreadsFixtures {
  environmentThreads: EnvironmentThreadsRef;
}

/** How long `trace_threads` may take to catch up with the traces. */
const THREADS_VISIBLE_TIMEOUT_MS = 180_000;

/**
 * How long the workspace's environment registry may take to learn a name.
 *
 * The registration is a listener on the trace write, not part of it, so the
 * name a trace carries is filterable before the dropdown can offer it.
 */
const ENVIRONMENT_REGISTERED_TIMEOUT_MS = 120_000;

/** Usage on each trace's one llm span, so every thread row aggregates something real. */
const SPAN_USAGE = { prompt_tokens: 90, completion_tokens: 30, total_tokens: 120 } as const;

/** What each thread is seeded as: its label, and which environment slot it belongs to. */
const PLAN: Array<{ label: string; slot: 'a' | 'b' | 'untagged' }> = [
  { label: 'a1', slot: 'a' },
  { label: 'a2', slot: 'a' },
  { label: 'b1', slot: 'b' },
  { label: 'u1', slot: 'untagged' },
];

/**
 * Four threads in one project across two environments and one untagged, for the
 * Threads tab's environment filter (opik#9177).
 *
 * The shape is what makes the filter's answer a finding rather than a
 * tautology:
 *
 *  - **Two threads in A, one in B, one untagged.** Four distinct answers are
 *    then possible (4 / 2 / 1 / 1) and no two of them are the same number, so
 *    neither "the filter was ignored" nor "the filter dropped everything" nor
 *    "the filter matched the wrong name" can pass as the right answer. A single
 *    thread per environment would make "1" the answer to three different
 *    questions.
 *  - **A thread with no environment at all.** It is the row that must disappear
 *    under a filter on A *and* under a filter on B, and the only one that can
 *    tell "matches A" from "has any environment".
 *  - **One trace per thread.** The subject is which threads come back, and a
 *    multi-turn thread would make "two threads in A" and "the trace count" two
 *    different numbers for no gain. Each trace carries one llm span with usage
 *    so the row's aggregates are real: a filtered listing that returned the
 *    right ids with empty aggregates is a blank table no count assertion would
 *    notice.
 *
 * Environment names are REGISTERED by seeding traces that carry them rather
 * than by `POST /environments`: that is the path a user's data takes, and the
 * dropdown reads the registry while the filter reads the trace column — so a
 * fixture that created the registry entry directly could leave the two
 * disagreeing and the spec would never know. The registration is asynchronous,
 * hence the poll.
 *
 * Teardown deletes the traces (which collapses the `trace_threads` rows with
 * them) and then the two environments. Environments are workspace-scoped and
 * cascade with neither the project nor the run-prefix sweep in
 * `global-teardown.ts`, and they are deleted BY ID resolved from this fixture's
 * own two names — the workspace is shared, and anything looser would take an
 * environment someone else is relying on.
 */
export const test = baseTest.extend<EnvironmentThreadsFixtures>({
  environmentThreads: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const environmentA = `${testNamespace}-env-a`;
    const environmentB = `${testNamespace}-env-b`;
    const unknownEnvironment = `${testNamespace}-env-nope`;

    const traceIds: string[] = [];

    try {
      const threads: EnvironmentThreadRef[] = [];
      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];
      const base = new Date();

      PLAN.forEach((seed, position) => {
        const threadId = `${testNamespace}-thread-${seed.label}`;
        const traceId = uuid7();
        const spanId = uuid7();
        const environment =
          seed.slot === 'a' ? environmentA : seed.slot === 'b' ? environmentB : null;
        const start = new Date(base.getTime() + position * 20);

        traces.push({
          id: traceId,
          name: `${testNamespace}-trace-${seed.label}`,
          input: { prompt: `which environment is ${seed.label} in` },
          output: { answer: `${seed.label}` },
          threadId,
          startTime: start,
          endTime: new Date(start.getTime() + 1_200),
          ...(environment === null ? {} : { environment }),
        });
        spans.push({
          id: spanId,
          traceId,
          name: `${testNamespace}-span-${seed.label}`,
          type: 'llm' as const,
          model: 'gpt-4o-mini',
          provider: 'openai',
          usage: { ...SPAN_USAGE },
          startTime: start,
          endTime: new Date(start.getTime() + 1_100),
        });
        threads.push({ threadId, traceId, environment });
      });

      // ---- the fixture proves its own premises before anything is written ----

      expect(
        new Set([environmentA, environmentB, unknownEnvironment]).size,
        'the three environment names are distinct',
      ).toBe(3);
      for (const name of [environmentA, environmentB, unknownEnvironment]) {
        // `Environment.NAME_PATTERN` is `^[A-Za-z0-9_-]+$` with a 150-character
        // cap. `testNamespace` is built from the run prefix and the test title,
        // neither of which this fixture controls — so a title carrying anything
        // else would make the seed fail as a 400 on the trace write, which from
        // inside the spec reads as the filter being broken.
        expect(name, `'${name}' is a name the backend will accept`).toMatch(
          /^[A-Za-z0-9_-]{1,150}$/,
        );
      }
      const ids = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);
      expect(
        new Set(threads.map((t) => t.threadId)).size,
        'every seeded thread id is distinct',
      ).toBe(threads.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      const sorted = (values: string[]) => [...values].sort();
      const idsIn = (slot: 'a' | 'b' | 'untagged') =>
        sorted(
          threads
            .filter((_, position) => PLAN[position].slot === slot)
            .map((t) => t.threadId),
        );

      // Threads are materialised from the traces that share a `thread_id`, on a
      // separate eventually-consistent path. Polled on the UNFILTERED total, so
      // a seed that lost a thread fails here rather than as a short filtered
      // answer the spec would report as the product's fault.
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
        .toBe(threads.length);

      // The seed really carries the environments it claims — read at the TRACE
      // level, which is the column the filter matches, and asserted as the whole
      // answer rather than as membership. Without this a UI assertion about
      // narrowing could pass over a seed whose `environment` never landed: the
      // filtered table would be empty either way.
      for (const [name, slot] of [
        [environmentA, 'a'],
        [environmentB, 'b'],
      ] as const) {
        const matching = await backendClient.listTraceIds({
          projectId: project.id,
          filters: [{ field: 'environment', operator: '=', value: name, type: 'string' }],
        });
        expect(
          sorted(matching),
          `exactly the '${slot}' traces carry environment '${name}'`,
        ).toEqual(
          sorted(
            threads
              .filter((_, position) => PLAN[position].slot === slot)
              .map((t) => t.traceId),
          ),
        );
      }

      // The dropdown is built from the workspace registry, not from the traces,
      // so the names have to be registered before the spec can pick one.
      await expect
        .poll(
          async () => {
            const registered = new Set((await backendClient.listEnvironments()).map((e) => e.name));
            return registered.has(environmentA) && registered.has(environmentB);
          },
          {
            message:
              'both seeded environments must reach the workspace registry — the dropdown is ' +
              'built from it, and the registration is a listener on the trace write',
            timeout: ENVIRONMENT_REGISTERED_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(true);

      // The negative control for the invalid-param branch, asserted at the point
      // it is established: a name the registry DID hold would send the spec down
      // the ordinary filter path and its page-reset assertion would mean nothing.
      expect(
        (await backendClient.listEnvironments()).map((e) => e.name),
        `'${unknownEnvironment}' must be a name the workspace does not know`,
      ).not.toContain(unknownEnvironment);

      const ref: EnvironmentThreadsRef = {
        projectId: project.id,
        projectName: project.name,
        environmentA,
        environmentB,
        threads,
        threadIdsInA: idsIn('a'),
        threadIdsInB: idsIn('b'),
        untaggedThreadIds: idsIn('untagged'),
        allThreadIds: sorted(threads.map((t) => t.threadId)),
        unknownEnvironment,
      };

      await testInfo.attach('opik.environmentThreads', {
        body: JSON.stringify(ref, null, 2),
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
          console.warn('[environmentThreads fixture] trace delete warning:', err);
        }
        try {
          const mine = (await backendClient.listEnvironments()).filter(
            (e) => e.name === environmentA || e.name === environmentB,
          );
          await backendClient.deleteEnvironments(mine.map((e) => e.id));
        } catch (err) {
          console.warn('[environmentThreads fixture] environment delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './gemini-thinking-stub.fixture';
