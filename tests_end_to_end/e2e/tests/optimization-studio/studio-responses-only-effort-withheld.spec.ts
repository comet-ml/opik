import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import {
  expectPinApplied,
  pinOpenAiPipelineMode,
} from '@e2e/core/openai-pipeline-mode';

/**
 * The Optimization Studio new-run form never offers the Responses-API-only
 * reasoning effort `Max`, whatever the OpenAI key's pipeline mode — and carries
 * a picked effort across a model switch within the provider (opik#9236 /
 * OPIK-8927).
 *
 * The Studio runner calls LiteLLM rather than Opik's OpenAI pipeline, so
 * `useModelFormHandlers` pins `openAiPipelineMode: "chat_completions_api"` when
 * it reconciles the config, and `OptimizationsNewPromptSection` passes
 * `PromptModelConfigs` no mode at all — which resolves to the same thing.
 * `Max` is a level OpenAI accepts only through the Responses API; Chat
 * Completions answers 400. So offering it here would make every optimization
 * run on a gpt-5.6/gpt-6 model fail on its first LiteLLM call, and the user
 * would have picked a level the form showed them.
 *
 * ## Why this is a pair, and only means anything as one
 *
 * "The Studio does not offer Max" is satisfied just as well by a build that
 * lost the whole Responses-only branch — or the whole effort control — as by
 * one that correctly withholds it. The Playground leg is what rules that out:
 * on the IDENTICAL pinned key, in the same browser session, the Playground must
 * offer Max on the same model. Two surfaces reading one `modelUtils.ts`, one of
 * which is told the key's real mode and one of which is told Chat Completions.
 *
 * ## Why this is not already covered
 *
 * `optimization-studio.new-run-form-validation` reads "covered", by
 * `optimization-rerun-unavailable-model.spec.ts` — whose `readModelParameters()`
 * reads `input[data-testid$="-input"]` only, i.e. the numeric sliders, and which
 * never switches model within a provider. `playground-openai-effort-pipeline-mode.spec.ts`
 * drives the effort gate thoroughly but only on the Playground, and names the
 * mid-flight-keys race a RECORDED GAP in its own docblock.
 * `playground-max-output-tokens-model-switch.spec.ts` does do an OpenAI→OpenAI
 * switch, but carries max-output-tokens rather than the effort. A grep of the
 * estate for an effort read on the Studio form returned nothing before this.
 *
 * ## What this does NOT claim
 *
 * The headline sub-case of opik#9236 — a stored `max` surviving a model change
 * made WHILE the provider-keys request is still in flight — is not reachable
 * here and is not asserted. Holding that response open means the model picker
 * never mounts at all (`select-a-llm-model` still absent after 30s, measured
 * during the 2.2.131 staging pass), so there is no picker to change the model
 * in; it stays covered by `modelUtils.test.ts`. The rule-editor leg is not
 * asserted either, and for a product reason rather than a technical one:
 * `reasoningEffort` is in `RULE_UNSUPPORTED_PARAMS`, so for an OpenAI reasoning
 * model the rule editor renders no model-parameters control of any kind and
 * there is nothing for an effort to carry across.
 *
 * Nothing is launched and nothing can be billed: no run is submitted, no
 * completion is sent, and every model named here is registered unbilled by
 * `modelRegistryProviders`.
 */

/** The five efforts GPT 5.6 Sol and GPT 5.5 both take on Chat Completions. */
const CHAT_COMPLETIONS_EFFORTS = ['None', 'Low', 'Medium', 'High', 'xHigh'] as const;

/** The one the Responses API adds, for a model that declares it. */
const RESPONSES_ONLY_EFFORT = 'Max';

/**
 * The level the switch test picks, and the one both models offer.
 *
 * `xHigh` and not `Max`: picking Max on the Studio form is impossible by
 * construction — that is the first test — so a carry-over test has to use a
 * level the form will actually store. It is also the top of the shared list, so
 * "the panel still reads xHigh" is a claim about the level being KEPT rather
 * than about it happening to be the new model's default (GPT 5.5's default is
 * not xHigh; the test asserts that it is a level GPT 5.5 offers).
 */
const CARRIED_EFFORT = 'xHigh';

