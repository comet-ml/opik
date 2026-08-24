import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The sentinel a mapped argument falls back to when its path does not resolve.
 *
 * `OnlineScoringEngine.toReplacements` drops an unresolved variable from the map
 * it hands the Python runner, and `process_worker` calls `metric.score(**data)`,
 * so a dropped variable shows up as the parameter's Python default. Making that
 * default an explicit sentinel is what lets the spec tell "the variable did not
 * resolve" (fine) apart from "the rule produced no score at all" (the bug).
 */
const MISSING = '<<MISSING>>';

/**
 * The metric every rule in this spec runs.
 *
 * Two mapped arguments, and both are load-bearing:
 *   - `echoed` carries the path under test and may legitimately fail to resolve;
 *   - `anchor` is mapped to `input.q`, which resolves on every seeded trace.
 *
 * Without the anchor, a rule whose only mapping fails to resolve hands the
 * runner an empty argument map and produces no score — for a benign reason that
 * is indistinguishable on the wire from the crash this spec exists to catch.
 * The anchor guarantees at least one resolved argument, so "no score" can only
 * mean the evaluation itself failed.
 *
 * The reason is emitted as JSON so the spec can assert on the resolved value
 * exactly, rather than pattern-matching a Python `repr`.
 *
 * `json` is a module import, not a `BaseMetric` subclass — the python_evaluator
 * backend's `get_metric_class` picks the first `BaseMetric` subclass in the
 * module alphabetically, so importing another metric class here would shadow
 * this one. Importing a plain module is safe.
 */
function buildEchoMetric(scoreName: string): string {
  return `import json
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

MISSING = ${JSON.stringify(MISSING)}
SCORE_NAME = ${JSON.stringify(scoreName)}

class EchoMapping(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, echoed: Any = MISSING, anchor: Any = MISSING, **ignored_kwargs: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            value=0.0 if echoed == MISSING else 1.0,
            name=self.name,
            reason=json.dumps({"echoed": echoed, "anchor": anchor}),
        )`;
}

/** The value `input.q` carries on every seeded trace, echoed back as `anchor`. */
const ANCHOR_VALUE = 'question';

/** A trace whose `output` section has a particular JSON shape. */
interface SectionShape {
  key: string;
  /** The raw `output` section, passed to the REST write verbatim. */
  output: unknown;
  /** Why this shape is in the matrix. */
  note: string;
}

const SHAPES: SectionShape[] = [
  {
    key: 'scalar',
    output: 'the answer',
    note: 'bare string — the production shape that used to fail the evaluation',
  },
  {
    key: 'array',
    output: ['first', 'second'],
    note: 'array root — the other non-object section shape',
  },
  {
    key: 'flatkey',
    output: { 'flat.key': 'flatvalue' },
    note: 'object with a literal dotted property name (reached via the flat fallback)',
  },
  {
    key: 'nested',
    output: { a: { b: 'nestedvalue' } },
    note: 'ordinary nested object — the shape the existing smoke spec already covers',
  },
];

/** A rule, identified by the variable mapping its `echoed` argument carries. */
interface MappingUnderTest {
  key: string;
  path: string;
  note: string;
}

const MAPPINGS: MappingUnderTest[] = [
  {
    key: 'root',
    path: 'output',
    note: 'section root — resolves to the whole section, whatever its shape',
  },
  {
    key: 'descend',
    path: 'output.output',
    note: 'nested path that resolves on none of the seeded shapes',
  },
  { key: 'flatkey', path: 'output.flat.key', note: 'flat-fallback lookup of "flat.key"' },
  { key: 'nested', path: 'output.a.b', note: 'ordinary nested JsonPath descent' },
];

/**
 * The value each (mapping, shape) pair must echo back, keyed `<mapping>/<shape>`.
 * `MISSING` means the mapping legitimately did not resolve against that section
 * — which must still produce a score, just a 0.0 one.
 *
 * Every cell is stated, including the boring ones: the regression this spec
 * guards is a missing score, so an expectation table with holes in it would let
 * exactly the interesting cells go unasserted.
 *
 * The root mapping's values are the section serialized back to JSON, which is
 * why the scalar cell carries its own quotes — `extractFromJson` special-cases
 * the `$` path to `writeValueAsString(section)`. Everything else resolves to a
 * bare string, so it arrives unquoted.
 */
