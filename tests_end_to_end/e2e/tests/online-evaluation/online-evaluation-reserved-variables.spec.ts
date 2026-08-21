import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * The toggle that gates every behaviour below. It is a deployment default, not
 * a workspace setting: `serviceToggles.agenticToolsEnabled` in the backend
 * config, surfaced to the frontend by `GET /v1/private/toggles/`.
 */
const AGENTIC_TOOLS_TOGGLE = 'agentic_tools_enabled';

/**
 * One prompt, reused on every scope, naming all three reserved judge variables
 * plus a plain one.
 *
 * `question` rather than `input`: the canned "Custom LLM-as-judge" template
 * ships a pre-set `input -> input` mapping, so an `{{input}}` row would prove
 * only that an existing value survived. A name the template has never seen
 * arrives with no mapping at all, which is the state the auto-fill acts on —
 * so its staying empty is evidence that auto-fill is confined to the reserved
 * names, not merely that nothing overwrote it.
 */
const RESERVED_VARIABLES_PROMPT = 'Judge this. {{question}} {{span}} {{spans}} {{trace}}';

test.describe('Online Evaluation — reserved judge variables', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('Reserved judge variables auto-fill per scope while agentic tools are on', { tag: ['@cap:online-evaluation.rule-scope-thread-span'] }, async ({
    project,
    backendClient,
    page,
  }) => {
    // Which variables the LLM-as-judge dialog treats as reserved depends on the
    // rule's scope, and the whole mechanism is gated on one deployment toggle:
    //
    //   trace scope  -> {{spans}}, {{trace}} auto-fill to their sentinels
    //   span scope   -> {{span}} does, and only {{span}}
    //   thread scope -> there is no variable mapping at all
    //
    // A row is dropped from "Variable mapping" only when its value equals that
    // sentinel (LLMPromptMessagesVariables filters on the value, never on the
    // name), so an absent row IS the assertion that the auto-fill happened: had
    // the variable been left unmapped it would render as an empty row, exactly
    // like the non-reserved ones this test keeps on screen as controls.
    //
    // Nothing here needs a provider key or an LLM call. The rule is never
    // submitted; the dialog's own derivation of the variable list is the
    // behaviour under test, and it is pure frontend logic over one API field.
    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('The deployment serves agentic tools on by default', async () => {
      const toggles = await backendClient.getFeatureToggles();
      // Assert the key is served before asserting its value: a toggle that has
      // been renamed away and a toggle serving `false` are different answers,
      // and reading a missing key as falsy would report the first as the second.
      expect(
        Object.keys(toggles),
        `${AGENTIC_TOOLS_TOGGLE} is part of the toggles payload`,
      ).toContain(AGENTIC_TOOLS_TOGGLE);
      expect(
        toggles[AGENTIC_TOOLS_TOGGLE],
        'agentic tools default to ON for every deployment, self-hosted included',
      ).toBe(true);
    });

    await test.step('Open the create-rule dialog on the default (trace) scope', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await expect(onlineEval.scopeSelect, 'a new rule opens on trace scope').toHaveText('Trace');
    });

    await test.step('Trace scope auto-fills {{spans}} and {{trace}} only', async () => {
      await onlineEval.setLLMJudgePrompt(RESERVED_VARIABLES_PROMPT);

      await onlineEval.expectVariableMappingRows(['question', 'span']);
      await expect(
        onlineEval.variableMappingRow('spans'),
        '{{spans}} is auto-filled with its sentinel, so it leaves the list',
      ).toHaveCount(0);
      await expect(
        onlineEval.variableMappingRow('trace'),
        '{{trace}} is auto-filled with its sentinel, so it leaves the list',
      ).toHaveCount(0);

      // The two survivors prove the auto-fill is scoped, not blanket: a plain
      // variable and one that is reserved on a DIFFERENT scope both stay
      // on-screen, unmapped and waiting for the user.
      await expect(onlineEval.variableMappingInput('question')).toHaveValue('');
      await expect(
        onlineEval.variableMappingInput('span'),
        '{{span}} is reserved on span scope, not on trace scope',
      ).toHaveValue('');
    });

    await test.step('Span scope auto-fills {{span}} only', async () => {
      // Changing scope resets the judge details — prompt included — so the
      // prompt has to be written again against the span-scope defaults.
      await onlineEval.selectScope('Span', { expectResetConfirm: true });
      await onlineEval.setLLMJudgePrompt(RESERVED_VARIABLES_PROMPT);

      await onlineEval.expectVariableMappingRows(['question', 'spans', 'trace']);
      await expect(
        onlineEval.variableMappingRow('span'),
        '{{span}} is auto-filled with its sentinel on span scope',
      ).toHaveCount(0);

      // The trace-scope reserved set must not leak here: a span has no
      // sub-spans, and the trace skeleton belongs to trace scope.
      await expect(onlineEval.variableMappingInput('spans')).toHaveValue('');
      await expect(onlineEval.variableMappingInput('trace')).toHaveValue('');
      await expect(onlineEval.variableMappingInput('question')).toHaveValue('');
    });

    await test.step('Thread scope renders no variable mapping at all', async () => {
      await onlineEval.selectScope('Thread', { expectResetConfirm: true });
      // Write the same prompt first, so the absent section is a fact about
      // thread scope rather than about a prompt that named no variables.
      await onlineEval.setLLMJudgePrompt(RESERVED_VARIABLES_PROMPT);

      await expect(
        onlineEval.variableMappingHeader,
        'thread-scope rules take no variables, so the whole section is absent',
      ).toHaveCount(0);
    });
  });
});
