import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { buildScoreResultMetric } from '@e2e/core/metrics';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A python metric's scores are usable, or they are not, and the engine now says
 * which per score instead of per response.
 *
 * `online-evaluation-python-metric-errors.spec.ts` covers the neighbouring
 * question — a metric that produces no output at all — and nothing covers this
 * one: a metric that produces output the backend is supposed to refuse. The two
 * ways it can be wrong are silent in opposite directions and both render as a
 * plausible number in the trace panel:
 *
 *   - **over-dropping** loses a metric's deliberate `0.0`, so a real judgement
 *     of "no, not at all" disappears;
 *   - **under-dropping** stores the placeholder `0.0` the SDK pairs with
 *     `scoring_failed=True`, so an evaluation that never happened reads as a
 *     genuine zero.
 *
 * Neither is visible from one rule, which is why four rules judge one trace:
 * the flagged `0.0` and the deliberate `0.0` are the same number reached
 * through different fields, and only their disagreement on a single trace
 * separates the two failures. Two more rules pin the response-level half — a
 * response with no usable score at all is a 400 and stores nothing, while a
 * mixed list keeps its usable scores and reports the rest.
 *
 * Deterministic and LLM-free: a python rule runs the user's own code, so every
 * score here is the literal one the metric constructed.
 */

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

/**
 * What the python evaluator answers for a response holding no storable score.
 * Matched on the classified text, not the status alone: a 400 with the older
 * "didn't return any 'ScoreResult'" wording would be the wrong classification —
 * these metrics DO return one, it is just unusable.
 */
const NO_USABLE_SCORE_400 =
  "400 Bad Request: The provided 'code' field didn't return any usable";

/** The two causes `logDroppedPythonScores` distinguishes, verbatim. */
const DROPPED_FOR_NO_VALUE = 'because the metric returned no value';
const DROPPED_FOR_FAILED_FLAG = 'because the metric reported the scoring as failed';

