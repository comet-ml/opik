import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import {
  permanentFailureSkipReason,
  permanentFailureStatus,
} from '@e2e/core/failing-provider';

/**
 * The 500-era wording. A provider failure that reaches the user as this has
 * lost the status the engine was told, which is the whole point of classifying
 * one.
 */
const OPAQUE_FAILURE_MESSAGE = 'An unexpected error occurred';

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to LLM:';

/** Emitted once when the sampler hands a trace to the rule. */
const SAMPLED_LINE = 'sampled by rule';

/**
 * Every line the engine writes for one trace judged by one healthy-path rule:
 * sampled, sent, then the outcome. A permanent failure must not lengthen it.
 */
const EXPECTED_LINES_PER_TRACE = 3;

test.describe('Online Evaluation — LLM-judge provider failure classification', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('An LLM-judge rule whose provider answers a permanent 4xx fails once, reports the provider status, and writes no score', { tag: ['@cap:online-evaluation.llm-judge-scores'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    providerKeys,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // No page: the subject is how the online-scoring engine classifies a
    // provider outcome, and the rule's log stream is where that classification
    // is stated. The page that renders those same lines is driven by
    // online-evaluation-automation-logs.spec.ts, which is a claim about the
    // page — driving a browser here would add a rendering failure mode to an
    // assertion that has nothing to do with rendering.

    const skipReason = await permanentFailureSkipReason();
    test.skip(skipReason !== null, skipReason ?? '');
    const expectedStatus = await permanentFailureStatus();

    const providerName = `${testNamespace}-perm4xx`;
    const ruleName = `${testNamespace}-judge`;

    const model = await test.step('Register a provider whose base URL answers a permanent 4xx', async () =>
      providerKeys.createPermanentFailure({ providerName, modelName: 'judge' }));

    const ruleId = await test.step('Create an LLM-judge rule pointed at that provider', async () =>
      backendClient.createLlmJudgeRule({
        projectId: project.id,
        name: ruleName,
        // Every trace, so the single seeded one is provably judged rather than
        // sampled away — a skipped trace and a never-delivered one look alike
        // from the outside.
        samplingRate: 1,
        model,
        prompt: 'Rate the quality of this output from 0 to 1: {{output}}',
        variables: { output: 'output.output' },
        schema: [{ name: 'Quality', type: 'INTEGER', description: '0 or 1' }],
      }));

    const trace = await test.step('Seed one trace for the rule to judge', async () =>
      sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'whatever',
        output: 'seed output',
      }));

    const logs = await test.step('Wait for the rule to report its failure', async () => {
      let collected: AutomationRuleLogRef[] = [];
      await expect
        .poll(
          async () => {
            collected = await backendClient.getAutomationRuleLogs(ruleId);
            // The failure line is written last in the scorer's chain, so its
            // arrival is what makes the stream complete for this trace.
            return collected.some((l) => l.level === 'ERROR');
          },
          {
            timeout: 180_000,
            intervals: [2_000, 5_000],
            message:
              `rule '${ruleName}' never reported a failure — its provider cannot succeed, so a ` +
              'silent stream means the rule was never invoked at all',
          },
        )
        .toBe(true);
      return collected;
    });

    await test.step('The rule called the provider once and reported once', async () => {
      // A permanent 4xx is a terminal answer: the subscriber must ack and retire
      // the message rather than redeliver it. Both counts are asserted because
      // they fail differently — a redelivered message repeats the call line,
      // while an in-process retry loop that eventually gives up repeats only the
      // error line.
      expect(
        logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
        `rule '${ruleName}' must send the trace to the provider exactly once`,
      ).toHaveLength(1);
      expect(
        logs.filter((l) => l.level === 'ERROR'),
        `rule '${ruleName}' must report its terminal failure exactly once`,
      ).toHaveLength(1);
      expect(
        logs.filter((l) => l.message.includes(SAMPLED_LINE)),
        `rule '${ruleName}' must sample the trace exactly once`,
      ).toHaveLength(1);
    });

    await test.step('The stream holds nothing but those three lines, all for the seeded trace', async () => {
      // Counting the individual line kinds above would still pass if a
      // redelivery had also written lines this spec does not name. The project
      // is fixture-fresh and carries exactly one trace, so the whole stream is
      // knowable — assert it, not just the part that was looked for.
      expect(
        logs.map((l) => `${l.level} ${l.message.split('\n')[0]}`),
        'a fresh rule that judged one trace once writes exactly three lines',
      ).toHaveLength(EXPECTED_LINES_PER_TRACE);
      expect(
        logs.map((l) => l.traceId),
        'every line must be attributed to the seeded trace',
      ).toEqual(Array(EXPECTED_LINES_PER_TRACE).fill(trace.id));
    });

    await test.step(`The failure reports the provider's own ${expectedStatus}, not a blanket 500`, async () => {
      const error = logs.find((l) => l.level === 'ERROR');
      expect(error, 'the ERROR line asserted above must still be here to read').toBeDefined();

      // The engine writes "…with rule '<name>': \n\n<what the provider said>".
      // Assert against the second half only: the header carries a trace id and
      // a run-stamped rule name, and either can contain the digits of a status
      // by chance — a whole-message search for "500" would fail one run in a
      // few hundred for no reason at all.
      const [header, ...detail] = error!.message.split('\n\n');
      const providerDetail = detail.join('\n\n');
      expect(
        providerDetail,
        `the failure must carry what the provider said; got only the header '${header}'`,
      ).not.toBe('');
      expect(
        providerDetail,
        'the classified provider status is what tells a user their key or endpoint is wrong',
      ).toContain(String(expectedStatus));
      expect(
        providerDetail,
        'a 5xx would be the retryable classification, and this failure is not retryable',
      ).not.toContain('500');
      expect(
        error!.message,
        'the pre-classification catch-all carries no status at all',
      ).not.toContain(OPAQUE_FAILURE_MESSAGE);
    });

    await test.step('A failed evaluation writes no score', async () => {
      // A rule that failed but still stored something would be worse than one
      // that failed loudly: the score would be read as a judgement.
      const detail = await backendClient.getTrace(trace.id);
      expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => s.name),
        'a rule that never reached its provider must not write a feedback score',
      ).toEqual([]);
    });
  });
});
