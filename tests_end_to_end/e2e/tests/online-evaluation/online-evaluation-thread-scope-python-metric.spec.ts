import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/** The score name the metric below reports, and the row the thread panel renders. */
const SCORE_NAME = 'spans_presence';

/**
 * Span names are deliberately short and NOT namespaced.
 *
 * Everything else a spec creates is named through `testNamespace` so teardown
 * can sweep it, but spans are only reachable through their trace and die with
 * it — and these names travel inside the score's `reason`, which this spec
 * asserts verbatim. Keeping them short keeps that assertion readable.
 *
 * Two per turn, so "the assistant message carries spans" and "it carries the
 * right ONES" are different failures.
 */
const SPANS_PER_TURN = ['span-a', 'span-b'] as const;

const TURNS = [
  { question: 'What is the capital of France?', answer: 'The capital of France is Paris.' },
  { question: 'What is its population?', answer: 'Paris has about 2.1 million residents.' },
] as const;

/**
 * A metric that reports the SHAPE of the conversation it was handed, not a
 * judgement about it — so the expected value is a fixed number rather than an
 * LLM verdict, and the spec needs no provider key.
 *
 * The thread-scope python evaluator calls `score()` with the whole conversation
 * as a single positional argument (`process_worker.py` branches on the
 * TRACE_THREAD payload type), so the parameter name is ours to choose;
 * `conversation` matches `ConversationThreadMetric`'s own signature.
 *
 * Do NOT import another BaseMetric subclass into this source: the evaluator
 * picks the alphabetically-first subclass *defined* in the module, and an
 * import that shadowed this class would be instantiated instead.
 */
const SPANS_PRESENCE_METRIC = `from typing import Any, Dict, List
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(SCORE_NAME)}


class SpansPresenceMetric(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(
        self, conversation: List[Dict[str, Any]], **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        keys = sorted({key for message in conversation for key in message})
        nested = [
            ",".join(sorted(span["name"] for span in message["spans"]))
            for message in conversation
            if message.get("spans")
        ]
        return score_result.ScoreResult(
            value=float(len(nested)),
            name=self.name,
            reason=f"keys={','.join(keys)}; msgs={len(conversation)}; nested={'|'.join(nested)}",
        )
`;

