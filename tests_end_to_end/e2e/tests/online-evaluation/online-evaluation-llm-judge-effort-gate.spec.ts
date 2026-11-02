import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/**
 * A saved LLM-judge rule carrying an effort its model refuses (OPIK-8748,
 * opik#8928).
 *
 * That PR moved the Anthropic effort gate off the hardcoded
 * `EFFORT_LEVELS_BY_MODEL_ID` map onto the curated catalog, so what a judge
 * model accepts is now data. The judge path is where getting that wrong is
 * worst, and for a reason specific to it: creation does **not** validate the
 * parameter. `POST /v1/private/automations/evaluators/` answers 201 for an
 * effort the model cannot take, and the gate only fires later, per trace, when
 * the scorer builds the model. So a bad catalog row does not break a rule at
 * the moment a user saves it — it turns every subsequent scoring run on that
 * model into an ERROR the user has to go looking for, while the rule sits in
 * the list looking enabled and healthy.
 *
 * What a user cares about being wrong, in order: the scores silently stop; and
 * when they go to find out why, the reason has to name the effort, because
 * "unexpected error" would leave them with no way to connect it to the one
 * setting they chose.
 *
 * ## The two controls, and why both are needed
 *
 * A single failing rule proves almost nothing on its own — a silent judge
 * stream looks identical to online scoring being down on the environment, and
 * an ERROR row looks identical to the provider being unreachable. So:
 *
 *  - a **python metric rule** scores the same trace, proving online scoring
 *    reached this project at all. It needs no provider, so it can only fail
 *    for reasons that would fail the judges too;
 *  - an **effort control rule** — the same model, the same prompt, at a level
 *    the row *does* accept — gets PAST the gate. That is what makes the first
 *    rule's failure a fact about the effort rather than about the model, the
 *    workspace or the key.
 *
 * ## Why this is not already covered
 *
 * `online-evaluation-llm-judge-model-parameters.spec.ts` drives the effort
 * control in the rule dialog but never submits it, so no rule of its ever
 * runs. `online-evaluation-llm-judge-provider-failure.spec.ts` asserts the
 * same no-score-plus-ERROR outcome for an unreachable provider, one layer
 * past this gate. `online-evaluation-error-row-names-cause.spec.ts` is about
 * python metric failures. None of them reaches the parameter gate.
 *
 * ## Cost
 *
 * The refused rule is refused during request mapping and never calls a
 * provider. The control rule, by design, does — one delivery, on whatever key
 * the workspace holds for Anthropic. `providerKeys.ensureBuiltIn` reuses an
 * existing key and otherwise seeds a junk one (on which that delivery is an
 * auth failure), and registers the model unbilled on that basis.
 */

/** Written when a rule is sampled for a trace, before anything can go wrong. */
const SAMPLED_LINE = 'sampled by rule';

/** Written immediately before the provider call — i.e. after the gate let it through. */
const LLM_CALL_LINE = 'to LLM';

/**
 * The gate's refusal for a level outside the model's curated list.
 *
 * The parameter path is quoted in full rather than matched loosely: it is the
 * string that tells a user which of their settings is at fault, and a message
 * that merely said "unsupported effort" would satisfy a substring check while
 * failing the only job this error has.
 */
const EFFORT_REFUSAL = 'Unsupported custom_parameters.output_config.effort';

/**
 * The judge model, and the two levels either side of its curated boundary.
 *
 * `claude-opus-4-5-20251101` is the single Anthropic row curating exactly
 * low/medium/high, which makes it the only model where one accepted and one
 * refused level can both be named without picking a level no row withholds.
 * Pinned to the dated id because that is the row: the undated
 * `claude-opus-4-5` is a separate entry the backend's enum does not route at
 * all.
 */
const JUDGE_MODEL = 'claude-opus-4-5-20251101';
const REFUSED_EFFORT = 'max';
const ACCEPTED_EFFORT = 'high';

/** A collapsed message cell shows only the first line. */
const firstLine = (message: string) => message.split('\n')[0]!.trim();

