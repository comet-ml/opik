import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';
import {
  buildPythonEqualsMetric,
  PYTHON_EQUALS_ARGUMENTS,
} from '@e2e/pom/online-evaluation.page';

const REFERENCE_OUTPUT = 'seed output';

/**
 * The four trace sources the sampler routes on, and what a `production`-scope
 * rule is supposed to do with each.
 *
 * The split is not "SDK vs the rest". `OnlineScoringSampler.matchesTriggerScope`
 * asks three questions in order: a logging source (`sdk`) matches `production`
 * or `both`; `experiment` matches `experiment` or `both`; anything else —
 * `playground`, `optimization` — matches *every* scope, and is instead gated on
 * carrying `selected_rule_ids` metadata naming the rule. So a production-scope
 * rule scores three of these four, and the one it skips is the experiment trace.
 *
 * Sources deliberately spelled out per row rather than derived, so the expected
 * routing is readable next to the source that produces it.
 */
const TRACE_SOURCES = [
  {
    source: 'sdk' as const,
    /** Non-logging sources need explicit rule selection to be scorable at all. */
    needsRuleSelection: false,
    scoredByProductionRule: true,
  },
  { source: 'experiment' as const, needsRuleSelection: false, scoredByProductionRule: false },
  { source: 'playground' as const, needsRuleSelection: true, scoredByProductionRule: true },
  { source: 'optimization' as const, needsRuleSelection: true, scoredByProductionRule: true },
] satisfies Array<{
  source: 'sdk' | 'experiment' | 'playground' | 'optimization';
  needsRuleSelection: boolean;
  scoredByProductionRule: boolean;
}>;

