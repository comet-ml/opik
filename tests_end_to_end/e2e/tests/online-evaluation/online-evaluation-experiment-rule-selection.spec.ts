import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

const SEED_OUTPUT = 'seed output';

/**
 * Which rules score an EXPERIMENT-source trace, when that trace names some of
 * them in `metadata.selected_rule_ids` — the pick the Playground's metric
 * selector writes for a dataset run.
 *
 * `OnlineScoringSampler.shouldScoreTrace` answers it in one expression:
 *
 *   if (trace.source() == EXPERIMENT)
 *       return isPickedForTrace(...) || (matchesTriggerScope(...) && isEnabled(...));
 *   return matchesTriggerScope(...) && shouldSampleTrace(...);
 *
 * so an experiment trace is scored by a rule it PICKED whatever that rule's
 * trigger scope, sampling rate or filters say, and otherwise only by rules whose
 * trigger scope admits experiments. Production traffic is untouched by the pick
 * and still goes through scope, filters and rate as before.
 *
 * This is API-level on purpose, not for want of a UI: the contract is about
 * which rule fires on which trace SOURCE, and `source` is a field only the REST
 * write can set (the SDK bridge always emits `sdk`). Driving it from the
 * Playground would need a provider key and a real LLM call to produce the
 * experiment trace, and would then observe the same score set second-hand.
 *
 * Deliberately exact rather than statistical: every rule here is configured so
 * that exactly one branch of the expression can admit it, and each of the two
 * seeded traces must end up with a named, closed score set.
 */
