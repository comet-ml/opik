import { test, expect } from '@e2e/fixtures';
import type { BackendClient } from '@e2e/core/backend';
import { AutomationLogsPage } from '@e2e/pom/automation-logs.page';

/**
 * A model whose provider cannot call tools.
 *
 * `custom-llm/` resolves to `LlmProvider.CUSTOM_LLM` on the prefix alone, so
 * the routing decision below is reached on any install — including one with no
 * provider keys at all. The rest of the name is arbitrary and never dialled:
 * routing happens before the LLM call, which is the whole point of asserting on
 * it rather than on a score.
 */
const NON_TOOL_CALLING_MODEL = 'custom-llm/fake-vllm/no-tools-model';

/**
 * A model whose provider does support tool calling, for the size-driven branch.
 *
 * The two routing lines this spec asserts are both written before the request
 * leaves the backend, so the assertions hold identically whether or not the
 * target workspace has an OpenAI key configured.
 */
const TOOL_CALLING_MODEL = 'gpt-4o';

/** `OnlineScoringConfig.agenticToolsThresholdTokens`, quoted verbatim in the log line. */
const AGENTIC_TOOLS_THRESHOLD_TOKENS = 50_000;

/**
 * Output size for the trace that must cross the threshold.
 *
 * The backend estimates tokens at 4 chars each, so the threshold is ~200 000
 * characters. 270 000 clears it by ~35% — enough that a change to the estimator
 * ratio does not silently drop this trace back under the line, and small enough
 * to seed in one request.
 */
const OVERSIZE_OUTPUT_CHARS = 270_000;

/** Escapes a value for embedding in a `RegExp` built from a log message. */
const rx = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Block until a rule's log stream carries a line matching `pattern`.
 *
 * The automation-logs page fetches once on load and has no auto-refresh, so
 * navigating before the engine has written would land on the empty state and
 * stay there. This gate is a wait, not the assertion: what the page renders is
 * asserted afterwards, against the page.
 */
async function waitForRuleLog(
  backendClient: BackendClient,
  ruleId: string,
  ruleName: string,
  pattern: RegExp,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const logs = await backendClient.getAutomationRuleLogs(ruleId);
        return logs.some((line) => pattern.test(line.message));
      },
      {
        timeout: 180_000,
        intervals: [2_000, 5_000],
        message:
          `rule '${ruleName}' never logged a line matching ${pattern} — the engine ` +
          `did not reach its routing decision, so there is nothing for the ` +
          `automation-logs page to render`,
      },
    )
    .toBe(true);
}

