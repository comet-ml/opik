import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * The Gemini thinking-level control on the LLM-as-judge rule dialog, and — the
 * half that matters — what a save actually persists into
 * `code.model.custom_parameters`.
 *
 * The failure mode here is silent. A judge rule that carries a thinking block
 * it should not looks identical in the list, scores traces normally, and reads
 * correctly in the form; the only symptom is that every judge call is slower
 * and more expensive than it needs to be. So each assertion is made against
 * the persisted evaluator over REST rather than against the form that wrote
 * it, and the form is asserted separately as the thing the user reads.
 *
 * No completion is ever made: the model picker only needs a provider key to
 * exist, and everything asserted here is decided before the judge would run.
 * That is what makes the spec deterministic and free.
 */

/** Display label and model id of a Flash Lite model — does not think by default. */
const FLASH_LITE_LABEL = 'Gemini 3.5 Flash Lite';
const FLASH_LITE_MODEL = 'gemini-3.5-flash-lite';

/** Display label and model id of the sibling that DOES think by default. */
const FLASH_LABEL = 'Gemini 3.5 Flash';
const FLASH_MODEL = 'gemini-3.5-flash';

/**
 * The exact level lists the two models offer, in the control's own order.
 *
 * Asserted as whole lists rather than as "None is present": a change that
 * added `None` to every Gemini model would be as wrong as one that removed it
 * from Flash Lite, and only the full list rejects both.
 */
const FLASH_LITE_LEVELS = ['None', 'Minimal', 'Low', 'Medium', 'High'];
const FLASH_LEVELS = ['Minimal', 'Low', 'Medium', 'High'];

