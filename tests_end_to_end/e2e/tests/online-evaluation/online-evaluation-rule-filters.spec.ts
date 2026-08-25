import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/**
 * The filter a production trace must match, and no trace in this spec does.
 *
 * The claim is about the branch a trace takes, not about the expressiveness of
 * the filter language, so the condition is deliberately one that cannot match
 * anything: `name contains NEVER-MATCHES` against names the spec itself mints.
 * A filter that could plausibly match would turn "the experiment trace was
 * scored because filters are skipped" into "…or because it happened to match".
 */
const UNMATCHABLE_VALUE = 'NEVER-MATCHES';

/** The engine's own words for the filter-mismatch skip, from `OnlineScoringSampler.shouldSampleTrace`. */
const FILTER_SKIP_PHRASE = 'does not match the configured filters';

/** The engine's own words for the sampling-rate skip — the neighbouring branch. */
const SAMPLING_SKIP_PHRASE = 'per the sampling rate';

test.describe('Online Evaluation — rule filters', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A rule\'s filters gate its production traces and are ignored on its experiment traces', { tag: ['@cap:online-evaluation.rule-filters'] }, async ({
    project,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    // API-level throughout: the subject is which traces the scoring engine
    // decides to evaluate, which no page renders. The rule's filter is visible
    // in the rules list, but "this rule scored a trace its filter excludes" is
    // not a UI fact, so driving a browser here would only observe the same
    // scores second-hand and add flake.
    test.setTimeout(180_000);

    const ruleName = `${testNamespace}-filtered-rule`;
    // The score lands under the ScoreResult's name, not the rule's — see
    // buildConstantScoreMetric. Kept distinct so a mix-up fails loudly.
    const scoreName = `${testNamespace}-filtered-score`;

    const ruleId = await test.step(
      'Create one enabled 100% rule at trigger_scope=both whose filter cannot match',
      async () => {
        return backendClient.createAutomationRule({
          projectId: project.id,
          name: ruleName,
          // Full rate and enabled on purpose: `shouldSampleTrace` checks
          // enabled, then filters, then the rate. Anything less than 1.0 or a
          // disabled rule would let the SDK trace be skipped by a branch this
          // test is not about, and the absence assertion below would pass for
          // the wrong reason.
          samplingRate: 1,
          enabled: true,
          // `both` is what makes the comparison fair: the rule is in scope for
          // the SDK trace AND the experiment trace, so the only difference
          // between the two outcomes is the filter.
          triggerScope: 'both',
          filters: [{ field: 'name', operator: 'contains', value: UNMATCHABLE_VALUE }],
          metric: buildConstantScoreMetric(scoreName),
          arguments: { output: 'output' },
        });
      },
    );

    // This gate runs BEFORE any trace is seeded, and the rest of the test means
    // nothing without it. `filters` does not exist on the pinned SDK's evaluator
    // shape, so a payload the backend quietly dropped would leave an
    // unfiltered rule behind — which scores every trace of both sources and
    // makes "the experiment trace was scored despite the filter" trivially true
    // while asserting nothing about filters at all.
    await test.step('The rule persisted the filter, the scope and the rate it was created with', async () => {
      const rule = await backendClient.getAutomationRule(ruleId);
      expect(rule.triggerScope, 'the rule must be in scope for both sources').toBe('both');
      expect(rule.enabled, 'the rule must be enabled').toBe(true);
      expect(rule.samplingRate, 'the rule must be at full rate').toBeCloseTo(1.0, 5);
      expect(rule.filters, 'the filter must have persisted verbatim').toEqual([
        { field: 'name', operator: 'contains', value: UNMATCHABLE_VALUE },
      ]);
    });

    const traces = await test.step(
      'Seed one source=sdk trace and one source=experiment trace, neither matching the filter',
      async () => {
        const now = new Date();
        const seeds = [
          { source: 'sdk' as const, name: `${testNamespace}-production-trace` },
          { source: 'experiment' as const, name: `${testNamespace}-experiment-trace` },
        ];
        for (const seed of seeds) {
          expect(
            seed.name.includes(UNMATCHABLE_VALUE),
            `seeded trace '${seed.name}' must not match the rule's filter`,
          ).toBe(false);
        }
        const ids = await Promise.all(
          seeds.map((seed) =>
            backendClient.createTraceWithSource({
              id: uuid7(),
              projectName: project.name,
              name: seed.name,
              source: seed.source,
              input: { question: 'what is the capital of France' },
              output: { answer: 'Paris' },
              // Both ends are required: the sampler drops every trace with a
              // null end_time as a partial write, so a trace seeded without one
              // is never offered to the rule at all.
              startTime: now,
              endTime: now,
            }),
          ),
        );
        return { sdkId: ids[0], sdkName: seeds[0].name, experimentId: ids[1], experimentName: seeds[1].name };
      },
    );

    await test.step(
      'The experiment trace is scored — filters do not apply on the experiment path',
      async () => {
        // `shouldScoreTrace` returns `matchesTriggerScope && isEnabled` for an
        // EXPERIMENT trace and never consults `getFilters()`, so a rule scoped
        // to a slice of production scores every experiment trace in its project.
        const score = await backendClient.pollTraceForFeedbackScore(
          traces.experimentId,
          scoreName,
          { timeoutMs: 120_000 },
        );
        expect(
          score.value,
          'the constant metric returns 1.0 for any input, so anything else means it ran on something unexpected',
        ).toBe(1.0);

        const trace = await backendClient.getTrace(traces.experimentId);
        expect(trace, `experiment trace ${traces.experimentId} must be readable`).not.toBeNull();
        expect(
          trace!.feedbackScores.map((s) => s.name).sort(),
          'the experiment trace carries this rule\'s score and nothing else',
        ).toEqual([scoreName]);
      },
    );

    const skipLine = await test.step(
      'The rule logged a filter-mismatch decision for the production trace',
      async () => {
        // The absence assertion below needs an anchor that is not a timeout.
        // A skipped trace produces no score, so "no score yet" and "never going
        // to be scored" are indistinguishable — except in the rule's own log
        // stream, where the skip decision is written explicitly.
        let line: string | undefined;
        await expect
          .poll(
            async () => {
              const logs = await backendClient.getAutomationRuleLogs(ruleId);
              line = logs.map((l) => l.message).find((m) => m.includes(traces.sdkId));
              return line;
            },
            {
              timeout: 120_000,
              intervals: [1000, 2000, 5000],
              message: `the rule must record a decision for the production trace ${traces.sdkId}`,
            },
          )
          .toBeDefined();
        return line!;
      },
    );

    await test.step(
      'That decision is the filter skip, not the sampling-rate skip or a scoring line',
      async () => {
        // Asserting the reason, not just that some line mentions the trace: a
        // rate-based skip would also leave the trace unscored, and would mean
        // the filter branch was never reached.
        expect(
          skipLine,
          `expected the filter-mismatch skip for ${traces.sdkId}, got: ${skipLine}`,
        ).toContain(FILTER_SKIP_PHRASE);
        expect(
          skipLine,
          `${traces.sdkId} was skipped by the sampling rate, not by the filter — ` +
            'the filter branch was never reached',
        ).not.toContain(SAMPLING_SKIP_PHRASE);
      },
    );

    await test.step('The production trace carries no feedback score at all', async () => {
      const trace = await backendClient.getTrace(traces.sdkId);
      expect(trace, `production trace ${traces.sdkId} must be readable`).not.toBeNull();
      // The whole collection, not just this rule's name: a filtered rule that
      // leaked a score under some other name is the same bug.
      expect(
        trace!.feedbackScores,
        `a filtered-out production trace must be unscored, got ` +
          `${JSON.stringify(trace!.feedbackScores)}`,
      ).toEqual([]);
    });
  });
});
