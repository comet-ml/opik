import { test, expect } from '@e2e/fixtures';
import type { AutomationRuleLogRef, BackendClient } from '@e2e/core/backend';
import {
  buildConstantScoreMetric,
  buildModuleLevelRaisingMetric,
  buildRaisingMetric,
  buildSilentMetric,
  buildRejectedArgumentMetric,
  buildUnparseableMetric,
} from '@e2e/core/metrics';

/**
 * The 500-era wording. The whole point of the fix is that a metric which
 * misbehaves gets told WHAT went wrong instead of this.
 */
const OPAQUE_FAILURE_MESSAGE = 'An unexpected error occurred';

/** Emitted once per evaluator call, immediately before the HTTP request. */
const EVALUATOR_CALL_LINE = 'to Python evaluator';

/**
 * Block until a rule has reported a failure, and return its whole log stream.
 *
 * Polling for the ERROR line rather than for a fixed delay is what makes these
 * tests deterministic: the failure line is written last in the scorer's chain,
 * so its arrival is what makes the stream complete for that rule.
 */
async function waitForRuleErrorLogs(
  backendClient: BackendClient,
  ruleId: string,
  ruleName: string,
): Promise<AutomationRuleLogRef[]> {
  let logs: AutomationRuleLogRef[] = [];
  await expect
    .poll(
      async () => {
        logs = await backendClient.getAutomationRuleLogs(ruleId);
        return logs.some((l) => l.level === 'ERROR');
      },
      {
        timeout: 180_000,
        intervals: [2_000, 5_000],
        message: `rule '${ruleName}' never reported a failure — its metric cannot succeed, so a silent stream means the rule was never invoked`,
      },
    )
    .toBe(true);
  return logs;
}

