import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

const REFERENCE_OUTPUT = 'seed output';

/**
 * Size of the single seeded batch both rules are judged against.
 *
 * The backend samples with an independent Bernoulli draw per trace
 * (`OnlineScoringSampler.shouldSampleTrace`: `secureRandom.nextFloat() >=
 * samplingRate` -> skip). There is no seed hook, so the count scored at 50% is
 * a genuine binomial random variable and the assertion has to be a band, not an
 * equality.
 *
 * n=30 with the band below leaves a ~1-in-29,000 false-failure rate
 * (P(5 <= X <= 25) for X~B(30, 0.5) = 0.999966). Tightening the band to
 * 25%-75% would cut that to ~1-in-300 — a flake every few weeks of CI — while
 * catching no additional real regression, since every plausible break in this
 * code path lands far outside even the wide band (see PARTIAL_RATE_* below).
 */
const BATCH_SIZE = 30;

/**
 * Acceptance band for the partial-rate rule, as trace counts out of
 * BATCH_SIZE: 15%-85% of the batch.
 *
 * Deliberately loose. The regressions this test exists to catch are structural,
 * not subtle drifts in the sampler's uniformity:
 *   - sampling ignored entirely          -> 30 scored (above band)
 *   - rate read as 0 / rule never fires  -> 0 scored  (below band)
 *   - percent/fraction confusion (50 vs 0.5, the exact bug the UI's
 *     percent-display / API-fraction split invites) -> 30 or 0 (outside)
 *   - off-by-10x (0.05 instead of 0.5)   -> ~2 scored (below band)
 * A band that only rejects "not close to half" still rejects all of these.
 */
const PARTIAL_RATE_MIN_SCORED = 5; // 15% of 30, rounded down
const PARTIAL_RATE_MAX_SCORED = 25; // 85% of 30, rounded down