const EXPECTED_ECHO: Record<string, string> = {
  'root/scalar': '"the answer"',
  'root/array': '["first","second"]',
  'root/flatkey': '{"flat.key":"flatvalue"}',
  'root/nested': '{"a":{"b":"nestedvalue"}}',

  'descend/scalar': MISSING,
  'descend/array': MISSING,
  'descend/flatkey': MISSING,
  'descend/nested': MISSING,

  'flatkey/scalar': MISSING,
  'flatkey/array': MISSING,
  'flatkey/flatkey': 'flatvalue',
  'flatkey/nested': MISSING,

  'nested/scalar': MISSING,
  'nested/array': MISSING,
  'nested/flatkey': MISSING,
  'nested/nested': 'nestedvalue',
};

test.describe(
  'Online Evaluation — odd-shaped trace sections',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'A rule whose mapping cannot resolve still scores a scalar or array output section',
      { tag: ['@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] },
      async ({ project, backendClient, testNamespace, page, automationRulesCleanup }) => {
        // The inner waits run concurrently across the 4 traces, so the worst
        // case is one trace's chain — a 120s anchor poll plus a 90s settle —
        // not the sum over the matrix. 300s covers that plus rule creation,
        // the seed and the UI leg. Kept just above the inner waits on purpose:
        // each of those throws a diagnostic naming the trace and the scores it
        // actually saw, which beats an opaque "test timeout exceeded".
        test.setTimeout(300_000);

        // One rule per mapping under test, all four scoring the same four
        // traces. Score names are the rule names, so the matrix reads directly
        // off each trace's feedback_scores. No rule name is a prefix of
        // another, which the trace panel's substring row filter relies on.
        const ruleName = (m: MappingUnderTest) => `${testNamespace}-r-${m.key}`;
        const traceName = (s: SectionShape) => `${testNamespace}-t-${s.key}`;

        await test.step('Create one code-metric rule per variable mapping', async () => {
          // Sequential: rule creation is a cheap write and a serial loop keeps
          // the failure message pointing at one mapping.
          for (const mapping of MAPPINGS) {
            await backendClient.createPythonMetricRule({
              projectId: project.id,
              name: ruleName(mapping),
              metric: buildEchoMetric(ruleName(mapping)),
              arguments: { echoed: mapping.path, anchor: 'input.q' },
            });
          }
        });

        // Guard, before a single trace is seeded, and it is the reason the rest
        // of the test means anything. Every claim below is about which path a
        // rule was mapped to; a rule that silently stored a different mapping
        // would still score every trace, and the matrix would then pass while
        // asserting nothing about the paths it names. Reading the persisted
        // mapping back up front turns that silent no-op into a loud, early
        // failure.
        await test.step('All four rules persisted the mapping they were created with', async () => {
          const rules = await backendClient.listAutomationRulesForProject(project.id);
          expect(
            rules.map((r) => r.name).sort(),
            'exactly the four rules this spec created exist on the project',
          ).toEqual(MAPPINGS.map(ruleName).sort());

          const byName = new Map(rules.map((r) => [r.name, r]));
          for (const mapping of MAPPINGS) {
            const rule = byName.get(ruleName(mapping))!;
            expect(rule.enabled, `${rule.name} is enabled`).toBe(true);
            expect(rule.projectIds, `${rule.name} targets only this project`).toEqual([
              project.id,
            ]);
            expect(
              rule.pythonMetricArguments,
              `${rule.name} stored its variable mapping verbatim`,
            ).toEqual({ echoed: mapping.path, anchor: 'input.q' });
          }
        });

        const traceIds = await test.step(
          'Seed one trace per output-section shape, each with the same resolvable input',
          async () => {
            const entries = await Promise.all(
              SHAPES.map(async (shape) => {
                const id = await backendClient.createTraceWithRawOutput({
                  id: uuid7(),
                  projectName: project.name,
                  name: traceName(shape),
                  input: { q: ANCHOR_VALUE },
                  output: shape.output,
                });
                return [shape.key, id] as const;
              }),
            );
            return new Map(entries);
          },
        );

        // The fixture has to prove it produced the shapes the matrix assumes.
        // A trace whose output arrived as an object because the write coerced
        // it would make every "odd-shaped section" assertion below vacuous, and
        // it would read as coverage forever.
        await test.step('Each seeded trace really carries the section shape it stands for', async () => {
          for (const shape of SHAPES) {
            const sections = await backendClient.getTraceSections(traceIds.get(shape.key)!);
            expect(sections, `trace ${shape.key} is readable`).not.toBeNull();
            expect(
              sections!.output,
              `trace ${shape.key} kept its ${shape.note} output section verbatim`,
            ).toEqual(shape.output);
            expect(sections!.input, `trace ${shape.key} carries the anchor input`).toEqual({
              q: ANCHOR_VALUE,
            });
          }
        });

        const scoresByShape = await test.step(
          'Wait for scoring to settle on every trace, then collect the score matrix',
          async () => {
            // Two-phase wait per trace, and the order matters.
            // `waitForTraceScoresSettled` decides "settled" from a stable
            // score-set fingerprint, so it must not start while the trace is
            // still unscored. The root mapping resolves against every section
            // shape — it is the `$` path, which `extractFromJson` special-cases
            // before any conversion — so its score is guaranteed to arrive on
            // all four traces and is safe to anchor on. Only then wait for the
            // set to go quiet, which is what catches the other three rules'
            // scores if they are coming.
            const rootRule = ruleName(MAPPINGS.find((m) => m.key === 'root')!);
            const collected = await Promise.all(
              SHAPES.map(async (shape) => {
                const id = traceIds.get(shape.key)!;
                await backendClient.pollTraceForFeedbackScore(id, rootRule, {
                  timeoutMs: 120_000,
                });
                const trace = await backendClient.waitForTraceScoresSettled(id, {
                  quietPeriodMs: 10_000,
                  timeoutMs: 90_000,
                  minScores: 1,
                });
                return [shape.key, trace.feedbackScores] as const;
              }),
            );
            return new Map(collected);
          },
        );

        // THE regression. Before the fix, `extractFromJson` converted the
        // section straight to a Map, which threw on anything that was not a
        // JSON object; the throw escaped `toReplacements` and failed the whole
        // evaluation, so a scalar or array output section produced NO score at
        // all from any nested-path rule. After the fix the evaluation completes
        // and only the unresolved variable drops.
        await test.step('Every rule scored every trace — including the scalar and array sections', async () => {
          const expectedNames = MAPPINGS.map(ruleName).sort();
          for (const shape of SHAPES) {
            const names = scoresByShape.get(shape.key)!.map((fs) => fs.name).sort();
            expect(
              names,
              `every rule must score the ${shape.key} trace (${shape.note}) — ` +
                `a missing score means the evaluation failed before the metric ran, ` +
                `which is the regression this spec guards`,
            ).toEqual(expectedNames);
          }
        });

        await test.step('Each rule echoed back exactly the value its mapping resolves to', async () => {
          for (const shape of SHAPES) {
            const byName = new Map(
              scoresByShape.get(shape.key)!.map((fs) => [fs.name, fs]),
            );
            for (const mapping of MAPPINGS) {
              const cell = `${mapping.key}/${shape.key}`;
              const score = byName.get(ruleName(mapping));
              // Asserted rather than optional-chained: the step above proved
              // the score is there, and coding around its absence here would
              // turn a missing score into a silent pass.
              expect(score, `score for ${cell} is present`).toBeDefined();
              expect(
                score!.reason,
                `${cell} must carry the metric's JSON reason`,
              ).not.toBeNull();

              const reason = JSON.parse(score!.reason!) as {
                echoed: string;
                anchor: string;
              };
              expect(
                reason.anchor,
                `${cell}: the anchor resolves on every trace, so its absence means the ` +
                  `evaluation ran on the wrong data rather than that a mapping failed`,
              ).toBe(ANCHOR_VALUE);
              expect(
                reason.echoed,
                `${cell}: mapping "${mapping.path}" (${mapping.note}) against a ` +
                  `${shape.note} section`,
              ).toBe(EXPECTED_ECHO[cell]);
              expect(score!.value, `${cell}: value tracks whether "echoed" resolved`).toBe(
                EXPECTED_ECHO[cell] === MISSING ? 0.0 : 1.0,
              );
            }
          }
        });

        // The backend fix is only useful if the page a user reads shows the
        // result. The scalar trace is the one that used to come back unscored,
        // so it is the one worth opening.
        await test.step('The scalar trace renders both scores in the Logs trace panel', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          await logs.waitForReady();

          const panel = await logs.openTraceById(traceIds.get('scalar')!);
          await panel.waitForFullyLoaded();
          await panel.openFeedbackScoresTab();

          const rootRule = ruleName(MAPPINGS.find((m) => m.key === 'root')!);
          const descendRule = ruleName(MAPPINGS.find((m) => m.key === 'descend')!);

          // Resolve to exactly one row each, so an ambiguous name match fails
          // loudly instead of silently reading a different rule's score.
          await expect(
            panel.feedbackScoreRow(rootRule),
            'the resolving rule renders exactly one row',
          ).toHaveCount(1);
          await expect(
            panel.feedbackScoreRow(descendRule),
            'the unresolved-mapping rule renders a row too — it scored, it did not fail',
          ).toHaveCount(1);

          expect(await panel.readFeedbackScoreValue(rootRule)).toBe(1.0);
          expect(await panel.readFeedbackScoreValue(descendRule)).toBe(0.0);
        });
      },
    );
  },
);
