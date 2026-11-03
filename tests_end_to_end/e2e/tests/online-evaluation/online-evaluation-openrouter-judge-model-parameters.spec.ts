import { test, expect, OPEN_ROUTER_GROUP, OPEN_ROUTER_MODEL } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { registryModelIds } from '@e2e/core/model-registry';

/**
 * Which model parameters the LLM-judge rule editor panel offers for an
 * OpenRouter-routed OpenAI reasoning model (OPIK-8636, opik#8830).
 *
 * A pure rendering decision, so UI is the right and only surface: the rule editor panel is
 * never submitted, no provider is ever called, and there is no rule to tear
 * down. `PromptModelConfigs` returns `null` rather than disabling the button —
 * "an empty panel misleads, and a disabled button can't say why" — and what
 * decides is `hasVisibleControls` over the intersection of the model's own
 * capabilities with `RULE_UNSUPPORTED_PARAMS`, which strips topP, the token
 * cap and the two runner controls. #8830's contribution is still what this spec
 * is about: temperature is resolved away for an OpenRouter id whose native
 * OpenAI row is a reasoning model, so no sampling slider is offered there.
 *
 * UPDATED FOR 2.2.133 (opik#9153, OPIK-8748). This spec asserted that the whole
 * gear DISAPPEARED for such a model, which was true while
 * `RULE_UNSUPPORTED_PARAMS` also stripped `reasoningEffort`: with temperature
 * resolved away, nothing was left to render. #9153 removed `reasoningEffort`
 * from that set, so the gear now renders carrying the effort. The assertion was
 * inverted to the new behaviour rather than dropped — #8830's rule is still
 * asserted, now as "no sampling slider" rather than "no panel", which is the
 * same question about the same intersection and does not weaken on the
 * temperature branch this spec exists for.
 *
 * `online-evaluation-llm-judge-model-parameters.spec.ts` covers the Claude and
 * native-OpenAI branches and cannot reach the OpenRouter route at all. This is
 * the branch #8830 adds, and the one its own vitest case pins.
 *
 * The effort's SHAPE on save — OpenRouter's nested `reasoning.effort` with no
 * flat copy beside it — is a different question and is covered by
 * `online-evaluation-llm-judge-reasoning-effort.spec.ts`; this spec still
 * submits nothing.
 *
 * The paired positive case is what stops either assertion passing because the
 * locator never matched anything.
 */
test.describe(
  'Online evaluation — OpenRouter LLM judge model parameters',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'an OpenRouter judge model gets exactly the parameter controls a rule can store for it',
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

        await test.step(`${OPEN_ROUTER_MODEL.openAiReasoning} loses its sampling slider and keeps only its effort`, async () => {
          expect(
            openAiNativeIds.has('gpt-5-nano'),
            'the native OpenAI reasoning row behind this id is listed — which is what gates it',
          ).toBe(true);

          await openPanelOn(OPEN_ROUTER_MODEL.openAiReasoning);

          await expect(
            onlineEval.judgeModelParametersTrigger,
            'the gear is there, because since opik#9153 the effort survives for a rule',
          ).toHaveCount(1);

          await onlineEval.openJudgeModelParameters();
          // #8830's rule, asserted where it still bites: the temperature is
          // resolved away for an OpenRouter id whose native row is a reasoning
          // model. Compared as the whole id list rather than by the slider's
          // absence, so a build that offered topP or the token cap here — both
          // still in RULE_UNSUPPORTED_PARAMS — fails too.
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'no sampling slider for an OpenRouter id behind a native OpenAI reasoning row',
          ).toEqual([]);
          await expect(
            onlineEval.judgeParameterLabel('Reasoning effort'),
            'and the effort is the one control left',
          ).toHaveCount(1);

          // The readings above mean nothing without these: an editor that
          // closed, or a selection that silently did not take, would show an
          // empty parameter list too. Read from the form, not the panel root,
          // which stays mounted (translated off screen) while closed.
          await onlineEval.closeJudgeModelParameters();
          await expect(onlineEval.nameInput, 'the editor is still open').toBeVisible();
          await expect(
            onlineEval.panel,
            'and still shows the chosen model as the judge',
          ).toContainText(OPEN_ROUTER_MODEL.openAiReasoning);
        });
      },
    );
  },
);
