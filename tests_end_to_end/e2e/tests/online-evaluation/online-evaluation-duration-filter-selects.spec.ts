import { test, expect } from '@e2e/fixtures';
import { buildConstantScoreMetric } from '@e2e/core/metrics';
import { uuid7 } from '@e2e/core/backend';
import type { BackendFilter } from '@e2e/core/backend';

/** The rule's threshold, in the milliseconds the backend stores and matches on. */
const THRESHOLD_MS = 5_000;

/** Comfortably either side of the threshold, so no clock skew can reorder them. */
const FAST_MS = 1_000;
const SLOW_MS = 10_000;

/**
 * A duration filter on an online-evaluation rule, asserted by which traces the
 * rule actually SCORES.
 *
 * This is the half the taxonomy's own `rule-filters` note calls out as
 * uncovered: "whether a filter actually SELECTS the traces a rule scores.
 * Nothing asserts that at either scope." `online-evaluation-experiment-scope-
 * hidden-filters.spec.ts` covers the other half — that a filter survives a
 * round trip through the edit dialog — which is persistence, not selection.
 *
 * Why this is the assertion that matters for OPIK-8000. The dialog took the
 * threshold in seconds and stored it as-is against a column held in
 * milliseconds, so "> 5" meant "> 5ms" and matched essentially every trace.
 * Nothing about that is visible from a payload round trip: 5 goes out, 5 comes
 * back, both ends agree, and the rule quietly scores everything. The vitest
 * helpers agree with themselves too. The only place the unit error surfaces is
 * in which traces get a score — so that is what is asserted here.
 *
 * Two things make the negative real rather than a timing guess:
 *
 *   - **The unfiltered control rule.** "The fast trace has no duration score"
 *     is satisfied just as well by an engine that is down, a sampler that never
 *     fired, or a metric that failed to compile. The control scores on the same
 *     project, from the same write, through the same stream, with no filter —
 *     so its presence on BOTH traces is what turns the absence into evidence.
 *   - **Settling before asserting the absence.** The sampler fans rules out
 *     onto Redis streams with no ordering guarantee between them, so the
 *     control's score landing does not mean the duration rule has finished with
 *     that trace. `waitForTraceScoresSettled` waits for the whole score set to
 *     stop moving first; without it this spec would pass on a slow engine for
 *     the wrong reason.
 *
 * Both rules are created BEFORE any trace is written, which is not tidiness:
 * `OnlineScoringSampler` binds the rule set at trace-creation time, so a rule
 * created afterwards never sees these traces at all and the whole spec would
 * go green having evaluated nothing.
 *
 * Deterministic and free: a python code metric returning a constant, explicit
 * start/end times, no LLM and no provider key.
 */
