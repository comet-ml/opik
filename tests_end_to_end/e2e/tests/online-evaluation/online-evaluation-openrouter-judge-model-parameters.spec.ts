import { test, expect, OPEN_ROUTER_GROUP, OPEN_ROUTER_MODEL } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { registryModelIds } from '@e2e/core/model-registry';

/**
 * The LLM-judge rule editor panel hides the model-parameters gear for an
 * OpenRouter-routed OpenAI reasoning model (OPIK-8636, opik#8830).
 *
 * A pure rendering decision, so UI is the right and only surface: the rule editor panel is
 * never submitted, no provider is ever called, and there is no rule to tear
 * down. `PromptModelConfigs` returns `null` rather than disabling the button —
 * "an empty panel misleads, and a disabled button can't say why" — and what
 * decides is `hasVisibleControls` over the intersection of the model's own
 * capabilities with `RULE_UNSUPPORTED_PARAMS`, which strips topP, the token
 * cap, both effort controls and the two runner controls. For a rule, temperature
 * is all that is ever left — so once this change resolves temperature away for
 * an OpenRouter id with a native OpenAI reasoning row, the whole panel goes.
 *
 * `online-evaluation-llm-judge-model-parameters.spec.ts` covers the Claude and
 * native-OpenAI branches and cannot reach the OpenRouter route at all. This is
 * the branch #8830 adds, and the one its own vitest case pins.
 *
 * The paired positive case is what stops the absence assertion passing because
 * the locator never matched anything.
 */
test.describe(
  'Online evaluation — OpenRouter LLM judge model parameters',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'the judge model-parameters gear appears only for an OpenRouter model the rule can tune',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ openRouterNativeModels, project, page }) => {
        test.setTimeout(180_000);
        const openAiNativeIds = registryModelIds(openRouterNativeModels.registry, 'openai');

        const onlineEval = new OnlineEvaluationPage(page);

        // Each case opens the page fresh rather than cancelling the previous
        // editor: dismissing the parameters dropdown and then the rule editor panel is a
        // race — the dropdown closes with an animation, and an Escape aimed at
        // it lands on the rule editor panel instead — and a reload costs about a second.
        const openPanelOn = async (model: string): Promise<void> => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel();
          await onlineEval.selectJudgeModelFromProvider(OPEN_ROUTER_GROUP, model);
        };

        await test.step(`${OPEN_ROUTER_MODEL.openAiStandard} can be tuned, so the gear is there`, async () => {
          expect(
            openAiNativeIds.has('gpt-4o-mini'),
            'the native OpenAI row this id resolves to is listed, and is not a reasoning model',
          ).toBe(true);

          // The positive case first, deliberately: if the gear never renders
          // at all — a broken locator, an editor panel that failed to open — this
          // fails before the absence below has been read as meaningful.
          await openPanelOn(OPEN_ROUTER_MODEL.openAiStandard);

          await expect(
            onlineEval.judgeModelParametersTrigger,
            'the model-parameters gear',
          ).toHaveCount(1);

          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'a rule can set temperature and nothing else',
          ).toEqual(['temperature']);
        });

        await test.step(`${OPEN_ROUTER_MODEL.openAiReasoning} has nothing a rule can set, so the gear is gone`, async () => {
          expect(
            openAiNativeIds.has('gpt-5-nano'),
            'the native OpenAI reasoning row behind this id is listed — which is what gates it',
          ).toBe(true);

          await openPanelOn(OPEN_ROUTER_MODEL.openAiReasoning);

          // Hidden, not disabled. A build that disabled it instead would still
          // have count 1 here and fail, which is the intended reading.
          await expect(
            onlineEval.judgeModelParametersTrigger,
            'no gear for an OpenRouter id behind a native OpenAI reasoning row',
          ).toHaveCount(0);

          // The absence means nothing without these: an editor that closed, or
          // a selection that silently did not take, would also show no gear.
          // Read from the form, not the panel root, which stays mounted
          // (translated off screen) while closed.
          await expect(onlineEval.nameInput, 'the editor is still open').toBeVisible();
          await expect(
            onlineEval.panel,
            'and still shows the gated model as the chosen judge',
          ).toContainText(OPEN_ROUTER_MODEL.openAiReasoning);
        });
      },
    );
  },
);
