import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { VERTEX_AI_MODEL, ensureVertexAiProviderKey } from '@e2e/pom/model-availability';

/**
 * A Vertex AI LLM judge still scores after VERTEX_AI moved from
 * `ToolChoice.AUTO` to `ToolChoice.REQUIRED` (opik#9356, OPIK-7855).
 *
 * The riskiest behavioural flip in that PR. `AgenticScoringService
 * #firstRoundToolChoice` used to answer AUTO for VERTEX_AI and now answers
 * REQUIRED with the rest of the tool-calling providers, and the comment the PR
 * deleted says why the AUTO arm existed at all: langchain4j's
 * `VertexAiGeminiChatModel` threw `UnsupportedFeatureException` on any explicit
 * tool choice. That failure is terminal, not a degradation — it does not make
 * the judge worse, it fails the evaluation and leaves the trace silently
 * unscored — and the client that is supposed to accept a forced choice is the
 * `GoogleGenAiChatModel` this same PR swapped in. So the whole claim rests on
 * the new client, and nothing covered it: online-evaluation's judge specs run on
 * other providers, and the three Vertex-adjacent provider-failure specs assert
 * failures rather than a scored run.
 *
 * API-level, with no page: the subject is a backend scoring path, and the rule
 * dialog is asserted by this directory's own UI specs. Reading it through a
 * browser would observe the same facts second-hand and more flakily.
 *
 * What makes it deterministic despite a real model: REQUIRED forces a tool call
 * on round one, so "the first response carried a tool call, no error was
 * reported, and a score was stored" does not depend on the model's wording.
 * Only the score VALUE would, and this spec never reads it.
 *
 * `@llm-daily`, with no tier, for the reason `playground-providers.spec.ts` has
 * no tier: it makes real, billable Vertex calls — one judge evaluation on the
 * cheapest model Vertex serves — so it runs on the daily real-provider cadence
 * and is excluded from t1/t2/t3. It skips cleanly without a `VERTEX_AI_API_KEY`;
 * see `vertexAiKeyOrSkip` for why no mock can stand in for a Vertex credential.
 */

/** The `{{trace}}` sentinel: it is what forces the agentic-tools path. */
const TRACE_SENTINEL = 'trace';

/**
 * Why `variables: { trace: 'trace' }` AND `{{trace}}` in the message.
 *
 * `OnlineScoringEngine#templateReferencesTraceStructure` accepts either opt-in
 * shape — a variable whose value is the bare sentinel, or a direct template
 * reference the variables map does not rebind — and `shouldUseAgenticTools`
 * reads it as "this rule wants tools regardless of context size". Sending both
 * is what the FE writes when a user types `{{trace}}`, and it keeps the routing
 * independent of the seeded trace being small: the two other ways into the
 * agentic path (an experiment-scoped rule, a context over
 * `agenticToolsThresholdTokens`) would make the spec depend on a size estimate.
 */
const JUDGE_PROMPT = `Rate the quality of this trace from 0 to 10. Trace: {{${TRACE_SENTINEL}}}`;

/** The score the rule's output schema declares, and the one it must write. */
const SCORE_NAME = 'Accuracy';

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * `AgenticScoringService#summarizeRequest`, as the rule's own log stream carries
 * it: `model='…', messages=N, tools=N, toolsEnabled=true|false`.
 *
 * Anchored and whole rather than matched by substring, and with the model in it,
 * because the line is the only place the stream says which path the evaluation
 * took: a judge that fell back to the inline path reports `tools=0,
 * toolsEnabled=false` on the same line, and a substring match for "toolsEnabled"
 * would accept it.
 */
const sentToLlmLine = (traceId: string): RegExp =>
  new RegExp(
    `^Sending traceId '${escapeRegExp(traceId)}' to LLM: ` +
      `model='${escapeRegExp(VERTEX_AI_MODEL.modelId)}', messages=\\d+, ` +
      `tools=(\\d+), toolsEnabled=(\\w+)$`,
  );

/** `AgenticScoringService#summarizeResponse`, one line per tool-loop round. */
const receivedResponseLine = (traceId: string): RegExp =>
  new RegExp(
    `^Received response for traceId '${escapeRegExp(traceId)}': ` +
      `'textChars=\\d+, toolCalls=(\\d+), finishReason=(\\w+)'$`,
  );

/** Written when the rule is sampled, before anything can go wrong. */
const sampledLine = (traceId: string, ruleName: string): RegExp =>
  new RegExp(
    `^Evaluating traceId '${escapeRegExp(traceId)}' sampled by rule ` +
      `'${escapeRegExp(ruleName)}'$`,
  );

/** The success line, written last — which is what makes the stream complete. */
const storedScoresLine = (traceId: string): RegExp =>
  new RegExp(`^Scores for traceId '${escapeRegExp(traceId)}' stored successfully:`);

