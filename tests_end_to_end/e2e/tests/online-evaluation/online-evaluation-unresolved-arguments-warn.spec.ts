import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { buildConstantScoreMetric } from '@e2e/core/metrics';
import { uuid7 } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';

/**
 * The fixed leading sentence of the WARN, byte-for-byte from
 * `OnlineScoringEngine.UNRESOLVED_ARGUMENTS_LOG`. The values trail it so this
 * half is a stable prefix, which is what makes an exact-equality assertion on
 * the whole message possible below.
 */
const UNRESOLVED_PREFIX =
  "None of the metric's declared arguments resolved, so there is no data to evaluate. " +
  'Check the declared paths against the input, output and metadata present on the entity.';

/** The INFO line the engine writes only when it really handed data to the evaluator. */
const SENDING_FRAGMENT = 'to Python evaluator';

/**
 * `OnlineScoringEngine.MAX_LOGGED_VALUE_CHARS`. Names and paths longer than
 * this are truncated with an ellipsis before they reach the row, so every
 * expected message built here first asserts its inputs are shorter — otherwise
 * a namespace that grew would turn an exact comparison into a false failure
 * about the test's own naming.
 */
const MAX_LOGGED_VALUE_CHARS = 100;

/** How long a rule may take to put its first row on the stream. */
const LOG_ROW_TIMEOUT_MS = 180_000;

/**
 * How long the row set must stay unchanged to count as acked rather than
 * redelivered. A fixed wait, like `online-evaluation-thread-judge-provider-failure.spec.ts`
 * and `online-evaluation-thread-scope-batch-close.spec.ts`: there is no state
 * to wait *for* here — the claim is that nothing further arrives — so the only
 * thing to do is let the consumer's visibility window pass and look again.
 */
const QUIET_PERIOD_MS = 30_000;

/**
 * What a python rule's log stream says when NONE of its declared arguments
 * resolve (opik#8658, OPIK-8556).
 *
 * Before this release such a rule was silent: the backend refuses to call the
 * evaluator with an empty argument map, so the message was dropped, no score
 * was written, and the one place a user can look — the rule log — said only
 * "Evaluating …" followed by a `Sending … arguments=[]` line that contradicted
 * it. A misconfigured path therefore looked exactly like a rule that had not
 * fired yet.
 *
 * Nothing in the estate entered that branch, and that is deliberate rather than
 * accidental: every `arguments:` map across `tests/online-evaluation/` keeps at
 * least one resolvable path, and
 * `online-evaluation-non-object-sections.spec.ts` says so in its own header.
 * `online-evaluation-required-param-binding.spec.ts` is the nearest neighbour
 * and covers the ADJACENT ticket (OPIK-8292, *some* arguments unresolved → bound
 * to `None`); it keeps `output: 'output.output'` mapped, so it passes with this
 * release's bug present. Every automation-logs spec matches INFO and ERROR rows
 * only, and no spec renders a WARN row at all.
 *
 * Three tests, because three different things can break independently:
 *
 *   - **Trace scope** is the headline: one WARN naming every declared argument
 *     with its configured path, the contradictory `Sending` line suppressed,
 *     and the suppression pinned as CONDITIONAL by a control rule judging the
 *     SAME trace that keeps its `Sending` line. Plus the consequence a user
 *     feels — the trace carries the control rule's score and nothing else —
 *     and the row rendered on `/automation-logs`.
 *   - **Span scope** goes through `OnlineScoringSpanUserDefinedMetricPythonScorer`
 *     and its own Redis stream, sharing only the helper. Same WARN, keyed on
 *     `spanId` instead of `traceId`.
 *   - **Sanitizing and capping** the argument map. Paths and names are rule
 *     configuration a user controls, so a newline in a path must not forge a
 *     second row in the persisted log, and a rule declaring many arguments must
 *     not decide how much the log carries.
 *
 * Deterministic and free throughout: the evaluator is never called, so nothing
 * depends on an LLM, a provider key or wall-clock time. One REST-seeded trace
 * per test.
 */