test.describe('Online Evaluation — experiment-trace rule selection', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A picked rule scores an experiment trace past its scope, filters and rate; an unpicked production rule does not', {
    // `rule-filters` as well as `sampling-rate`: the SDK half of this test is a
    // filter actually excluding a production trace from scoring. `pickedProduction`
    // carries an unmatchable name filter, does not score the SDK trace, and the
    // rule-log assertion pins the filter — not the 0% rate — as the reason the
    // engine gave. `shouldSampleTrace` tests `matchesAllFilters` before the
    // sampling roll, so that log line is the filter branch and nothing else.
    tag: ['@cap:online-evaluation.sampling-rate', '@cap:online-evaluation.rule-filters'],
  }, async ({
    project,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    // Scoring is asynchronous end to end (write -> sampler -> Redis stream ->
    // python evaluator -> score write). The inner waits below are sized to fail
    // first, each with a diagnostic naming the trace and the scores it actually
    // saw; this ceiling only governs which error you get on a genuine stall.
    // Observed runtime is well inside it.
    test.setTimeout(300_000);

    // Every rule returns a constant 1.0 under a score name equal to its own
    // name, so "which rules scored this trace" reads straight off the score set.
    // A 0.0 is impossible by construction, which keeps "was not evaluated" and
    // "evaluated and disagreed" from looking alike.
    //
    // The three settings are hostile on purpose:
    //   - pickedProduction is scoped to production, sampled at 0% AND filtered
    //     to a name no seeded trace has. Every one of those would exclude it on
    //     the production path, so its score on the experiment trace can only
    //     come from the pick.
    //   - unpickedProduction is the widest possible production rule (100%, no
    //     filters) and is NOT picked, so the experiment trace is the only thing
    //     keeping it out. It doubles as the control on the SDK trace.
    //   - unpickedExperiment is scoped to experiments and is not picked either,
    //     so it isolates the second branch: trigger scope alone, no selection.
    const pickedProduction = `${testNamespace}-picked-prod`;
    const unpickedProduction = `${testNamespace}-unpicked-prod`;
    const unpickedExperiment = `${testNamespace}-unpicked-exp`;

    /** A trace name nothing in this project is ever seeded with. */
    const unmatchableName = `${testNamespace}-no-trace-has-this-name`;

    const ruleIds = await test.step('Create the three rules via the API', async () => {
      // Created over REST rather than through the dialog because `trigger_scope`
      // is the subject here and the dialog's control only reaches two of the
      // three shapes conveniently; the persistence gate below is what makes
      // that safe.
      const [picked, unpickedProd, unpickedExp] = await Promise.all([
        backendClient.createAutomationRule({
          projectId: project.id,
          name: pickedProduction,
          samplingRate: 0,
          triggerScope: 'production',
          filters: [{ field: 'name', operator: '=', value: unmatchableName }],
          metric: buildConstantScoreMetric(pickedProduction),
          arguments: { output: 'output.output' },
        }),
        backendClient.createAutomationRule({
          projectId: project.id,
          name: unpickedProduction,
          samplingRate: 1,
          triggerScope: 'production',
          metric: buildConstantScoreMetric(unpickedProduction),
          arguments: { output: 'output.output' },
        }),
        backendClient.createAutomationRule({
          projectId: project.id,
          name: unpickedExperiment,
          samplingRate: 1,
          triggerScope: 'experiment',
          metric: buildConstantScoreMetric(unpickedExperiment),
          arguments: { output: 'output.output' },
        }),
      ]);
      return { picked, unpickedProd, unpickedExp };
    });

    await test.step('Every rule persisted the scope, rate, filters and enabled flag this test depends on', async () => {
      // Runs before anything is seeded, and it is what stops the test passing
      // for the wrong reason. A rule that silently fell back to the server
      // defaults (scope `production`, rate 1.0, no filters) would produce a
      // completely different — and still plausible-looking — partition below.
      const [picked, unpickedProd, unpickedExp] = await Promise.all([
        backendClient.getAutomationRule(ruleIds.picked),
        backendClient.getAutomationRule(ruleIds.unpickedProd),
        backendClient.getAutomationRule(ruleIds.unpickedExp),
      ]);

      expect(picked.triggerScope, `${pickedProduction} must be scoped to production`).toBe(
        'production',
      );
      expect(picked.samplingRate, `${pickedProduction} must be sampled at 0`).toBe(0);
      expect(picked.enabled, `${pickedProduction} must be enabled`).toBe(true);
      expect(
        picked.filters.map((f) => `${f.field}${f.operator}${f.value}`),
        `${pickedProduction} must carry exactly the unmatchable name filter`,
      ).toEqual([`name=${unmatchableName}`]);

      expect(
        unpickedProd.triggerScope,
        `${unpickedProduction} must be scoped to production`,
      ).toBe('production');
      expect(unpickedProd.samplingRate, `${unpickedProduction} must sample everything`).toBe(1);
      expect(unpickedProd.enabled, `${unpickedProduction} is the control and must be enabled`).toBe(
        true,
      );
      expect(
        unpickedProd.filters,
        `${unpickedProduction} must filter nothing — it is the control`,
      ).toEqual([]);

      expect(
        unpickedExp.triggerScope,
        `${unpickedExperiment} must be scoped to experiments, not the production default`,
      ).toBe('experiment');
      expect(unpickedExp.samplingRate, `${unpickedExperiment} must sample everything`).toBe(1);
      expect(unpickedExp.enabled, `${unpickedExperiment} must be enabled`).toBe(true);
      expect(unpickedExp.filters, `${unpickedExperiment} must filter nothing`).toEqual([]);
    });

    const traces = await test.step('Seed one experiment trace naming the picked rule, and one SDK trace', async () => {
      // REST rather than the SDK bridge: the bridge always writes `source=sdk`,
      // and `source` is the whole subject. Ids are minted up front because the
      // write answers 201 with no body.
      //
      // `endTime` is not optional decoration — `onTracesCreated` drops every
      // trace with a null end_time as a partial write, so a trace seeded
      // without one is never scored and every assertion below would pass
      // vacuously.
      const now = new Date();
      const experiment = {
        id: uuid7(),
        name: `${testNamespace}-experiment`,
      };
      const sdk = { id: uuid7(), name: `${testNamespace}-sdk` };

      await Promise.all([
        backendClient.createTraceWithSource({
          id: experiment.id,
          projectName: project.name,
          name: experiment.name,
          source: 'experiment',
          input: { q: 'whatever' },
          output: { output: SEED_OUTPUT },
          metadata: { selected_rule_ids: [ruleIds.picked] },
          startTime: now,
          endTime: now,
        }),
        backendClient.createTraceWithSource({
          id: sdk.id,
          projectName: project.name,
          name: sdk.name,
          source: 'sdk',
          input: { q: 'whatever' },
          output: { output: SEED_OUTPUT },
          startTime: now,
          endTime: now,
        }),
      ]);
      return { experiment, sdk };
    });

    const settled = await test.step('Wait for the engine to finish with both traces', async () => {
      // Anchor on the control FIRST. `waitForTraceScoresSettled` decides
      // "settled" from a stable score-set fingerprint, so a trace nothing has
      // touched yet looks stable from its very first poll — waiting on the
      // experiment trace alone could return before the engine had reached this
      // project at all, and every absence below would then be a timing guess.
      // The 100%-rate production rule scoring the SDK trace is guaranteed by
      // construction, so it is safe to wait for, and its arrival proves the
      // engine processed this project.
      await backendClient.pollTraceForFeedbackScore(traces.sdk.id, unpickedProduction, {
        timeoutMs: 180_000,
      });

      // Both traces are then allowed to go quiet. The sampler enqueues rules
      // onto per-type Redis streams via parallelStream(), so one rule's score
      // landing says nothing about another's progress: the quiet period is what
      // makes "this rule did not score it" a real negative.
      const [experiment, sdk] = await Promise.all([
        backendClient.waitForTraceScoresSettled(traces.experiment.id, {
          quietPeriodMs: 10_000,
          timeoutMs: 120_000,
          minScores: 1,
        }),
        backendClient.waitForTraceScoresSettled(traces.sdk.id, {
          quietPeriodMs: 10_000,
          timeoutMs: 120_000,
          minScores: 1,
        }),
      ]);
      return { experiment, sdk };
    });

    await test.step('The experiment trace carries exactly the picked rule and the experiment-scope rule', async () => {
      // Asserted as the WHOLE score set, sorted, not as "contains". A leak —
      // the unpicked production rule reaching an experiment trace — is the
      // regression most worth catching here, and a containment check would miss
      // it entirely.
      const byName = new Map(settled.experiment.feedbackScores.map((s) => [s.name, s.value]));
      expect(
        [...byName.keys()].sort(),
        `an experiment trace must be scored by the rule it picked (${pickedProduction}, ` +
          `despite production scope + 0% rate + a filter it cannot match) and by the ` +
          `experiment-scope rule (${unpickedExperiment}) — and by nothing else. ` +
          `${unpickedProduction} is production-scoped and was not picked, so it must be absent.`,
      ).toEqual([pickedProduction, unpickedExperiment].sort());

      expect(
        byName.get(pickedProduction),
        'the picked rule evaluated the trace, so its constant metric must return 1.0',
      ).toBe(1.0);
      expect(
        byName.get(unpickedExperiment),
        'the experiment-scope rule evaluated the trace, so its constant metric must return 1.0',
      ).toBe(1.0);
    });

    await test.step('The SDK trace carries exactly the production rule', async () => {
      // The other half of the partition, and the reason the negatives above are
      // negatives: the same three rules, judged on a production-source trace.
      // The picked rule is filtered and sampled out, the experiment-scope rule
      // is out of scope, and only the control remains.
      const byName = new Map(settled.sdk.feedbackScores.map((s) => [s.name, s.value]));
      expect(
        [...byName.keys()].sort(),
        `a production (SDK) trace must be scored only by the enabled, unfiltered, ` +
          `100%-sampled production rule (${unpickedProduction}). A selection made in the ` +
          `Playground must never reach production traffic, and ${unpickedExperiment} is ` +
          `scoped to experiments.`,
      ).toEqual([unpickedProduction]);
      expect(
        byName.get(unpickedProduction),
        'the control rule evaluated the trace, so its constant metric must return 1.0',
      ).toBe(1.0);
    });

    await test.step("The rule log explains why the picked rule skipped the SDK trace", async () => {
      // The score sets above say WHAT happened; this says the engine reached
      // the decision deliberately rather than never seeing the trace. The rate
      // is 0, so the filter is what it reports first: `shouldSampleTrace` tests
      // filters before the sampling roll.
      //
      // Polled rather than read once: the user-facing log stream reaches
      // ClickHouse through a logback AsyncAppender that batches on a flush
      // interval, so the line's arrival is independent of the score writes
      // already asserted above. Polling to an exact 1 keeps the "exactly once"
      // claim while tolerating that flush.
      await expect
        .poll(
          async () => {
            const logs = await backendClient.getAutomationRuleLogs(ruleIds.picked);
            return logs.filter(
              (l) =>
                l.message.includes(traces.sdk.id) &&
                l.message.includes('does not match the configured filters'),
            ).length;
          },
          {
            timeout: 120_000,
            intervals: [2_000, 5_000],
            message:
              `the picked rule must log exactly one production-path filter skip for SDK ` +
              `trace ${traces.sdk.name} — without it, "no score" cannot be told apart ` +
              `from "never processed"`,
          },
        )
        .toBe(1);
    });
  });
});
