import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { uuid7 } from '@e2e/core/backend';
import {
  buildConstantScoreMetric,
  buildNetworkReachProbeMetric,
  buildUnguardedNetworkMetric,
  NETWORK_REACH_ATTEMPT_NAMES,
} from '@e2e/core/metrics';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A python online-evaluation metric cannot reach the network, and when it does
 * not expect that, the refusal is NAMED rather than stored as silence
 * (opik#9675).
 *
 * 2.3.0 turns `PYTHON_CODE_EXECUTOR_PROCESS_NETWORK_ISOLATION` and
 * `PYTHON_CODE_EXECUTOR_DOCKER_HARDENING` on by default — unset or empty now
 * means ON — so "user code has no network" is the behaviour of every fresh
 * install rather than of an install that opted in. Nothing in the estate
 * asserted it either way: `online-evaluation-smoke.spec.ts` and
 * `online-evaluation-python-dependency-imports.spec.ts` both pass whether or not
 * the sandbox is applied, because neither metric tries to reach anything.
 *
 * **Deployment-agnostic by construction, which is why it is worth pinning
 * permanently.** The two executor strategies refuse at different layers, and
 * they are a deployment decision (`PYTHON_CODE_EXECUTOR_STRATEGY`): compose and
 * the chart default to `process`, where a seccomp filter fails the `socket`
 * syscall itself with `EPERM`; Opik Cloud runs `docker`, where
 * `PYTHON_CODE_EXECUTOR_ALLOW_NETWORK=false` creates the executor container with
 * `network_disabled`, so socket creation and loopback both still work and only
 * the connection has nowhere to go. This spec therefore asserts the property
 * BOTH must have — the metric reached nothing — and never the errno or the
 * exception type, which are exactly what differs. Those go in an attachment, as
 * the only thing that tells a reader of a future failure which layer answered.
 *
 * **Two rules on one trace keep "refused" apart from "never ran".** The whole
 * risk of the flip is that isolation stops workers starting, in which case every
 * python rule silently scores nothing — and a probe asserting only "the network
 * was not reached" would be perfectly satisfied by a dead evaluator. The control
 * rule scores a constant unconditionally and is asserted FIRST, so that failure
 * mode fails on the control and names itself.
 *
 * **What keeps the refusal half from passing vacuously.** "Nothing was reached"
 * is also true of a deployment with no egress at all, so one of the probe's
 * three attempts is deliberately not an internet target: on the process strategy
 * an unisolated worker is a fork of the python-backend itself, so
 * `127.0.0.1:8000` is its own gunicorn — reachable with certainty, needing no
 * network beyond loopback. On the docker strategy that attempt is refused
 * trivially (nothing listens in the executor container) and the public ones
 * carry the claim instead, against a deployment that demonstrably has egress
 * because it calls LLM providers. Neither strategy leaves the claim resting on
 * an absence.
 *
 * Deterministic and free: a python rule runs the user's own code, so the score
 * is the literal one the metric constructed. No provider key, no LLM.
 */

/** The prefix the backend puts in front of whatever cause it managed to format. */
const CANT_BE_EVALUATED = "can't be evaluated: ";

/** Runner internals that must never reach a user-facing log line. */
const RUNNER_FRAMES = ['process_worker.py', 'scoring_runner.py'];

/**
 * The value the probe metric returns when every attempt was refused, and the
 * constant the control metric returns.
 *
 * `1`, not `1.0`: both metrics construct a Python `1.0`, which REST reports as
 * the number 1 and the panel renders as `1`.
 */
const SCORED = 1;

/** How long a rule may take to store a score, or to produce its first ERROR row. */
const SCORING_TIMEOUT_MS = 180_000;

test.describe(
  "Online Evaluation — the python executor's network sandbox",
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test.setTimeout(300_000);

    test(
      'a python metric reaches nothing over the network, while a control rule on the same trace still scores',
      {
        tag: [
          '@cap:online-evaluation.python-executor-isolation',
          '@cap:online-evaluation.python-rule-scores',
          '@cap:online-evaluation.scores-in-trace-panel',
        ],
      },
      async (
        { project, backendClient, testNamespace, automationRulesCleanup, page },
        testInfo,
      ) => {
        // Score names, not rule names: the engine stores a score under the name
        // its ScoreResult carries.
        const controlScore = `${testNamespace}-control`;
        const probeScore = `${testNamespace}-netprobe`;

        await test.step('Create a control rule and a network-reach probe rule', async () => {
          await backendClient.createAutomationRule({
            projectId: project.id,
            name: `${testNamespace}-rule-control`,
            samplingRate: 1,
            metric: buildConstantScoreMetric(controlScore),
            // A resolvable mapping is mandatory: the backend refuses to call the
            // evaluator with an empty argument map, which would fail the rule
            // before its metric ran — i.e. produce this spec's failure for an
            // unrelated reason.
            arguments: { output: 'output.output' },
          });
          await backendClient.createAutomationRule({
            projectId: project.id,
            name: `${testNamespace}-rule-netprobe`,
            samplingRate: 1,
            metric: buildNetworkReachProbeMetric(probeScore),
            arguments: { output: 'output.output' },
          });
        });

        const traceId = uuid7();
        await test.step('Seed one trace for both rules to judge', async () => {
          // One trace, both rules: identical input, so a difference between the
          // two scores is a difference between the metrics and nothing else.
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: traceId,
                name: `${testNamespace}-isolation`,
                input: { q: 'capital of France' },
                output: { output: 'Paris' },
              },
            ],
          });
        });

        await test.step('The control rule scored, so the executor is alive', async () => {
          // Asserted before the probe on purpose. Isolation that cannot be
          // applied starts no process worker at all, and then NOTHING scores —
          // a state in which every assertion about what the probe failed to
          // reach would hold while saying nothing about the network.
          const control = await backendClient.pollTraceForFeedbackScore(traceId, controlScore, {
            timeoutMs: SCORING_TIMEOUT_MS,
          });
          expect(
            control.value,
            'the control metric returns its constant for any input; a missing or different ' +
              'score means the python executor is not scoring at all, which is what an ' +
              'isolation that could not be applied looks like',
          ).toBe(SCORED);
        });

        const probe = await test.step('The probe rule scored too', async () => {
          return backendClient.pollTraceForFeedbackScore(traceId, probeScore, {
            timeoutMs: SCORING_TIMEOUT_MS,
            // The reason is written with the score rather than after it, so this
            // only guards against reading a half-written row.
            until: (score) => score.reason !== null,
          });
        });

        await test.step('And it reached nothing', async () => {
          expect(
            probe.reason,
            'the probe reports its per-attempt verdicts in the score reason; a null one ' +
              'leaves every assertion below with nothing to read',
          ).not.toBeNull();
          // The errno and exception type are strategy-specific, so they are
          // attached rather than asserted — and attached before the assertions,
          // so a failing run carries them too.
          await testInfo.attach('network-reach-verdicts', {
            body: probe.reason!,
            contentType: 'text/plain',
          });

          expect(
            probe.value,
            "the metric's own verdict: 1.0 only when every attempt was refused",
          ).toBe(SCORED);
          // The value alone cannot tell "every attempt was refused" from "the
          // metric made fewer attempts than it was built with", so the attempt
          // list and the refusal count are asserted directly.
          expect(
            probe.reason,
            'the reason must name the attempts this spec asked for, in order — a metric ' +
              'that quietly probed a shorter list would score 1.0 just the same',
          ).toContain(`attempted=${NETWORK_REACH_ATTEMPT_NAMES.join(',')}`);
          expect(probe.reason, 'every attempt refused, none merely skipped').toContain(
            `refused=${NETWORK_REACH_ATTEMPT_NAMES.length}/${NETWORK_REACH_ATTEMPT_NAMES.length}`,
          );
          // Named per attempt rather than once over the whole string, so a
          // failure says WHICH target the sandbox let through.
          for (const attempt of NETWORK_REACH_ATTEMPT_NAMES) {
            expect(
              probe.reason,
              `'${attempt}' got through — user code reached it from inside the sandbox`,
            ).not.toContain(`${attempt}=reached`);
          }
        });

        await test.step('The trace carries exactly those two scores', async () => {
          // Settled rather than read once: the two rules ride the same Redis
          // stream but are enqueued in parallel, so "the probe's score is here"
          // does not mean every rule has finished with this trace.
          const settled = await backendClient.waitForTraceScoresSettled(traceId, {
            timeoutMs: SCORING_TIMEOUT_MS,
            minScores: 2,
          });
          // Exhaustive rather than a lookup: finding both scores in the set
          // would pass just as well on an engine that also stored scores it
          // should not have.
          expect(
            settled.feedbackScores.map((score) => score.name).sort(),
            'exactly two rules, exactly two scores',
          ).toEqual([controlScore, probeScore].sort());
        });

        await test.step('And the trace panel renders both rows with the values the API stored', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          // Raised from the 15s default, which `waitForReady` documents as the
          // case for doing so: this project was created inside this test and
          // holds a single trace written moments ago, so the listing and its
          // count query are both racing the write path.
          await logs.waitForReady({ timeout: 60_000 });

          const panel = await logs.openTraceById(traceId);
          await panel.waitForFullyLoaded();
          await panel.openFeedbackScoresTab();

          // The tab renders a second table for span scores when the trace has
          // any. This trace has no spans, so one table means the row count below
          // really is the trace's own scores.
          await expect(
            panel.feedbackScoreTables(),
            'a spanless trace renders only the Trace scores table',
          ).toHaveCount(1);
          await expect(
            panel.feedbackScoreRows(),
            'two stored scores, two rows — the panel is where a user reads them',
          ).toHaveCount(2);

          for (const scoreName of [controlScore, probeScore]) {
            await expect(panel.feedbackScoreRowByName(scoreName)).toHaveCount(1);
            await expect(
              panel.feedbackScoreValueCell(scoreName),
              `the panel renders the value the API resolved for '${scoreName}', not a placeholder`,
            ).toHaveText(String(SCORED));
          }
        });
      },
    );

    test(
      'a metric that does not expect the refusal fails loudly: the ERROR row names its own frame, and no score is stored',
      {
        tag: [
          '@cap:online-evaluation.python-executor-isolation',
          '@cap:online-evaluation.automation-logs',
        ],
      },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const controlScore = `${testNamespace}-control`;
        const unguardedScore = `${testNamespace}-unguarded`;

        const rules = await test.step(
          'Create a control rule and one whose metric connects without catching',
          async () => ({
            control: await backendClient.createAutomationRule({
              projectId: project.id,
              name: `${testNamespace}-rule-control`,
              samplingRate: 1,
              metric: buildConstantScoreMetric(controlScore),
              arguments: { output: 'output.output' },
            }),
            unguarded: await backendClient.createAutomationRule({
              projectId: project.id,
              name: `${testNamespace}-rule-unguarded`,
              samplingRate: 1,
              metric: buildUnguardedNetworkMetric(unguardedScore),
              arguments: { output: 'output.output' },
            }),
          }),
        );

        const traceId = uuid7();
        await test.step('Seed one trace for both rules to judge', async () => {
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: traceId,
                name: `${testNamespace}-unguarded`,
                input: { q: 'capital of France' },
                output: { output: 'Paris' },
              },
            ],
          });
        });

        const errorRows = await test.step(
          "The unguarded rule logged an ERROR row rather than nothing",
          async () => {
            // The regression this test exists for is SILENCE: a refusal that
            // produced no score and no log line is indistinguishable from a rule
            // that was never invoked, which is how a user who upgrades and loses
            // a metric to the sandbox would have no way to find out why.
            let rows: AutomationRuleLogRef[] = [];
            await expect
              .poll(
                async () => {
                  rows = await backendClient.getAutomationRuleLogs(rules.unguarded);
                  return rows.filter((row) => row.level === 'ERROR').length;
                },
                {
                  message:
                    "ERROR rows on the unguarded rule's log stream — a refusal the user is " +
                    'never told about is the whole failure this asserts against',
                  timeout: SCORING_TIMEOUT_MS,
                  intervals: [2_000, 5_000],
                },
              )
              .toBeGreaterThan(0);
            return rows.filter((row) => row.level === 'ERROR');
          },
        );

        await test.step("It names the metric's own frame, with a non-empty cause", () => {
          const joined = errorRows.map((row) => row.message).join('\n');
          // The metric arrives as a string, so its own frame is
          // `File "<string>"`, and the refusal leaves `score()` — so both the
          // file and the function are named. Deliberately NOT the exception
          // type: the process strategy raises PermissionError from the blocked
          // socket syscall and the docker strategy an OSError from a connect
          // with no route, and pinning either would fail on the other
          // deployment for the one reason this spec is not about.
          expect(
            joined,
            "the row must carry the metric's own traceback frame",
          ).toContain('File "<string>"');
          expect(
            joined,
            'the frame must name score(), so a user can see the refusal came out of their code',
          ).toContain('in score');

          // An ERROR row whose cause is empty after the prefix reads exactly
          // like one that named it, at a glance, and tells a user nothing.
          const withPrefix = errorRows.filter((row) => row.message.includes(CANT_BE_EVALUATED));
          expect(
            withPrefix.length,
            `at least one ERROR row must carry the "${CANT_BE_EVALUATED}" prefix`,
          ).toBeGreaterThan(0);
          const emptyCauses = withPrefix
            .map((row) =>
              row.message
                .slice(row.message.indexOf(CANT_BE_EVALUATED) + CANT_BE_EVALUATED.length)
                .trim(),
            )
            .filter((cause) => cause === '');
          expect(
            emptyCauses,
            `ERROR rows whose cause is empty after "${CANT_BE_EVALUATED}"`,
          ).toEqual([]);

          for (const frame of RUNNER_FRAMES) {
            expect(
              errorRows
                .filter((row) => row.message.includes(frame))
                .map((row) => row.message.slice(0, 120)),
              `ERROR rows leaking the runner internal ${frame}`,
            ).toEqual([]);
          }
        });

        await test.step('The refusal was not stored as a score', async () => {
          // The control rule is the bystander that makes this absence mean
          // something: the trace demonstrably received a score, so "no score
          // from the unguarded rule" is a decision the engine made about that
          // rule and not the state of a trace nothing has scored yet.
          const settled = await backendClient.waitForTraceScoresSettled(traceId, {
            timeoutMs: SCORING_TIMEOUT_MS,
            minScores: 1,
          });
          expect(
            settled.feedbackScores.map((score) => score.name),
            'the control rule scored and the refused one stored nothing',
          ).toEqual([controlScore]);
          expect(
            settled.feedbackScores.map((score) => score.value),
            'and the control score is its constant, not a value the refusal reached',
          ).toEqual([SCORED]);
        });

        await test.step('The automation-logs page renders the same ERROR row', async () => {
          // The page is where a user actually reads this, and it renders the
          // message through a truncating cell that has to be expanded — a
          // different path from the API read above.
          const logs = new AutomationLogsPage(page);
          await logs.goto(rules.unguarded);
          await logs.waitForReady();

          const errorRow = logs.rowsAtLevel('ERROR').first();
          await expect(errorRow, 'an ERROR row on the automation-logs page').toBeVisible();
          await logs.expandRow(errorRow);
          await expect(
            errorRow,
            'the rendered ERROR row must carry the same cause and user frame as the API',
          ).toContainText(CANT_BE_EVALUATED);
          await expect(errorRow, 'and name the frame it came from').toContainText('in score');
        });
      },
    );
  },
);
