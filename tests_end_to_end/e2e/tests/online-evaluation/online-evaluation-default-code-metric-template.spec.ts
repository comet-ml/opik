import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { LogsPage } from '@e2e/pom/logs.page';
import { uuid7 } from '@e2e/core/backend';
import type { AutomationRuleLogRef, BackendClient } from '@e2e/core/backend';

/**
 * The score name the dialog's default template hard-codes
 * (`MyCustomMetric.__init__(name="my_custom_metric")`). The engine writes the
 * ScoreResult's name verbatim, not the rule's, so this — and not the rule name —
 * is what lands on the entity.
 */
const DEFAULT_SCORE_NAME = 'my_custom_metric';

/** The default template returns `ScoreResult(value=0, ...)` for any input. */
const DEFAULT_SCORE_VALUE = 0;

/**
 * The variable mapping the dialog ships with the Code-metric type, for both the
 * trace and the span scope (`DEFAULT_PYTHON_CODE_TRACE_DATA` /
 * `DEFAULT_PYTHON_CODE_SPAN_DATA`). Asserted as a whole object rather than key
 * by key: a mapping that gained or lost an entry is exactly the regression that
 * changes which arguments the metric is called with.
 */
const DEFAULT_ARGUMENTS = { input: 'input', output: 'output', metadata: 'metadata' };

/**
 * The signature fragment that makes the shipped template tolerate an entity with
 * no metadata. Before OPIK-8292 the template declared `metadata: dict` with no
 * default, so the argument was mandatory — and the annotation was wrong as well,
 * since these arguments arrive as JSON strings.
 */
const OPTIONAL_METADATA_SIGNATURE = 'metadata: Optional[str] = None';

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

const SCORING_TIMEOUT_MS = 180_000;

/**
 * Block until the rule logged one evaluator call line per entity, and return the
 * whole stream.
 *
 * The stored score and the rule's log stream settle on different paths, so a
 * stream read straight after the score can be a call line short: the scores
 * prove the rule ran, not that everything it wrote has arrived. Polling for the
 * expected count is what lets the assertions below read a settled stream instead
 * of a half-flushed one — and it keeps the ERROR-line assertion meaningful,
 * since an empty stream would otherwise pass for a clean one.
 */
async function waitForEvaluatorCallLines(
  backendClient: BackendClient,
  ruleId: string,
  expected: number,
): Promise<AutomationRuleLogRef[]> {
  let lines: AutomationRuleLogRef[] = [];
  await expect
    .poll(
      async () => {
        lines = await backendClient.getAutomationRuleLogs(ruleId);
        return lines.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)).length;
      },
      {
        timeout: 60_000,
        intervals: [1_000, 2_000],
        message: `rule '${ruleId}' never logged ${expected} evaluator call lines`,
      },
    )
    .toBe(expected);
  return lines;
}

/**
 * The rule the create dialog builds when a user picks "Code metric" and changes
 * nothing else.
 *
 * This input is not reachable from any existing spec, and the gap is systematic
 * rather than accidental: every python-rule spec in this directory replaces the
 * default template with a snippet of its own and remaps its variables to paths
 * that resolve (`online-evaluation-smoke` retypes a one-parameter Equals metric
 * mapped to `output.output`; `python-metric-errors`, `non-object-sections`,
 * `sampling-rate` and `oversized-payload` all pass mappings that resolve). So
 * the estate has never sent the evaluator the argument map the product itself
 * ships — which is the map a user gets by clicking Create and typing nothing.
 *
 * What that map does that no test map did: it names `metadata`, and most traces
 * carry none. A plain `@opik.track` function sends no metadata field at all, so
 * `toReplacements` dropped the key, the metric was called without an argument
 * its signature required, and the rule scored nothing while its row still read
 * Enabled. Both halves of the fix are asserted here — the template the frontend
 * now persists, and the backend binding that keeps a rule saved BEFORE it
 * working (that half is pinned on its own by
 * `online-evaluation-declared-argument-binding.spec.ts`).
 *
 * Deterministic by construction: the default template returns a constant 0 for
 * whatever it is handed, so every assertion is score-present vs score-absent
 * with no LLM, no provider key and no wall-clock dependency.
 */
