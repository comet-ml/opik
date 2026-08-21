import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7 } from '@e2e/core/backend';

/**
 * A rule variable maps to a path inside a trace section (`output.answer`). When
 * the section is not a JSON object — a bare string, an array, a number — the
 * path cannot resolve. The contract is that the variable is simply dropped and
 * the metric still runs on whatever else resolved; the regression this covers
 * is the whole evaluation aborting instead, which a user only ever sees as a
 * trace that silently never got a score.
 *
 * Two rules run over the same five traces so both halves of the contract are
 * asserted on the same data: `output.answer` resolves only against the object
 * output, and `output.[0].name` resolves only against the array-of-objects one.
 * Every other pairing is an unresolvable path over a section of some other
 * shape, and every one of them must still produce a score.
 */

/** The metric's answer when its `answer` kwarg resolved. */
const RESOLVED = 1.0;
/** The metric's answer when `answer` was dropped and defaulted. */
const DROPPED = 0.5;

interface OutputShape {
  /** Suffix for the trace name; also the key results are collated under. */
  key: string;
  output: unknown;
  /** What `output.answer` resolves to for this shape, or null if it cannot. */
  nestedAnswer: string | null;
  /** What `output.[0].name` resolves to for this shape, or null if it cannot. */
  indexedAnswer: string | null;
}

const OUTPUT_SHAPES: OutputShape[] = [
  { key: 'object', output: { answer: 'forty two' }, nestedAnswer: 'forty two', indexedAnswer: null },
  { key: 'bare-string', output: 'how can I help?', nestedAnswer: null, indexedAnswer: null },
  { key: 'string-array', output: ['a', 'b'], nestedAnswer: null, indexedAnswer: null },
  { key: 'number', output: 42, nestedAnswer: null, indexedAnswer: null },
  {
    key: 'object-array',
    output: [{ name: 'first' }, { name: 'second' }],
    nestedAnswer: null,
    indexedAnswer: 'first',
  },
];

const INPUT = { x: 'seed-question' };

/**
 * A metric that reports which of its two mapped variables actually arrived.
 *
 * `answer` carries a default so a dropped variable is a *value* the metric can
 * report rather than a `TypeError` — the reason string is what turns "scored
 * 0.5" into "scored 0.5 because `answer` never resolved", which is the whole
 * claim of this test. A metric that declared `answer` with no default would
 * fail instead, and that boundary belongs to a different test.
 *
 * Only one `BaseMetric` subclass is declared and nothing else is imported:
 * `get_metric_class` in the python_evaluator backend takes the first subclass
 * in the module alphabetically, so an extra import would shadow this one.
 */
