import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * Clone, from the rules list's row kebab (opik#8976). The editor opens on a
 * copy of the source rule, pre-named `<name> (Copy)`, and saving it creates a
 * second, independent rule.
 *
 * A clone is the shape where "it looks right" is worth very little. The rules
 * list shows a name, a status and a sampling rate, so a clone that silently
 * dropped its cost limit, its second score or the names of its variables looks
 * completely healthy in the list and in the editor — and scores differently, or
 * not at all, for as long as it lives.
 *
 * Two halves, deliberately, because they fail independently:
 *
 *   1. The BODY is copied. Asserted by comparing the whole stored `code` of the
 *      two rules rather than field by field: a key the form forgot to carry
 *      over is exactly what a list of named assertions cannot see, since nobody
 *      writes an assertion for the field they did not know existed.
 *   2. The clone really SCORES. A rule that was written correctly but never
 *      registered with the engine is the other way this fails, and it is
 *      invisible to the comparison above. Driven on a python code evaluator,
 *      which is deterministic and needs no provider key.
 *
 * Both are provider-free, and so run on any deployment.
 */

/** Neither the form default (1.0) nor 0 — a rate only this test would set. */
const SEEDED_SAMPLING_RATE = 0.42;

/** A limit the server has no default for: the key is absent unless it was set. */
const SEEDED_COST_LIMIT = 1.5;

/** The source's prompt, with two mappings the author named itself. */
const SEEDED_PROMPT = 'Grade {{answer}} against {{question}}.';

const SEEDED_VARIABLES = { answer: 'output.output', question: 'input.question' };

/**
 * Two scores, not one. A clone that rebuilt the schema from the editor's own
 * default would carry exactly one entry, which a single-score source could not
 * distinguish from a faithful copy.
 */
const SEEDED_SCHEMA = [
  { name: 'Accuracy', type: 'INTEGER' as const, description: 'How accurate the answer is.' },
  { name: 'Grounded', type: 'BOOLEAN' as const, description: 'Whether the answer cites the input.' },
];