test.describe(
  'Online Evaluation — a python rule whose arguments none resolve',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test.setTimeout(420_000);

    /**
     * The WARN message in full, as the engine renders it.
     *
     * Built here rather than asserted fragment-by-fragment because the
     * regression this spec guards is a message that still appears, at the right
     * level, on the right stream, and stops naming the paths — which a
     * `toContain` on the prefix cannot see. `pairs` must already be in the
     * engine's order (sorted by argument name) and carry its quoting.
     */
    const expectedWarn = (
      entityLabel: 'traceId' | 'spanId',
      entityId: string,
      ruleName: string,
      renderedArguments: string,
    ): string =>
      `${UNRESOLVED_PREFIX} ${entityLabel} '${entityId}', rule '${ruleName}', ` +
      `unresolved arguments: ${renderedArguments}`;

    /** `'name' -> 'path'`, the shape `logUnresolvedEvaluatorArguments` renders each pair in. */
    const pair = (name: string, path: string): string => `'${name}' -> '${path}'`;

    /**
     * Guard the inputs an exact-equality expectation depends on.
     *
     * The engine truncates any name or path over `MAX_LOGGED_VALUE_CHARS` and
     * appends an ellipsis. Every value this spec feeds it is well short of that,
     * but the run prefix and the test-title slug both feed the rule name — so a
     * longer title would silently push it over and the failure would read as a
     * product regression instead of a naming problem here.
     */
    const assertLoggedVerbatim = (values: Record<string, string>): void => {
      for (const [label, value] of Object.entries(values)) {
        expect(
          value.length,
          `${label} must stay under the engine's ${MAX_LOGGED_VALUE_CHARS}-character log cap, ` +
            'or it is truncated and the expected message below is wrong',
        ).toBeLessThanOrEqual(MAX_LOGGED_VALUE_CHARS);
      }
    };

    test(
      'a trace-scope rule reports one WARN naming every argument and its path, and scores nothing',
      {
        tag: [
          '@cap:online-evaluation.automation-logs',
          '@cap:online-evaluation.python-rule-scores',
        ],
      },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const unresolvedRuleName = `${testNamespace}-unres`;
        const controlRuleName = `${testNamespace}-control`;
        assertLoggedVerbatim({ unresolvedRuleName, controlRuleName });

        const rules = await test.step(
          'Create one rule whose arguments cannot resolve and one control rule that can',
          async () => ({
            // Two arguments, not one: the WARN has to name EVERY declared
            // argument, and a formatter that reported only the first would be
            // invisible to a single-argument rule.
            //
            // `metadata.ctx` and `output.execution_plan` are both sub-paths
            // under sections the trace below does not carry, which is what
            // `toReplacements` drops. An empty `metadata: {}` would RESOLVE and
            // not reproduce this at all.
            unresolved: await backendClient.createAutomationRule({
              projectId: project.id,
              name: unresolvedRuleName,
              samplingRate: 1,
              metric: buildConstantScoreMetric(unresolvedRuleName, ['ctx', 'plan']),
              arguments: { ctx: 'metadata.ctx', plan: 'output.execution_plan' },
            }),
            // Judges the SAME trace, so it turns the suppression of the
            // `Sending` line into a conditional claim. Without it, a release
            // that deleted that line outright would look identical to one that
            // suppressed it only where it would have contradicted the WARN —
            // and the second is the fix, the first is a regression for every
            // working rule in the product.
            control: await backendClient.createAutomationRule({
              projectId: project.id,
              name: controlRuleName,
              samplingRate: 1,
              metric: buildConstantScoreMetric(controlRuleName),
              arguments: { output: 'output.output' },
            }),
          }),
        );

        await test.step('Both rules are trace-scope, enabled and sampling everything', async () => {
          // The API's default type is the trace scorer, so a silent wrong-scope
          // rule would spend the whole budget proving a property about a stream
          // it never touched. At any sampling rate below 1 an absent row is a
          // legitimate outcome and every assertion below stops meaning anything.
          for (const [label, ruleId] of Object.entries(rules)) {
            const rule = await backendClient.getAutomationRule(ruleId);
            expect(rule.type, `the ${label} rule must score traces`).toBe(
              'user_defined_metric_python',
            );
            expect(rule.samplingRate, `every trace must be eligible for the ${label} rule`).toBe(1);
            expect(rule.enabled, `a disabled ${label} rule scores nothing at all`).toBe(true);
          }
        });

        const traceId = uuid7();
        await test.step('Seed one trace carrying no metadata at all', async () => {
          // Rules first, trace second, and not interchangeably: OnlineScoringSampler
          // binds the project's rules at trace-creation time, so a trace written
          // before its rule is never offered to it.
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: traceId,
                name: `${testNamespace}-unresolved-args`,
                input: { q: 'capital of France' },
                // `output.output` for the control rule, and deliberately no
                // `execution_plan` key and no `metadata` block, so the
                // unresolved rule's two paths have nothing to bind to.
                output: { output: 'Paris' },
              },
            ],
          });
        });

        /** Poll a rule's stream until it has a row at `level`, then return every row. */
        const rowsOnceLevelAppears = async (
          ruleId: string,
          level: string,
          label: string,
        ): Promise<AutomationRuleLogRef[]> => {
          let rows: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                rows = await backendClient.getAutomationRuleLogs(ruleId);
                return rows.filter((row) => row.level === level).length;
              },
              {
                message: `${level} rows on the ${label} rule's log stream`,
                timeout: LOG_ROW_TIMEOUT_MS,
                intervals: [2_000, 5_000],
              },
            )
            .toBeGreaterThan(0);
          return rows;
        };

        const unresolvedRows = await test.step('The unresolved rule logged a WARN row', () =>
          rowsOnceLevelAppears(rules.unresolved, 'WARN', 'unresolved'),
        );

        await test.step('Its whole stream is one Evaluating INFO and one WARN, and nothing else', () => {
          // By exhaustion, not by filtering. A count of matching rows says
          // nothing about an ERROR row sitting beside them, or about the
          // `Sending … arguments=[]` line this release suppresses — and that
          // line's survival is half of what the fix is.
          const byLevel = unresolvedRows.map((row) => row.level).sort();
          expect(
            byLevel,
            'a rule that resolved nothing evaluates, warns, and stops — no ERROR, no second WARN',
          ).toEqual(['INFO', 'WARN']);

          const infoRow = unresolvedRows.find((row) => row.level === 'INFO');
          expect(infoRow?.message, 'the INFO row must name the trace the rule was offered').toBe(
            `Evaluating traceId '${traceId}' sampled by rule '${unresolvedRuleName}'`,
          );

          expect(
            unresolvedRows.filter((row) => row.message.includes(SENDING_FRAGMENT)),
            `no row may claim it sent ${traceId} to the evaluator — the engine refused to call it`,
          ).toEqual([]);
        });

        await test.step('The WARN names both arguments with their configured paths', () => {
          const warnRow = unresolvedRows.find((row) => row.level === 'WARN');
          // Exact, not `toContain`. The regression guarded here is a message
          // that still renders at the right level on the right stream and stops
          // naming the paths; the paths are the only actionable half, and a
          // prefix match cannot tell the two apart. Pairs sorted by argument
          // name, which is the order `logUnresolvedEvaluatorArguments` renders.
          expect(warnRow?.message, 'the WARN must name each declared argument and its path').toBe(
            expectedWarn(
              'traceId',
              traceId,
              unresolvedRuleName,
              `${pair('ctx', 'metadata.ctx')}, ${pair('plan', 'output.execution_plan')}`,
            ),
          );
        });

        const controlRows = await test.step(
          'The control rule, on the same trace, still says it sent its data to the evaluator',
          async () => {
            const rows = await rowsOnceLevelAppears(rules.control, 'INFO', 'control');
            await expect
              .poll(
                async () => {
                  const current = await backendClient.getAutomationRuleLogs(rules.control);
                  return current.filter((row) => row.message.includes(SENDING_FRAGMENT)).length;
                },
                {
                  message: `Sending rows on the control rule's stream for ${traceId}`,
                  timeout: LOG_ROW_TIMEOUT_MS,
                  intervals: [2_000, 5_000],
                },
              )
              .toBe(1);
            return rows;
          },
        );

        await test.step('And the control rule warned about nothing', () => {
          // The other half of "conditional": the release must not have started
          // warning on rules whose arguments resolve perfectly well.
          expect(
            controlRows.filter((row) => row.level !== 'INFO').map((row) => row.message),
            'a rule whose argument resolved has nothing to warn or error about',
          ).toEqual([]);
        });

        await test.step('The trace carries the control rule\'s score and no other', async () => {
          // Settled rather than read once: the sampler enqueues the two rules
          // onto the stream independently, so "the unresolved rule did not
          // score" read the moment the control's score lands could pass while
          // the other is still in flight.
          const settled = await backendClient.waitForTraceScoresSettled(traceId, {
            timeoutMs: LOG_ROW_TIMEOUT_MS,
            quietPeriodMs: 15_000,
          });
          expect(
            settled.feedbackScores.map((fs) => `${fs.name}=${fs.value}`).sort(),
            'exactly one score, from the rule whose argument resolved',
          ).toEqual([`${controlRuleName}=1`]);
        });

        await test.step('Nothing is redelivered: the row set is unchanged after a quiet period', async () => {
          // `reportUnresolvedArguments` completes empty so the stream message is
          // ACKED rather than retried. If it were not, the WARN would pile up
          // once per redelivery and a user would read a misconfigured rule as a
          // failing one.
          const before = unresolvedRows.length;
          await new Promise((resolve) => setTimeout(resolve, QUIET_PERIOD_MS));
          const after = await backendClient.getAutomationRuleLogs(rules.unresolved);
          expect(
            after.map((row) => `${row.level}: ${row.message}`).sort(),
            'the unresolved rule\'s rows must not multiply — an unacked message would redeliver',
          ).toEqual(unresolvedRows.map((row) => `${row.level}: ${row.message}`).sort());
          expect(after.length, 'row count held across the quiet period').toBe(before);
        });

        await test.step('The automation-logs page renders the WARN row for the seeded trace', async () => {
          // Where a user actually reads this. A different path from the API read
          // above: the page renders the level as a badge, attributes the line to
          // a trace through the `trace_id` marker column, and puts the message
          // through a truncating cell.
          const logs = new AutomationLogsPage(page);
          await logs.goto(rules.unresolved);
          await logs.waitForReady();
          await logs.waitForRowCount(2);

          const rendered = await logs.readRows();
          const warnRows = rendered.filter((row) => row.level === 'WARN');
          expect(warnRows, 'exactly one rendered WARN row').toHaveLength(1);
          expect(
            warnRows[0].traceId,
            'the WARN row must be attributed to the seeded trace in the marker column',
          ).toBe(traceId);
          expect(
            warnRows[0].message,
            'the rendered row must carry the same message the API answered',
          ).toBe(
            expectedWarn(
              'traceId',
              traceId,
              unresolvedRuleName,
              `${pair('ctx', 'metadata.ctx')}, ${pair('plan', 'output.execution_plan')}`,
            ),
          );
        });
      },
    );

    test(
      'a span-scope rule reports the same WARN against its span id, and the span carries no score',
      {
        tag: [
          '@cap:online-evaluation.automation-logs',
          '@cap:online-evaluation.rule-scope-thread-span',
        ],
      },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-unres-span`;
        assertLoggedVerbatim({ ruleName });

        const ruleId = await test.step('Create a span-scope rule whose arguments cannot resolve', () =>
          backendClient.createAutomationRule({
            projectId: project.id,
            name: ruleName,
            type: 'span_user_defined_metric_python',
            samplingRate: 1,
            metric: buildConstantScoreMetric(ruleName, ['ctx', 'plan']),
            arguments: { ctx: 'metadata.ctx', plan: 'output.execution_plan' },
          }),
        );

        await test.step('The rule really is span-scope, enabled and sampling everything', async () => {
          // Scope decides which Redis stream carries the message, and the API
          // defaults to the trace scorer — so without this the test could prove
          // the trace-scope claim a second time and report it as the span one.
          const rule = await backendClient.getAutomationRule(ruleId);
          expect(rule.type, 'the rule must score spans, not traces').toBe(
            'span_user_defined_metric_python',
          );
          expect(rule.samplingRate, 'every span must be eligible').toBe(1);
          expect(rule.enabled, 'a disabled rule scores nothing at all').toBe(true);
        });

        const traceId = uuid7();
        const spanId = uuid7();
        await test.step('Seed one span, under its trace, carrying no metadata at all', async () => {
          const now = new Date();
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: traceId,
                name: `${testNamespace}-span-parent`,
                input: { q: 'capital of France' },
                output: { output: 'Paris' },
              },
            ],
          });
          // `source: 'sdk'` is mandatory — OnlineScoringSpanSampler drops a span
          // whose source is not a logging source before any rule sees it.
          await backendClient.createSpan({
            id: spanId,
            traceId,
            projectName: project.name,
            name: `${testNamespace}-unresolved-args-span`,
            source: 'sdk',
            input: { q: 'capital of France' },
            output: { output: 'Paris' },
            startTime: now,
            endTime: now,
          });
        });

        const rows = await test.step('The span rule logged a WARN row', async () => {
          let current: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                current = await backendClient.getAutomationRuleLogs(ruleId);
                return current.filter((row) => row.level === 'WARN').length;
              },
              {
                message: `WARN rows on the span rule's log stream`,
                timeout: LOG_ROW_TIMEOUT_MS,
                intervals: [2_000, 5_000],
              },
            )
            .toBeGreaterThan(0);
          return current;
        });

        await test.step('Its stream is one Evaluating INFO and one WARN keyed on the span id', () => {
          expect(
            rows.map((row) => row.level).sort(),
            'the span scorer evaluates, warns, and stops',
          ).toEqual(['INFO', 'WARN']);

          expect(
            rows.find((row) => row.level === 'INFO')?.message,
            'the INFO row must name the span the rule was offered',
          ).toBe(`Evaluating spanId '${spanId}' sampled by rule '${ruleName}'`);

          // The byte-identical message the trace scorer produces, differing only
          // in the entity label and id — both scorers call the one shared
          // helper, and a divergence here means one of them grew its own copy.
          expect(
            rows.find((row) => row.level === 'WARN')?.message,
            'the span WARN must name each declared argument and its path',
          ).toBe(
            expectedWarn(
              'spanId',
              spanId,
              ruleName,
              `${pair('ctx', 'metadata.ctx')}, ${pair('plan', 'output.execution_plan')}`,
            ),
          );

          expect(
            rows.filter((row) => row.message.includes(SENDING_FRAGMENT)),
            `no row may claim it sent span ${spanId} to the evaluator`,
          ).toEqual([]);
        });

        await test.step('The span carries no feedback score at all', async () => {
          // The consequence a user sees. Read after the WARN has landed, so the
          // rule has demonstrably finished with this span and an empty score set
          // cannot just mean "still in flight". Held across the quiet period
          // below for the same reason.
          const span = await backendClient.getSpan(spanId);
          expect(span, `span ${spanId} must be readable, or the absence below proves nothing`).not.toBeNull();
          expect(
            span?.feedbackScores.map((fs) => fs.name) ?? null,
            'a rule that never reached its evaluator cannot have scored',
          ).toEqual([]);
        });

        await test.step('Nothing is redelivered, and no score arrives late', async () => {
          await new Promise((resolve) => setTimeout(resolve, QUIET_PERIOD_MS));
          const after = await backendClient.getAutomationRuleLogs(ruleId);
          expect(
            after.map((row) => `${row.level}: ${row.message}`).sort(),
            'the span rule\'s rows must not multiply — an unacked message would redeliver',
          ).toEqual(rows.map((row) => `${row.level}: ${row.message}`).sort());

          const span = await backendClient.getSpan(spanId);
          expect(
            span?.feedbackScores.map((fs) => fs.name) ?? null,
            'still no score once the stream has had time to redeliver',
          ).toEqual([]);
        });

        await test.step('The automation-logs page renders the span rule\'s WARN row', async () => {
          const logs = new AutomationLogsPage(page);
          await logs.goto(ruleId);
          await logs.waitForReady();
          await logs.waitForRowCount(2);

          const warnRow = logs.rowsAtLevel('WARN');
          await expect(warnRow, 'exactly one rendered WARN row').toHaveCount(1);
          await expect(
            warnRow,
            'the rendered row must name the span and both unresolved paths',
          ).toContainText(
            expectedWarn(
              'spanId',
              spanId,
              ruleName,
              `${pair('ctx', 'metadata.ctx')}, ${pair('plan', 'output.execution_plan')}`,
            ),
          );

          // Deliberately NOT asserted: the `trace_id` marker column, which the
          // trace-scope test above does assert. A span-scope line carries no
          // marker at all — `AutomationRuleEvaluatorLogsDAO.CUSTOM_MARKER_KEYS`
          // is `trace_id` and `thread_model_id`, with no `span_id` — so the
          // column is absent and the id is readable only inside the message.
          // That predates this release (the pre-existing `Evaluating spanId`
          // INFO is equally markerless) and pinning it either way here would
          // assert a limitation rather than the fix.
        });
      },
    );

    test(
      'the WARN sanitizes a path that looks like a log entry and caps a large argument map',
      { tag: ['@cap:online-evaluation.python-rule-scores'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup }) => {
        // API-level: both claims are about the string the engine persists, and
        // the page renders whatever that string is. Driving a browser to read it
        // back would assert the table, which the two tests above already do.
        //
        // Deliberately NOT tagged `automation-logs`, which the two tests above
        // do carry. That key is the /automation-logs PAGE — its taxonomy entry
        // says so, and `online-evaluation-python-metric-errors.spec.ts` asserts
        // this same GET /automations/evaluators/{id}/logs stream and declines
        // the key for exactly this reason: an API read says nothing about what
        // renders. `python-rule-scores` is what this test really pins — a
        // python rule that reached no evaluator and therefore stored no score.
        const forgedRuleName = `${testNamespace}-forged`;
        const cappedRuleName = `${testNamespace}-capped`;

        /**
         * A configured path carrying a newline and a line that reads like an
         * ERROR entry. `sanitize` replaces control characters with a space, so
         * the forged text must stay inside the quoted path and must not become
         * a row of its own at a level the user would act on.
         */
        const FORGED_PATH = 'input.missing\n2026-01-01 ERROR forged row';
        const SANITIZED_PATH = 'input.missing 2026-01-01 ERROR forged row';

        /**
         * Twelve arguments against `MAX_REPORTED_FIELD_NAMES = 10`. Zero-padded
         * so lexicographic order — what the engine sorts by — is also numeric
         * order, making the reported ten and the two omitted unambiguous.
         */
        const CAPPED_ARGUMENT_COUNT = 12;
        const REPORTED_LIMIT = 10;
        const cappedArguments = Object.fromEntries(
          Array.from({ length: CAPPED_ARGUMENT_COUNT }, (_, i) => {
            const n = String(i + 1).padStart(2, '0');
            return [`arg${n}`, `metadata.missing${n}`];
          }),
        );

        assertLoggedVerbatim({
          forgedRuleName,
          cappedRuleName,
          SANITIZED_PATH,
          ...cappedArguments,
        });

        const rules = await test.step('Create the forging rule and the twelve-argument rule', async () => ({
          forged: await backendClient.createAutomationRule({
            projectId: project.id,
            name: forgedRuleName,
            samplingRate: 1,
            metric: buildConstantScoreMetric(forgedRuleName, ['forged']),
            arguments: { forged: FORGED_PATH },
          }),
          capped: await backendClient.createAutomationRule({
            projectId: project.id,
            name: cappedRuleName,
            samplingRate: 1,
            metric: buildConstantScoreMetric(cappedRuleName, Object.keys(cappedArguments)),
            arguments: cappedArguments,
          }),
        }));

        const traceId = uuid7();
        await test.step('Seed one trace neither rule can resolve against', async () => {
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: traceId,
                name: `${testNamespace}-sanitize`,
                input: { q: 'capital of France' },
                output: { output: 'Paris' },
              },
            ],
          });
        });

        /** Poll a rule's stream until a WARN appears, then return every row. */
        const rowsOnceWarned = async (
          ruleId: string,
          label: string,
        ): Promise<AutomationRuleLogRef[]> => {
          let rows: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                rows = await backendClient.getAutomationRuleLogs(ruleId);
                return rows.filter((row) => row.level === 'WARN').length;
              },
              {
                message: `WARN rows on the ${label} rule's log stream`,
                timeout: LOG_ROW_TIMEOUT_MS,
                intervals: [2_000, 5_000],
              },
            )
            .toBeGreaterThan(0);
          return rows;
        };

        await test.step('A newline in a configured path cannot forge a second row', async () => {
          const rows = await rowsOnceWarned(rules.forged, 'forged');
          // Exhaustion is the whole claim: the forged text reads like an ERROR
          // entry, so "one WARN exists" would pass equally well if a second row
          // had appeared at ERROR alongside it.
          expect(
            rows.map((row) => row.level).sort(),
            'the forged ERROR line must not become a row — one INFO, one WARN, nothing else',
          ).toEqual(['INFO', 'WARN']);
          expect(
            rows.find((row) => row.level === 'WARN')?.message,
            'the newline renders as a space and the forged text stays inside the quoted path',
          ).toBe(expectedWarn('traceId', traceId, forgedRuleName, pair('forged', SANITIZED_PATH)));
        });

        await test.step(`A rule declaring ${CAPPED_ARGUMENT_COUNT} arguments reports ${REPORTED_LIMIT} and counts the rest`, async () => {
          const rows = await rowsOnceWarned(rules.capped, 'capped');
          const reported = Object.entries(cappedArguments)
            .sort(([a], [b]) => (a < b ? -1 : 1))
            .slice(0, REPORTED_LIMIT)
            .map(([name, path]) => pair(name, path))
            .join(', ');
          const omitted = CAPPED_ARGUMENT_COUNT - REPORTED_LIMIT;
          expect(
            rows.find((row) => row.level === 'WARN')?.message,
            `the WARN must report ${REPORTED_LIMIT} pairs sorted by name and then "and ${omitted} more"`,
          ).toBe(
            expectedWarn(
              'traceId',
              traceId,
              cappedRuleName,
              `${reported} and ${omitted} more`,
            ),
          );
          expect(
            rows.map((row) => row.level).sort(),
            'capping is not an error — one INFO, one WARN, nothing else',
          ).toEqual(['INFO', 'WARN']);
        });

        await test.step('Neither rule scored the trace', async () => {
          const settled = await backendClient.waitForTraceScoresSettled(traceId, {
            timeoutMs: LOG_ROW_TIMEOUT_MS,
            quietPeriodMs: 15_000,
            // A genuinely empty score set IS the expected end state here —
            // neither rule reached its evaluator — so the default "wait for at
            // least one score" would time out on a correct product.
            minScores: 0,
          });
          expect(
            settled.feedbackScores.map((fs) => fs.name),
            'a rule that resolved nothing cannot have scored',
          ).toEqual([]);
        });
      },
    );
  },
);
