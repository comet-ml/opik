import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { LogsPage } from '@e2e/pom/logs.page';
import { ensureMockModelAvailable } from '@e2e/pom/model-availability';

interface ContentTrace {
  id: string;
  name: string;
  /** Expected score bucket for this trace's content. */
  bucket: 'match' | 'nomatch';
  output: string;
}

test.describe('Online Evaluation — smoke', { tag: ['@t1-smoke', '@area:online-evaluation'] }, () => {
  // The judge is the mock gateway, so every trace gets the same verdict: this asserts
  // Opik's rule → judge → score → panel pipeline, not a model's judgement.
  test('LLM-judge Moderation rule scores every trace and renders in the trace panel', { tag: ['@cap:online-evaluation.create-llm-judge-rule', '@cap:online-evaluation.llm-judge-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
    providerKeys,
  }) => {
    test.setTimeout(240_000);

    const modelDisplayName = await test.step('Provision a mock-gateway judge model', async () =>
      ensureMockModelAvailable(providerKeys, testNamespace),
    );

    const ruleName = `${testNamespace}-llmjudge-mod`;

    await test.step('Create the LLM-judge Moderation rule via the UI', async () => {
      const onlineEval = new OnlineEvaluationPage(page);
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.fillAndSubmitCreateRuleLLMJudge({
        name: ruleName,
        template: 'Moderation',
        modelDisplayName,
      });
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const seededTraces = await test.step('Seed two traces via SDK', async () =>
      Promise.all(
        ['The capital of France is Paris.', 'Water boils at 100 degrees Celsius.'].map((output, i) =>
          sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-trace-${i}`,
            input: 'evaluate this content',
            output,
          }),
        ),
      ),
    );

    const scoreValues = new Map<string, number>();
    await test.step(
      'Poll each seeded trace for the Moderation score (the rule name is the score name)',
      async () => {
        const results = await Promise.all(
          seededTraces.map((t) =>
            backendClient
              .pollTraceForFeedbackScore(t.id, ruleName, { timeoutMs: 120_000 })
              .then((s) => ({ id: t.id, value: s.value })),
          ),
        );
        for (const r of results) scoreValues.set(r.id, r.value);
      },
    );

    await test.step('Assert engine fan-out: every seeded trace got a Moderation score', async () => {
      expect(scoreValues.size, 'each seeded trace produced a score').toBe(seededTraces.length);
    });

    await test.step('UI spot-check: the panel shows each trace\'s stored score', async () => {
      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();

      for (const trace of seededTraces) {
        const panel = await logs.openTraceById(trace.id);
        await panel.waitForFullyLoaded();
        await panel.openFeedbackScoresTab();
        const uiValue = await panel.readFeedbackScoreValue(ruleName);
        expect(uiValue, `UI value for ${trace.name}`).toBeCloseTo(scoreValues.get(trace.id)!, 1);
      }
    });

    await test.step('Cleanup: delete the rule (project teardown does not cascade)', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const rule = rules.find((r) => r.name === ruleName);
      if (rule) await backendClient.deleteAutomationRule(project.id, rule.id);
    });
  });

  test('Python Equals rule scores 5 traces deterministically (3 matching = 1.0, 2 non-matching = 0.0)', { tag: ['@cap:online-evaluation.create-python-rule', '@cap:online-evaluation.python-rule-scores'] }, async ({
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
      await onlineEval.fillAndSubmitCreateRulePythonEquals({
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
            .pollTraceForFeedbackScore(t.id, ruleName, { timeoutMs: 90_000 })
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