test.describe(
  'Online Evaluation — clone rule',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'Cloning a judge rule copies the whole evaluator body and differs only in id and name',
      { tag: ['@cap:online-evaluation.clone-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const sourceName = `${testNamespace}-clone-source`;
        const cloneName = `${sourceName} (Copy)`;

        // Seeded over REST, and seeded DISABLED at a non-default rate with a
        // cost limit: all three are values the server would never choose, so a
        // clone that lost them and got the defaults back cannot compare equal
        // to one that carried them.
        const sourceId = await test.step('Seed a judge rule with named variables, two scores and a cost limit', async () =>
          backendClient.createLlmJudgeRule({
            projectId: project.id,
            name: sourceName,
            messages: [{ role: 'USER', content: SEEDED_PROMPT }],
            variables: SEEDED_VARIABLES,
            schema: SEEDED_SCHEMA,
            samplingRate: SEEDED_SAMPLING_RATE,
            maxCostUsd: SEEDED_COST_LIMIT,
            enabled: false,
          }));

        const source = await test.step('The seed really holds everything the clone has to carry', async () => {
          // Asserted before the browser opens. A comparison against a source
          // that silently failed to hold its own cost limit would pass while
          // proving nothing, and would read as coverage forever.
          const body = await backendClient.getLlmJudgeRuleBody(sourceId);
          expect(body.samplingRate, 'the non-default rate').toBe(SEEDED_SAMPLING_RATE);
          expect(body.enabled, 'seeded disabled').toBe(false);
          expect(body.code.max_cost_usd, 'the cost limit').toBe(SEEDED_COST_LIMIT);
          expect(body.code.variables, 'both named mappings').toEqual(SEEDED_VARIABLES);
          expect(body.code.schema, 'both score definitions').toHaveLength(2);
          return body;
        });

        const onlineEval = new OnlineEvaluationPage(page);

        const cloneId = await test.step('Clone it from the row actions menu and save', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await expect(onlineEval.ruleRow(sourceName)).toHaveCount(1);
          await onlineEval.cloneRuleByName(sourceName);
          return onlineEval.submitPanelAndCaptureRuleId();
        });

        await test.step('The clone is a second rule, not the same one renamed', async () => {
          expect(cloneId, 'the clone has its own id').not.toBe(sourceId);
          await expect(onlineEval.ruleRow(sourceName), 'the source is still listed').toHaveCount(1);
          await expect(onlineEval.ruleRow(cloneName), 'and the clone beside it').toHaveCount(1);
        });

        const clone = await test.step('The clone carries the whole evaluator body', async () => {
          const body = await backendClient.getLlmJudgeRuleBody(cloneId);
          // The load-bearing assertion. `code` is compared whole and
          // un-normalised — model, messages, variables, schema and the cost
          // limit in one comparison — so a field the form dropped fails here
          // whether or not this spec knew to name it.
          expect(
            body.code,
            'the clone holds the same evaluator as its source, key for key',
          ).toEqual(source.code);
          expect(body.samplingRate, 'and the same sampling rate').toBe(source.samplingRate);
          expect(body.triggerScope, 'and the same trigger scope').toBe(source.triggerScope);
          expect(body.enabled, 'and the same enabled state').toBe(source.enabled);
          return body;
        });

        await test.step('…and differs from it only in id and name', async () => {
          expect(clone.name, 'the clone takes the pre-filled copy name').toBe(cloneName);
          expect(clone.id, 'and its own id').toBe(cloneId);
        });

        await test.step('The source rule is untouched by the clone', async () => {
          // A clone implemented as an edit-then-create would show up here and
          // nowhere else: the source would have been saved on its way past.
          const after = await backendClient.getLlmJudgeRuleBody(sourceId);
          expect(after.name, 'the source keeps its name').toBe(sourceName);
          expect(after.code, 'and its evaluator body').toEqual(source.code);
          expect(after.enabled, 'and its enabled state').toBe(source.enabled);
          expect(after.samplingRate, 'and its sampling rate').toBe(source.samplingRate);
        });
      },
    );

    test(
      'A cloned code evaluator scores new traces on its own',
      { tag: ['@cap:online-evaluation.clone-rule'] },
      async ({
        project,
        sdkClient,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        test.setTimeout(240_000);

        const sourceName = `${testNamespace}-clone-code`;
        const cloneName = `${sourceName} (Copy)`;
        const reference = 'seed output';

        const onlineEval = new OnlineEvaluationPage(page);

        const sourceId = await test.step('Create a python Equals evaluator through the UI', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          return onlineEval.fillAndSubmitCreateRulePythonEquals({
            name: sourceName,
            referenceValue: reference,
          });
        });

        const cloneId = await test.step('Clone it and save', async () => {
          await expect(onlineEval.ruleRow(sourceName)).toHaveCount(1);
          await onlineEval.cloneRuleByName(sourceName);
          return onlineEval.submitPanelAndCaptureRuleId();
        });

        await test.step('Disable the SOURCE so only the clone can score', async () => {
          // Authorship is otherwise ambiguous: the clone copies the metric
          // source verbatim, and the metric names its own score, so BOTH rules
          // write a score called after the source rule. Silencing the source is
          // what turns "a score arrived" into "the clone produced it".
          await onlineEval.setRuleEnabledByName(sourceName, false);
        });

        await test.step('The two rules really are in the states this test needs', async () => {
          const [sourceRule, cloneRule] = await Promise.all([
            backendClient.getAutomationRule(sourceId),
            backendClient.getAutomationRule(cloneId),
          ]);
          expect(sourceRule.enabled, 'the source is silenced').toBe(false);
          expect(cloneRule.enabled, 'and the clone is live').toBe(true);
          expect(cloneRule.type, 'the clone is the same kind of evaluator').toBe(sourceRule.type);
          expect(cloneRule.triggerScope, 'on the same trace sources').toBe(sourceRule.triggerScope);
        });

        const trace = await test.step('Seed a trace the metric scores 1.0', async () =>
          sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-trace`,
            input: 'whatever',
            output: reference,
          }));

        await test.step('The trace carries the score, and only that score', async () => {
          const score = await backendClient.pollTraceForFeedbackScore(trace.id, sourceName, {
            timeoutMs: 180_000,
          });
          expect(score.value, 'the copied Equals metric matched the reference').toBe(1.0);

          // By exhaustion rather than by lookup: the disabled source writing a
          // second copy, or the clone writing twice under two names, is the
          // failure a `find` for the one expected name passes straight over.
          const detail = await backendClient.getTrace(trace.id);
          expect(detail, 'the seeded trace must exist to be asserted about').not.toBeNull();
          expect(
            detail!.feedbackScores.map((s) => s.name).sort(),
            'exactly one score, written once',
          ).toEqual([sourceName]);
        });

        await test.step("The clone's own log stream names it as the author", async () => {
          let logs: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                logs = await backendClient.getAutomationRuleLogs(cloneId);
                // The store line is written last in the scorer's chain, so its
                // arrival is what makes this rule's stream complete.
                return logs.some((l) => l.message.includes('stored successfully'));
              },
              {
                timeout: 120_000,
                intervals: [2_000, 5_000],
                message: `the clone '${cloneName}' never reported storing a score`,
              },
            )
            .toBe(true);

          const stream = logs.map((l) => l.message).join('\n---\n');
          expect(stream, 'the clone sampled the seeded trace under its own name').toContain(
            `sampled by rule '${cloneName}'`,
          );
          expect(stream, 'and the line is about this trace').toContain(trace.id);
          expect(stream, 'and it stored the score it computed').toContain(
            `Scores for traceId '${trace.id}' stored successfully`,
          );
        });

        await test.step('The source saw the same trace and skipped it for being disabled', async () => {
          // The complement, and it is stronger than silence would be: the
          // sampler writes a skip line for a disabled rule, so the source is
          // provably reached and provably declines. An absent stream could
          // equally mean the fan-out never got as far as this rule, which
          // would say nothing about who wrote the score above.
          let aboutThisTrace: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                const sourceLogs = await backendClient.getAutomationRuleLogs(sourceId);
                aboutThisTrace = sourceLogs.filter((l) => l.message.includes(trace.id));
                return aboutThisTrace.length;
              },
              {
                timeout: 60_000,
                intervals: [2_000, 5_000],
                message: `the source '${sourceName}' never reported seeing the seeded trace`,
              },
            )
            .toBeGreaterThan(0);

          for (const line of aboutThisTrace) {
            expect(
              line.message,
              'every line the source wrote about this trace is a skip, not a score',
            ).toContain('as the rule is disabled');
            expect(line.message, 'and it names the source rule').toContain(sourceName);
          }
        });
      },
    );
  },
);
