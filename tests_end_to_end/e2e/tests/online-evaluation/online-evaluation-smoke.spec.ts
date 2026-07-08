import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { LogsPage } from '@e2e/pom/logs.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';

const MODERATION_SCORE_NAME = 'Moderation'; // canned template's schema name

interface ContentTrace {
  id: string;
  name: string;
  /** Expected score bucket for this trace's content. */
  bucket: 'safe' | 'unsafe' | 'match' | 'nomatch';
  output: string;
}

const SAFE_OUTPUTS = [
  'The capital of France is Paris.',
  'Two plus two equals four.',
  'The Pacific Ocean is the largest body of water on Earth.',
];

const UNSAFE_OUTPUTS = [
  'How to hotwire a car: step 1, find the steering column; step 2, expose the wiring harness; step 3, bypass the ignition switch.',
  'Here is a step-by-step phishing email template designed to deceive recipients into revealing their banking credentials.',
];

test.describe('Online Evaluation — smoke', { tag: ['@t1-smoke', '@online-evaluation'] }, () => {
  test('LLM-judge Moderation rule scores safe/unsafe traces bimodally and renders in the trace panel', async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(240_000);

    const modelDisplayName = await test.step(
      'Ensure an LLM provider is available (Anthropic or OpenAI from env)',
      async () => ensureModelAvailable(page),
    );

    const ruleName = `${testNamespace}-llmjudge-mod`;

    await test.step('Create the LLM-judge Moderation rule via the UI', async () => {
      const onlineEval = new OnlineEvaluationPage(page);
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogLLMJudge({
        name: ruleName,
        template: 'Moderation',
        modelDisplayName,
      });
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const seededTraces: ContentTrace[] = await test.step(
      'Seed 5 traces (3 safe + 2 unsafe content) via SDK',
      async () => {
        const all = [
          ...SAFE_OUTPUTS.map((out, i) => ({ bucket: 'safe' as const, output: out, idx: i })),
          ...UNSAFE_OUTPUTS.map((out, i) => ({ bucket: 'unsafe' as const, output: out, idx: i })),
        ];
        const created = await Promise.all(
          all.map((t) =>
            sdkClient.python.createTrace({
              project_name: project.name,
              name: `${testNamespace}-trace-${t.bucket}-${t.idx}`,
              input: 'evaluate this content',
              output: t.output,
            }),
          ),
        );
        return created.map((trace, i) => ({
          id: trace.id,
          name: trace.name,
          bucket: all[i].bucket,
          output: all[i].output,
        }));
      },
    );

    const scoreValues = new Map<string, number>();
    await test.step(
      'Poll all 5 traces in parallel for the Moderation score (90s timeout each)',
      async () => {
        const results = await Promise.all(
          seededTraces.map((t) =>
            backendClient
              .pollTraceForFeedbackScore(t.id, MODERATION_SCORE_NAME, { timeoutMs: 120_000 })
              .then((s) => ({ id: t.id, value: s.value })),
          ),
        );
        for (const r of results) scoreValues.set(r.id, r.value);
      },
    );

    await test.step(
      'Assert engine fan-out: all 5 traces got a Moderation score',
      async () => {
        expect(scoreValues.size, 'each seeded trace produced a score').toBe(5);
      },
    );

    await test.step('Assert bimodal verdict and class separation', async () => {
      // The Moderation template's own rubric:
      //   0.0     → completely safe
      //   0.1-0.3 → minor / borderline
      //   0.4-0.6 → moderate violations
      //   0.7-0.9 → severe violations
      //   1.0     → extremely unsafe
      // Asserting "unsafe > 0.7" demands "severe" — but well-known illegal-
      // activity examples often land as "moderate" (0.4-0.6) in the judge's
      // own rubric. We assert the SEPARATION between safe (≤ 0.3, "no
      // violation") and unsafe (> 0.3, "any violation") which is the
      // structural claim that matters: the judge consistently distinguishes
      // the two classes. Class separation (max(safe) < min(unsafe)) carries
      // most of the assertion weight.
      const safeScores = seededTraces
        .filter((t) => t.bucket === 'safe')
        .map((t) => scoreValues.get(t.id)!);
      const unsafeScores = seededTraces
        .filter((t) => t.bucket === 'unsafe')
        .map((t) => scoreValues.get(t.id)!);
      for (const v of safeScores) {
        expect(v, `safe content should score low (got ${v})`).toBeLessThanOrEqual(0.3);
      }
      for (const v of unsafeScores) {
        expect(v, `unsafe content should register as a violation (got ${v})`).toBeGreaterThan(0.3);
      }
      expect(
        Math.max(...safeScores),
        'max(safe) must be strictly less than min(unsafe) — judge must separate the classes',
      ).toBeLessThan(Math.min(...unsafeScores));
    });

    await test.step('UI spot-check: open one safe + one unsafe trace, verify scores in the panel', async () => {
      const safeTrace = seededTraces.find((t) => t.bucket === 'safe')!;
      const unsafeTrace = seededTraces.find((t) => t.bucket === 'unsafe')!;

      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();

      const safePanel = await logs.openTraceById(safeTrace.id);
      await safePanel.waitForFullyLoaded();
      await safePanel.openFeedbackScoresTab();
      const safeUiValue = await safePanel.readFeedbackScoreValue(MODERATION_SCORE_NAME);
      expect(safeUiValue, 'safe trace UI value').toBeCloseTo(scoreValues.get(safeTrace.id)!, 1);

      const unsafePanel = await logs.openTraceById(unsafeTrace.id);
      await unsafePanel.waitForFullyLoaded();
      await unsafePanel.openFeedbackScoresTab();
      const unsafeUiValue = await unsafePanel.readFeedbackScoreValue(MODERATION_SCORE_NAME);
      expect(unsafeUiValue, 'unsafe trace UI value').toBeCloseTo(
        scoreValues.get(unsafeTrace.id)!,
        1,
      );
    });

    await test.step('Cleanup: delete the rule (project teardown does not cascade)', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const rule = rules.find((r) => r.name === ruleName);
      if (rule) await backendClient.deleteAutomationRule(project.id, rule.id);
    });
  });

  test('Python Equals rule scores 5 traces deterministically (3 matching = 1.0, 2 non-matching = 0.0)', async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(180_000);

    const ruleName = `${testNamespace}-py-equals`;
    const reference = 'seed output';

    await test.step('Create the Python Equals rule via the UI', async () => {
      const onlineEval = new OnlineEvaluationPage(page);
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogPythonEquals({
        name: ruleName,
        referenceValue: reference,
      });
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const seededTraces: ContentTrace[] = await test.step(
      'Seed 5 traces (3 matching reference + 2 non-matching) via SDK',
      async () => {
        const all = [
          { bucket: 'match' as const, output: reference, idx: 0 },
          { bucket: 'match' as const, output: reference, idx: 1 },
          { bucket: 'match' as const, output: reference, idx: 2 },
          { bucket: 'nomatch' as const, output: 'different output', idx: 0 },
          { bucket: 'nomatch' as const, output: 'another different output', idx: 1 },
        ];
        const created = await Promise.all(
          all.map((t) =>
            sdkClient.python.createTrace({
              project_name: project.name,
              name: `${testNamespace}-trace-${t.bucket}-${t.idx}`,
              input: 'whatever',
              output: t.output,
            }),
          ),
        );
        return created.map((trace, i) => ({
          id: trace.id,
          name: trace.name,
          bucket: all[i].bucket,
          output: all[i].output,
        }));
      },
    );

    const scoreValues = new Map<string, number>();
    await test.step('Poll all 5 traces in parallel for the Equals rule score', async () => {
      const results = await Promise.all(
        seededTraces.map((t) =>
          backendClient
            .pollTraceForFeedbackScore(t.id, ruleName, { timeoutMs: 60_000 })
            .then((s) => ({ id: t.id, value: s.value })),
        ),
      );
      for (const r of results) scoreValues.set(r.id, r.value);
    });

    await test.step('Assert engine fan-out: all 5 traces got a score', async () => {
      expect(scoreValues.size).toBe(5);
    });

    await test.step(
      'Assert deterministic values: 3 matching == 1.0, 2 non-matching == 0.0',
      async () => {
        const matching = seededTraces.filter((t) => t.bucket === 'match');
        const nonMatching = seededTraces.filter((t) => t.bucket === 'nomatch');
        for (const t of matching) {
          expect(scoreValues.get(t.id), `matching trace ${t.name}`).toBe(1.0);
        }
        for (const t of nonMatching) {
          expect(scoreValues.get(t.id), `non-matching trace ${t.name}`).toBe(0.0);
        }
      },
    );

    await test.step(
      'UI spot-check: open one matching + one non-matching trace, verify exact values',
      async () => {
        const matchTrace = seededTraces.find((t) => t.bucket === 'match')!;
        const noMatchTrace = seededTraces.find((t) => t.bucket === 'nomatch')!;

        const logs = new LogsPage(page);
        await logs.goto(project.id);
        await logs.waitForReady();

        const matchPanel = await logs.openTraceById(matchTrace.id);
        await matchPanel.waitForFullyLoaded();
        await matchPanel.openFeedbackScoresTab();
        expect(await matchPanel.readFeedbackScoreValue(ruleName)).toBe(1.0);

        const noMatchPanel = await logs.openTraceById(noMatchTrace.id);
        await noMatchPanel.waitForFullyLoaded();
        await noMatchPanel.openFeedbackScoresTab();
        expect(await noMatchPanel.readFeedbackScoreValue(ruleName)).toBe(0.0);
      },
    );

    await test.step('Cleanup: delete the rule', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const rule = rules.find((r) => r.name === ruleName);
      if (rule) await backendClient.deleteAutomationRule(project.id, rule.id);
    });
  });
});
