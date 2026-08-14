import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { LogsPage } from '@e2e/pom/logs.page';
import { resolveJudgeModel } from '@e2e/pom/model-availability';

const MEANING_MATCH_SCORE = 'Meaning Match'; // canned template's schema name
const STRUCTURE_SCORE = 'Structure Compliance'; // canned template's schema name

const QUESTION = 'What is the capital of France?';
const GROUND_TRUTH = 'Paris';
const EXPECTED_SCHEMA = '{"city": "<string>", "country": "<string>"}';

/**
 * Two traces chosen so both BOOLEAN judgements are unambiguous in both
 * directions: an answer that matches the ground truth alongside well-formed
 * JSON, and one that names a different thing alongside JSON that is cut off
 * mid-token. These are the deterministic half of the rewritten template set —
 * the DOUBLE templates grade on a rubric and would not give a stable value.
 */
interface GradedTrace {
  bucket: 'aligned' | 'contradicted';
  answer: string;
  payload: string;
  /** What both BOOLEAN templates must return for this trace. */
  expected: number;
}

const GRADED: GradedTrace[] = [
  {
    bucket: 'aligned',
    answer: 'The capital of France is Paris.',
    payload: '{"city": "Paris", "country": "France"}',
    expected: 1,
  },
  {
    bucket: 'contradicted',
    answer: 'The capital of France is bananas.',
    payload: '{"city": "Par',
    expected: 0,
  },
];

test.describe('Online Evaluation — BOOLEAN judge templates', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('Meaning Match and Structured Output Compliance separate matching content from contradicted content', { tag: ['@cap:online-evaluation.llm-judge-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(300_000);

    const modelDisplayName = await resolveJudgeModel(page);

    const meaningRule = `${testNamespace}-meaning`;
    const structureRule = `${testNamespace}-structure`;
    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('Create the Meaning Match rule via the UI', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogLLMJudge({
        name: meaningRule,
        template: 'Meaning Match',
        modelDisplayName,
        // The template ships `ground_truth` unmapped; every variable is bound
        // to an explicit key of the seeded trace so the judge sees bare
        // strings rather than the whole JSON node.
        variableMappings: {
          input: 'input.question',
          ground_truth: 'input.ground_truth',
          output: 'output.answer',
        },
      });
      await expect(onlineEval.ruleRow(meaningRule)).toBeVisible();
    });

    await test.step('Create the Structured Output Compliance rule via the UI', async () => {
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogLLMJudge({
        name: structureRule,
        template: 'Structured Output Compliance',
        modelDisplayName,
        variableMappings: {
          context: 'input.expected_schema',
          output: 'output.payload',
        },
      });
      await expect(onlineEval.ruleRow(structureRule)).toBeVisible();
    });

    await test.step('Both rules declare a BOOLEAN score under the template\'s own name', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const meaning = rules.find((r) => r.name === meaningRule);
      const structure = rules.find((r) => r.name === structureRule);
      expect(meaning, `rule "${meaningRule}" must exist`).toBeDefined();
      expect(structure, `rule "${structureRule}" must exist`).toBeDefined();
      // The declared schema is what the engine coerces the judge's answer
      // into; a template whose score type drifted would store a wrong value
      // rather than fail, so it is asserted before any score is read.
      expect(meaning!.schema).toEqual([{ name: MEANING_MATCH_SCORE, type: 'BOOLEAN' }]);
      expect(structure!.schema).toEqual([{ name: STRUCTURE_SCORE, type: 'BOOLEAN' }]);
    });

    const seeded = await test.step('Seed the aligned and contradicted traces via the SDK', async () => {
      const created = await Promise.all(
        GRADED.map((g) =>
          sdkClient.python.createNestedTrace({
            project_name: project.name,
            name: `${testNamespace}-${g.bucket}`,
            input: {
              question: QUESTION,
              ground_truth: GROUND_TRUTH,
              expected_schema: EXPECTED_SCHEMA,
            },
            output: { answer: g.answer, payload: g.payload },
            // Online scoring deliberately ignores partial traces: the sampler
            // filters on `end_time != null` so a half-written trace is never
            // judged. The nested seed leaves a trace open unless it is given a
            // duration, so without this the rules would never fire.
            duration_seconds: 1,
            spans: [],
          }),
        ),
      );
      return GRADED.map((g, i) => ({ ...g, traceId: created[i].id }));
    });

    for (const seed of seeded) {
      await test.step(`The ${seed.bucket} trace scores ${seed.expected} on both templates`, async () => {
        const [meaning, structure] = await Promise.all([
          backendClient.pollTraceForFeedbackScore(seed.traceId, MEANING_MATCH_SCORE, {
            timeoutMs: 150_000,
          }),
          backendClient.pollTraceForFeedbackScore(seed.traceId, STRUCTURE_SCORE, {
            timeoutMs: 150_000,
          }),
        ]);

        expect(
          meaning.value,
          `${MEANING_MATCH_SCORE} on the ${seed.bucket} answer "${seed.answer}"`,
        ).toBe(seed.expected);
        expect(
          structure.value,
          `${STRUCTURE_SCORE} on the ${seed.bucket} payload "${seed.payload}"`,
        ).toBe(seed.expected);

        // A BOOLEAN score with no justification is not actionable, and the
        // reason is written on the same parse path as the value.
        expect(meaning.reason, `${MEANING_MATCH_SCORE} carries a reason`).toBeTruthy();
        expect(structure.reason, `${STRUCTURE_SCORE} carries a reason`).toBeTruthy();
      });
    }

    await test.step('Both scores render in the trace panel with their reasons', async () => {
      const aligned = seeded.find((s) => s.bucket === 'aligned')!;
      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();

      const panel = await logs.openTraceById(aligned.traceId);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();

      expect(await panel.readFeedbackScoreValue(MEANING_MATCH_SCORE)).toBe(aligned.expected);
      expect(await panel.readFeedbackScoreValue(STRUCTURE_SCORE)).toBe(aligned.expected);
      expect(await panel.readFeedbackScoreReason(MEANING_MATCH_SCORE)).not.toBe('');
      expect(await panel.readFeedbackScoreReason(STRUCTURE_SCORE)).not.toBe('');
    });

    await test.step('Cleanup: delete both rules (project teardown does not cascade)', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      for (const rule of rules) {
        await backendClient.deleteAutomationRule(project.id, rule.id);
      }
    });
  });
});