test.describe('Online Evaluation — python metric failure classification', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A python metric that exits 0 without a result line fails with a 400 naming the cause, and is not re-attempted', { tag: ['@cap:online-evaluation.python-rule-scores'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // No page: the subject is how the backend classifies an evaluator outcome,
    // and the rule log stream is where that classification is stated. Driving a
    // browser to read the same lines second-hand would be slower and would add
    // a rendering failure mode to an assertion that has nothing to do with
    // rendering.

    const controlRuleName = `${testNamespace}-control`;
    const silentRuleName = `${testNamespace}-silent`;
    const unparseableRuleName = `${testNamespace}-unparseable`;

    const rules = await test.step('Create one healthy and two failing python rules', async () => {
      const create = (name: string, metric: string) =>
        backendClient.createAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          metric,
          // A resolvable mapping is mandatory: the backend refuses to call the
          // evaluator with an empty argument map, which would fail these rules
          // before their metric ever ran.
          arguments: { output: 'output.output' },
        });
      return {
        control: await create(controlRuleName, buildConstantScoreMetric(controlRuleName)),
        silent: await create(silentRuleName, buildSilentMetric(silentRuleName)),
        unparseable: await create(
          unparseableRuleName,
          buildUnparseableMetric(unparseableRuleName),
        ),
      };
    });

    const trace = await test.step('Seed one trace for all three rules to judge', async () => {
      // One trace, three rules: the control and the failures are then provably
      // judging identical input, so a difference in outcome is a difference in
      // the metric and not in what it was given.
      return sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'whatever',
        output: 'seed output',
      });
    });

    await test.step('Control: the healthy rule scored the trace', async () => {
      // Establishes that the python evaluator is reachable and this project's
      // rules are firing. Without it, two rules that logged nothing would be
      // indistinguishable from two rules that were never invoked.
      const score = await backendClient.pollTraceForFeedbackScore(trace.id, controlRuleName, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the control metric returns a constant 1.0').toBe(1.0);
    });

    const waitForRuleLogs = (ruleId: string, ruleName: string) =>
      waitForRuleErrorLogs(backendClient, ruleId, ruleName);

    const silentLogs = await test.step(
      'The no-output rule reports a 400 that names the missing result line',
      async () => {
        const logs = await waitForRuleLogs(rules.silent, silentRuleName);
        const errors = logs.filter((l) => l.level === 'ERROR');
        expect(
          errors.map((l) => l.message).join('\n---\n'),
          'the failure must state the classified cause, at 400 rather than 500',
        ).toContain('400 Bad Request: Execution failed: the metric produced no output');
        return logs;
      },
    );

    const unparseableLogs = await test.step(
      'The non-JSON-output rule reports a 400 that names the unparseable result',
      async () => {
        const logs = await waitForRuleLogs(rules.unparseable, unparseableRuleName);
        const errors = logs.filter((l) => l.level === 'ERROR');
        expect(
          errors.map((l) => l.message).join('\n---\n'),
          'a last line that is not the result JSON is the client metric being wrong, not the server',
        ).toContain('400 Bad Request: Execution failed: the metric returned an unparseable result');
        return logs;
      },
    );

    await test.step('Neither failure fell back to the opaque 500 wording', async () => {
      for (const [name, logs] of [
        [silentRuleName, silentLogs],
        [unparseableRuleName, unparseableLogs],
      ] as const) {
        for (const line of logs) {
          expect(
            line.message,
            `rule '${name}' must not report the pre-classification catch-all`,
          ).not.toContain(OPAQUE_FAILURE_MESSAGE);
        }
      }
    });

    await test.step('Each failing rule called the evaluator once and reported once', async () => {
      // A 400 is a terminal answer: the caller must not re-run the metric
      // hoping for a different one. Both counts are asserted because they fail
      // differently — a re-queued message repeats the call line, while a retry
      // loop that eventually gives up repeats only the error line.
      for (const [name, logs] of [
        [silentRuleName, silentLogs],
        [unparseableRuleName, unparseableLogs],
      ] as const) {
        expect(
          logs.filter((l) => l.message.includes(EVALUATOR_CALL_LINE)),
          `rule '${name}' must send the trace to the evaluator exactly once`,
        ).toHaveLength(1);
        expect(
          logs.filter((l) => l.level === 'ERROR'),
          `rule '${name}' must report its terminal failure exactly once`,
        ).toHaveLength(1);
      }
    });

    await test.step('A failed evaluation writes no score', async () => {
      // The complement of the control. A rule that failed but still stored
      // something would be worse than one that failed loudly.
      const detail = await backendClient.getTrace(trace.id);
      expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => s.name).sort(),
        'only the control rule may have written a score',
      ).toEqual([controlRuleName]);
    });
  });

  test('A metric failure names the exception type and message, and keeps the user frame when there is one', { tag: ['@cap:online-evaluation.python-rule-scores'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    // The other half of what a failing rule owes a user, and the half the two
    // cases above cannot reach: they assert the backend's own CLASSIFICATION
    // strings ("the metric produced no output"), and neither metric ever raises,
    // so no formatted stacktrace is produced by either.
    //
    // The runner used to strip its own frames with a fixed
    // `traceback.format_exc().splitlines()[3:]`. A call-site binding failure
    // raises before any user frame exists, so nothing pads the traceback: it is
    // exactly the three lines the slice removed, and the reported cause came
    // back EMPTY. `user_facing_stacktrace` walks frames instead. Asserted
    // against the deployment's own runner rather than a named file — both
    // executor strategies carry the same helper, so these assertions hold on
    // either. The three rules below cover the zero-frame case, the other call
    // site (module-level `exec`), and the ordinary with-a-frame case, whose
    // frame the same slice discarded even where the message survived.
    //
    // Pure string assertions over the rule log stream: no timing, no LLM, no UI.

    const controlRuleName = `${testNamespace}-trace-control`;
    const rejectedRuleName = `${testNamespace}-rejected-arg`;
    const moduleRaiseRuleName = `${testNamespace}-module-raise`;
    const scoreRaiseRuleName = `${testNamespace}-score-raise`;

    const SCORE_RAISE_MESSAGE = 'deliberate failure inside score';
    const MODULE_RAISE_MESSAGE = 'deliberate failure at module level';

    const rules = await test.step('Create one healthy and three failing python rules', async () => {
      const create = (name: string, metric: string, args: Record<string, string>) =>
        backendClient.createAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          metric,
          arguments: args,
        });
      return {
        control: await create(controlRuleName, buildConstantScoreMetric(controlRuleName), {
          output: 'output.output',
        }),
        // The mapping names `reference`; the metric's `score()` does not accept
        // it and declares no `**kwargs`, so the call itself raises before any
        // user frame exists. It has to fail this way round: a parameter the
        // mapping omits is filled with `None` before dispatch now, which is the
        // fix under test. What `reference` points at is irrelevant — only that it
        // RESOLVES, so the engine actually passes the key; `output.output` is
        // already proven resolvable by the control rule above.
        rejected: await create(
          rejectedRuleName,
          buildRejectedArgumentMetric(rejectedRuleName),
          { output: 'output.output', reference: 'output.output' },
        ),
        moduleRaise: await create(
          moduleRaiseRuleName,
          buildModuleLevelRaisingMetric(moduleRaiseRuleName, MODULE_RAISE_MESSAGE),
          { output: 'output.output' },
        ),
        scoreRaise: await create(
          scoreRaiseRuleName,
          buildRaisingMetric(scoreRaiseRuleName, SCORE_RAISE_MESSAGE),
          { output: 'output.output' },
        ),
      };
    });

    const trace = await test.step('Seed one trace for all four rules to judge', async () => {
      return sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: 'whatever',
        output: 'seed output',
      });
    });

    await test.step('Control: the healthy rule scored the trace', async () => {
      // Without it, three rules that logged nothing would be indistinguishable
      // from three rules that were never invoked.
      const score = await backendClient.pollTraceForFeedbackScore(trace.id, controlRuleName, {
        timeoutMs: 180_000,
      });
      expect(score.value, 'the control metric returns a constant 1.0').toBe(1.0);
    });

    /**
     * The reported cause — everything the backend put AFTER its own prefix.
     *
     * Asserted as a slice rather than with a `toContain` over the whole message
     * because emptiness is the regression: a message that is nothing but the
     * prefix contains every prefix substring you could search it for, and would
     * pass a naive `toContain('can\'t be evaluated:')` while telling the user
     * precisely nothing.
     */
    const causeAfter = (message: string, prefix: string): string => {
      const at = message.indexOf(prefix);
      expect(at, `the failure must be reported under the '${prefix}' prefix`).toBeGreaterThanOrEqual(
        0,
      );
      return message.slice(at + prefix.length).trim();
    };

    const singleError = (logs: AutomationRuleLogRef[], ruleName: string): string => {
      const errors = logs.filter((l) => l.level === 'ERROR');
      expect(errors, `rule '${ruleName}' must report its failure exactly once`).toHaveLength(1);
      return errors[0].message;
    };

    await test.step(
      'A binding failure with no user frame still reports the exception type and message',
      async () => {
        const logs = await waitForRuleErrorLogs(backendClient, rules.rejected, rejectedRuleName);
        const message = singleError(logs, rejectedRuleName);
        const cause = causeAfter(message, "can't be evaluated:");

        expect(cause, 'the reported cause must not be empty — the whole regression').not.toBe('');
        expect(cause, 'the exception type must survive a zero-frame traceback').toContain(
          'TypeError',
        );
        expect(cause, 'and so must its message').toContain(
          "unexpected keyword argument 'reference'",
        );
      },
    );

    await test.step(
      'A module-level failure reports under the invalid-code prefix, with type and message',
      async () => {
        // The other `user_facing_stacktrace` call site: the runner's `exec()` of
        // the submitted source, which fails before any class exists to
        // instantiate. A fix covering only the scoring call site would leave
        // this one blank.
        const logs = await waitForRuleErrorLogs(
          backendClient,
          rules.moduleRaise,
          moduleRaiseRuleName,
        );
        const message = singleError(logs, moduleRaiseRuleName);
        const cause = causeAfter(message, "Field 'code' contains invalid Python code:");

        expect(cause, 'the reported cause must not be empty').not.toBe('');
        expect(cause).toContain('RuntimeError');
        expect(cause).toContain(MODULE_RAISE_MESSAGE);
      },
    );

    await test.step(
      'A failure inside score() reports the message AND the user frame',
      async () => {
        const logs = await waitForRuleErrorLogs(backendClient, rules.scoreRaise, scoreRaiseRuleName);
        const message = singleError(logs, scoreRaiseRuleName);
        const cause = causeAfter(message, "can't be evaluated:");

        expect(cause).toContain('ValueError');
        expect(cause).toContain(SCORE_RAISE_MESSAGE);
        // The frame is the part the old fixed slice discarded even when the
        // message survived: without it a user is told what broke but not where
        // in their own metric it broke.
        expect(
          cause,
          'a failure raised inside the user\'s own code must name the frame it came from',
        ).toContain('File "<string>"');
        // Neither runner may appear: the sandbox strategy runs `scoring_runner`
        // and the process strategy `process_worker`, and the frame-dropping is
        // the same guarantee in both.
        for (const runner of ['scoring_runner', 'process_worker']) {
          expect(cause, `the runner must still hide its own ${runner} frame`).not.toContain(runner);
        }
      },
    );

    await test.step('None of the three failures fell back to the opaque 500 wording', async () => {
      for (const [name, ruleId] of [
        [rejectedRuleName, rules.rejected],
        [moduleRaiseRuleName, rules.moduleRaise],
        [scoreRaiseRuleName, rules.scoreRaise],
      ] as const) {
        const logs = await backendClient.getAutomationRuleLogs(ruleId);
        for (const line of logs) {
          expect(
            line.message,
            `rule '${name}' must not report the pre-classification catch-all`,
          ).not.toContain(OPAQUE_FAILURE_MESSAGE);
        }
      }
    });

    await test.step('No failing rule wrote a feedback score', async () => {
      const detail = await backendClient.getTrace(trace.id);
      expect(detail, 'the seeded trace must still exist to be asserted about').not.toBeNull();
      expect(
        detail!.feedbackScores.map((s) => s.name).sort(),
        'only the control rule may have written a score',
      ).toEqual([controlRuleName]);
    });
  });
});