test.describe(
  'Online Evaluation — Vertex AI agentic judge',
  { tag: ['@llm-daily', '@area:online-evaluation'] },
  () => {
    test(
      'a Vertex AI judge forced to call a tool on round one still stores its score',
      { tag: ['@cap:online-evaluation.llm-judge-scores'] },
      async ({
        project,
        sdkClient,
        backendClient,
        testNamespace,
        automationRulesCleanup,
      }) => {
        // One real judge evaluation, plus the agentic tool loop's own rounds.
        test.setTimeout(420_000);

        await ensureVertexAiProviderKey();

        const ruleName = `${testNamespace}-vertex-agentic-judge`;

        const ruleId = await test.step(
          'Create a Vertex AI LLM-judge rule whose prompt opts into the agentic-tools path',
          async () =>
            backendClient.createLlmJudgeRule({
              projectId: project.id,
              name: ruleName,
              model: VERTEX_AI_MODEL.modelId,
              messages: [{ role: 'USER', content: JUDGE_PROMPT }],
              variables: { [TRACE_SENTINEL]: TRACE_SENTINEL },
              scoreName: SCORE_NAME,
              samplingRate: 1,
            }),
        );

        const trace = await test.step('Seed one small trace for the judge to score', async () =>
          // Small on purpose: the context stays well under
          // `agenticToolsThresholdTokens`, so the ONLY thing routing this
          // evaluation through the tool loop is the `{{trace}}` sentinel.
          sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-trace`,
            input: 'What is 2 + 2?',
            output: '4',
          }));

        await test.step('The judge stored its score on the seeded trace', async () => {
          const score = await backendClient.pollTraceForFeedbackScore(trace.id, SCORE_NAME, {
            timeoutMs: 300_000,
          });
          expect(
            score.source,
            'the score came from online scoring, not from an SDK write',
          ).toBe('online_scoring');
          // The judge's own number is the model's opinion, so the claim is that
          // an INTEGER landed in the schema's range — not which one.
          expect(Number.isInteger(score.value), `a whole number, got ${score.value}`).toBe(true);
          expect(score.value).toBeGreaterThanOrEqual(0);
          expect(score.value).toBeLessThanOrEqual(10);
        });

        await test.step(
          'The rule log shows the forced first round: tools attached, a tool call made, no error',
          async () => {
            let logs: AutomationRuleLogRef[] = [];
            await expect
              .poll(
                async () => {
                  logs = await backendClient.getAutomationRuleLogs(ruleId);
                  // The stored-scores line is written last on the happy path, so
                  // its arrival is what makes this stream complete. Polling for
                  // it rather than reading once avoids asserting "no ERROR" over
                  // a stream that has not finished being written.
                  return logs.some((l) => storedScoresLine(trace.id).test(l.message));
                },
                {
                  timeout: 300_000,
                  intervals: [2_000, 5_000],
                  message:
                    `rule '${ruleName}' never reported storing a score for the seeded trace`,
                },
              )
              .toBe(true);

            expect(
              logs.filter((l) => sampledLine(trace.id, ruleName).test(l.message)),
              'the rule was sampled for the seeded trace exactly once',
            ).toHaveLength(1);

            // The whole of what must NOT be there, as a level set rather than an
            // ERROR hunt, because the two ways this can go wrong arrive at
            // different levels:
            //
            //  - ERROR — an `UnsupportedFeatureException` from a client refusing
            //    a forced tool choice, the failure the AUTO arm existed to
            //    avoid. It surfaces here and nowhere else, and it is compatible
            //    with a score having been stored by a later re-delivery, so
            //    "a score exists" does not cover it.
            //  - WARN — the agentic path declined. Measured on this build, a
            //    `{{trace}}` rule on a provider without tool calling logs
            //    "rule references {{trace}} but provider … does not support tool
            //    calling; falling back to inline path", scores anyway, and would
            //    otherwise read as a pass.
            expect(
              logs.filter((l) => l.level !== 'INFO').map((l) => `${l.level}|${l.message}`),
              'the evaluation reported nothing wrong: no refusal, and no fallback off the ' +
                'agentic path',
            ).toEqual([]);

            const sent = logs.filter((l) => sentToLlmLine(trace.id).test(l.message));
            expect(
              sent,
              `the judge reached its provider on ${VERTEX_AI_MODEL.modelId} — a rule that fell ` +
                'back to the inline path logs a different model line or none',
            ).not.toHaveLength(0);
            // Oldest last: the logs endpoint orders `timestamp DESC`
            // (`AutomationRuleEvaluatorLogsDAO.FIND_ALL`), so the final element
            // is round one — the round whose tool choice this PR changed.
            const firstRoundRequest = sent[sent.length - 1]!.message.match(
              sentToLlmLine(trace.id),
            )!;
            expect(
              Number(firstRoundRequest[1]),
              'the request carried the tool specs',
            ).toBeGreaterThan(0);
            expect(firstRoundRequest[2], 'and the tool loop was enabled').toBe('true');

            const received = logs.filter((l) => receivedResponseLine(trace.id).test(l.message));
            expect(received, 'the provider answered the first round').not.toHaveLength(0);
            const firstRoundResponse = received[received.length - 1]!.message.match(
              receivedResponseLine(trace.id),
            )!;
            // This is the assertion that tells REQUIRED from the AUTO it
            // replaced: under AUTO the model may answer round one with text and
            // no tool call, and the loop would finish there. Without it the spec
            // would pass on a judge that never entered the tool loop at all.
            expect(
              Number(firstRoundResponse[1]),
              'the forced first round produced a tool call',
            ).toBeGreaterThanOrEqual(1);
          },
        );
      },
    );
  },
);
