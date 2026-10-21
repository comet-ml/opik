import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * The LLM-judge rule dialog hides the model-parameters gear when the chosen
 * model has nothing this surface can set (OPIK-8565, opik#8603).
 *
 * A pure rendering decision, so UI is the right and only surface: the dialog is
 * never submitted and no provider is ever called — which also means there is no
 * rule to tear down. `PromptModelConfigs` returns `null` rather than disabling
 * the button — "an empty panel misleads, and a disabled button can't say why" —
 * and what makes that decision is the intersection of the model's own
 * capabilities with `RULE_UNSUPPORTED_PARAMS`, which strips topP, the token cap,
 * both effort controls and the two runner controls. For a rule, temperature is
 * all that is ever left.
 *
 * The paired positive case is what stops this being a locator that passes
 * because it never matched anything: a model with one storable parameter must
 * still show the gear, and its panel must hold exactly that one control.
 *
 * Two different reasons for "nothing to set" are covered, because they are
 * computed by different branches: a Claude model that declares
 * `supportsSamplingParams: false`, and an OpenAI reasoning model whose
 * temperature is resolved away and whose penalties are gated off.
 *
 * Each case opens the page fresh rather than cancelling the previous dialog.
 * Dismissing the parameters dropdown and then the dialog is a race — the
 * dropdown closes with an animation, and an Escape aimed at it lands on the
 * dialog instead — and a reload costs about a second.
 */
test.describe(
  'Online evaluation — LLM judge model parameters',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'the model-parameters gear appears only for a model the rule can tune',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ modelRegistryProviders, project, page }) => {
        expect(
          modelRegistryProviders.providers,
          'both registries this test picks from are selectable',
        ).toEqual(expect.arrayContaining(['openai', 'anthropic']));

        const onlineEval = new OnlineEvaluationPage(page);

        const openDialogOn = async (modelDisplayName: string): Promise<void> => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRuleDialog();
          await onlineEval.selectJudgeModel(modelDisplayName);
        };

        await test.step(`${REGISTRY_MODEL.claudeWithSampling} can be tuned, so the gear is there`, async () => {
          // The positive case first, deliberately: if the gear never renders at
          // all — a broken locator, a dialog that failed to open — this fails
          // before any absence below has been read as meaningful.
          await openDialogOn(REGISTRY_MODEL.claudeWithSampling);

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

        await test.step(`${REGISTRY_MODEL.claudeWithoutSampling} refuses sampling, so the gear is gone`, async () => {
          await openDialogOn(REGISTRY_MODEL.claudeWithoutSampling);

          // Hidden, not disabled. A build that disabled it instead would still
          // have count 1 here and fail — which is the intended reading.
          await expect(
            onlineEval.judgeModelParametersTrigger,
            'no gear for a Claude model with no sampling params',
          ).toHaveCount(0);
        });

        await test.step(`${REGISTRY_MODEL.openAiReasoning} has nothing a rule can set either`, async () => {
          // A different branch from the Claude case: here temperature is
          // resolved away because the model is an OpenAI reasoning model, and
          // the penalties are gated off by `supportsPenaltyParams`. Its
          // Reasoning effort control would be the one thing left, and
          // RULE_UNSUPPORTED_PARAMS strips it.
          await openDialogOn(REGISTRY_MODEL.openAiReasoning);

          await expect(
            onlineEval.judgeModelParametersTrigger,
            'no gear for an OpenAI reasoning model',
          ).toHaveCount(0);
        });
      },
    );
  },
);
