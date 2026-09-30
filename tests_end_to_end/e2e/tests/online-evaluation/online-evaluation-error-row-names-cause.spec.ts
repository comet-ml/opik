import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef } from '@e2e/core/backend';
import { buildRaisingMetric, buildBindFailureMetric } from '@e2e/core/metrics';
import { uuid7 } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';

/** The message the raising metric throws, quoted back in its ERROR row. */
const RAISE_MESSAGE = 'boom from inside score';

/** The prefix the backend puts in front of whatever cause it managed to format. */
const CANT_BE_EVALUATED = "can't be evaluated: ";

/** Runner internals that must never reach a user-facing log line. */
const RUNNER_FRAMES = ['process_worker.py', 'scoring_runner.py'];

/** How long a rule may take to produce its first ERROR row. */
const ERROR_ROW_TIMEOUT_MS = 120_000;

/**
 * What a python rule's ERROR row actually SAYS when the metric fails
 * (opik#8220, OPIK-8292).
 *
 * `online-evaluation-python-metric-errors.spec.ts` covers a neighbouring but
 * different question: how the backend CLASSIFIES a metric that exits 0 without
 * a result line. Nothing asserts the CONTENT of an ERROR row when the metric
 * itself raises — and the content is the whole value of the row. It is the only
 * thing a user has to work from, and a regression in it is silent: the row
 * still appears, in the right place, at the right level. It just stops naming
 * the cause.
 *
 * TWO FAILURES, because they produce different tracebacks and only one of them
 * was broken:
 *
 *   - **A metric that raises inside `score()`** has a real user frame, so
 *     there is something to format. This is the control: it proves the row
 *     names causes at all, so a failure on the other case is about the other
 *     case.
 *   - **A metric that fails while BINDING** — `score(self, output)` with no
 *     `**kwargs`, handed an extra mapped argument — fails before any user code
 *     runs. Its traceback has no user frame at all, which is the shortest one
 *     there is, and the old fixed-slice formatter returned an EMPTY STRING for
 *     it. The row then read "can't be evaluated: " and stopped.
 *
 * That empty-cause case is why the assertion is on what follows the prefix
 * being non-empty, and not only on the row existing.
 *
 * Both rules also have to keep runner internals OUT of the row. A traceback
 * sliced too generously leaks `process_worker.py` / `scoring_runner.py` frames,
 * which are noise to the person reading it and an implementation detail to
 * everyone else.
 *
 * Deterministic and free: no provider key, no LLM, one REST-seeded trace.
 */