test.describe('Online Evaluation — automation logs', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('The automation-logs page reports a fallback to the inline path when the rule\'s provider cannot call tools', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // Two rules, each waiting on its own 180s log gate, then two page loads.
    test.setTimeout(300_000);

    const traceRuleName = `${testNamespace}-trace-nontool`;
    const spanRuleName = `${testNamespace}-span-nontool`;

    const rules = await test.step(
      'Create a trace-scope and a span-scope judge on a non-tool-calling model',
      async () => {
        // Both prompts reference the reserved structure variable for their
        // scope — `{{trace}}` and `{{span}}` — which is what asks for the
        // tool-driven path and so what makes the provider's inability to call
        // tools an actionable misconfiguration worth telling the user about.
        const traceRule = await backendClient.createLlmJudgeRule({
          projectId: project.id,
          name: traceRuleName,
          scope: 'trace',
          samplingRate: 1,
          model: NON_TOOL_CALLING_MODEL,
          prompt: 'Given the trace {{trace}}, rate the answer {{output}} from 0 to 10.',
          variables: { trace: 'trace', output: 'output.answer' },
          scoreName: `${testNamespace}-trace-score`,
        });
        const spanRule = await backendClient.createLlmJudgeRule({
          projectId: project.id,
          name: spanRuleName,
          scope: 'span',
          samplingRate: 1,
          model: NON_TOOL_CALLING_MODEL,
          prompt: 'Given the span {{span}}, rate the completion {{output}} from 0 to 10.',
          variables: { span: 'span', output: 'output.completion' },
          scoreName: `${testNamespace}-span-score`,
        });
        return { traceRule, spanRule };
      },
    );

    const seeded = await test.step('Seed one trace carrying one span', async () => {
      const trace = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-trace`,
        input: { question: 'what is the capital of France?' },
        output: { answer: 'Paris' },
        // Online scoring is triggered by a trace ending; without a duration the
        // bridge leaves the trace open and no rule ever sees it.
        duration_seconds: 1,
        spans: [
          {
            name: `${testNamespace}-span`,
            type: 'llm',
            input: { prompt: 'what is the capital of France?' },
            output: { completion: 'Paris' },
          },
        ],
      });

      // Resolve the span id: the span-scope rule names the span it judged by
      // id, and asserting against that id is what ties the rendered line to
      // this test's own seed.
      const spans = await backendClient.listSpans({
        projectId: project.id,
        traceId: trace.id,
      });
      expect(
        spans.map((s) => s.name),
        'the span-scope rule has nothing to judge unless exactly this span exists',
      ).toEqual([`${testNamespace}-span`]);
      return { traceId: trace.id, spanId: spans[0].id };
    });

    await test.step('Wait for both rules to reach their routing decision', async () => {
      await Promise.all([
        waitForRuleLog(
          backendClient,
          rules.traceRule,
          traceRuleName,
          new RegExp(`Sending traceId '${rx(seeded.traceId)}' to LLM`),
        ),
        waitForRuleLog(
          backendClient,
          rules.spanRule,
          spanRuleName,
          new RegExp(`Sending spanId '${rx(seeded.spanId)}' to LLM`),
        ),
      ]);
    });

    const logsPage = new AutomationLogsPage(page);

    await test.step('The trace rule\'s page names the misconfiguration and the trace', async () => {
      await logsPage.goto(rules.traceRule);
      await logsPage.waitForReady();

      const warning = logsPage.logRow(
        new RegExp(
          `Trace '${rx(seeded.traceId)}' rule references \\{\\{trace\\}\\} but provider for ` +
            `model '${rx(NON_TOOL_CALLING_MODEL)}' does not support tool calling; falling back ` +
            `to inline path`,
        ),
      );
      // Exactly one: the engine evaluated this trace once, and a repeated line
      // would mean it was re-queued rather than that routing was decided.
      await expect(
        warning,
        'the fallback must be reported to the user, once, on the page they read',
      ).toHaveCount(1);
      await expect(logsPage.levelCell(warning)).toHaveText('WARN');
      await expect(
        logsPage.traceIdCell(warning),
        'the line must be attributed to the trace this test seeded',
      ).toHaveText(seeded.traceId);

      // The warning states an intention; this line states what was actually
      // sent. Asserting only the warning would pass even if the request had
      // gone out carrying tool specs anyway.
      await expect(
        logsPage.logRow(
          new RegExp(
            `Sending traceId '${rx(seeded.traceId)}' to LLM: ` +
              `model='${rx(NON_TOOL_CALLING_MODEL)}', messages=1, tools=0, toolsEnabled=false`,
          ),
        ),
        'the request must have gone out with no tools bound',
      ).toHaveCount(1);
    });

    await test.step('The span rule\'s page reports its own variant of the fallback', async () => {
      await logsPage.goto(rules.spanRule);
      await logsPage.waitForReady();

      // Span scope has its own wording — it loses attachments rather than the
      // trace skeleton — so this is a second branch, not the same line again.
      const warning = logsPage.logRow(
        new RegExp(
          `Span '${rx(seeded.spanId)}' rule references \\{\\{span\\}\\} but provider for ` +
            `model '${rx(NON_TOOL_CALLING_MODEL)}' does not support tool calling; falling back ` +
            `to inline path — the judge cannot load attachments`,
        ),
      );
      await expect(warning).toHaveCount(1);
      await expect(logsPage.levelCell(warning)).toHaveText('WARN');

      await expect(
        logsPage.logRow(
          new RegExp(
            `Sending spanId '${rx(seeded.spanId)}' to LLM: ` +
              `model='${rx(NON_TOOL_CALLING_MODEL)}', messages=1, tools=0, toolsEnabled=false`,
          ),
        ),
      ).toHaveCount(1);
    });
  });

  test('The automation-logs page reports the switch to agentic-tools mode for an over-threshold trace only', { tag: ['@cap:online-evaluation.automation-logs'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(300_000);

    const ruleName = `${testNamespace}-oversize`;

    const ruleId = await test.step(
      'Create one judge on a tool-calling model with a size-neutral prompt',
      async () => {
        // The prompt deliberately references only `{{output}}`: `{{trace}}`
        // would force the tool path on its own, and then the size branch this
        // test is about could not be told apart from the variable branch.
        return backendClient.createLlmJudgeRule({
          projectId: project.id,
          name: ruleName,
          scope: 'trace',
          samplingRate: 1,
          model: TOOL_CALLING_MODEL,
          prompt: 'Rate the answer {{output}} from 0 to 10.',
          variables: { output: 'output.output' },
          scoreName: `${testNamespace}-score`,
        });
      },
    );

    const seeded = await test.step(
      'Seed one over-threshold trace and one small trace, judged by the same rule',
      async () => {
        // One rule, two traces. The small trace is the control: same model,
        // same prompt, same rule — so the only thing that can explain a
        // different routing decision is the context size.
        const [big, small] = await Promise.all([
          sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-oversize-trace`,
            input: 'summarise this',
            output: 'x'.repeat(OVERSIZE_OUTPUT_CHARS),
          }),
          sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-small-trace`,
            input: 'summarise this',
            output: 'a short answer',
          }),
        ]);
        return { bigId: big.id, smallId: small.id };
      },
    );

    await test.step('Wait for the rule to route both traces', async () => {
      // Both, not just the big one. The absence assertion below is only sound
      // once the small trace has been routed too — and the switch line is
      // written before the "Sending" line, so a small trace that had been
      // switched would already have said so by the time this returns.
      await Promise.all([
        waitForRuleLog(
          backendClient,
          ruleId,
          ruleName,
          new RegExp(`Sending traceId '${rx(seeded.bigId)}' to LLM`),
        ),
        waitForRuleLog(
          backendClient,
          ruleId,
          ruleName,
          new RegExp(`Sending traceId '${rx(seeded.smallId)}' to LLM`),
        ),
      ]);
    });

    const logsPage = new AutomationLogsPage(page);
    await test.step('Open the rule\'s automation logs', async () => {
      await logsPage.goto(ruleId);
      await logsPage.waitForReady();
    });

    await test.step('The over-threshold trace switched to agentic-tools mode', async () => {
      const switched = logsPage.logRow(
        new RegExp(
          `Trace context exceeds '${AGENTIC_TOOLS_THRESHOLD_TOKENS}' tokens; switching to ` +
            `agentic-tools mode for traceId '${rx(seeded.bigId)}'`,
        ),
      );
      await expect(switched).toHaveCount(1);
      await expect(logsPage.levelCell(switched)).toHaveText('INFO');
      await expect(logsPage.traceIdCell(switched)).toHaveText(seeded.bigId);

      // `tools=` is asserted as "at least one" rather than a fixed count: the
      // number is the size of the tool registry, which grows as tools are
      // added, but "some tools were bound" is the claim that matters.
      await expect(
        logsPage.logRow(
          new RegExp(
            `Sending traceId '${rx(seeded.bigId)}' to LLM: model='${rx(TOOL_CALLING_MODEL)}', ` +
              `messages=1, tools=[1-9][0-9]*, toolsEnabled=true`,
          ),
        ),
        'the switch must actually have bound tool specs to the request',
      ).toHaveCount(1);
    });

    await test.step('The small trace, on the same rule, did not', async () => {
      await expect(
        logsPage.logRow(
          new RegExp(
            `Sending traceId '${rx(seeded.smallId)}' to LLM: model='${rx(TOOL_CALLING_MODEL)}', ` +
              `messages=1, tools=0, toolsEnabled=false`,
          ),
        ),
        'a trace under the threshold takes the inline path',
      ).toHaveCount(1);
      await expect(
        logsPage.logRow(
          new RegExp(`switching to agentic-tools mode for traceId '${rx(seeded.smallId)}'`),
        ),
        'size is the discriminator: the same rule must not switch for a small trace',
      ).toHaveCount(0);
    });
  });
});
