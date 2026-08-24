import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';
import {
  buildPythonEqualsMetric,
  PYTHON_EQUALS_ARGUMENTS,
} from '@e2e/pom/online-evaluation.page';

const REFERENCE_OUTPUT = 'seed output';

/**
 * Traces seeded for the zero-rate rule to skip.
 *
 * Small on purpose: at rate 0 every trace produces exactly one line, so the
 * page's row count is fixed by construction and nothing is gained from a larger
 * batch. Bigger than 1 so "one line per trace" is a real claim rather than a
 * coincidence, and not a round number that a hardcoded page size could match by
 * accident.
 */
const BATCH_SIZE = 6;

test.describe('Online Evaluation — automation logs', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('The automation-logs page shows one skip line per trace for a 0%-rate rule', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // The two API polls (60s for the log lines, then 120s + 60s per trace for
    // the control's score and the settle) run to at most ~240s in the worst
    // case, and the per-trace waits are concurrent. 300s leaves room for the
    // page load without pre-empting the inner waits, whose diagnostics name the
    // trace and the lines actually seen.
    test.setTimeout(300_000);

    // At rate 0, `secureRandom.nextFloat() >= 0.0` is true for every draw, so
    // every SDK trace is skipped — no binomial band, no flake budget. That is
    // what makes an exact row count assertable here where the 50%-rate spec can
    // only assert a range.
    const ZERO_RATE = 0.0;
    const skipRule = `${testNamespace}-zero-rate`;
    const controlRule = `${testNamespace}-control`;

    const rules = await test.step(
      'Create a 0%-rate rule and a full-rate control rule via the API',
      async () => {
        // Both production-scope (the backend default) so both see the same SDK
        // traces, and both run the same metric, so the only difference between
        // them is the rate.
        const [skip, control] = await Promise.all(
          (
            [
              { name: skipRule, rate: ZERO_RATE },
              { name: controlRule, rate: 1.0 },
            ] as const
          ).map((spec) =>
            backendClient.createAutomationRule({
              projectId: project.id,
              name: spec.name,
              metric: buildPythonEqualsMetric(spec.name, REFERENCE_OUTPUT),
              metricArguments: PYTHON_EQUALS_ARGUMENTS,
              samplingRate: spec.rate,
              triggerScope: 'production',
            }),
          ),
        );
        return { skip, control };
      },
    );

    // Asserted before seeding. The dialog shows a percentage and the API stores
    // a fraction; a rule that silently landed on the 100% default would score
    // every trace and emit no skip lines at all, and the failure would then read
    // as "the logs page is broken" rather than "the rate was never applied".
    await test.step('The zero-rate rule persisted as the fraction 0', async () => {
      expect(rules.skip.samplingRate, 'the 0% rule persisted as 0.0').toBeCloseTo(ZERO_RATE, 5);
      expect(rules.control.samplingRate, 'the control persisted at full rate').toBeCloseTo(
        1.0,
        5,
      );
    });

    const seededTraces = await test.step(
      `Seed ${BATCH_SIZE} SDK traces via the REST write`,
      async () => {
        // Ids minted up front because the write answers 204 with no body and
        // every row assertion below is "this line names this trace".
        //
        // Seeded complete (start and end in one write) rather than through the
        // SDK bridge: the sampler drops traces with a null end_time, and a
        // single complete write produces exactly one scoring event, hence
        // exactly one log line per trace. A create-then-update sequence would
        // make the expected line count depend on how the SDK chose to split it.
        const now = new Date();
        return Promise.all(
          Array.from({ length: BATCH_SIZE }, async (_, i) => {
            const id = uuid7();
            const name = `${testNamespace}-trace-${String(i).padStart(2, '0')}`;
            await backendClient.createTraceWithSource({
              id,
              projectName: project.name,
              name,
              source: 'sdk',
              startTime: now,
              endTime: now,
              input: { text: 'whatever' },
              output: { output: REFERENCE_OUTPUT },
            });
            return { id, name };
          }),
        );
      },
    );

    const seededIds = new Set(seededTraces.map((t) => t.id));

    await test.step(
      `The rule's log settles at exactly ${BATCH_SIZE} lines, one per trace`,
      async () => {
        // Poll to equality rather than to a minimum: the log stream is
        // eventually consistent, so a `>=` wait would return on the first
        // partial page and a later duplicate would never be noticed. Equality
        // holds by construction — one skip decision per trace, no scoring
        // afterwards to add lines of its own.
        await expect
          .poll(
            async () => (await backendClient.getAutomationRuleLogs(rules.skip.id)).length,
            {
              message: `the 0%-rate rule must log exactly one line per seeded trace (${BATCH_SIZE})`,
              timeout: 60_000,
              intervals: [1_000, 2_000, 5_000],
            },
          )
          .toBe(BATCH_SIZE);

        const logs = await backendClient.getAutomationRuleLogs(rules.skip.id);
        // Assert the whole answer: the set of trace ids the log names must be
        // exactly the set seeded. Checking only that each seeded trace appears
        // would pass while the rule was also logging somebody else's traces.
        expect(
          [...new Set(logs.map((entry) => entry.traceId))].sort(),
          'the log names exactly the seeded traces, no more and no fewer',
        ).toEqual([...seededIds].sort());

        for (const entry of logs) {
          expect(
            entry.message,
            `a skip line must name its own trace (${entry.traceId})`,
          ).toContain(entry.traceId);
          expect(entry.message, 'a skip line must name the rule it belongs to').toContain(
            skipRule,
          );
          expect(
            entry.message,
            'a skip line must say the sampling rate is why the trace was skipped',
          ).toContain('sampling rate');
        }
      },
    );

    await test.step(
      'The lines and the scores agree: the control scored every trace, the 0% rule scored none',
      async () => {
        // The control is what makes the absence meaningful. Its score landing
        // proves the engine processed the trace, so "no score from the 0% rule"
        // is a decision rather than a trace still in flight.
        const settled = await Promise.all(
          seededTraces.map(async (trace) => {
            await backendClient.pollTraceForFeedbackScore(trace.id, controlRule, {
              timeoutMs: 120_000,
            });
            const detail = await backendClient.waitForTraceScoresSettled(trace.id, {
              quietPeriodMs: 8_000,
              timeoutMs: 60_000,
              minScores: 1,
            });
            return { ...trace, scoreNames: detail.feedbackScores.map((s) => s.name) };
          }),
        );

        for (const trace of settled) {
          expect(
            trace.scoreNames,
            `the control rule must score ${trace.name} — otherwise the absence below is a ` +
              'timing artefact, not a sampling decision',
          ).toContain(controlRule);
          expect(
            trace.scoreNames,
            `${trace.name} was logged as skipped, so it must carry no score from ${skipRule}`,
          ).not.toContain(skipRule);
        }
      },
    );

    await test.step('The automation-logs page renders those lines', async () => {
      const logsPage = new AutomationLogsPage(page);
      await logsPage.goto(rules.skip.id);
      await logsPage.waitForRowCount(BATCH_SIZE);

      const rendered = await logsPage.readRows();
      expect(
        rendered.map((row) => row.traceId).sort(),
        'the Trace Id column shows exactly the seeded traces',
      ).toEqual([...seededIds].sort());

      for (const row of rendered) {
        expect(
          row.message,
          `the Message column for ${row.traceId} must name that trace`,
        ).toContain(row.traceId);
        expect(row.message, 'the Message column names the rule').toContain(skipRule);
      }
    });

    // Rules are removed by the `automationRulesCleanup` fixture, which runs
    // whatever the test's outcome — they do NOT cascade with the project. The
    // traces do (see projects/project-delete.spec.ts), so the project fixture
    // covers them.
  });
});