test.describe(
  'Online Evaluation — python score usability',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'Unusable python scores are dropped per score with their cause, a wholly unusable response is a single 400, and a deliberate zero still lands',
      {
        tag: [
          '@cap:online-evaluation.python-rule-scores',
          '@cap:online-evaluation.scores-in-trace-panel',
        ],
      },
      async ({ project, sdkClient, backendClient, testNamespace, automationRulesCleanup, page }) => {
        test.setTimeout(300_000);

        // Score names, not rule names: the engine stores a score under the name
        // its ScoreResult carries, so these are what the trace and the log lines
        // are asserted against.
        const valuelessScore = `${testNamespace}-valueless`;
        const failedFlagScore = `${testNamespace}-failedflag`;
        const mixedKeptScore = `${testNamespace}-mixed-kept`;
        const mixedFailedScore = `${testNamespace}-mixed-failed`;
        const mixedValuelessScore = `${testNamespace}-mixed-valueless`;
        const deliberateZeroScore = `${testNamespace}-zero`;

        /** The one value a storable score carries, chosen so 0.0 stays unambiguous. */
        const KEPT_VALUE = 0.7;

        const rules = await test.step(
          'Create four python rules, before any trace exists',
          async () => {
            const create = (name: string, metric: string) =>
              backendClient.createAutomationRule({
                projectId: project.id,
                name,
                samplingRate: 1,
                metric,
                // A resolvable mapping is mandatory: the backend refuses to call
                // the evaluator with an empty argument map, which would fail
                // these rules before their metric ever ran.
                arguments: { output: 'output.output' },
              });

            return {
              // Every score unusable, one way each. The response, not the score,
              // is what the evaluator rejects.
              valueless: await create(
                `${testNamespace}-rule-valueless`,
                buildScoreResultMetric(`${testNamespace}-rule-valueless`, [
                  { name: valuelessScore, value: null },
                ]),
              ),
              // The SDK's own failure shape: a placeholder 0.0 the flag
              // disqualifies. Storable-looking, and must still be dropped.
              failedFlag: await create(
                `${testNamespace}-rule-failedflag`,
                buildScoreResultMetric(`${testNamespace}-rule-failedflag`, [
                  { name: failedFlagScore, value: 0.0, scoringFailed: true },
                ]),
              ),
              // One usable score and one of each drop, so the split has to be
              // per score rather than per response.
              mixed: await create(
                `${testNamespace}-rule-mixed`,
                buildScoreResultMetric(`${testNamespace}-rule-mixed`, [
                  { name: mixedKeptScore, value: KEPT_VALUE },
                  { name: mixedFailedScore, value: 0.0, scoringFailed: true },
                  { name: mixedValuelessScore, value: null },
                ]),
              ),
              // The control for over-dropping: the same 0.0 as the flagged
              // score, reached without the flag, and it must survive.
              zero: await create(
                `${testNamespace}-rule-zero`,
                buildScoreResultMetric(`${testNamespace}-rule-zero`, [
                  { name: deliberateZeroScore, value: 0.0 },
                ]),
              ),
            };
          },
        );

        const trace = await test.step('Seed one trace for all four rules to judge', async () => {
          // One trace, four rules: every outcome below is then provably a
          // difference in the metric rather than in what it was handed.
          return sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-trace`,
            input: 'whatever',
            output: 'seed output',
          });
        });

        await test.step('A deliberate 0.0 is stored', async () => {
          // Also the liveness control: it proves the python evaluator is
          // reachable and this project's rules fired at all, without which the
          // two rules that store nothing would be indistinguishable from two
          // rules that were never invoked.
          const score = await backendClient.pollTraceForFeedbackScore(
            trace.id,
            deliberateZeroScore,
            { timeoutMs: 180_000 },
          );
          expect(
            score.value,
            'a metric that deliberately scores 0.0 is not a metric that failed',
          ).toBe(0);
        });

        await test.step('The mixed rule stored its one usable score', async () => {
          const score = await backendClient.pollTraceForFeedbackScore(trace.id, mixedKeptScore, {
            timeoutMs: 180_000,
          });
          expect(score.value, 'the usable score in a mixed list survives its siblings').toBe(
            KEPT_VALUE,
          );
        });

        const waitForRuleLogs = async (
          ruleId: string,
          ruleLabel: string,
          done: (logs: AutomationRuleLogRef[]) => boolean,
        ) => {
          let logs: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                logs = await backendClient.getAutomationRuleLogs(ruleId);
                return done(logs);
              },
              {
                timeout: 180_000,
                intervals: [2_000, 5_000],
                message: `rule '${ruleLabel}' never reported the lines this test is about — a silent stream means it was never invoked`,
              },
            )
            .toBe(true);
          return logs;
        };

        const unusableLogs = await test.step(
          'A response with no usable score is one 400 naming the cause, not a retry loop',
          async () => {
            const collected: Record<string, AutomationRuleLogRef[]> = {};
            for (const [label, ruleId] of [
              ['valueless', rules.valueless],
              ['failedFlag', rules.failedFlag],
            ] as const) {
              const logs = await waitForRuleLogs(ruleId, label, (l) =>
                l.some((line) => line.level === 'ERROR'),
              );
              collected[label] = logs;

              const errors = logs.filter((l) => l.level === 'ERROR');
              expect(
                errors.map((l) => l.message).join('\n---\n'),
                `rule '${label}' must classify an unusable response as a client-side 400`,
              ).toContain(NO_USABLE_SCORE_400);
              expect(
                errors,
                `rule '${label}' must report its terminal failure exactly once`,
              ).toHaveLength(1);
              // A 400 is a terminal answer: re-running the metric hoping for a
              // different one would burn the retry budget on a permanent error.
              expect(
                logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
                `rule '${label}' must send the trace to the evaluator exactly once`,
              ).toHaveLength(1);
            }
            return collected;
          },
        );

        await test.step('The rejected responses reported no dropped-score lines', async () => {
          // The response never reached the backend's splitter, so the per-score
          // reporting must not fire for it — a WARN here would mean the engine
          // both rejected the response and claimed to have dropped scores out
          // of it.
          for (const [label, logs] of Object.entries(unusableLogs)) {
            expect(
              logs.filter((l) => l.level === 'WARN'),
              `rule '${label}' rejected the whole response, so it dropped no individual score`,
            ).toHaveLength(0);
          }
        });

        await test.step(
          'The mixed rule named each dropped score by its own cause, and only those',
          async () => {
            const logs = await waitForRuleLogs(
              rules.mixed,
              'mixed',
              (l) => l.filter((line) => line.level === 'WARN').length >= 2,
            );
            const warnings = logs.filter((l) => l.level === 'WARN');
            expect(
              warnings.map((l) => l.message),
              'one line per cause, and no third line — both drops are reported, neither twice',
            ).toHaveLength(2);

            const valuelessLine = warnings.filter((l) => l.message.includes(DROPPED_FOR_NO_VALUE));
            const failedLine = warnings.filter((l) =>
              l.message.includes(DROPPED_FOR_FAILED_FLAG),
            );
            expect(
              valuelessLine,
              'the score with no value is reported as having returned no value',
            ).toHaveLength(1);
            expect(
              failedLine,
              'the flagged score is reported by its flag, not as a missing value',
            ).toHaveLength(1);

            expect(
              valuelessLine[0].message,
              'the line names the score that was dropped, not just the count',
            ).toContain(mixedValuelessScore);
            expect(failedLine[0].message, 'likewise for the flagged one').toContain(
              mixedFailedScore,
            );
            expect(
              warnings.map((l) => l.message).join('\n---\n'),
              'the kept score must not be reported as dropped',
            ).not.toContain(mixedKeptScore);

            // Same retry claim as above, on the path that succeeded: a partially
            // usable response is a complete answer, not something to re-ask.
            expect(
              logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
              'a mixed response is answered once',
            ).toHaveLength(1);
          },
        );

        await test.step('The trace carries those two scores and nothing else', async () => {
          // Exhaustive, not a lookup: asserting only that the two storable
          // scores are present would pass just as well on an engine that also
          // stored all three dropped ones.
          const detail = await backendClient.getTrace(trace.id);
          expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
          expect(
            detail!.feedbackScores.map((s) => s.name).sort(),
            'exactly the storable scores — no unusable one leaked through',
          ).toEqual([deliberateZeroScore, mixedKeptScore].sort());
        });

        await test.step('And the trace panel renders exactly those two rows', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          await logs.waitForReady();

          const panel = await logs.openTraceById(trace.id);
          await panel.waitForFullyLoaded();
          await panel.openFeedbackScoresTab();

          // The tab renders a second table for span scores when the trace has
          // any. This trace has no spans, so one table means the row count
          // below really is the trace's own scores.
          await expect(
            panel.feedbackScoreTables(),
            'a spanless trace renders only the Trace scores table',
          ).toHaveCount(1);
          await expect(
            panel.feedbackScoreRows(),
            'two stored scores, two rows — a dropped score must not reach the panel',
          ).toHaveCount(2);

          await expect(panel.feedbackScoreRowByName(mixedKeptScore)).toHaveCount(1);
          await expect(panel.feedbackScoreValueCell(mixedKeptScore)).toHaveText(
            String(KEPT_VALUE),
          );
          await expect(panel.feedbackScoreRowByName(deliberateZeroScore)).toHaveCount(1);
          await expect(
            panel.feedbackScoreValueCell(deliberateZeroScore),
            'the deliberate zero renders as a score, not as a blank or a missing row',
          ).toHaveText('0');

          for (const dropped of [valuelessScore, failedFlagScore, mixedFailedScore, mixedValuelessScore]) {
            await expect(
              panel.feedbackScoreRowByName(dropped),
              `'${dropped}' was dropped server-side and must not render`,
            ).toHaveCount(0);
          }
        });
      },
    );
  },
);
