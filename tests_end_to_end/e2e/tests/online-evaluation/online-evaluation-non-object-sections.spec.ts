import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7, type TraceJsonSection } from '@e2e/core/backend';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

interface SectionShape {
  /** Used in the trace name and in every failure message. */
  label: string;
  input: TraceJsonSection;
  output: TraceJsonSection;
  /** What the section under test must actually be once stored. */
  assertShape: (sections: { input: unknown; output: unknown }) => void;
}

/**
 * One trace per JSON shape a REST writer can put in a mapped section.
 *
 * Every row keeps ONE section as a well-formed object. That is not tidiness:
 * the backend refuses to call the evaluator with an empty argument map, so a
 * trace where BOTH mapped variables dropped would fail for a reason that has
 * nothing to do with the shape being tested.
 */
const SHAPES: SectionShape[] = [
  {
    label: 'object-control',
    input: { q: 'what is the capital of France' },
    output: { answer: 'Paris' },
    assertShape: (s) => {
      expect(s.output, 'control output must be an object').toEqual({ answer: 'Paris' });
    },
  },
  {
    label: 'string-output',
    input: { q: 'what is the capital of France' },
    output: 'Paris',
    assertShape: (s) => {
      expect(typeof s.output, 'output must be stored as a bare string').toBe('string');
    },
  },
  {
    label: 'array-output',
    input: { q: 'what is the capital of France' },
    output: ['Paris', 'Lyon'],
    assertShape: (s) => {
      expect(Array.isArray(s.output), 'output must be stored as an array').toBe(true);
    },
  },
  {
    label: 'number-output',
    input: { q: 'what is the capital of France' },
    output: 42,
    assertShape: (s) => {
      expect(typeof s.output, 'output must be stored as a bare number').toBe('number');
    },
  },
  {
    label: 'string-input',
    input: 'what is the capital of France',
    output: { answer: 'Paris' },
    assertShape: (s) => {
      expect(typeof s.input, 'input must be stored as a bare string').toBe('string');
    },
  },
  {
    label: 'array-input',
    input: ['what is the capital of France'],
    output: { answer: 'Paris' },
    assertShape: (s) => {
      expect(Array.isArray(s.input), 'input must be stored as an array').toBe(true);
    },
  },
];

test.describe('Online Evaluation — non-object mapped sections', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A rule with sub-path variable mappings still scores traces whose section is a string, an array or a number', { tag: ['@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // The estate structurally cannot produce these traces through its usual
    // seeding path: everything that goes through the Python SDK's `@track`
    // wraps a non-dict return into `{"output": ...}`, so a mapped section is
    // always an object and this whole shape class goes unexercised. The REST
    // write does produce it, which is why the seed below is deliberately not a
    // fixture built on the SDK bridge.
    //
    // What used to break: `extractFromJson` converted the section with
    // `convertValue(json, Map.class)`, which threw for any non-object node and
    // failed the WHOLE evaluation before the metric was ever called — so the
    // user saw no score at all rather than a score computed without that
    // variable.
    const ruleName = `${testNamespace}-subpath`;

    const ruleId = await test.step('Create a rule with SUB-PATH variable mappings', async () => {
      // Sub-paths, not whole sections, and that distinction is the test.
      // `toVariableMapping` turns a dot-free mapping (`output`) into the JSON
      // path `$`, which `extractFromJson` short-circuits before reaching the
      // conversion that used to throw. A rule mapped that way would pass on
      // every shape below while exercising none of them.
      return backendClient.createAutomationRule({
        projectId: project.id,
        name: ruleName,
        samplingRate: 1,
        // Both mapped variables are declared optional: a sub-path into a
        // non-object section resolves to null and `toReplacements` drops it, so
        // `score()` is called with a subset of its arguments.
        metric: buildConstantScoreMetric(ruleName, ['out_nested', 'q_nested']),
        arguments: { out_nested: 'output.answer', q_nested: 'input.q' },
      });
    });

    const traces = await test.step(
      `Seed ${SHAPES.length} traces, one per JSON shape a mapped section can hold`,
      async () => {
        return Promise.all(
          SHAPES.map(async (shape) => {
            const id = uuid7();
            const now = new Date();
            await backendClient.createTraceWithSource({
              id,
              projectName: project.name,
              name: `${testNamespace}-${shape.label}`,
              source: 'sdk',
              input: shape.input,
              output: shape.output,
              startTime: now,
              // Without an end_time the sampler discards the trace as a partial
              // write and nothing below would ever be evaluated.
              endTime: now,
            });
            return { id, shape, name: `${testNamespace}-${shape.label}` };
          }),
        );
      },
    );

    await test.step('The seed really did store non-object sections', async () => {
      // Without this gate the test cannot fail honestly: if the write coerced
      // every section into an object, all six traces would score for the most
      // boring possible reason and the spec would read as coverage forever.
      for (const t of traces) {
        // The REST write answers 201 before the row is queryable, so the
        // read-back is polled rather than assumed.
        await expect
          .poll(async () => (await backendClient.getTraceSections(t.id)) !== null, {
            timeout: 60_000,
            intervals: [500, 1_000, 2_000],
            message: `${t.name} never became readable, so its stored shape cannot be checked`,
          })
          .toBe(true);
        const sections = await backendClient.getTraceSections(t.id);
        expect(sections, `${t.name} must be readable`).not.toBeNull();
        t.shape.assertShape(sections!);
      }
    });

    await test.step('Every shape was scored, including the five non-object sections', async () => {
      const scored = await Promise.all(
        traces.map(async (t) => {
          const score = await backendClient.pollTraceForFeedbackScore(t.id, ruleName, {
            timeoutMs: 180_000,
          });
          return { name: t.name, value: score.value };
        }),
      );
      for (const s of scored) {
        expect(
          s.value,
          `${s.name} was evaluated, so the constant metric must return 1.0`,
        ).toBe(1.0);
      }
      expect(scored.length, 'no shape may be dropped before evaluation').toBe(SHAPES.length);
    });

    await test.step('The rule reported no error while resolving any of the shapes', async () => {
      // The scores above prove the evaluation completed; this proves it
      // completed cleanly. An unresolvable variable is expected to be dropped
      // silently — it must not surface as an ERROR line, which is what the
      // pre-fix failure looked like on `/automation-logs`.
      const logs = await backendClient.getAutomationRuleLogs(ruleId);
      expect(
        logs.filter((l) => l.level === 'ERROR').map((l) => l.message),
        'a dropped variable is a designed fallback, not a failure',
      ).toEqual([]);
      for (const line of logs) {
        expect(
          line.message.toLowerCase(),
          'no line may report an unexpected error',
        ).not.toContain('unexpected error');
      }
    });

    await test.step('The bare-string-output trace shows its score in the trace panel', async () => {
      // Written through REST, read back through the UI — the disagreement this
      // catches is a section the API happily scored that the panel cannot
      // render.
      const stringOutputTrace = traces.find((t) => t.shape.label === 'string-output');
      expect(stringOutputTrace, 'the seed must include a bare-string output').toBeDefined();

      const logs = new LogsPage(page);
      await logs.goto(project.id);
      await logs.waitForReady();
      const panel = await logs.openTraceById(stringOutputTrace!.id);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();
      expect(await panel.readFeedbackScoreValue(ruleName)).toBe(1.0);
    });
  });
});