test.describe(
  'Optimization Studio — the Responses-API-only effort, and a carried effort across a model switch',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test(
      'the new-run form withholds Max on a Responses-API key, while the Playground on that same key offers it',
      { tag: ['@cap:optimization-studio.new-run-form-validation'] },
      async ({ modelRegistryProviders, project, page }) => {
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        // The hardest case for the Studio: the key really IS on the Responses
        // API, so the only thing that can withhold Max on this form is the
        // form's own pin.
        const pin = await pinOpenAiPipelineMode(page, 'responses_api');

        await test.step('The premise: on this key the Playground does offer Max', async () => {
          // Not a courtesy check. Without it, the Studio assertion below passes
          // against a build that lost the Responses-only branch entirely, or
          // that stopped reading the key's mode at all — neither of which is the
          // behaviour under test, and both of which silently downgrade the model
          // a Playground user is paying for.
          const playground = new PlaygroundPage(page, project.id);
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.openAiResponsesOnlyEffort);
          await playground.openModelParameters(0);
          expect(
            await playground.reasoningEffortOptions(),
            'a Responses API key adds Max to the base five on the Playground',
          ).toEqual([...CHAT_COMPLETIONS_EFFORTS, RESPONSES_ONLY_EFFORT]);
          await playground.closeModelParameters();
          expectPinApplied(pin);
        });

        const studio = new OptimizationStudioPage(page, project.id);

        await test.step(`Open the Studio new-run form on ${REGISTRY_MODEL.openAiResponsesOnlyEffort}`, async () => {
          await studio.gotoNew();
          await studio.selectModelByName(REGISTRY_MODEL.openAiResponsesOnlyEffort);
        });

        await test.step('The Studio offers exactly the five Chat Completions levels, with Max absent', async () => {
          // The whole offered list, in order — not just "Max is absent". The
          // Playground leg above is what makes the absence attributable to the
          // Studio's pin; this is what makes it attributable to the pin rather
          // than to a panel that lost its effort control.
          await studio.openModelParameters();
          expect(
            await studio.promptEffortOptions(),
            'the Studio runner calls LiteLLM through Chat Completions, which answers 400 ' +
              'for reasoning_effort "max" — so the form must never offer it',
          ).toEqual([...CHAT_COMPLETIONS_EFFORTS]);
          await studio.dismissModelParameters();
          expectPinApplied(pin);
        });
      },
    );

    test(
      'an effort picked on the new-run form survives a switch to another OpenAI model that offers it',
      { tag: ['@cap:optimization-studio.new-run-form-validation'] },
      async ({ modelRegistryProviders, project, page }) => {
        expect(modelRegistryProviders.providers).toContain('openai');

        // No pipeline-mode pin here, deliberately: the Studio reconciles against
        // Chat Completions whatever the key says, so this test is about the
        // carry-over and pinning the mode would add a variable that cannot
        // change the answer.
        const studio = new OptimizationStudioPage(page, project.id);

        await test.step(`Pick ${CARRIED_EFFORT} on ${REGISTRY_MODEL.openAiResponsesOnlyEffort}`, async () => {
          await studio.gotoNew();
          await studio.selectModelByName(REGISTRY_MODEL.openAiResponsesOnlyEffort);
          await studio.openModelParameters();
          await studio.selectPromptEffort(CARRIED_EFFORT);
          expect(
            await studio.readPromptEffort(),
            'the panel keeps the effort that was just picked',
          ).toBe(CARRIED_EFFORT);
          await studio.dismissModelParameters();
        });

        await test.step(`Switch to ${REGISTRY_MODEL.openAiEffortWithoutMax} without reloading`, async () => {
          // No reload, which is the whole subject: `updateProviderConfig` runs
          // in `handleModelChange` and reconciles the carried level against the
          // new model's options in the same React commit. A reload would read
          // the stored config back through a different path and say nothing
          // about the reconciler.
          await studio.selectModelByName(REGISTRY_MODEL.openAiEffortWithoutMax);
        });

        await test.step(`${REGISTRY_MODEL.openAiEffortWithoutMax} still reads ${CARRIED_EFFORT}, and offers it`, async () => {
          await studio.openModelParameters();
          const offered = await studio.promptEffortOptions();
          // Asserted BEFORE the level itself: "the panel still reads xHigh" is
          // only good news if xHigh is a level the new model accepts. A carried
          // level the new model does not offer is the 400-on-every-run failure
          // this reconciler exists to prevent, and it would read exactly the
          // same in the panel.
          expect(
            offered,
            `${REGISTRY_MODEL.openAiEffortWithoutMax} declares no Responses-only levels, so it ` +
              'offers the base five here too',
          ).toEqual([...CHAT_COMPLETIONS_EFFORTS]);
          expect(
            offered,
            `${CARRIED_EFFORT} must be a level the new model really takes`,
          ).toContain(CARRIED_EFFORT);
          expect(
            await studio.readPromptEffort(),
            'a level the user deliberately picked is carried to the nearest one the new ' +
              "model offers — here the same one — not reset to the new model's default",
          ).toBe(CARRIED_EFFORT);
          await studio.dismissModelParameters();
        });
      },
    );
  },
);