test.describe('Online Evaluation — LLM-judge thinking level', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  // A workspace holds at most one provider key per built-in provider, and
  // `gemini` cannot be namespaced per test the way a custom provider can, so
  // two of these tests running at once would have one deleting the key the
  // other is still using. Serial keeps the shared, unnamespaceable resource to
  // one owner at a time.
  test.describe.configure({ mode: 'serial' });

  test('A Flash Lite judge saves no thinking block, keeps a level that was chosen, and clears it again on None', { tag: ['@cap:online-evaluation.create-llm-judge-rule', '@cap:online-evaluation.edit-rule'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    geminiProviderKey,
    automationRulesCleanup,
  }) => {
    const onlineEval = new OnlineEvaluationPage(page);
    const ruleName = `${testNamespace}-flash-lite`;

    await test.step('Create an LLM-judge rule on a Flash Lite model', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillLLMJudgeRuleBasics({
        name: ruleName,
        template: 'Moderation',
        providerGroupLabel: geminiProviderKey.groupLabel,
        modelDisplayName: FLASH_LITE_LABEL,
      });
    });

    await test.step('The dialog preselects None and offers the full Flash Lite level list', async () => {
      await onlineEval.openModelParameters();
      expect(
        await onlineEval.readThinkingLevel(),
        `${FLASH_LITE_LABEL} does not think by default, so the control must not ` +
          'preselect a level that turns thinking on',
      ).toBe('None');
      expect(await onlineEval.listThinkingLevels()).toEqual(FLASH_LITE_LEVELS);
      await onlineEval.closeModelParameters();
    });

    const ruleId = await test.step('Submit, and find the rule the dialog created', async () => {
      await onlineEval.submitRuleDialog();
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();

      const rules = await backendClient.listAutomationRulesForProject(project.id);
      // The project fixture is fresh, so the rule this test created is the
      // only one there is — asserting the whole list rather than picking our
      // name out of it turns a stray rule into a failure instead of a
      // coincidence.
      expect(rules.map((r) => r.name)).toEqual([ruleName]);
      return rules[0].id;
    });

    await test.step('The saved evaluator carries no thinking block at all', async () => {
      const model = await backendClient.getLlmJudgeModel(ruleId);
      expect(model.name, 'the Gemini model, not the identically-labelled Vertex AI one').toBe(
        FLASH_LITE_MODEL,
      );
      expect(
        model.customParameters,
        'a "none" level must send no thinkingConfig — an empty `{}` or a ' +
          '`{thinking: {}}` would still be a block on the wire',
      ).toBeNull();
    });

    await test.step('Reopening hydrates None, and choosing Minimal persists it', async () => {
      await onlineEval.openEditRuleDialog(ruleName);
      await onlineEval.openModelParameters();
      expect(
        await onlineEval.readThinkingLevel(),
        'a rule saved with no thinking block reopens on the model default',
      ).toBe('None');
      await onlineEval.setThinkingLevel('Minimal');
      await onlineEval.closeModelParameters();
      await onlineEval.submitRuleDialog();

      const model = await backendClient.getLlmJudgeModel(ruleId);
      expect(model.customParameters).toEqual({ thinking: { level: 'minimal' } });
    });

    await test.step('An untouched reopen-and-save keeps the chosen level', async () => {
      // The regression this guards against is the new "none" default
      // overwriting a level someone deliberately picked: the level is one
      // Flash Lite offers, so it is a real past choice and only the DEFAULT
      // changed. A save that quietly downgraded it would be the same bug in
      // reverse.
      await onlineEval.openEditRuleDialog(ruleName);
      await onlineEval.openModelParameters();
      expect(await onlineEval.readThinkingLevel()).toBe('Minimal');
      await onlineEval.closeModelParameters();
      await onlineEval.submitRuleDialog();

      const model = await backendClient.getLlmJudgeModel(ruleId);
      expect(model.customParameters).toEqual({ thinking: { level: 'minimal' } });
    });

    await test.step('Setting it back to None removes the persisted block, rather than leaving it behind', async () => {
      // This is the assertion the whole spec exists for. Declining to ADD a
      // thinking block is not enough once one has been saved: the rule would
      // keep sending `minimal` while the form read "None", and the model would
      // keep thinking.
      await onlineEval.openEditRuleDialog(ruleName);
      await onlineEval.openModelParameters();
      expect(await onlineEval.readThinkingLevel()).toBe('Minimal');
      await onlineEval.setThinkingLevel('None');
      await onlineEval.closeModelParameters();
      await onlineEval.submitRuleDialog();

      const model = await backendClient.getLlmJudgeModel(ruleId);
      expect(
        model.customParameters,
        'the previously persisted {thinking: {level: minimal}} must be gone',
      ).toBeNull();
    });
  });

  test('A thinking-by-default Gemini model is untouched: Flash preselects Medium, is never offered None, and saves the level', { tag: ['@cap:online-evaluation.create-llm-judge-rule'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    geminiProviderKey,
    automationRulesCleanup,
  }) => {
    // The control for the test above. The change under test is scoped to the
    // models that do not think by default, and that scoping is the only reason
    // it is safe: a "just stop sending thinking for Gemini" simplification
    // would silently switch every Gemini judge to non-thinking and the Flash
    // Lite test would still pass. This one would not.
    const onlineEval = new OnlineEvaluationPage(page);
    const ruleName = `${testNamespace}-flash`;

    await test.step('Create an LLM-judge rule on Gemini 3.5 Flash', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillLLMJudgeRuleBasics({
        name: ruleName,
        template: 'Moderation',
        providerGroupLabel: geminiProviderKey.groupLabel,
        modelDisplayName: FLASH_LABEL,
      });
    });

    await test.step('The dialog preselects Medium and does not offer None', async () => {
      await onlineEval.openModelParameters();
      expect(
        await onlineEval.readThinkingLevel(),
        `${FLASH_LABEL} thinks by default, so its preselected level must stay Medium`,
      ).toBe('Medium');
      expect(
        await onlineEval.listThinkingLevels(),
        'None is meaningless on a model that cannot stop thinking, and offering ' +
          'it here would promise something the request cannot deliver',
      ).toEqual(FLASH_LEVELS);
      await onlineEval.closeModelParameters();
    });

    await test.step('The saved evaluator still carries the thinking level', async () => {
      await onlineEval.submitRuleDialog();
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();

      const rules = await backendClient.listAutomationRulesForProject(project.id);
      expect(rules.map((r) => r.name)).toEqual([ruleName]);

      const model = await backendClient.getLlmJudgeModel(rules[0].id);
      expect(model.name).toBe(FLASH_MODEL);
      expect(model.customParameters).toEqual({ thinking: { level: 'medium' } });
    });
  });
});
