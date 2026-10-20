import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { buildDependencyImportMetric } from '@e2e/core/metrics';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A python metric still scores when it imports the executor's own third-party
 * dependency set.
 *
 * Seven specs already carry `@cap:online-evaluation.python-rule-scores`, and
 * between them they ask whether the evaluator RAN and what it CONCLUDED — a
 * deterministic 3x1.0/2x0.0, sub-path mappings, per-score usability, thread and
 * span scope. None of them asks whether the interpreter it ran in can import
 * its own dependencies, because every metric they send imports nothing but
 * `opik`'s own lightweight stubs. This spec is the second test for a covered
 * capability on purpose, and that gap is why.
 *
 * It is not a hypothetical gap. The executor image is loaded from a tarball
 * baked into the python-backend at build time, so a dependency resolution that
 * went wrong at BUILD time is only observable at SCORING time — and it is
 * observable as nothing at all. An `ImportError` at module scope means the rule
 * produces no score, so the trace panel renders one fewer row and no error
 * reaches the user. 2.2.107 is the release that nearly shipped exactly that:
 * its sandbox-executor and python-backend image builds both failed on the
 * aiohttp/yarl pin conflict that #9072 then fixed, and the two images never
 * published at all — so 2.2.108 is the first build of either since 2.2.106 and
 * ships eight pins no released image has ever run.
 *
 * `IMPORTED_MODULES` is that set, which is why it reads as an odd grab-bag:
 * it is not a sample of interesting packages but the actual first-time-shipped
 * pin list, so the thing under assertion is the thing this release changed.
 *
 * **Deployment-agnostic by construction, and that is the point.** Which
 * interpreter runs a python metric is a deployment decision
 * (`PYTHON_CODE_EXECUTOR_STRATEGY`): `process` forks the python-backend's own,
 * `docker` hands it to the sandbox-executor image. Compose and Helm both
 * default to `process`; Opik Cloud runs `docker`. This spec needs no
 * configuration to tell them apart because it asserts the property both must
 * have — the metric's imports resolve wherever it ran — so on a `process`
 * deployment it pins the python-backend's dependency set and on a `docker` one
 * the sandbox executor's. Nothing in the estate covered the latter at all; the
 * release report lists it under "Not measured here".
 *
 * **No version number is asserted.** The metric reports a fingerprint of the
 * runtime that ran it, which is the only thing that can say WHICH image
 * answered, and it is attached as a diagnostic rather than asserted — every
 * field in it moves on a routine pin bump. Asserting it would make this spec
 * fail for the one reason it is not about. See
 * `buildDependencyImportMetric`, which also explains why the real `opik`
 * package is deliberately not in the import set.
 *
 * Deterministic and LLM-free: a python rule runs the user's own code, so the
 * score is the literal one the metric constructed.
 */

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

/**
 * The engine's last line on the happy path, and what this spec waits for.
 *
 * Waiting on it rather than on a quiet period is what makes the "no ERROR rows"
 * assertion below mean something: an empty log stream has no ERROR rows either,
 * and a rule that was never invoked would satisfy a bare `toHaveLength(0)`
 * perfectly.
 */
const SCORES_STORED_LINE = 'stored successfully';

/**
 * The modules the metric imports at module scope — the subject of the spec.
 *
 * These are the eight pins the sandbox-executor image ships for the first time
 * at 2.2.108, named one by one rather than left to a transitive import, so a
 * conflict in any single one of them fails this spec by name:
 * `aiohttp` 3.14.3→3.14.4 and the `yarl` 1.23.0→1.25.1 that #9072 raised to
 * satisfy it, `multidict`, `idna` 3.11→3.15, `urllib3` 2.7.0→2.8.0, `fsspec`,
 * `pydantic_settings` and `pytest`.
 *
 * **`litellm` is deliberately absent, and the gap is real rather than an
 * oversight.** It is in the image and the exploration's fingerprint named its
 * version, but a module-scope `import litellm` cannot complete inside
 * `PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS` — measured on staging it produces
 * no score, and the rule's log stream stops after "Sending … to Python
 * evaluator" with no error row at all. So an importability claim about
 * `litellm` is not something a deterministic spec can make here; what it would
 * actually assert is the timeout. Its version is still reported in the
 * fingerprint, which costs nothing because that is read from distribution
 * metadata rather than by importing.
 *
 * Every addition to this list is spent from the same 3s budget, so it is a
 * list to prune rather than extend.
 */
const IMPORTED_MODULES = [
  'aiohttp',
  'yarl',
  'multidict',
  'idna',
  'urllib3',
  'fsspec',
  'pydantic_settings',
  'pytest',
] as const;

/**
 * The constant the metric scores.
 *
 * Neither 0 nor 1 on purpose: both are values a score could arrive at for
 * reasons that have nothing to do with this metric running, and the whole claim
 * here is that this metric's own body executed.
 */
const SCORE_VALUE = 0.5;