test.describe('Online Evaluation — sampling rate', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A 50% rule scores roughly half of a 30-trace batch while a 100% rule scores all of it', { tag: ['@cap:online-evaluation.sampling-rate'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    // Sized above the sum of the inner waits: one 30-trace seed plus a settle
    // pass over 30 traces. Each inner wait throws a diagnostic naming what it
    // saw, which is far more useful than an opaque test-level timeout, so this
    // ceiling only decides which error surfaces on a genuine stall.
    test.setTimeout(900_000);

    // Two deterministic Python-Equals rules over ONE batch of traces. The
    // full-rate rule is the control: it turns "the partial rule skipped these
    // traces" into a real negative rather than a timing guess, because its own
    // score landing on a trace proves the engine actually processed that trace.
    // Without it, a stalled scoring pipeline reads identically to aggressive
    // sampling.
    const partialRule = `${testNamespace}-partial-50`;
    const fullRule = `${testNamespace}-full-100`;

    const onlineEval = new OnlineEvaluationPage(page);

    // try/finally rather than a trailing cleanup step: a failure anywhere in
    // the body would otherwise skip the deletion, and the project fixture's
    // teardown does not reach automation rules (ProjectService.delete does not
    // cascade through automation_rule_projects), so a red run leaks both rules.
    try {

      await test.step('Create a 50% rule and a 100% rule via the UI', async () => {
        await onlineEval.goto(project.id);
        await onlineEval.waitForReady();

        await onlineEval.openCreateRuleDialog();
        await onlineEval.fillAndSubmitCreateRuleDialogPythonEquals({
          name: partialRule,
          referenceValue: REFERENCE_OUTPUT,
          samplingRatePercent: 50,
        });
        await expect(onlineEval.ruleRow(partialRule)).toBeVisible();

        // Left at the control's 100% default — set explicitly so the test states
        // the value it depends on rather than inheriting it.
        await onlineEval.openCreateRuleDialog();
        await onlineEval.fillAndSubmitCreateRuleDialogPythonEquals({
          name: fullRule,
          referenceValue: REFERENCE_OUTPUT,
          samplingRatePercent: 100,
        });
        await expect(onlineEval.ruleRow(fullRule)).toBeVisible();
      });

      await test.step('The list shows each rule\'s configured rate', async () => {
        await expect(onlineEval.ruleSamplingRateCell(partialRule, '50%')).toBeVisible();
        await expect(onlineEval.ruleSamplingRateCell(fullRule, '100%')).toBeVisible();
      });

      // This gate runs BEFORE any trace is seeded, and it is the reason the rest
      // of the test means anything. The dialog commits the sampling rate on blur,
      // so a UI regression there posts the default 1.0 while still displaying the
      // typed number. A rule silently left at 100% scores every trace — which is
      // indistinguishable from "sampling works and we got a high draw" — so the
      // whole test would pass while asserting nothing about sampling at all.
      // Checking the persisted fraction up front converts that silent no-op into
      // a loud, early failure.
      await test.step('Both rules persisted the rate the dialog displayed', async () => {
        const rules = await backendClient.listAutomationRulesForProject(project.id);
        const byName = new Map(rules.map((r) => [r.name, r.samplingRate]));
        // The dialog shows a percentage; the API stores a fraction.
        expect(byName.get(partialRule), 'the 50% rule persisted as the fraction 0.5').toBeCloseTo(
          0.5,
          5,
        );
        expect(byName.get(fullRule), 'the 100% rule persisted as the fraction 1.0').toBeCloseTo(
          1.0,
          5,
        );
      });

      const seededTraces = await test.step(
        `Seed one batch of ${BATCH_SIZE} matching traces via SDK`,
        async () => {
          // One batch, both rules — the comparison is only meaningful if the
          // control and the partial rule are judged on the same traces. Every
          // output matches REFERENCE_OUTPUT, so any trace either rule actually
          // evaluates scores 1.0; a 0.0 would mean the metric ran on unexpected
          // input rather than that sampling skipped the trace.
          const created = await Promise.all(
            Array.from({ length: BATCH_SIZE }, (_, i) =>
              sdkClient.python.createTrace({
                project_name: project.name,
                name: `${testNamespace}-trace-${String(i).padStart(2, '0')}`,
                input: 'whatever',
                output: REFERENCE_OUTPUT,
              }),
            ),
          );
          return created;
        },
      );

      const scoredByRule = await test.step(
        'Wait for scoring to settle on every trace, then tally which rules scored which traces',
        async () => {
          // An absence assertion is only safe once the engine has stopped
          // writing. The sampler enqueues rules onto per-type Redis streams via
          // parallelStream(), so one rule's score landing says nothing about the
          // other's progress.
          //
          // Two-phase wait, and the order matters. waitForTraceScoresSettled
          // decides "settled" from a stable score-set fingerprint, so a
          // not-yet-scored trace looks stable from its very first poll: with
          // minScores: 0 it returns after one quiet period holding an EMPTY score
          // set while the control's score is still in flight, and that trace is
          // then tallied as "the control never scored it". timeoutMs cannot
          // rescue that — the helper returns early rather than timing out.
          //
          // So anchor on the control first and poll each trace until the 100%
          // rule has scored it. That arrival is guaranteed by construction (a
          // full-rate rule scores every trace), so it is safe to wait for, and it
          // proves the engine processed the trace. Only then wait for the score
          // set to go quiet, which is what catches the partial rule's score if it
          // is also coming. minScores: 1 is now correct, because the control's
          // score is already present before the settle begins.
          const settled = await Promise.all(
            seededTraces.map(async (t) => {
              await backendClient.pollTraceForFeedbackScore(t.id, fullRule, {
                timeoutMs: 240_000,
              });
              const trace = await backendClient.waitForTraceScoresSettled(t.id, {
                quietPeriodMs: 10_000,
                timeoutMs: 120_000,
                minScores: 1,
              });
              return { id: t.id, name: t.name, scores: trace.feedbackScores };
            }),
          );

          const partialScored: string[] = [];
          const fullScored: string[] = [];
          for (const trace of settled) {
            for (const score of trace.scores) {
              if (score.name === partialRule) {
                expect(
                  score.value,
                  `partial rule scored ${trace.name}; every seeded output matches the ` +
                    `reference so an evaluated trace must be 1.0`,
                ).toBe(1.0);
                partialScored.push(trace.name);
              }
              if (score.name === fullRule) {
                expect(score.value, `control rule scored ${trace.name}`).toBe(1.0);
                fullScored.push(trace.name);
              }
            }
          }
          return { partialScored, fullScored };
        },
      );

      await test.step(
        `Control: the 100% rule scored all ${BATCH_SIZE} traces`,
        async () => {
          // Establishes that the engine processed the whole batch. Every
          // conclusion about the partial rule's skips rests on this.
          expect(
            scoredByRule.fullScored.length,
            `a 100%-sampled rule must score every trace in the batch — ` +
              `missing: ${seededTraces
                .map((t) => t.name)
                .filter((n) => !scoredByRule.fullScored.includes(n))
                .join(', ')}`,
          ).toBe(BATCH_SIZE);
        },
      );

      await test.step(
        `The 50% rule scored a partial subset (${PARTIAL_RATE_MIN_SCORED}-${PARTIAL_RATE_MAX_SCORED} of ${BATCH_SIZE})`,
        async () => {
          const scored = scoredByRule.partialScored.length;

          // Report the observed rate on failure: the count alone doesn't say
          // whether the break was "sampling ignored" or "rule never fired".
          const observedPct = ((scored / BATCH_SIZE) * 100).toFixed(1);
          const diagnostic =
            `50%-sampled rule scored ${scored}/${BATCH_SIZE} traces (${observedPct}%). ` +
            `Expected roughly half; band is ${PARTIAL_RATE_MIN_SCORED}-${PARTIAL_RATE_MAX_SCORED}. ` +
            `The control rule scored ${scoredByRule.fullScored.length}/${BATCH_SIZE}, so the ` +
            `engine did process this batch.`;

          expect(scored, diagnostic).toBeGreaterThanOrEqual(PARTIAL_RATE_MIN_SCORED);
          expect(scored, diagnostic).toBeLessThanOrEqual(PARTIAL_RATE_MAX_SCORED);
        },
      );

      await test.step(
        'The 50% rule genuinely skipped traces the control scored',
        async () => {
          // The band above would still pass if the partial rule had scored every
          // trace in a batch that the control somehow under-covered. State the
          // real claim directly: sampling SKIPPED work that was available to be
          // done, and the skipped traces are ones we can prove reached the engine.
          const skipped = scoredByRule.fullScored.filter(
            (name) => !scoredByRule.partialScored.includes(name),
          );
          expect(
            skipped.length,
            `at least one trace the control scored must have been skipped by the ` +
              `50% rule — otherwise the sampling rate had no effect`,
          ).toBeGreaterThan(0);
          expect(
            scoredByRule.partialScored.length,
            `the 50% rule must still score some traces — zero would mean the rule ` +
              `never fired rather than that it sampled`,
          ).toBeGreaterThan(0);
        },
      );

    } finally {
      // Deletes every rule in the project rather than two captured ids: this
      // project is the test's own throwaway fixture, so anything here is ours,
      // and a rule created moments before a failure has no captured id yet.
      await test.step('Cleanup: delete both rules (project teardown does not cascade)', async () => {
        const rules = await backendClient.listAutomationRulesForProject(project.id);
        for (const rule of rules) {
          await backendClient.deleteAutomationRule(project.id, rule.id);
        }
      });
    }
  });
});