test.describe(
  'Online Evaluation — what an ERROR row names',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test.setTimeout(300_000);

    test(
      'an ERROR row names the exception and its message, with no empty cause and no runner frames',
      { tag: ['@cap:online-evaluation.automation-logs'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const raisingRuleName = `${testNamespace}-raising`;
        const bindingRuleName = `${testNamespace}-binding`;

        const rules = await test.step('Create one raising rule and one that fails to bind', async () => ({
          raising: await backendClient.createAutomationRule({
            projectId: project.id,
            name: raisingRuleName,
            samplingRate: 1,
            metric: buildRaisingMetric(raisingRuleName, RAISE_MESSAGE),
            arguments: { output: 'output.output' },
          }),
          binding: await backendClient.createAutomationRule({
            projectId: project.id,
            name: bindingRuleName,
            samplingRate: 1,
            metric: buildBindFailureMetric(bindingRuleName),
            // `unmapped_extra` resolves to a real field, so it IS passed — and
            // the metric's `score()` takes no `**kwargs`, so the call fails
            // while binding. A mapping that did not resolve would simply be
            // dropped and the metric would succeed.
            arguments: { output: 'output.output', unmapped_extra: 'input.q' },
          }),
        }));

        const traceId = uuid7();
        await test.step('Seed one trace carrying both mapped fields', async () => {
          await backendClient.createTracesBatch({
            projectName: project.name,
            traces: [
              {
                id: traceId,
                name: `${testNamespace}-error-rows`,
                input: { q: 'capital of France' },
                output: { output: 'Paris' },
              },
            ],
          });
        });

        /** Poll a rule's log stream until it has an ERROR row, then return them all. */
        const errorRows = async (ruleId: string, label: string): Promise<AutomationRuleLogRef[]> => {
          let rows: AutomationRuleLogRef[] = [];
          await expect
            .poll(
              async () => {
                rows = await backendClient.getAutomationRuleLogs(ruleId);
                return rows.filter((row) => row.level === 'ERROR').length;
              },
              {
                message: `ERROR rows on the ${label} rule's log stream`,
                timeout: ERROR_ROW_TIMEOUT_MS,
              },
            )
            .toBeGreaterThan(0);
          return rows.filter((row) => row.level === 'ERROR');
        };

        const raisingErrors = await test.step('The raising rule logged an ERROR row', () =>
          errorRows(rules.raising, 'raising'),
        );

        await test.step('It names the exception type and the message', () => {
          const joined = raisingErrors.map((row) => row.message).join('\n');
          expect(
            joined,
            'an ERROR row for a metric that raised must name the exception and its message',
          ).toContain(`ValueError: ${RAISE_MESSAGE}`);
          // The compiled-source frame: the metric arrives as a string, so its
          // own frame is `File "<string>"`. Its presence is what says a real
          // traceback was formatted rather than just the exception line.
          expect(
            joined,
            'the row must carry the metric\'s own traceback frame',
          ).toContain('File "<string>"');
        });

        const bindingErrors = await test.step('The binding rule logged an ERROR row', () =>
          errorRows(rules.binding, 'binding'),
        );

        await test.step('It names the offending argument, and its cause is not empty', () => {
          const joined = bindingErrors.map((row) => row.message).join('\n');
          expect(
            joined,
            'a bind failure must name the argument that could not be bound',
          ).toContain("unexpected keyword argument 'unmapped_extra'");

          // The regression this test exists for. A bind failure has no user
          // frame, and the old fixed-slice formatter returned "" for it — so
          // the row read exactly "can't be evaluated: " and stopped. Asserting
          // the row merely CONTAINS the argument name would not catch a
          // formatter that put the cause somewhere else and left this empty,
          // so the text after the prefix is checked directly.
          const withPrefix = bindingErrors.filter((row) => row.message.includes(CANT_BE_EVALUATED));
          expect(
            withPrefix.length,
            `at least one ERROR row must carry the "${CANT_BE_EVALUATED}" prefix`,
          ).toBeGreaterThan(0);
          const emptyCauses = withPrefix
            .map((row) => row.message.slice(row.message.indexOf(CANT_BE_EVALUATED) + CANT_BE_EVALUATED.length).trim())
            .filter((cause) => cause === '');
          expect(
            emptyCauses,
            `ERROR rows whose cause is empty after "${CANT_BE_EVALUATED}" — the shortest ` +
              'traceback there is, and the one the old fixed-slice formatter dropped entirely',
          ).toEqual([]);
        });

        await test.step('Neither row leaks the runner\'s own frames', () => {
          // Checked over BOTH rules' rows as one collection: the slicing is
          // shared, so a change that leaked internals would leak them
          // everywhere, and naming which rule leaked is more useful than
          // failing on the first.
          const all = [...raisingErrors, ...bindingErrors];
          for (const frame of RUNNER_FRAMES) {
            expect(
              all.filter((row) => row.message.includes(frame)).map((row) => row.message.slice(0, 120)),
              `ERROR rows leaking the runner internal ${frame}`,
            ).toEqual([]);
          }
        });

        await test.step('The automation-logs page renders the same exception line', async () => {
          // The page is where a user actually reads this, and it renders the
          // message through a truncating cell that has to be expanded — a
          // different path from the API read above.
          const logs = new AutomationLogsPage(page);
          await logs.goto(rules.raising);
          await logs.waitForReady();

          const errorRow = logs.rowsAtLevel('ERROR').first();
          await expect(errorRow, 'an ERROR row on the automation-logs page').toBeVisible();
          await logs.expandRow(errorRow);
          await expect(
            errorRow,
            'the rendered ERROR row must carry the same exception line as the API',
          ).toContainText(`ValueError: ${RAISE_MESSAGE}`);
        });
      },
    );
  },
);
