import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import type { ProviderScopedModel } from '@e2e/pom/model-picker';

const JUDGE_MODEL: ProviderScopedModel = { provider: 'Gemini', model: 'Gemini 2.5 Pro' };
/** The model id the rule must persist for the picker selection above. */
const JUDGE_MODEL_ID = 'gemini-2.5-pro';

/**
 * A Gemini judge's thinking level is carried in the rule's
 * `code.model.custom_parameters.thinking.level`, and the add/edit dialog is the
 * only place a user sets it. Three hops have to agree for that to work, and
 * each fails silently on its own:
 *
 *   1. save    — the dialog folds the picked level into `custom_parameters`;
 *   2. hydrate — reopening the rule shows the level that was saved, not the
 *                model's default. A dialog that fell back to the default would
 *                look right to the user and then overwrite their choice on the
 *                next save, because the form writes whatever it is displaying;
 *   3. re-save — an edit that never touches the control leaves the level alone.
 *
 * Nothing else in the product reports the level a rule will run at, so a break
 * anywhere here changes what the judge is billed for with no visible symptom.
 *
 * Deterministic and free: the rule is created and read back, never run, so no
 * completion is requested and the provider key is a throwaway string.
 */
test.describe('Online Evaluation — LLM-judge thinking level', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('The judge model\'s thinking level survives save, reopen and re-save, and can be edited', { tag: ['@cap:online-evaluation.edit-rule'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    builtInProviderKeys,
    automationRulesCleanup,
  }) => {
    // Well above the observed runtime (~20s). Every wait below is a UI
    // interaction with its own bounded timeout and its own diagnostic, so this
    // ceiling only decides which error a genuine stall produces — and a
    // named-locator failure beats "test timeout exceeded". Raised over the
    // 90s default because the run opens the model picker and the Model
    // parameters popover five times, and each of those retries to absorb the
    // popover's mount animation.
    test.setTimeout(180_000);

    const ruleName = `${testNamespace}-thinking`;
    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('Configure the Gemini provider so its models are pickable', async () => {
      await builtInProviderKeys.ensure('gemini');
    });

    await test.step('Open Online evaluation and start a new LLM-judge rule', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openCreateRuleDialog();
      await onlineEval.fillRuleName(ruleName);
      await onlineEval.selectJudgeModel(JUDGE_MODEL);
    });

    await test.step('The dialog preselects the model\'s own default, High', async () => {
      // This is what makes every later assertion discriminating: Low is only
      // evidence of a round trip because it is NOT what the form shows by
      // default. Without this, a dialog stuck on one hardcoded level would
      // satisfy the reopen assertion below by accident.
      const thinking = await onlineEval.modelParameters.readThinkingLevel();
      expect(thinking.options, 'the Gemini 2.5 family offers four levels').toEqual([
        'Off',
        'Low',
        'Medium',
        'High',
      ]);
      expect(thinking.selected, `${JUDGE_MODEL.model} defaults to High`).toBe('High');
    });

    await test.step('Set the thinking level to Low and create the rule', async () => {
      await onlineEval.modelParameters.setThinkingLevel('Low');
      await onlineEval.submitRuleDialog();
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    const ruleId = await test.step('Resolve the created rule\'s id', async () => {
      const rules = await backendClient.listAutomationRulesForProject(project.id);
      const matching = rules.filter((r) => r.name === ruleName);
      // Exactly one: a namespace collision would leave the rest of the test
      // asserting about somebody else's rule.
      expect(matching.map((r) => r.id), `exactly one rule named ${ruleName}`).toHaveLength(1);
      return matching[0].id;
    });

    await test.step('The rule persisted the level the dialog was showing', async () => {
      const model = await backendClient.getLlmJudgeRuleModel(ruleId);
      expect(model.name, 'the picked model is what got saved').toBe(JUDGE_MODEL_ID);
      // Compared whole rather than reaching for `.level`: an optional-chained
      // read would turn an absent `custom_parameters` — the exact regression
      // this covers — into `undefined` compared against nothing. Equality
      // rather than a subset match, so a stray extra parameter the dialog
      // never offered also fails.
      expect(
        model.customParameters,
        'the level the user picked must reach custom_parameters.thinking',
      ).toEqual({ thinking: { level: 'low' } });
    });

    await test.step('Reopening the rule shows Low, not the model default', async () => {
      await onlineEval.openEditRuleDialog(ruleName);
      await expect(
        onlineEval.modelPicker,
        'the edit dialog hydrates the saved model',
      ).toContainText(JUDGE_MODEL.model);
      const thinking = await onlineEval.modelParameters.readThinkingLevel();
      expect(
        thinking.selected,
        'the edit dialog must show the persisted level, not getDefaultThinkingLevel(model)',
      ).toBe('Low');
    });

    await test.step('Re-saving without touching the control leaves the level at Low', async () => {
      // The form writes whatever it is displaying, so this is the assertion
      // that a hydration bug would actually be caught by: a dialog that showed
      // High would silently persist High here.
      await onlineEval.submitRuleDialog();
      const model = await backendClient.getLlmJudgeRuleModel(ruleId);
      expect(model.name, 'an unedited re-save must not change the model').toBe(JUDGE_MODEL_ID);
      expect(
        model.customParameters,
        'an unedited re-save must not change the thinking level',
      ).toEqual({ thinking: { level: 'low' } });
    });

    await test.step('Editing the level to Medium persists the new value', async () => {
      await onlineEval.openEditRuleDialog(ruleName);
      await onlineEval.modelParameters.setThinkingLevel('Medium');
      await onlineEval.submitRuleDialog();
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();

      const model = await backendClient.getLlmJudgeRuleModel(ruleId);
      expect(model.name, 'editing the level must not disturb the model').toBe(JUDGE_MODEL_ID);
      expect(
        model.customParameters,
        'the edited level must replace the old one, not merge with it',
      ).toEqual({ thinking: { level: 'medium' } });
    });
  });
});