test.describe(
  'Online Evaluation — an LLM judge carrying a refused effort',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'writes no score and reports an ERROR naming the effort and the levels the model does accept',
      {
        tag: [
          '@cap:online-evaluation.llm-judge-scores',
          '@cap:online-evaluation.automation-logs',
        ],
      },
      async ({
        page,
        project,
        sdkClient,
        backendClient,
        testNamespace,
        providerKeys,
        automationRulesCleanup,
      }) => {
        test.setTimeout(300_000);

        const refusedRuleName = `${testNamespace}-refused-effort`;
        const controlRuleName = `${testNamespace}-accepted-effort`;
        const pythonRuleName = `${testNamespace}-python-control`;
        /**
         * Short and distinct from the rule names, which carry the run
         * namespace: the score name is what the no-score assertion reads off
         * the trace, so it has to be nameable there.
         */
        const refusedScoreName = 'RefusedEffort';

        await test.step('The catalog really does refuse this level and accept the other', async () => {
          // The premise of the whole test, read from the data the gate now
          // enforces from. Without it, "the rule errored" would be equally
          // satisfied by a catalog that refuses BOTH levels — in which case the
          // control rule proves nothing and the spec would pass having tested
          // the model rather than the effort.
          const catalog = await backendClient.getLlmCatalogModels('anthropic');
          const row = catalog.find((model) => model.id === JUDGE_MODEL);
          expect(row, `the catalog must carry a curated row for '${JUDGE_MODEL}'`).toBeDefined();
          expect(
            row!.effort.accepted,
            'the row must admit the parameter, or the refusal below would be the other 400 shape',
          ).toBe(true);
          expect(
            row!.effort.levels,
            `'${ACCEPTED_EFFORT}' must be curated for this model — the control rule depends on ` +
              'getting past the gate',
          ).toContain(ACCEPTED_EFFORT);
          expect(
            row!.effort.levels,
            `'${REFUSED_EFFORT}' must NOT be curated for this model, or there is nothing to refuse`,
          ).not.toContain(REFUSED_EFFORT);
        });

        const { refusedRuleId, controlRuleId } = await test.step(
          'Create the refused judge, the accepted-effort control, and a provider-free control',
          async () => {
            await providerKeys.ensureBuiltIn('anthropic', [JUDGE_MODEL]);

            const judgeMessages = [
              { role: 'USER' as const, content: 'Rate this output out of 10: {{output}}' },
            ];
            const refusedRuleId = await backendClient.createLlmJudgeRule({
              projectId: project.id,
              name: refusedRuleName,
              model: JUDGE_MODEL,
              messages: judgeMessages,
              samplingRate: 1,
              scoreName: refusedScoreName,
              customParameters: { output_config: { effort: REFUSED_EFFORT } },
            });
            // Identical in every respect but the effort, so the difference in
            // outcome cannot be attributed to the prompt, the schema or the
            // model.
            const controlRuleId = await backendClient.createLlmJudgeRule({
              projectId: project.id,
              name: controlRuleName,
              model: JUDGE_MODEL,
              messages: judgeMessages,
              samplingRate: 1,
              customParameters: { output_config: { effort: ACCEPTED_EFFORT } },
            });
            await backendClient.createAutomationRule({
              projectId: project.id,
              name: pythonRuleName,
              samplingRate: 1,
              metric: buildConstantScoreMetric(pythonRuleName),
              arguments: { output: 'output.output' },
            });
            return { refusedRuleId, controlRuleId };
          },
        );

        const trace = await test.step('Seed one complete trace for all three rules', async () => {
          // One trace, three rules: they are then provably judging identical
          // input. The sampler only considers COMPLETE traces, which is why
          // this goes through the SDK rather than a bare trace write — a trace
          // with no end_time is skipped and every stream below stays empty.
          return sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-trace`,
            input: 'whatever',
            output: 'seed output',
          });
        });

        await test.step('Control: online scoring reached this project', async () => {
          const score = await backendClient.pollTraceForFeedbackScore(trace.id, pythonRuleName, {
            timeoutMs: 180_000,
          });
          expect(score.value, 'the python control metric returns a constant 1.0').toBe(1.0);
        });

        const refusedLogs = await test.step(
          'The refused rule reports the effort and the levels the model does accept',
          async () => {
            let logs: AutomationRuleLogRef[] = [];
            await expect
              .poll(
                async () => {
                  logs = await backendClient.getAutomationRuleLogs(refusedRuleId);
                  // The error line is written last, so its arrival is what
                  // makes this rule's stream complete for the seeded trace.
                  return logs.some((l) => l.level === 'ERROR');
                },
                {
                  timeout: 180_000,
                  intervals: [2_000, 5_000],
                  message:
                    `rule '${refusedRuleName}' never reported a failure — its effort cannot be ` +
                    'accepted, so a silent stream means the gate let it through',
                },
              )
              .toBe(true);

            expect(
              logs.filter((l) => l.message.includes(SAMPLED_LINE)),
              'the refused rule must have been sampled for the seeded trace',
            ).toHaveLength(1);

            const errors = logs.filter((l) => l.level === 'ERROR');
            expect(errors, 'the refusal is reported exactly once').toHaveLength(1);
            const message = errors[0]!.message;

            expect(
              message,
              'the error must name the parameter at fault — this is the only place a user can ' +
                'connect a rule that stopped scoring to the one setting they chose',
            ).toContain(EFFORT_REFUSAL);
            expect(message, 'and the model the level was refused for').toContain(
              `model '${JUDGE_MODEL}'`,
            );
            expect(message, 'and the level itself').toContain(`effort '${REFUSED_EFFORT}'`);
            // The remedy, not just the complaint. The gate quotes the curated
            // list, which is what tells the user which levels to choose
            // instead; an error naming only the bad value leaves them guessing.
            expect(
              message,
              'and the levels the model does accept, so the user knows what to pick instead',
            ).toContain("supported '[low, medium, high]'");
            expect(
              message,
              'and the rule that failed, so a workspace of rules is separable',
            ).toContain(refusedRuleName);
            return logs;
          },
        );

        await test.step('Control: the same judge at an accepted level gets past the gate', async () => {
          // What makes the assertion above about the EFFORT. This rule differs
          // only in that one value, so if it too were refused by the gate, the
          // refusal would be about the model or the environment instead.
          //
          // Asserted as "the gate did not refuse it" rather than as a specific
          // outcome: past the gate the request really goes to Anthropic, and
          // what comes back depends on the key the workspace holds — an auth
          // error on a seeded junk key, a real score on a real one. Both are
          // equally "the gate let it through", and pinning either would make
          // this spec depend on which environment it runs in.
          let controlLogs: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                controlLogs = await backendClient.getAutomationRuleLogs(controlRuleId);
                return controlLogs.some((l) => l.message.includes(LLM_CALL_LINE));
              },
              {
                timeout: 180_000,
                intervals: [2_000, 5_000],
                message:
                  `rule '${controlRuleName}' never reached its provider call — without that, the ` +
                  'refused rule’s failure cannot be attributed to the effort',
              },
            )
            .toBe(true);

          expect(
            controlLogs.filter((l) => l.message.includes(SAMPLED_LINE)),
            'the control must have been sampled for the same trace',
          ).toHaveLength(1);
          expect(
            controlLogs.filter((l) => l.message.includes(EFFORT_REFUSAL)),
            'no line of the control’s stream may mention the effort gate — it is the same model ' +
              'and the same prompt, so a refusal here would mean the gate ignores the level',
          ).toHaveLength(0);
        });

        await test.step('The refused judge wrote no score', async () => {
          // The complement of the python control. A rule that failed the gate
          // and still stored something would be worse than one that failed
          // loudly.
          //
          // Scoped to this rule's own score name rather than asserting the
          // trace has no scores at all: the python control deliberately wrote
          // one, and on a workspace with a real Anthropic key the effort
          // control may legitimately have written one too.
          const detail = await backendClient.getTrace(trace.id);
          expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
          expect(
            detail!.feedbackScores.map((s) => s.name),
            `'${refusedScoreName}' must be absent: the judge never ran, so there is no score it ` +
              'could honestly have produced',
          ).not.toContain(refusedScoreName);
          expect(
            detail!.feedbackScores.map((s) => s.name),
            'while the provider-free control’s score is there, proving the read itself works',
          ).toContain(pythonRuleName);
        });

        await test.step('The automation logs page renders the reason a user reads', async () => {
          const logsPage = new AutomationLogsPage(page);
          await logsPage.goto(refusedRuleId);
          await logsPage.waitForReady();
          // Settle on the count the API already reported before reading the
          // DOM: waitForReady returns on the first row, so without this a table
          // caught mid-render would be read as the whole stream.
          await logsPage.waitForRowCount(refusedLogs.length);

          const errorRow = logsPage.rowsAtLevel('ERROR');
          await expect(errorRow, 'exactly one ERROR row to expand').toHaveCount(1);
          // The scorer writes the reason after a newline, and a collapsed cell
          // shows only the first line — so the effort is invisible until this
          // is clicked. Asserting on the collapsed row would pass while the
          // user still could not see why their rule stopped scoring.
          await logsPage.expandRow(errorRow);
          const messageCell = errorRow.locator('[data-cell-id$="_message"]');
          await expect(
            messageCell,
            'the expanded row must carry the same parameter name the API reported',
          ).toContainText(EFFORT_REFUSAL);
          await expect(
            messageCell,
            'and the supported list, which is the actionable half',
          ).toContainText("supported '[low, medium, high]'");

          expect(
            await logsPage.readRows().then((rows) => rows.map((r) => firstLine(r.message))),
            'every rendered line belongs to this rule',
          ).toEqual(expect.arrayContaining([firstLine(refusedLogs[0]!.message)]));
        });
      },
    );
  },
);