test.describe(
  'Online Evaluation — thread-scope python metric context',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'A thread-scope python metric receives each turn\'s spans nested under that turn\'s assistant message',
      { tag: ['@cap:online-evaluation.rule-scope-thread-span'] },
      async ({ project, sdkClient, backendClient, testNamespace, automationRulesCleanup, page }) => {
        test.setTimeout(300_000);

        // What this pins: the conversation handed to a user's `score()` is the
        // enriched `[{role, content, spans}]` shape, with each trace's own
        // spans under its own assistant message. It is a wire contract for code
        // users wrote, and it fails silently — a regression to the legacy
        // `[{role, content}]` shape leaves every span-walking metric scoring
        // happily against nothing.

        const ruleName = `${testNamespace}-py-thread`;
        const threadId = `${testNamespace}-thread`;

        // The rule does not cascade with its project, so `automationRulesCleanup`
        // owns its deletion — on pass, fail and timeout alike.
        await test.step('Create the thread-scope python rule', async () => {
          // Created before the turns are seeded: the engine samples a thread
          // when it closes, and a rule that did not exist yet would never see
          // it.
          await backendClient.createAutomationRule({
            projectId: project.id,
            name: ruleName,
            type: 'trace_thread_user_defined_metric_python',
            samplingRate: 1,
            metric: SPANS_PRESENCE_METRIC,
          });
        });

        const turnTraceIds = await test.step(
          'Seed two turns in one thread, each with two child spans',
          async () => {
            const ids: string[] = [];
            for (let i = 0; i < TURNS.length; i++) {
              const { question, answer } = TURNS[i];
              const created = await sdkClient.python.createNestedTrace({
                project_name: project.name,
                name: `${testNamespace}-turn-${i + 1}`,
                input: { question },
                output: { answer },
                thread_id: threadId,
                spans: SPANS_PER_TURN.map((name) => ({
                  name,
                  type: 'llm' as const,
                  input: { prompt: `${question} (${name})` },
                  output: { completion: `${answer} (${name})` },
                })),
              });
              ids.push(created.id);
              // Turn order is derived from the trace id's embedded timestamp;
              // without a gap two turns can land in the same millisecond.
              if (i < TURNS.length - 1) await new Promise((r) => setTimeout(r, 50));
            }
            return ids;
          },
        );

        await test.step('The spans really landed, before any scoring claim rests on them', async () => {
          // The metric reports what it was handed. If the seed had silently
          // dropped the spans, "no spans in the context" would look identical
          // to the regression this spec exists to catch — so the precondition
          // is asserted server-side, by trace, first.
          for (const traceId of turnTraceIds) {
            // Ingestion is asynchronous, so poll rather than read once.
            await expect
              .poll(
                () => backendClient.listSpanNamesForTrace({ projectId: project.id, traceId }),
                {
                  timeout: 60_000,
                  intervals: [1_000, 2_000],
                  message: `turn ${traceId} never showed its two child spans server-side`,
                },
              )
              .toEqual([...SPANS_PER_TURN]);
          }
        });

        await test.step('Close the thread so the engine picks it up', async () => {
          await backendClient.closeThread({ projectName: project.name, threadId });
        });

        const expectedReason = [
          // Assistant messages carry `spans`; user messages omit the key
          // entirely (the backend serializes it NON_NULL), so the union over
          // all four messages is what proves enrichment happened at all.
          'keys=content,role,spans',
          `msgs=${TURNS.length * 2}`,
          // One group per assistant message, in turn order: each turn's own
          // spans, and only its own. A cross-attachment bug that gave every
          // message all four spans still scores 2.0 — this is what fails it.
          `nested=${TURNS.map(() => SPANS_PER_TURN.join(',')).join('|')}`,
        ].join('; ');

        await test.step('The stored thread score reports the enriched context', async () => {
          let scores: Array<{ name: string; value: number; reason: string | null }> = [];
          await expect
            .poll(
              async () => {
                scores = (await backendClient.getThread({ projectId: project.id, threadId }))
                  .feedbackScores;
                return scores.length;
              },
              {
                timeout: 240_000,
                intervals: [2_000, 5_000],
                message:
                  `rule '${ruleName}' never scored thread '${threadId}' — the metric cannot ` +
                  'fail silently, so an empty score set means it was never invoked',
              },
            )
            .toBeGreaterThan(0);

          // The whole set, not just ours: a second score here would mean the
          // engine ran something this spec did not ask for.
          expect(scores.map((s) => s.name)).toEqual([SCORE_NAME]);
          expect(
            scores[0].value,
            'one group of nested spans per assistant message — 0.0 is the pre-enrichment shape',
          ).toBe(TURNS.length);
          expect(
            scores[0].reason,
            'the metric saw the enriched keys, all four messages, and each turn\'s own spans',
          ).toBe(expectedReason);
        });

        await test.step('The thread panel renders the same score', async () => {
          const logs = new LogsPage(page);
          await logs.gotoThreads(project.id);
          await logs.waitForThreadsReady(threadId);

          const panel = await logs.openThreadById(threadId);
          await panel.waitForFullyLoaded();
          await panel.openFeedbackScoresTab();

          await expect(
            panel.feedbackScoreRow(SCORE_NAME),
            'exactly one row for the rule\'s score',
          ).toHaveCount(1);
          expect(await panel.readFeedbackScoreValue(SCORE_NAME)).toBe(TURNS.length);
          await expect(
            panel.feedbackScoreReasonCell(SCORE_NAME),
            'the enriched key set reaches the page a human reads it on',
          ).toContainText('keys=content,role,spans');
        });
      },
    );
  },
);