test.describe('Online Evaluation — trigger scope', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A production-scope rule scores SDK, playground and optimization traces but never experiment traces', { tag: ['@cap:online-evaluation.sampling-rate'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // Worst case is one trace's chain — a 120s control poll plus a 60s settle —
    // because the per-trace waits run concurrently, not the sum over four
    // traces. 300s covers that plus rule creation, the seed and the UI check.
    // Kept only just above the inner waits so those fire first: each throws a
    // diagnostic naming the trace and the scores it actually saw, which is far
    // more useful than an opaque "test timeout exceeded".
    test.setTimeout(300_000);

    // Both rules run the SAME deterministic metric at the SAME full rate, and
    // differ only in trigger scope. That is what makes the comparison mean
    // something: any difference in what they scored is attributable to scope
    // and to nothing else.
    //
    // The `both`-scope rule is the control. Without it, "the production rule
    // did not score the experiment trace" is indistinguishable from "the
    // experiment trace never reached the scoring engine" — and the second would
    // make this spec pass forever while asserting nothing. The control's score
    // landing on a trace proves that trace was processed.
    const productionRule = `${testNamespace}-prod-scope`;
    const bothRule = `${testNamespace}-both-scope`;

    // Full rate on both rules, so the sampling roll is not in play:
    // `secureRandom.nextFloat()` is drawn from [0, 1), so `>= 1.0` is never
    // true and every trace the scope admits is scored. Nothing here is
    // probabilistic.
    const FULL_RATE = 1.0;

    const rules = await test.step(
      'Create a production-scope rule and a both-scope control rule via the API',
      async () => {
        // Seeded through the REST write rather than the create dialog because
        // the dialog has no trigger-scope control — there is no UI gesture that
        // produces the rule this spec needs. Dialog creation is covered by
        // online-evaluation-smoke.
        const [production, both] = await Promise.all(
          (
            [
              { name: productionRule, triggerScope: 'production' as const },
              { name: bothRule, triggerScope: 'both' as const },
            ] as const
          ).map((spec) =>
            backendClient.createAutomationRule({
              projectId: project.id,
              name: spec.name,
              metric: buildPythonEqualsMetric(spec.name, REFERENCE_OUTPUT),
              metricArguments: PYTHON_EQUALS_ARGUMENTS,
              samplingRate: FULL_RATE,
              triggerScope: spec.triggerScope,
            }),
          ),
        );
        return { production, both };
      },
    );

    // Precondition, asserted before a single trace is seeded. Every conclusion
    // below reads a scope difference out of a score difference, so a write that
    // silently dropped `trigger_scope` — leaving both rules on the backend's
    // `production` default — would make the control stop scoring experiment
    // traces and the spec would then "prove" the very thing it is testing.
    await test.step('Both rules persisted the scope and rate they were created with', async () => {
      expect(rules.production.triggerScope, 'the production rule is production-scoped').toBe(
        'production',
      );
      expect(rules.both.triggerScope, 'the control rule is both-scoped').toBe('both');
      expect(rules.production.samplingRate, 'production rule at full rate').toBeCloseTo(
        FULL_RATE,
        5,
      );
      expect(rules.both.samplingRate, 'control rule at full rate').toBeCloseTo(FULL_RATE, 5);
    });

    const seeded = await test.step(
      'Seed one trace per source, all with the same matching output',
      async () => {
        // Ids minted up front: the REST write answers 204 with no body and
        // every assertion below is about a specific trace.
        //
        // `endTime` is not optional decoration — the sampler drops traces with a
        // null end_time as incomplete, so a trace without one is never offered
        // to any rule and would read as "the scope excluded it".
        //
        // Identical outputs on purpose: with one reference value, any trace a
        // rule actually evaluates scores 1.0. A 0.0 would mean the metric ran on
        // unexpected input, not that scope routing skipped the trace — so the
        // two failure modes stay distinguishable.
        const now = new Date();
        return Promise.all(
          TRACE_SOURCES.map(async (spec) => {
            const id = uuid7();
            await backendClient.createTraceWithSource({
              id,
              projectName: project.name,
              name: `${testNamespace}-${spec.source}`,
              source: spec.source,
              startTime: now,
              endTime: now,
              input: { text: 'whatever' },
              output: { output: REFERENCE_OUTPUT },
              // Playground and optimization traces are only scorable when they
              // name the rules the user selected. Both rules are listed so the
              // control can score them too — otherwise the control would have
              // nothing to say about these two sources.
              ...(spec.needsRuleSelection
                ? {
                    metadata: {
                      selected_rule_ids: [rules.production.id, rules.both.id],
                    },
                  }
                : {}),
            });
            return { ...spec, id, name: `${testNamespace}-${spec.source}` };
          }),
        );
      },
    );

    await test.step('Each seeded trace really carries the source it was seeded with', async () => {
      // The whole spec reads routing out of `source`. A write that dropped or
      // coerced it would produce a perfectly plausible score distribution over
      // traces that are not the traces this test believes it created.
      const sources = await Promise.all(
        seeded.map(async (trace) => ({
          name: trace.name,
          expected: trace.source,
          actual: await backendClient.getTraceSource(trace.id),
        })),
      );
      for (const trace of sources) {
        expect(trace.actual, `${trace.name} persisted its source`).toBe(trace.expected);
      }
    });

    const scoresByTrace = await test.step(
      'Wait for the control rule to score every trace, then let each score set settle',
      async () => {
        // Two-phase wait, and the order matters. `waitForTraceScoresSettled`
        // decides "settled" from a stable score-set fingerprint, so a
        // not-yet-scored trace looks stable from its very first poll and would
        // be tallied as "never scored". Anchor on the control first: a
        // both-scope full-rate rule scores every source by construction, so its
        // arrival is safe to wait for and proves the engine processed the
        // trace. Only then wait for the set to go quiet, which is what catches
        // the production rule's score if it is also coming.
        const settled = await Promise.all(
          seeded.map(async (trace) => {
            await backendClient.pollTraceForFeedbackScore(trace.id, bothRule, {
              timeoutMs: 120_000,
            });
            const detail = await backendClient.waitForTraceScoresSettled(trace.id, {
              quietPeriodMs: 10_000,
              timeoutMs: 60_000,
              minScores: 1,
            });
            return { ...trace, scores: detail.feedbackScores };
          }),
        );
        return new Map(settled.map((t) => [t.source, t] as const));
      },
    );

    await test.step('Control: the both-scope rule scored all four sources at 1.0', async () => {
      for (const spec of TRACE_SOURCES) {
        const trace = scoresByTrace.get(spec.source)!;
        const control = trace.scores.filter((s) => s.name === bothRule);
        expect(
          control,
          `the both-scope control must score the ${spec.source} trace exactly once — ` +
            `without it, nothing below can tell "scope excluded this trace" from ` +
            `"this trace never reached the engine". Scores seen: ` +
            `${JSON.stringify(trace.scores.map((s) => s.name))}`,
        ).toHaveLength(1);
        expect(control[0].value, `control score on the ${spec.source} trace`).toBe(1.0);
      }
    });

    await test.step('The production-scope rule scored SDK, playground and optimization', async () => {
      for (const spec of TRACE_SOURCES.filter((s) => s.scoredByProductionRule)) {
        const trace = scoresByTrace.get(spec.source)!;
        const scored = trace.scores.filter((s) => s.name === productionRule);
        expect(
          scored,
          `a production-scope rule must score the ${spec.source} trace exactly once`,
        ).toHaveLength(1);
        expect(scored[0].value, `production-rule score on the ${spec.source} trace`).toBe(1.0);
      }
    });

    await test.step('The production-scope rule did not score the experiment trace', async () => {
      const experiment = scoresByTrace.get('experiment')!;
      const names = experiment.scores.map((s) => s.name);
      expect(
        names,
        'an experiment-sourced trace is outside a production-scope rule, and the control ' +
          'rule scoring it above proves the engine did see it',
      ).not.toContain(productionRule);
      // State the whole answer, not just the absence: the experiment trace must
      // carry the control's score and nothing else. A rule that leaked some
      // other score onto it would slip past a bare `not.toContain`.
      expect([...names].sort(), 'the experiment trace carries only the control rule score').toEqual(
        [bothRule],
      );
    });

    await test.step('The production rule\'s automation log never names the experiment trace', async () => {
      // Scope is decided before per-trace logging: `matchesTriggerScope` filters
      // the trace out upstream of `shouldSampleTrace`, which is where every
      // user-facing line is emitted. So the right assertion is not "a skip line
      // says why" — it is that the rule's log has nothing to say about this
      // trace at all, while it does have lines for the three it scored.
      const logs = await backendClient.getAutomationRuleLogs(rules.production.id);
      const loggedTraceIds = new Set(logs.map((entry) => entry.traceId));

      const experimentTraceId = scoresByTrace.get('experiment')!.id;
      expect(
        [...loggedTraceIds],
        `the production rule logged the experiment trace ${experimentTraceId}, so it was ` +
          'considered rather than excluded by scope',
      ).not.toContain(experimentTraceId);

      for (const spec of TRACE_SOURCES.filter((s) => s.scoredByProductionRule)) {
        expect(
          loggedTraceIds,
          `the rule's log must mention the ${spec.source} trace it scored — otherwise the ` +
            'absence above says nothing about scope, only that logging is off',
        ).toContain(scoresByTrace.get(spec.source)!.id);
      }
    });

    await test.step('The trace panel renders the production rule score the API reported', async () => {
      // Checked on the SDK trace specifically: the project Logs table lists
      // production (SDK-sourced) traces only, so it is the one source whose
      // score a user can reach from this page.
      const sdkTrace = scoresByTrace.get('sdk')!;
      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();

      const panel = await logs.openTraceById(sdkTrace.id);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();
      const rendered = await panel.readFeedbackScoreValue(productionRule);
      expect(rendered, 'the panel shows the score the API returned').toBe(
        sdkTrace.scores.find((s) => s.name === productionRule)!.value,
      );
    });

    // Rules are removed by the `automationRulesCleanup` fixture, which runs
    // whatever the test's outcome — they do NOT cascade with the project. The
    // traces do: `ProjectService.delete` takes them with it (asserted by
    // projects/project-delete.spec.ts), so the project fixture is enough.
  });
});