function buildMappingProbeMetric(scoreName: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

SCORE_NAME = ${JSON.stringify(scoreName)}

class MappingProbe(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, q: str = "<unmapped>", answer: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        value = ${RESOLVED} if answer is not None else ${DROPPED}
        return score_result.ScoreResult(value=value, name=self.name, reason=f"q={q!r} answer={answer!r}")`;
}

/** The reason string `MappingProbe` stamps for a given resolved `answer`. */
function expectedReason(answer: string | null): string {
  const rendered = answer === null ? 'None' : `'${answer}'`;
  return `q='${INPUT.x}' answer=${rendered}`;
}

test.describe(
  'Online Evaluation — variable mapping over non-object trace sections',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'A Python rule scores every trace whether or not its mapped path resolves, and drops only the unresolvable variable',
      { tag: ['@cap:online-evaluation.python-rule-scores'] },
      async ({ project, backendClient, pythonMetricRules, page }) => {
        // Two async waits dominate: the scores poll (120s) and the log poll
        // (60s). The ceiling sits above their sum so a stall fails inside the
        // step that stalled, naming the trace and the scores actually seen,
        // rather than as an opaque test timeout. Runs in ~40-60s in practice.
        test.setTimeout(300_000);

        // Both rules must exist before any trace is seeded: the sampler only
        // sees trace-created/updated events, so a rule created afterwards never
        // scores what already landed.
        const nestedRule = await test.step(
          'Create a rule mapping answer -> output.answer',
          async () =>
            pythonMetricRules.create({
              suffix: 'nested',
              arguments: { q: 'input.x', answer: 'output.answer' },
              buildMetric: buildMappingProbeMetric,
            }),
        );

        const indexedRule = await test.step(
          'Create a rule mapping answer -> output.[0].name',
          async () =>
            pythonMetricRules.create({
              suffix: 'indexed',
              arguments: { q: 'input.x', answer: 'output.[0].name' },
              buildMetric: buildMappingProbeMetric,
            }),
        );

        const traceIds = new Map<string, string>();
        await test.step(
          'Seed five traces with the same input and five differently-shaped outputs',
          async () => {
            await Promise.all(
              OUTPUT_SHAPES.map(async (shape) => {
                const id = uuid7();
                await backendClient.createTraceWithJsonOutput({
                  id,
                  projectName: project.name,
                  name: `${nestedRule.name}-${shape.key}`,
                  input: INPUT,
                  output: shape.output,
                });
                traceIds.set(shape.key, id);
              }),
            );
          },
        );

        await test.step(
          'The seeded outputs really are the shapes this test needs',
          async () => {
            // A fixture that silently normalised `42` or `"how can I help?"`
            // into an object would leave every assertion below passing against
            // the one shape that was never in question.
            for (const shape of OUTPUT_SHAPES) {
              const stored = await backendClient.getTrace(traceIds.get(shape.key)!);
              expect(stored, `trace for ${shape.key} was written`).not.toBeNull();
              expect(stored!.output, `stored output for ${shape.key}`).toEqual(shape.output);
            }
          },
        );

        // scores[shapeKey][scoreName] = { value, reason }
        const scores = new Map<string, Map<string, { value: number; reason: string | null }>>();
        await test.step(
          'Poll every trace for both rules, in parallel (120s budget each)',
          async () => {
            await Promise.all(
              OUTPUT_SHAPES.map(async (shape) => {
                const traceId = traceIds.get(shape.key)!;
                const [nested, indexed] = await Promise.all([
                  backendClient.pollTraceForFeedbackScore(traceId, nestedRule.scoreName, {
                    timeoutMs: 120_000,
                  }),
                  backendClient.pollTraceForFeedbackScore(traceId, indexedRule.scoreName, {
                    timeoutMs: 120_000,
                  }),
                ]);
                scores.set(
                  shape.key,
                  new Map([
                    [nestedRule.scoreName, { value: nested.value, reason: nested.reason }],
                    [indexedRule.scoreName, { value: indexed.value, reason: indexed.reason }],
                  ]),
                );
              }),
            );
          },
        );

        await test.step(
          'Every trace is scored by both rules — a non-object section must not abort the evaluation',
          async () => {
            // The regression is total absence of a score, so the count is the
            // assertion: naming the shapes that did score would pass on a run
            // where four of five were silently skipped.
            expect(
              [...scores.keys()].sort(),
              'all five shapes produced scores',
            ).toEqual(OUTPUT_SHAPES.map((s) => s.key).sort());
            for (const shape of OUTPUT_SHAPES) {
              expect(
                [...scores.get(shape.key)!.keys()].sort(),
                `both rules scored the ${shape.key} trace`,
              ).toEqual([nestedRule.scoreName, indexedRule.scoreName].sort());
            }
          },
        );

        await test.step(
          'Each rule resolves its variable for exactly the shape it can address, and drops it everywhere else',
          async () => {
            for (const shape of OUTPUT_SHAPES) {
              const byRule = scores.get(shape.key)!;

              const nested = byRule.get(nestedRule.scoreName)!;
              expect(nested.value, `output.answer over ${shape.key}`).toBe(
                shape.nestedAnswer === null ? DROPPED : RESOLVED,
              );
              expect(nested.reason, `output.answer reason over ${shape.key}`).toBe(
                expectedReason(shape.nestedAnswer),
              );

              const indexed = byRule.get(indexedRule.scoreName)!;
              expect(indexed.value, `output.[0].name over ${shape.key}`).toBe(
                shape.indexedAnswer === null ? DROPPED : RESOLVED,
              );
              expect(indexed.reason, `output.[0].name reason over ${shape.key}`).toBe(
                expectedReason(shape.indexedAnswer),
              );
            }
          },
        );

        await test.step(
          'Neither rule logged an error for any of the five traces',
          async () => {
            for (const rule of [nestedRule, indexedRule]) {
              // The log is written on its own path, so a line can still be in
              // flight when the score has landed. Wait until every seeded trace
              // is represented before reading levels off it — otherwise "no
              // ERROR line" is satisfied by a log that is merely empty.
              await expect
                .poll(
                  async () => {
                    const logs = await backendClient.getAutomationRuleLogs(rule.id);
                    const seen = new Set(logs.map((l) => l.traceId));
                    return [...traceIds.values()].filter((id) => seen.has(id)).length;
                  },
                  {
                    message: `rule ${rule.name} logged a line for each of the 5 seeded traces`,
                    timeout: 60_000,
                  },
                )
                .toBe(OUTPUT_SHAPES.length);

              const logs = await backendClient.getAutomationRuleLogs(rule.id);
              const seededIds = new Set(traceIds.values());
              const errors = logs.filter(
                (l) => l.level === 'ERROR' && l.traceId !== null && seededIds.has(l.traceId),
              );
              expect(
                errors.map((l) => `${l.traceId}: ${l.message}`),
                `rule ${rule.name} must score every shape without erroring`,
              ).toEqual([]);
            }
          },
        );

        await test.step(
          'The trace panel renders the same values the API reported',
          async () => {
            const logs = new LogsPage(page);
            await logs.goto(project.id);
            await logs.waitForReady();

            // The bare-string trace is the shape that used to score nothing at
            // all, and the object-array trace is the one whose indexed path
            // resolves — the negative and positive halves of the fix.
            for (const key of ['bare-string', 'object-array']) {
              const shape = OUTPUT_SHAPES.find((s) => s.key === key)!;
              const panel = await logs.openTraceById(traceIds.get(key)!);
              await panel.waitForFullyLoaded();
              await panel.openFeedbackScoresTab();

              for (const [rule, answer] of [
                [nestedRule, shape.nestedAnswer],
                [indexedRule, shape.indexedAnswer],
              ] as const) {
                await expect(
                  panel.feedbackScoreRow(rule.scoreName),
                  `${key}: exactly one ${rule.scoreName} row`,
                ).toHaveCount(1);
                expect(
                  await panel.readFeedbackScoreValue(rule.scoreName),
                  `${key}: ${rule.scoreName} in the panel`,
                ).toBe(answer === null ? DROPPED : RESOLVED);
              }
            }
          },
        );
      },
    );
  },
);
