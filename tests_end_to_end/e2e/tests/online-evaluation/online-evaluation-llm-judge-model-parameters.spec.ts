import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * Which model parameters the LLM-as-a-Judge editor offers for the chosen model,
 * and that it renders no panel at all when there are none (OPIK-8565, opik#8603).
 *
 * A pure rendering decision, so UI is the right and only surface: the editor is
 * never submitted and no provider is ever called — which also means there is no
 * rule to tear down. `PromptModelConfigs` returns `null` rather than disabling
 * the button — "an empty panel misleads, and a disabled button can't say why" —
 * and what makes that decision is the intersection of the model's own
 * capabilities with `RULE_UNSUPPORTED_PARAMS`, which strips topP, the token cap
 * and the two runner controls. For a rule, temperature, the Anthropic thinking
 * effort (opik#8683) and — since 2.2.133 — the OpenAI reasoning effort are what
 * is left.
 *
 * UPDATED FOR 2.2.133 (opik#9153, OPIK-8748). This spec asserted that an OpenAI
 * reasoning model showed NO gear, because `RULE_UNSUPPORTED_PARAMS` used to
 * strip `reasoningEffort` and temperature is resolved away for such a model, so
 * nothing was left for the panel to render. #9153 removed `reasoningEffort` from
 * that set — the efforts are kept because they are saved inside
 * `custom_parameters` — so the gear is now there with the Reasoning effort as
 * its one control. The assertion below was inverted to the new behaviour rather
 * than dropped: the gear's PRESENCE and the absence of a temperature slider are
 * both still rendering decisions this spec's subject, and asserting only the
 * presence would pass over a build that had put the sampling slider back.
 *
 * The paired positive case is what stops this being a locator that passes
 * because it never matched anything: a model with one storable parameter must
 * still show the gear, and its panel must hold exactly that one control.
 *
 * Two models that refuse sampling are covered, because they are computed by
 * different branches: a Claude model that declares `supportsSamplingParams:
 * false`, which keeps only its thinking effort, and an OpenAI reasoning model
 * whose temperature is resolved away and whose penalties are gated off, which
 * leaves only its reasoning effort.
 *
 * Each case opens the page fresh rather than closing the previous editor: an
 * Escape aimed at the parameters dropdown also closes the side panel, and a
 * reload costs about a second.
 */
test.describe(
  'Online evaluation — LLM judge model parameters',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'each judge model gets exactly the parameter controls a rule can store for it',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ modelRegistryProviders, project, page }) => {
        expect(
          modelRegistryProviders.providers,
          'both registries this test picks from are selectable',
        ).toEqual(expect.arrayContaining(['openai', 'anthropic']));

        const onlineEval = new OnlineEvaluationPage(page);

        const openEditorOn = async (modelDisplayName: string): Promise<void> => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel('LLM-as-a-Judge');
          await onlineEval.selectJudgeModel(modelDisplayName);
        };

        await test.step(`${REGISTRY_MODEL.claudeWithSampling} can be tuned, so the gear is there`, async () => {
          // The positive case first, deliberately: if the gear never renders at
          // all (a broken locator, an editor that failed to open) this fails
          // before any absence below has been read as meaningful.
          await openEditorOn(REGISTRY_MODEL.claudeWithSampling);

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

        await test.step(`${REGISTRY_MODEL.claudeWithoutSampling} refuses sampling, so only its thinking effort is offered`, async () => {
          await openEditorOn(REGISTRY_MODEL.claudeWithoutSampling);

          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'no sampling slider for a Claude model with no sampling params',
          ).toEqual([]);
          await expect(
            onlineEval.judgeModelParametersPanel.getByRole('combobox', { name: 'Thinking effort' }),
          ).toBeVisible();
        });

        await test.step(`${REGISTRY_MODEL.openAiReasoning} offers only its reasoning effort`, async () => {
          // A different branch from the Claude case: here temperature is
          // resolved away because the model is an OpenAI reasoning model, and
          // the penalties are gated off by `supportsPenaltyParams`. Since
          // opik#9153 its Reasoning effort is no longer in
          // RULE_UNSUPPORTED_PARAMS, so that control is what is left — and it
          // is the whole reason the gear renders at all here.
          await openEditorOn(REGISTRY_MODEL.openAiReasoning);

          await expect(
            onlineEval.judgeModelParametersTrigger,
            'the gear is there for an OpenAI reasoning model, carrying its effort',
          ).toHaveCount(1);

          await onlineEval.openJudgeModelParameters();
          // Both halves matter. The empty id list is the sampling slider still
          // being resolved away — a build that had put temperature back would
          // show the same gear — and the labelled combobox is the control that
          // replaced the absence this step used to assert. Named for OpenAI's
          // own vocabulary, not Anthropic's "Thinking effort".
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'no sampling slider for an OpenAI reasoning model',
          ).toEqual([]);
          await expect(
            onlineEval.judgeModelParametersPanel.getByRole('combobox', {
              name: 'Reasoning effort',
            }),
            'and the one control it does offer is the reasoning effort',
          ).toBeVisible();
        });
      },
    );
  },
);
