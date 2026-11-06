import { expect } from '@playwright/test';
import { test as baseTest } from './evaluation-method-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One seeded trace and the `source` its write declared. */
export interface EvaluatorSourceTraceRef {
  id: string;
  name: string;
  source: 'sdk' | 'evaluator';
}

export interface EvaluatorSourceTracesRef {
  projectId: string;
  projectName: string;
  /** The user's own traffic — what every Logs read must show. */
  userTraces: EvaluatorSourceTraceRef[];
  /** The monitoring rows a judge rule writes — what every Logs read must hide. */
  evaluatorTraces: EvaluatorSourceTraceRef[];
  /** Every seeded trace, in write order. */
  all: EvaluatorSourceTraceRef[];
  /** The Logs date-range preset the population is guaranteed to sit inside. */
  timeRangePreset: string;
}

export interface EvaluatorSourceTracesFixtures {
  evaluatorSourceTraces: EvaluatorSourceTracesRef;
}

/**
 * How many rows carry each `source`.
 *
 * Two different primes, neither of which is the other and neither of which is
 * the total (12). So a read that returned the wrong set cannot have the right
 * size, and "the table shows 5" cannot be satisfied by a table showing the
 * evaluator rows instead.
 */
const USER_TRACE_COUNT = 5;
const EVALUATOR_TRACE_COUNT = 7;

/**
 * The Logs date-range preset the spec opens the page with.
 *
 * Stated rather than inherited: the range is persisted per project in
 * localStorage AND in the URL, so a spec that did not say which window it
 * wanted would read whichever one the profile last stored. A week comfortably
 * contains a population seeded at `now`, and — unlike a day-bucketed read — it
 * cannot roll over mid-test, so this fixture needs no midnight guard.
 */
const TIME_RANGE_PRESET = 'past7days';

/** How long the whole population may take to become queryable. */
const QUERYABLE_TIMEOUT_MS = 120_000;

/**
 * One fresh project holding five `source=sdk` traces and seven
 * `source=evaluator` ones, all logged now.
 *
 * The seed for what opik#7317 made unconditional. That PR removed the
 * `onlineScoringTracingEnabled` service toggle, so every project with an
 * LLM-judge rule now accumulates one hidden monitoring trace per sampled trace,
 * with no way to turn it off. The product's answer to that is that the rows are
 * EXCLUDED from every Logs read (`TracesSpansTab` hard-codes
 * `logsSource: LOGS_SOURCE.sdk` on its listing, its stats and its KPI cards)
 * while staying findable by an explicit `source` filter.
 *
 * Seeded over REST with an explicit `source` rather than by running a judge
 * rule, for three reasons: a real rule needs a working provider key (staging's
 * are rejected upstream), it samples rather than scores everything, and the
 * number of monitoring traces it writes is then a property of the run instead
 * of a fact the spec knows. The bridge cannot express it either — it always
 * emits `source=sdk`.
 *
 * `evaluator` is the only non-sdk value seeded, deliberately narrow: the
 * exclusion across EVERY `Source` value is already pinned by
 * `sourceMixTraces` / `project-home-traces-logged-today.spec.ts`. What is
 * unasserted, and what this exists for, is that the evaluator rows are written
 * at all and can be read back on demand.
 *
 * Teardown deletes the traces explicitly. Traces do go with their project, but
 * the `project` fixture's delete is not ordered against this one, and
 * `global-teardown.ts`'s run-prefix sweep does not know about traces.
 */
export const test = baseTest.extend<EvaluatorSourceTracesFixtures>({
  evaluatorSourceTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const plan: EvaluatorSourceTraceRef[] = [
      ...Array.from({ length: USER_TRACE_COUNT }, (_, i) => ({
        id: uuid7(),
        name: `${testNamespace}-user-${i}`,
        source: 'sdk' as const,
      })),
      ...Array.from({ length: EVALUATOR_TRACE_COUNT }, (_, i) => ({
        id: uuid7(),
        name: `${testNamespace}-evaluator-${i}`,
        source: 'evaluator' as const,
      })),
    ];

    const seeded: EvaluatorSourceTraceRef[] = [];
    try {
      for (const trace of plan) {
        const now = new Date();
        await backendClient.createTraceWithSource({
          id: trace.id,
          projectName: project.name,
          name: trace.name,
          source: trace.source,
          input: { question: `${trace.name} question` },
          output: { answer: `${trace.name} answer` },
          startTime: now,
          // Closed, so each row is a complete write rather than one still open.
          endTime: now,
        });
        seeded.push(trace);
      }

      // The whole population queryable before the browser opens. Exactly, not
      // at-least: the assertions below are about which rows a read excludes, so
      // a half-landed seed would make "the table shows five" true of a project
      // whose other seven had simply not arrived yet.
      await expect
        .poll(
          async () =>
            (await backendClient.searchTraceIdsPage({ projectId: project.id, size: 200 })).total,
          {
            message: 'every seeded trace must be queryable before anything is asserted',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
          },
        )
        .toBe(plan.length);

      // And that each row really carries the source its write declared. This is
      // the fixture's discriminating property: a seed whose `evaluator` rows
      // were stored as `sdk` would make every exclusion assertion below fail
      // for the right reason, but one whose `sdk` rows were stored as something
      // else would make them PASS while testing nothing — an empty table
      // excludes everything.
      for (const trace of seeded) {
        const payload = await backendClient.getTracePayload(trace.id);
        expect(payload, `seeded trace ${trace.name} is readable`).not.toBeNull();
        expect(payload?.source, `seeded trace ${trace.name} stored its declared source`).toBe(
          trace.source,
        );
      }

      const ref: EvaluatorSourceTracesRef = {
        projectId: project.id,
        projectName: project.name,
        userTraces: seeded.filter((t) => t.source === 'sdk'),
        evaluatorTraces: seeded.filter((t) => t.source === 'evaluator'),
        all: seeded,
        timeRangePreset: TIME_RANGE_PRESET,
      };

      await testInfo.attach('opik.evaluatorSourceTraces', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && seeded.length > 0) {
        try {
          await backendClient.deleteTraces(seeded.map((t) => t.id));
        } catch (err) {
          // Never rethrow: a cleanup failure must not replace the test's own
          // error. The batch is one request, so one bad id loses the rest —
          // retried per id for that reason.
          console.warn('[evaluatorSourceTraces fixture] batch delete failed, retrying per id:', err);
          for (const trace of seeded) {
            try {
              await backendClient.deleteTraces([trace.id]);
            } catch (perId) {
              console.warn(`[evaluatorSourceTraces fixture] could not delete ${trace.name}:`, perId);
            }
          }
        }
      }
    }
  },
});

export { expect } from './evaluation-method-experiments.fixture';
