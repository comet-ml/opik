import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The `output` shapes a real trace can carry. Only the last one is an object —
 * every other row is a section the online-scoring extractor has to serialize
 * whole, and the first three are unreachable through the SDK: `@opik.track`
 * wraps a scalar return as `{"output": ...}` before it leaves the process, so
 * every existing online-evaluation spec has only ever driven the object branch.
 *
 * `expectedArgument` is the JSON serialization the engine hands the metric for
 * the whole-section mapping (`output` -> JsonPath `$`), which is what makes the
 * bare string arrive QUOTED. Written as a literal rather than computed with
 * `JSON.stringify(output)` so a change in the backend's serialization has to be
 * acknowledged here rather than silently tracked by the assertion.
 */
const OUTPUT_SHAPES = [
  { key: 'bare-string', output: 'hello world', expectedArgument: '"hello world"' },
  { key: 'number', output: 42, expectedArgument: '42' },
  { key: 'array', output: ['a', 'b'], expectedArgument: '["a","b"]' },
  {
    key: 'object',
    output: { output: 'hello world' },
    expectedArgument: '{"output":"hello world"}',
  },
] as const;

/**
 * A metric that scores every trace 1.0 and echoes back, as the score's reason,
 * the exact string the engine handed it.
 *
 * The value is deliberately constant: what this spec is about is not whether a
 * metric can compute, it is WHICH payload survives extraction — so the payload
 * has to come back out somewhere assertable, and `reason` is the only channel a
 * feedback score has. A metric that instead compared against a literal could
 * only answer "right or wrong" and would report a serialization change as a
 * plain 0.0 with nothing to debug from.
 *
 * Do NOT import additional BaseMetric subclasses here: the python_evaluator
 * backend's get_metric_class iterates module classes alphabetically and picks
 * the first BaseMetric subclass, so an import would shadow this one.
 */
function buildEchoMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class EchoRule(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name, reason=str(output))`;
}

test.describe(
  'Online Evaluation — scalar trace output',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test('A Python rule scores traces whose output is a bare string, number or array, and receives each section serialized whole', { tag: ['@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
      project,
      backendClient,
      testNamespace,
      page,
      automationRulesCleanup,
    }) => {
      // Scoring landed within seconds on every observed run; the budget is for
      // the four polls plus the browser step, not for expected latency.
      test.setTimeout(180_000);

      const ruleName = `${testNamespace}-echo`;

      await test.step('Create a Python rule with the whole-section mapping `output`', async () => {
        // `output` with no dot resolves to JsonPath `$` — the whole section,
        // whatever its type. The dotted form (`output.output`) is what every
        // other spec uses, and it only ever resolves against an object; this
        // spec exists for the other branch.
        const rule = await backendClient.createPythonAutomationRule({
          projectId: project.id,
          name: ruleName,
          metric: buildEchoMetric(ruleName),
          metricArguments: { output: 'output' },
        });
        expect(rule.enabled, 'a disabled rule would score nothing').toBe(true);
      });

      const seeded = await test.step('Seed one trace per output shape', async () => {
        return Promise.all(
          OUTPUT_SHAPES.map(async (shape) => ({
            ...shape,
            id: await backendClient.createTraceWithRawOutput({
              projectName: project.name,
              name: `${testNamespace}-${shape.key}`,
              input: { question: 'whatever' },
              output: shape.output,
            }),
          })),
        );
      });

      await test.step('Each trace really stored the shape it was seeded with', async () => {
        // The gate that keeps the rest of the test honest. If ingestion wrapped
        // a bare string into an object on the way in, every assertion below
        // would still pass while covering the object branch it claims not to —
        // coverage for a code path nothing reached.
        for (const trace of seeded) {
          const stored = await backendClient.getTraceRawOutput(trace.id);
          expect(
            stored,
            `trace ${trace.key} must be stored as the raw ${trace.key} it was sent as`,
          ).toEqual(trace.output);
        }
      });

      const scored = await test.step('Every shape produced a feedback score', async () => {
        const results = await Promise.all(
          seeded.map(async (trace) => {
            await backendClient.pollTraceForFeedbackScore(trace.id, ruleName, {
              timeoutMs: 90_000,
            });
            // Re-read the whole trace rather than trusting the polled score:
            // the claim is that this rule produced exactly one score on each
            // trace, and the poll can only speak for the score it looked for.
            const detail = await backendClient.getTrace(trace.id);
            expect(detail, `trace ${trace.key} must still exist`).not.toBeNull();
            return { ...trace, scores: detail!.feedbackScores };
          }),
        );
        expect(results.length, 'no shape may be skipped').toBe(OUTPUT_SHAPES.length);
        return results;
      });

      await test.step('The metric received each section serialized whole', async () => {
        for (const trace of scored) {
          expect(
            trace.scores.map((s) => s.name),
            `trace ${trace.key} carries exactly the rule's own score`,
          ).toEqual([ruleName]);

          const score = trace.scores[0];
          expect(score.value, `trace ${trace.key} score value`).toBe(1.0);
          // The point of the whole spec. A bare string arrives JSON-encoded
          // (quotes included), which is what OnlineScoringEngine's `$` branch
          // serializes; anything else means the section was mangled or the
          // variable silently dropped.
          expect(
            score.reason,
            `the metric's \`output\` argument for the ${trace.key} trace`,
          ).toBe(trace.expectedArgument);
        }
      });

      await test.step('The bare-string trace shows the score in the trace panel', async () => {
        const bareString = scored.find((t) => t.key === 'bare-string')!;
        const logs = new LogsPage(page);
        await logs.goto(project.id);
        await logs.waitForReady();

        const panel = await logs.openTraceById(bareString.id);
        await panel.waitForFullyLoaded();
        await panel.openFeedbackScoresTab();
        expect(
          await panel.readFeedbackScoreValue(ruleName),
          'the score a scalar-output trace earned is the one the user sees',
        ).toBe(1.0);
      });
    });
  },
);
