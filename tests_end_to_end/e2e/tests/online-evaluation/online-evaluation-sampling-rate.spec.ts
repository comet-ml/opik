import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7 } from '@e2e/core/backend';

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
 * n=30 is the smallest batch that keeps the band below comfortably wide while
 * staying quick to seed; the exact flake budget is derived from the band and
 * documented on PARTIAL_RATE_MIN_FRACTION below.
 */
const BATCH_SIZE = 30;

/**
 * Acceptance band for the partial-rate rule, expressed as the fraction of the
 * batch that may be scored, and converted to inclusive trace counts below.
 *
 * Deliberately loose. The regressions this test exists to catch are structural,
 * not subtle drifts in the sampler's uniformity:
 *   - sampling ignored entirely          -> 30 scored (above band)
 *   - rate read as 0 / rule never fires  -> 0 scored  (below band)
 *   - percent/fraction confusion (50 vs 0.5, the exact bug the UI's
 *     percent-display / API-fraction split invites) -> 30 or 0 (outside)
 *   - off-by-10x (0.05 instead of 0.5)   -> ~2 scored (below band)
 * A band that only rejects "not close to half" still rejects all of these.
 *
 * At BATCH_SIZE=30 these fractions give inclusive bounds of 4..26, for a
 * false-failure rate of ~1 in 118,000: P(4 <= X <= 26) for X~B(30, 0.5) =
 * 0.9999916. Tightening to 25%-75% (8..22) would cost ~1 in 1,500 — a flake
 * every few weeks of CI — and catch nothing extra, since every plausible break
 * lands at 0, ~2, or 30.
 *
 * Derived rather than hardcoded so the bounds, the reported percentages and the
 * quoted flake rate cannot drift apart from each other.
 */
const PARTIAL_RATE_MIN_FRACTION = 0.15;
const PARTIAL_RATE_MAX_FRACTION = 0.85;
const PARTIAL_RATE_MIN_SCORED = Math.floor(PARTIAL_RATE_MIN_FRACTION * BATCH_SIZE);
const PARTIAL_RATE_MAX_SCORED = Math.ceil(PARTIAL_RATE_MAX_FRACTION * BATCH_SIZE);

/**
 * A Python metric that scores every trace it is handed, whatever the trace says.
 *
 * The bypass test below is about WHICH traces reach the evaluator, never about
 * what the metric concludes, so the value is a constant: a 0.0 would then mean
 * "the metric ran on something unexpected", not "sampling skipped this trace".
 *
 * `output` is defaulted so a trace whose mapped section fails to resolve still
 * calls `score()` rather than raising a TypeError that would read as a scoring
 * failure. The same constraints as `buildPythonEqualsMetric` in
 * `online-evaluation.page.ts` apply: no extra BaseMetric imports (the python
 * evaluator picks the first BaseMetric subclass alphabetically), and the
 * ScoreResult name — not the rule name — is what lands on the trace.
 */
function buildAlwaysScoresMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class AlwaysScores(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)`;
}

/**
 * Trace sources that bypass the sampling rate, and how each becomes scorable.
 *
 * `evaluator` also bypasses it (`Source.isLoggingSource` is true only for `sdk`
 * and null), but is deliberately absent: an evaluator-source trace is a score's
 * own trace, so scoring it is what the product avoids rather than something to
 * assert here.
 */
const BYPASS_SOURCES = [
  // An experiment trace needs no rule selection — the sampler treats SDK and
  // experiment sources as implicitly eligible for every rule in the project.
  { source: 'experiment' as const, needsRuleSelection: false },
  // Playground and optimization traces are only scorable when they name the
  // rule in `metadata.selected_rule_ids` (OnlineScoringSampler.sampleAndScore).
  { source: 'playground' as const, needsRuleSelection: true },
  { source: 'optimization' as const, needsRuleSelection: true },
];

/** How many SDK traces the 0% rule must skip. */
const SDK_TRACE_COUNT = 4;

test.describe('Online Evaluation — sampling rate', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A 50% rule scores roughly half of a 30-trace batch while a 100% rule scores all of it', { tag: ['@cap:online-evaluation.sampling-rate'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // The inner waits run concurrently across the batch (Promise.all), so the
    // worst case is one trace's chain — a 120s control poll plus a 90s settle —
    // not the sum over 30 traces. 300s covers that plus rule creation and the
    // seed, with room for a slow CI box; observed runtime is 29-46s.
    //
    // Kept above the inner waits on purpose so those fire first: each throws a
    // diagnostic naming the trace and the scores it actually saw, which beats
    // an opaque "test timeout exceeded". But only just above — a ceiling far
    // higher than the inner waits only delays the failure without improving it.
    test.setTimeout(300_000);

    // Two deterministic Python-Equals rules over ONE batch of traces. The
    // full-rate rule is the control: it turns "the partial rule skipped these
    // traces" into a real negative rather than a timing guess, because its own
    // score landing on a trace proves the engine actually processed that trace.
    // Without it, a stalled scoring pipeline reads identically to aggressive
    // sampling.
    const partialRule = `${testNamespace}-partial-50`;
    const fullRule = `${testNamespace}-full-100`;

    const onlineEval = new OnlineEvaluationPage(page);


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
    // of the test means anything. The dialog shows a percentage and the API
    // stores a fraction, so a regression in that conversion — or any change
    // that drops the typed value before submit — would leave the rule at the
    // 100% default while the UI still displayed the number the test asked for.
    // A rule silently left at 100% scores every trace, which is
    // indistinguishable from "sampling works and we got a high draw", so the
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
              timeoutMs: 120_000,
            });
            const trace = await backendClient.waitForTraceScoresSettled(t.id, {
              quietPeriodMs: 10_000,
              timeoutMs: 90_000,
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
  });

  test('A 0% rule skips every SDK trace but still scores experiment, playground and optimization traces', { tag: ['@cap:online-evaluation.sampling-rate', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // Scoring is asynchronous end-to-end (ingest -> sampler -> Redis stream ->
    // python evaluator -> score write -> user log write), and the absence half
    // waits on the log stream rather than on a score arriving. Observed runtime
    // is well inside this; the inner polls below fail first with a diagnostic.
    test.setTimeout(300_000);

    // The complement of the 50%-vs-100% test above: that one pins the behaviour
    // that must NOT change (an SDK stream is thinned by the rate), this one pins
    // the carve-out — `OnlineScoringSampler.shouldSampleTrace` returns true for
    // any non-SDK source BEFORE consulting the rate, because the rate exists to
    // thin a production firehose and an experiment/playground/optimization trace
    // is a deliberate, user-initiated evaluation.
    //
    // Rate 0.0 makes that partition exact rather than statistical: no SDK trace
    // can ever clear `secureRandom.nextFloat() >= 0.0`, and every non-SDK trace
    // short-circuits above that line. So this test asserts equalities, not a
    // band, and has no flake budget to spend.
    const ruleName = `${testNamespace}-zero-rate`;
    const bypassCount = BYPASS_SOURCES.length;

    const ruleId = await test.step('Create a 0%-rate rule scoped to both production and experiment traces', async () => {
      // Created through the API, not the dialog: `trigger_scope` has no control
      // in the create-rule dialog, and without `both` an experiment-source trace
      // is filtered out by `matchesTriggerScope` before sampling is ever
      // consulted — the test would then pass for the wrong reason.
      return backendClient.createAutomationRule({
        projectId: project.id,
        name: ruleName,
        samplingRate: 0,
        triggerScope: 'both',
        metric: buildAlwaysScoresMetric(ruleName),
        arguments: { output: 'output.output' },
      });
    });

    await test.step('The rule persisted the rate and scope this test depends on', async () => {
      // Runs before anything is seeded. A rule that silently fell back to the
      // server defaults (rate 1.0, scope `production`) would score the SDK
      // traces and drop the experiment ones — a completely different test that
      // would still produce a plausible-looking result.
      const rule = await backendClient.getAutomationRule(ruleId);
      expect(rule.samplingRate, 'a 0% rule must persist as the fraction 0').toBe(0);
      expect(rule.triggerScope, 'scope must be `both`, not the `production` default').toBe(
        'both',
      );
      expect(rule.enabled, 'a disabled rule would skip every trace for the wrong reason').toBe(
        true,
      );
    });

    const sdkTraces = await test.step(
      `Seed ${SDK_TRACE_COUNT} SDK traces and ${bypassCount} non-SDK traces`,
      async () => {
        // Seeded through the REST write rather than the SDK bridge because the
        // bridge always emits `source=sdk`, and `source` is the entire subject
        // of this test. Ids are minted up front (the write answers 204 with no
        // body) so every assertion below can name the trace it is about.
        const seed = async (
          source: 'sdk' | 'experiment' | 'playground' | 'optimization',
          index: number,
          needsRuleSelection: boolean,
        ) => {
          const id = uuid7();
          const now = new Date();
          await backendClient.createTraceWithSource({
            id,
            projectName: project.name,
            name: `${testNamespace}-${source}-${index}`,
            source,
            input: { q: 'whatever' },
            output: { output: REFERENCE_OUTPUT },
            // end_time is what makes the trace a complete write; the sampler
            // discards partial traces outright.
            startTime: now,
            endTime: now,
            ...(needsRuleSelection ? { metadata: { selected_rule_ids: [ruleId] } } : {}),
          });
          return { id, source, name: `${testNamespace}-${source}-${index}` };
        };

        const sdk = await Promise.all(
          Array.from({ length: SDK_TRACE_COUNT }, (_, i) => seed('sdk', i, false)),
        );
        const bypass = await Promise.all(
          BYPASS_SOURCES.map((s, i) => seed(s.source, i, s.needsRuleSelection)),
        );
        return { sdk, bypass };
      },
    );

    await test.step(
      `Every non-SDK trace was scored despite the 0% rate (${bypassCount} of ${bypassCount})`,
      async () => {
        const scored = await Promise.all(
          sdkTraces.bypass.map(async (t) => {
            const score = await backendClient.pollTraceForFeedbackScore(t.id, ruleName, {
              timeoutMs: 180_000,
            });
            return { ...t, value: score.value };
          }),
        );
        for (const t of scored) {
          expect(
            t.value,
            `${t.source} trace ${t.name} was evaluated, so the constant metric must return 1.0`,
          ).toBe(1.0);
        }
        // The count is asserted separately from the values so "one source
        // silently stopped bypassing" fails as a count, not as a timeout on a
        // trace nobody named.
        expect(scored.length, 'every non-SDK source must bypass the rate').toBe(bypassCount);
      },
    );

    const logMessages = await test.step(
      `Wait until the rule has ruled on all ${SDK_TRACE_COUNT} SDK traces`,
      async () => {
        // The absence assertion below is only sound once the engine has
        // finished with each SDK trace, and a skipped trace produces no score
        // to wait on. The rule's own log stream is the only signal that says
        // "this trace was seen and deliberately dropped", so anchor on it: one
        // skip line per SDK trace id.
        let messages: string[] = [];
        await expect
          .poll(
            async () => {
              const logs = await backendClient.getAutomationRuleLogs(ruleId);
              messages = logs.map((l) => `${l.level} ${l.message}`);
              return sdkTraces.sdk.filter((t) =>
                messages.some(
                  (m) => m.includes(t.id) && m.includes('per the sampling rate'),
                ),
              ).length;
            },
            {
              timeout: 180_000,
              intervals: [2_000, 5_000],
              message:
                'the rule must log a sampling skip for every SDK trace — without it, ' +
                '"no score" cannot be distinguished from "not processed yet"',
            },
          )
          .toBe(SDK_TRACE_COUNT);
        return messages;
      },
    );

    await test.step('No SDK trace carries any feedback score', async () => {
      for (const t of sdkTraces.sdk) {
        const trace = await backendClient.getTrace(t.id);
        expect(trace, `SDK trace ${t.name} must still exist to be asserted about`).not.toBeNull();
        expect(
          trace!.feedbackScores.map((s) => s.name),
          `a 0%-sampled rule must never score SDK trace ${t.name}`,
        ).toEqual([]);
      }
    });

    await test.step('The log stream explains every decision, and only those', async () => {
      // Asserting the counts rather than "at least one of each" is what catches
      // a leak: a rule that bypassed an SDK trace as well would raise the bypass
      // count above the number of non-SDK traces without failing any assertion
      // made so far.
      const bypassLines = logMessages.filter((m) =>
        m.includes('the rate applies to production traces only'),
      );
      const skipLines = logMessages.filter((m) => m.includes('per the sampling rate'));

      for (const t of sdkTraces.bypass) {
        expect(
          bypassLines.filter((m) => m.includes(t.id)),
          `${t.source} trace ${t.name} must be logged as exempt from the rate`,
        ).toHaveLength(1);
      }
      expect(
        bypassLines,
        'exactly the non-SDK traces may bypass the rate',
      ).toHaveLength(bypassCount);
      expect(skipLines, 'exactly the SDK traces may be skipped by the rate').toHaveLength(
        SDK_TRACE_COUNT,
      );
    });

    await test.step('The bypassed playground trace renders its score in the trace panel', async () => {
      // The API says the score landed; this says a user can see it. Playground
      // traces are the case a reader is most likely to doubt, since they only
      // become scorable through `selected_rule_ids`.
      const playgroundTrace = sdkTraces.bypass.find((t) => t.source === 'playground');
      expect(playgroundTrace, 'the seed must include a playground trace').toBeDefined();

      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();
      const panel = await logs.openTraceById(playgroundTrace!.id);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();
      expect(await panel.readFeedbackScoreValue(ruleName)).toBe(1.0);
    });
  });
});