test.describe(
  'Online Evaluation — duration filter selection',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    /**
     * Online scoring is asynchronous end to end — sampler, stream, sandboxed
     * python runner — and this test waits for two rules across two traces and
     * then for a quiet period on top. The default 90s budget is not enough for
     * the waiting alone, never mind a slow first compile.
     */
    test.setTimeout(240_000);

    test(
      'a rule filtered on duration scores the slow trace and leaves the fast one alone',
      { tag: ['@cap:online-evaluation.rule-filters'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup }) => {
        const durationScore = `${testNamespace}-slow-only`;
        const controlScore = `${testNamespace}-control`;

        const durationFilter: BackendFilter = {
          field: 'duration',
          type: 'number',
          operator: '>',
          // A string, as the REST layer stores every filter value, and in
          // MILLISECONDS — the units the column holds. The dialog's job is to
          // convert the seconds a user types into this; here it is written
          // directly so the spec pins what the threshold MEANS independently of
          // the widget that sets it.
          value: String(THRESHOLD_MS),
        };

        await test.step('Create the filtered rule and an unfiltered control, before any trace', async () => {
          const durationRuleId = await backendClient.createAutomationRule({
            projectId: project.id,
            name: `${testNamespace}-duration-rule`,
            samplingRate: 1,
            filters: [durationFilter],
            metric: buildConstantScoreMetric(durationScore),
            arguments: { output: 'output.output' },
          });
          const controlRuleId = await backendClient.createAutomationRule({
            projectId: project.id,
            name: `${testNamespace}-control-rule`,
            samplingRate: 1,
            metric: buildConstantScoreMetric(controlScore),
            arguments: { output: 'output.output' },
          });
          // The filter really is stored as the rule's own, at the threshold
          // this spec thinks it is. A rule that dropped its filter on write
          // would score both traces, and the assertion below would read as the
          // unit bug rather than as a seed that never set one.
          const stored = await backendClient.getAutomationRule(durationRuleId);
          expect(stored.filters, 'the duration rule came back with a filter list').not.toBeNull();
          expect(
            (stored.filters ?? []).map((f) => ({
              field: f.field,
              operator: f.operator,
              value: f.value,
            })),
            'the stored duration filter',
          ).toEqual([
            { field: 'duration', operator: '>', value: String(THRESHOLD_MS) },
          ]);
          expect(
            (await backendClient.getAutomationRule(controlRuleId)).filters ?? [],
            'the control rule must carry no filter, or it is not a control',
          ).toEqual([]);
        });

        const fastTraceId = uuid7();
        const slowTraceId = uuid7();

        await test.step('Seed one fast and one slow trace', async () => {
          // Fixed instants rather than offsets from "now" taken twice: both
          // traces are written in one batch, and the durations are what the
          // filter matches on, so they are computed from a single reference
          // point.
          const base = Date.now() - 60_000;
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: fastTraceId,
                name: `${testNamespace}-fast`,
                input: { q: 'fast' },
                output: { output: 'Paris' },
                startTime: new Date(base),
                endTime: new Date(base + FAST_MS),
              },
              {
                id: slowTraceId,
                name: `${testNamespace}-slow`,
                input: { q: 'slow' },
                output: { output: 'Paris' },
                startTime: new Date(base),
                endTime: new Date(base + SLOW_MS),
              },
            ],
          });
        });

        await test.step('The seeded durations really straddle the threshold', async () => {
          // The premise of everything below, asserted rather than assumed. If
          // ingest rounded, dropped or recomputed these, the rule would be
          // selecting over numbers this spec never wrote — and a pass would
          // mean nothing.
          for (const [label, traceId, expected] of [
            ['fast', fastTraceId, FAST_MS],
            ['slow', slowTraceId, SLOW_MS],
          ] as const) {
            await expect
              .poll(() => backendClient.getTraceDuration(traceId), {
                message: `the ${label} trace's stored duration in ms`,
                timeout: 60_000,
              })
              .toBe(expected);
          }
          expect(
            FAST_MS < THRESHOLD_MS && SLOW_MS > THRESHOLD_MS,
            `the seed must straddle the ${THRESHOLD_MS}ms threshold for this test to mean anything`,
          ).toBe(true);
        });

        await test.step('The control rule scored both traces, so the engine processed both', async () => {
          for (const [label, traceId] of [
            ['fast', fastTraceId],
            ['slow', slowTraceId],
          ] as const) {
            const score = await backendClient.pollTraceForFeedbackScore(traceId, controlScore, {
              timeoutMs: 120_000,
            });
            expect(score.value, `the control score on the ${label} trace`).toBe(1);
          }
        });

        await test.step('The filtered rule scored the slow trace', async () => {
          const score = await backendClient.pollTraceForFeedbackScore(
            slowTraceId,
            durationScore,
            { timeoutMs: 120_000 },
          );
          expect(score.value, `the duration rule's score on the ${SLOW_MS}ms trace`).toBe(1);
        });

        await test.step('The filtered rule did NOT score the fast trace', async () => {
          // Settled first: the control landing says the engine reached this
          // trace, not that the duration rule has finished deciding about it.
          // Without the quiet period a still-in-flight score reads as a
          // correctly-excluded one.
          const settled = await backendClient.waitForTraceScoresSettled(fastTraceId, {
            timeoutMs: 120_000,
          });
          const names = settled.feedbackScores.map((fs) => fs.name).sort();
          // The whole score set, not just "the duration score is missing": a
          // fast trace that picked up some OTHER unexpected score means the
          // project is not as isolated as this spec assumes, and comparing the
          // collection says so instead of quietly tolerating it.
          expect(
            names,
            `the ${FAST_MS}ms trace is below the ${THRESHOLD_MS}ms threshold, so it must carry ` +
              'the control score and nothing else — a duration score here is the ' +
              'seconds-vs-milliseconds bug, where "> 5" means "> 5ms" and matches everything',
          ).toEqual([controlScore]);
        });
      },
    );
  },
);