test.describe(
  "Online Evaluation — the python executor's own dependency set",
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      "A metric importing the executor's first-time-shipped pins at module scope scores, reports no error, and its score reaches the trace panel",
      {
        tag: [
          '@cap:online-evaluation.python-rule-scores',
          '@cap:online-evaluation.scores-in-trace-panel',
        ],
      },
      async (
        { project, sdkClient, backendClient, testNamespace, automationRulesCleanup, page },
        testInfo,
      ) => {
        test.setTimeout(240_000);

        // The score name, not the rule name: the engine stores a score under the
        // name its ScoreResult carries.
        const scoreName = `${testNamespace}-depset`;

        const ruleId = await test.step(
          'Create a 100%-sampling python rule whose metric imports the dependency set',
          async () => {
            return backendClient.createAutomationRule({
              projectId: project.id,
              name: `${testNamespace}-rule-depset`,
              samplingRate: 1,
              metric: buildDependencyImportMetric(scoreName, SCORE_VALUE, IMPORTED_MODULES),
              // A resolvable mapping is mandatory: the backend refuses to call
              // the evaluator with an empty argument map, which would fail the
              // rule before its metric ever ran — i.e. produce this spec's
              // failure for an unrelated reason.
              arguments: { output: 'output.output' },
            });
          },
        );

        const trace = await test.step('Seed one trace for the rule to judge', async () => {
          return sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-trace`,
            input: 'whatever',
            output: 'seed output',
          });
        });

        const score = await test.step(
          'The metric imported its dependency set and scored',
          async () => {
            // A score of this name existing at all is the assertion: the imports
            // sit at module scope, so a broken pin aborts the module before the
            // metric class exists and nothing is ever stored.
            const stored = await backendClient.pollTraceForFeedbackScore(trace.id, scoreName, {
              timeoutMs: 180_000,
              // The fingerprint is written with the score, not after it, so this
              // only guards against reading a half-written row.
              until: (s) => s.reason !== null,
            });
            expect(
              stored.value,
              'the constant the metric constructed — a different number means the metric ran on something other than its own body',
            ).toBe(SCORE_VALUE);
            return stored;
          },
        );

        await test.step('Attach the runtime fingerprint as a diagnostic', async () => {
          // Diagnostic, never an assertion: this is what tells a human reading a
          // future failure WHICH interpreter answered (the sandbox-executor and
          // python-backend images pin `opik`, `litellm` and `Flask`
          // differently), and every field in it moves on a routine pin bump.
          expect(
            score.reason,
            'the metric reports its runtime in the score reason; a null one means the fingerprint path broke, so a later failure here would be undiagnosable',
          ).not.toBeNull();
          await testInfo.attach('python-executor-fingerprint', {
            body: score.reason!,
            contentType: 'text/plain',
          });
          // Pins the diagnostic to the set this spec actually asked for, so
          // editing IMPORTED_MODULES cannot leave the fingerprint describing the
          // old one.
          expect(
            score.reason,
            'the fingerprint names the import set under test',
          ).toContain(`imported=${IMPORTED_MODULES.join(',')}`);
        });

        await test.step(
          'The rule called the evaluator once and reported no error',
          async () => {
            // An ImportError inside the executor is reported on this stream and
            // nowhere else — it never reaches the trace. A score can also arrive
            // AFTER a retry, which is a different and worse health claim than
            // arriving first time, so the call count is asserted too.
            let logs: AutomationRuleLogRef[] = [];
            await expect
              .poll(
                async () => {
                  logs = await backendClient.getAutomationRuleLogs(ruleId);
                  return logs.some((l) => l.message.includes(SCORES_STORED_LINE));
                },
                {
                  timeout: 180_000,
                  intervals: [2_000, 5_000],
                  message:
                    'the rule never reported storing its scores — a silent stream means it was never invoked, and every assertion below would hold vacuously',
                },
              )
              .toBe(true);

            expect(
              logs.filter((l) => l.level === 'ERROR').map((l) => l.message),
              'a metric whose imports resolved reports no error at all',
            ).toEqual([]);
            expect(
              logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
              'scored on the first call — a retry would mean the first attempt failed and this spec would otherwise read as clean',
            ).toHaveLength(1);
          },
        );

        await test.step('The trace carries that score and nothing else', async () => {
          // Exhaustive rather than a lookup: finding this score in the set would
          // pass just as well on an engine that also stored scores it should not
          // have.
          const detail = await backendClient.getTrace(trace.id);
          expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
          expect(
            detail!.feedbackScores.map((s) => s.name),
            'exactly the one rule, exactly one score',
          ).toEqual([scoreName]);
        });

        await test.step('And the trace panel renders the score', async () => {
          const logs = new LogsPage(page);
          await logs.goto(project.id);
          await logs.waitForReady();

          const panel = await logs.openTraceById(trace.id);
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
            'one stored score, one row — this is the row a failed import would silently remove',
          ).toHaveCount(1);

          await expect(panel.feedbackScoreRowByName(scoreName)).toHaveCount(1);
          await expect(
            panel.feedbackScoreValueCell(scoreName),
            'the panel renders the value the API resolved, not a placeholder',
          ).toHaveText(String(SCORE_VALUE));
        });
      },
    );
  },
);