test.describe('Online Evaluation — the dialog\'s default code metric', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A trace-scope rule created from the untouched default scores a trace logged without metadata, and the score renders in the trace panel', { tag: ['@cap:online-evaluation.create-python-rule', '@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.scores-in-trace-panel'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    const ruleName = `${testNamespace}-default-trace`;

    await test.step('Create a Code-metric rule through the dialog, touching nothing else', async () => {
      const onlineEval = new OnlineEvaluationPage(page);
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogPythonDefaultTemplate({ name: ruleName });
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const ruleId = await test.step('Resolve the created rule', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const matching = rules.filter((r) => r.name === ruleName);
      // Exactly one, not `find`: a second rule under the same name would mean
      // the dialog submitted twice, and the rest of this test would then be
      // asserting about whichever one happened to come back first.
      expect(matching, `exactly one rule named '${ruleName}' must exist`).toHaveLength(1);
      return matching[0].id;
    });

    await test.step('The dialog persisted the shipped template and mapping unchanged', async () => {
      const code = await backendClient.getPythonRuleCode(ruleId);
      expect(
        code.arguments,
        'the untouched dialog must persist its own default variable mapping',
      ).toEqual(DEFAULT_ARGUMENTS);
      expect(
        code.metric,
        'the persisted signature must tolerate an entity that logged no metadata',
      ).toContain(OPTIONAL_METADATA_SIGNATURE);
    });

    const seedTrace = async (label: string, metadata?: Record<string, unknown>) => {
      const id = uuid7();
      const now = new Date();
      await backendClient.createTraceWithSource({
        id,
        projectName: project.name,
        name: `${testNamespace}-${label}-trace`,
        source: 'sdk',
        input: { question: 'what is the capital of France' },
        output: { output: 'Paris' },
        ...(metadata ? { metadata } : {}),
        startTime: now,
        // Without an end_time `OnlineScoringSampler.onTracesCreated` drops the
        // trace as a partial write and nothing is ever scored.
        endTime: now,
      });
      return id;
    };

    const traces = await test.step(
      'Seed one trace with no metadata and one carrying metadata',
      async () => {
        // The second trace is the control for the fix itself. Binding an absent
        // argument to null is only correct if it does NOT null a value the
        // entity really logged, and a spec seeding only the empty case would
        // pass just as happily against a backend that nulled every metadata.
        return {
          noMetadata: await seedTrace('no-metadata'),
          withMetadata: await seedTrace('with-metadata', { env: 'test' }),
        };
      },
    );

    await test.step('The seeds really are in the two states this test needs', async () => {
      // A UI assertion over a fixture that silently failed to set up is a test
      // that cannot fail. If ingest defaulted metadata to `{}`, the "absent"
      // trace would resolve its mapping like any other and the rest of this
      // spec would prove nothing about the bug.
      await expect
        .poll(async () => (await backendClient.getTracePayload(traces.noMetadata))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the no-metadata trace never became readable',
        })
        .toBeNull();
      await expect
        .poll(async () => (await backendClient.getTracePayload(traces.withMetadata))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the with-metadata trace never became readable',
        })
        .toEqual({ env: 'test' });
    });

    await test.step('Both traces are scored by the default metric', async () => {
      for (const [label, traceId] of Object.entries(traces)) {
        const score = await backendClient.pollTraceForFeedbackScore(traceId, DEFAULT_SCORE_NAME, {
          timeoutMs: SCORING_TIMEOUT_MS,
        });
        expect(
          score.value,
          `the default template returns a constant 0 for the ${label} trace`,
        ).toBe(DEFAULT_SCORE_VALUE);
      }
    });

    const logs = await test.step('The rule reported no failure, and passed metadata only where the trace logged it', async () => {
      const lines = await waitForEvaluatorCallLines(backendClient, ruleId, 2);

      expect(
        lines.filter((l) => l.level === 'ERROR').map((l) => l.message),
        'a rule that scored both traces must not also have reported a failure',
      ).toEqual([]);

      const callLine = (traceId: string) => {
        const matching = lines.filter(
          (l) => l.message.includes(EVALUATOR_CALL_LINE) && l.message.includes(traceId),
        );
        expect(
          matching,
          `the rule must have called the evaluator exactly once for trace ${traceId}`,
        ).toHaveLength(1);
        return matching[0].message;
      };

      // `summarizeEvaluatorInput` renders one `name=<len>c` part per argument the
      // ENGINE passed, and the engine drops a mapping that resolved to nothing
      // (`toReplacements` filters null values out of the map). The fill that
      // rescues a missing argument runs in the python backend, DOWNSTREAM of
      // this line — so an absent `metadata` here is the engine behaving
      // correctly, not the argument being lost on the way to the metric. What
      // the metric actually received is asserted where it is observable, by
      // encoding it into the score: see
      // online-evaluation-declared-argument-binding.spec.ts.
      expect(
        callLine(traces.noMetadata),
        'a trace logging no metadata resolves to nothing, so the engine omits the key',
      ).not.toMatch(/metadata=/);
      expect(
        callLine(traces.withMetadata),
        'a trace that logged metadata must still pass its real value',
        // The complement, and the assertion that still earns its place here: a
        // non-zero length proves the engine did not flatten a value the entity
        // carried into an empty one.
      ).toMatch(/metadata=[1-9]\d*c/);

      return lines;
    });

    await test.step('Exactly one rule ran, so exactly one score is stored', async () => {
      for (const traceId of Object.values(traces)) {
        const detail = await backendClient.getTrace(traceId);
        expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
        expect(
          detail!.feedbackScores.map((s) => s.name).sort(),
          'the project holds one rule, so no other score may appear',
        ).toEqual([DEFAULT_SCORE_NAME]);
      }
      // Guards the log assertion above against a stream that simply stopped
      // being written: two traces were sent, so two call lines must exist.
      expect(
        logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
        'both traces must have reached the evaluator',
      ).toHaveLength(2);
    });

    await test.step('The no-metadata trace shows its score in the trace panel', async () => {
      // The backend binding is only observable to a user here. A REST-only pass
      // would hide the report that actually gets filed: a Feedback scores tab
      // that says "no feedback scores yet" on a trace the rule claims to have
      // scored.
      const logsPage = new LogsPage(page);
      await logsPage.goto(project.id);
      await logsPage.waitForReady();

      const panel = await logsPage.openTraceById(traces.noMetadata);
      await panel.waitForFullyLoaded();
      await panel.openFeedbackScoresTab();
      await expect(
        panel.feedbackScoreRow(DEFAULT_SCORE_NAME),
        'the panel must show exactly one score row for the default metric',
      ).toHaveCount(1);
      expect(
        await panel.readFeedbackScoreValue(DEFAULT_SCORE_NAME),
        'the panel must render the value, not just a row',
      ).toBe(DEFAULT_SCORE_VALUE);
    });
  });

  test('A span-scope rule created from the untouched default scores a span logged without metadata', { tag: ['@cap:online-evaluation.create-python-rule', '@cap:online-evaluation.python-rule-scores', '@cap:online-evaluation.rule-scope-thread-span'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    const ruleName = `${testNamespace}-default-span`;

    await test.step('Create a span-scope Code-metric rule through the dialog', async () => {
      const onlineEval = new OnlineEvaluationPage(page);
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillAndSubmitCreateRuleDialogPythonDefaultTemplate({
        name: ruleName,
        scope: 'Span',
      });
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const ruleId = await test.step('Resolve the created rule', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const matching = rules.filter((r) => r.name === ruleName);
      expect(matching, `exactly one rule named '${ruleName}' must exist`).toHaveLength(1);
      return matching[0].id;
    });

    await test.step('The dialog persisted a SPAN-scope rule on the span template', async () => {
      // Without the discriminator read back, a scope select that silently failed
      // would leave this test proving a property about the trace stream while
      // claiming the span one.
      const rule = await backendClient.getAutomationRule(ruleId);
      expect(rule.type, 'the rule must score spans, not traces').toBe(
        'span_user_defined_metric_python',
      );
      expect(rule.samplingRate, 'every span must be eligible').toBe(1);
      expect(rule.enabled, 'a disabled rule scores nothing at all').toBe(true);

      const code = await backendClient.getPythonRuleCode(ruleId);
      expect(
        code.arguments,
        'the span template ships the same default mapping as the trace one',
      ).toEqual(DEFAULT_ARGUMENTS);
      expect(
        code.metric,
        'the persisted signature must tolerate a span that logged no metadata',
      ).toContain(OPTIONAL_METADATA_SIGNATURE);
    });

    const seedSpan = async (label: string, metadata?: Record<string, unknown>) => {
      const traceId = uuid7();
      const spanId = uuid7();
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}-trace`,
        source: 'sdk',
        input: { question: 'seed' },
        output: { output: 'seed' },
        startTime: now,
        endTime: now,
      });
      await backendClient.createSpan({
        id: spanId,
        traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}-span`,
        // `OnlineScoringSpanSampler` keeps only spans whose source is a logging
        // one; anything else is dropped before a rule sees it.
        source: 'sdk',
        input: { question: 'seed' },
        output: { output: 'seed' },
        ...(metadata ? { metadata } : {}),
        startTime: now,
        endTime: now,
      });
      return spanId;
    };

    const spans = await test.step(
      'Seed one span with no metadata and one carrying metadata',
      async () => ({
        noMetadata: await seedSpan('no-metadata'),
        withMetadata: await seedSpan('with-metadata', { env: 'test' }),
      }),
    );

    await test.step('The seeded spans really are in the two states this test needs', async () => {
      await expect
        .poll(async () => (await backendClient.getSpan(spans.noMetadata))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the no-metadata span never became readable',
        })
        .toBeNull();
      await expect
        .poll(async () => (await backendClient.getSpan(spans.withMetadata))?.metadata, {
          timeout: 60_000,
          intervals: [1_000, 2_000],
          message: 'the with-metadata span never became readable',
        })
        .toEqual({ env: 'test' });
    });

    await test.step('Both spans are scored by the default metric', async () => {
      for (const [label, spanId] of Object.entries(spans)) {
        const score = await backendClient.pollSpanForFeedbackScore(spanId, DEFAULT_SCORE_NAME, {
          timeoutMs: SCORING_TIMEOUT_MS,
        });
        expect(
          score.value,
          `the default template returns a constant 0 for the ${label} span`,
        ).toBe(DEFAULT_SCORE_VALUE);
      }
    });

    await test.step('The span scorer reported no failure, and passed metadata only where the span logged it', async () => {
      const lines = await waitForEvaluatorCallLines(backendClient, ruleId, 2);
      expect(
        lines.filter((l) => l.level === 'ERROR').map((l) => l.message),
        'a rule that scored both spans must not also have reported a failure',
      ).toEqual([]);

      const callLines = lines.filter((l) => l.message.includes(EVALUATOR_CALL_LINE));

      const callLine = (spanId: string) => {
        const matching = callLines.filter((l) => l.message.includes(spanId));
        expect(
          matching,
          `the rule must have called the evaluator exactly once for span ${spanId}`,
        ).toHaveLength(1);
        return matching[0].message;
      };
      expect(
        callLine(spans.noMetadata),
        'a span logging no metadata resolves to nothing, so the engine omits the key',
      ).not.toMatch(/metadata=/);
      expect(
        callLine(spans.withMetadata),
        'a span that logged metadata must still pass its real value',
      ).toMatch(/metadata=[1-9]\d*c/);
    });

    await test.step('Exactly one rule ran, so exactly one score is stored per span', async () => {
      for (const spanId of Object.values(spans)) {
        const detail = await backendClient.getSpan(spanId);
        expect(detail, 'the seeded span must still exist to be asserted about').not.toBeNull();
        expect(
          detail!.feedbackScores.map((s) => s.name).sort(),
          'the project holds one rule, so no other score may appear',
        ).toEqual([DEFAULT_SCORE_NAME]);
      }
    });
  });
});
