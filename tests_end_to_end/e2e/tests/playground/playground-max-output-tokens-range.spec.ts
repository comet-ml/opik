import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';
import {
  expectPinApplied,
  pinOpenAiPipelineMode,
} from '@e2e/core/openai-pipeline-mode';

/**
 * Max output tokens must stay inside what the SELECTED OpenAI or Anthropic
 * model accepts — in the panel and in the request (OPIK-8636, opik#8847 and
 * opik#8879).
 *
 * The control used to carry one flat ceiling per provider: 128000 for every
 * OpenAI model although `gpt-4o-mini` allows 16384, and 64000 for every Claude
 * although Sonnet 4.6 allows 128000. The ceiling now comes from the model's own
 * `max_output_tokens` in the bundled pricing data, falling back to those old
 * caps for a model the data does not list; the floor is 1, or 16 on an OpenAI
 * key set to the Responses API.
 *
 * Both halves are asserted on both surfaces, because they can regress apart and
 * each failure is user-visible but silent:
 *
 *   - the PANEL decides the range the user may tune (`getMaxCompletionTokensRange`);
 *   - the REQUEST BUILDER clamps a stored value into the same range
 *     (`resolveMaxCompletionTokens`, via `sanitizeConfigForRequest`).
 *
 * A cap left at 128000 on GPT 4o Mini is a 400 from OpenAI on every run; a
 * floor left at 1 on a Responses API key is a 400 on every run too. And a panel
 * that shows a clamped value the request does not send — or the reverse — is
 * invisible from either surface alone.
 *
 * ## Why this is not already covered
 *
 * `playground-reasoning-model-parameters.spec.ts` asserts that
 * `max_completion_tokens` is a KEY in the body for a reasoning model, never its
 * bounds or its value, and `playground-model-parameters.spec.ts` is about the
 * Anthropic sampling pair. A build that offered every model the old flat
 * ceiling would pass both.
 *
 * ## Why the numbers are compared, not pinned
 *
 * `model_prices_and_context_window.json` is a generated artifact (see the repo
 * guidelines) refreshed by automation, so a spec pinning 16384 would fail the
 * day a vendor raises a limit — and would be asserting the artifact's contents
 * rather than the behaviour. So every case reads the range the panel offers and
 * asserts a RELATION the change is about: the cap is below the flat value it
 * replaced, it differs between two models, and whatever it is, typing past it
 * commits to exactly it and sends exactly it. The two fallback constants ARE
 * pinned, because they live in the frontend's own code
 * (`MAX_COMPLETION_TOKENS_FALLBACK`), not in the artifact.
 *
 * Deterministic and provider-free despite naming real models: the panel renders
 * off the model identifier, the registries come from the `modelRegistryProviders`
 * fixture's placeholder keys, and every completion is fulfilled in the browser
 * by `captureCompletionBody`, so nothing is generated or billed.
 */

/** The control id the OpenAI and Anthropic panels mount Max output tokens as. */
const CONTROL = 'maxCompletionTokens';

/**
 * The per-provider ceilings the panels used before the pricing data was read,
 * and which a model the data does not list still falls back to
 * (`MAX_COMPLETION_TOKENS_FALLBACK`).
 */
const FLAT_CAP_BEFORE = { openAi: 128000, anthropic: 64000 } as const;

/** Comfortably past every ceiling in the pricing data, so the clamp has to act. */
const ABOVE_ANY_CAP = '999999';

/**
 * Read the range the panel offers for the selected model, then prove the
 * request builder agrees with it: type a value above the ceiling, and both the
 * input and the outbound body must land on the ceiling itself.
 *
 * One helper for both surfaces because neither is the claim on its own — the
 * point is that they agree, and a build whose panel clamped to one number while
 * its request sent another would pass two separate assertions.
 */
async function readRangeAndAssertClamp(
  page: Page,
  playground: PlaygroundPage,
  expectedModelId: string,
): Promise<{ min: number; max: number }> {
  return test.step(`read the Max output tokens range and clamp on ${expectedModelId}`, async () => {
    await playground.openModelParameters(0);
    const range = await playground.readSliderRange(CONTROL);

    await playground.setSliderValue(CONTROL, ABOVE_ANY_CAP);
    await expect(
      playground.sliderInput(CONTROL),
      `a value above the ceiling commits as the ceiling, not as ${ABOVE_ANY_CAP}`,
    ).toHaveValue(String(range.max));
    await expect(
      playground.sliderHint(CONTROL),
      'the No-limit hint belongs to the providers where 0 means no limit, not to this one',
    ).toHaveCount(0);
    await playground.closeModelParameters();

    const body = await captureCompletionBody(page, () => playground.clickRun());
    expect(body.model, 'the request names the selected model').toBe(expectedModelId);
    expect(
      body.max_completion_tokens,
      'the request carries exactly the ceiling the panel showed',
    ).toBe(range.max);
    // The POST goes out as the run STARTS, so capturing the body says nothing
    // about the run being over — and the next case touches the model picker.
    await playground.waitForFreeRunIdle();

    return range;
  });
}

async function openPlaygroundOn(page: Page, projectId: string): Promise<PlaygroundPage> {
  const playground = new PlaygroundPage(page, projectId);
  await test.step('Open the playground and write a prompt', async () => {
    await playground.goto();
    await playground.waitForReady();
    // Typed once: the message survives every model switch below, and Run does
    // nothing without it.
    await playground.fillUserMessage('Reply with the single word OK.');
  });
  return playground;
}

test.describe(
  'Playground — the Max output tokens range',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'the ceiling follows the selected OpenAI model and falls back for one the pricing data omits',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        test.setTimeout(240_000);
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const playground = await openPlaygroundOn(page, project.id);

        const standard = await test.step(`${REGISTRY_MODEL.openAiStandard} caps below the flat value it replaced`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.openAiStandard);
          const range = await readRangeAndAssertClamp(page, playground, 'gpt-4o-mini');
          expect(range.min, 'the floor on a Chat Completions key is 1').toBe(1);
          expect(
            range.max,
            `this model's own limit is well under the ${FLAT_CAP_BEFORE.openAi} every OpenAI ` +
              'model used to be offered, so a build still using the flat cap fails here',
          ).toBeLessThan(FLAT_CAP_BEFORE.openAi);
          return range;
        });

        await test.step(`${REGISTRY_MODEL.openAiReasoning} is offered a different ceiling`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.openAiReasoning);
          const range = await readRangeAndAssertClamp(page, playground, 'gpt-5-mini');
          expect(range.min, 'the floor does not move with the model').toBe(1);
          // The discriminator for the whole test: two models, two ceilings. A
          // build reading a single constant — whatever its value — passes every
          // absolute comparison above and fails this one.
          expect(
            range.max,
            'the ceiling tracks the selected model rather than the provider',
          ).not.toBe(standard.max);
        });

        await test.step(`${REGISTRY_MODEL.openAiWithoutPricingCap} falls back to the old flat OpenAI cap`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.openAiWithoutPricingCap);
          const range = await readRangeAndAssertClamp(page, playground, 'gpt-live-1');
          expect(range.min, 'the floor is the same on a model with no pricing row').toBe(1);
          // Pinned, unlike the two above: this number is the frontend's own
          // `MAX_COMPLETION_TOKENS_FALLBACK`, not a value read out of the
          // generated pricing artifact. It is what the panel must offer when
          // the lookup finds nothing — a build that dropped the fallback would
          // leave the control with no ceiling at all.
          expect(
            range.max,
            'a model the pricing data gives no max_output_tokens for keeps the old flat ceiling',
          ).toBe(FLAT_CAP_BEFORE.openAi);
          expect(
            range.max,
            'and it is a fallback, not the same lookup the listed models take',
          ).not.toBe(standard.max);
          // What this case is NOT evidence of, so nobody reads it as more: the
          // fallback happens to equal what the pricing data lists for
          // gpt-5-mini today, so a build that ignored the data entirely would
          // also land here. The step above is what separates those two, and it
          // does it by comparing two models rather than by naming a number.
        });
      },
    );

    test(
      'an Anthropic model is capped by its own limit rather than the flat Claude ceiling',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        test.setTimeout(180_000);
        expect(
          modelRegistryProviders.providers,
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const playground = await openPlaygroundOn(page, project.id);

        await playground.selectModel(0, REGISTRY_MODEL.claudeWithSampling);
        const range = await readRangeAndAssertClamp(page, playground, 'claude-sonnet-4-6');

        expect(range.min, 'the floor on Anthropic is 1 — a streamed run refuses 0').toBe(1);
        // Deliberately "not 64000" rather than "greater than": this model's own
        // limit is HIGHER than the flat Anthropic cap it replaces, so the change
        // moves the ceiling in both directions depending on the model and
        // "greater than" would read as a one-way claim that is not the rule.
        expect(
          range.max,
          `the ceiling is this model's own, not the flat ${FLAT_CAP_BEFORE.anthropic} ` +
            'every Claude used to be held to',
        ).not.toBe(FLAT_CAP_BEFORE.anthropic);
      },
    );

    test(
      'an OpenAI key on the Responses API raises the floor from 1 to 16',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        test.setTimeout(180_000);
        expect(modelRegistryProviders.providers).toContain('openai');

        // The floor the Responses API enforces: it rejects max_output_tokens
        // below 16, so a build that kept the floor at 1 gives every run a 400.
        const RESPONSES_API_FLOOR = 16;
        const BELOW_THE_FLOOR = '5';

        const pin = await pinOpenAiPipelineMode(page, 'responses_api');
        const playground = await openPlaygroundOn(page, project.id);

        await test.step(`Open the panel on ${REGISTRY_MODEL.openAiStandard}`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.openAiStandard);
          await playground.openModelParameters(0);
          // Asserted before the floor is read: with no OpenAI key to rewrite,
          // the panel would fall back to the Chat Completions default and a
          // floor of 1 would be reported as the Responses API's own.
          expectPinApplied(pin);
        });

        await test.step('The floor is 16, and a lower typed value is raised to it', async () => {
          const range = await playground.readSliderRange(CONTROL);
          expect(range.min, 'the Responses API floor').toBe(RESPONSES_API_FLOOR);

          await playground.setSliderValue(CONTROL, BELOW_THE_FLOOR);
          await expect(
            playground.sliderInput(CONTROL),
            `${BELOW_THE_FLOOR} is below the floor, so the panel commits ${RESPONSES_API_FLOOR}`,
          ).toHaveValue(String(RESPONSES_API_FLOOR));
          await playground.closeModelParameters();
        });

        await test.step('…and the request carries 16, never the typed 5', async () => {
          const body = await captureCompletionBody(page, () => playground.clickRun());
          expect(body.model).toBe('gpt-4o-mini');
          expect(
            body.max_completion_tokens,
            `sending ${BELOW_THE_FLOOR} to the Responses API is the 400 this floor prevents`,
          ).toBe(RESPONSES_API_FLOOR);
          await playground.waitForFreeRunIdle();
        });

        await test.step('The same key back on Chat Completions floors at 1 again', async () => {
          // The control that makes the floor mean something: without it, "the
          // minimum is 16" is equally satisfied by a build that holds every
          // OpenAI key to 16, which would be a floor nobody asked for.
          pin.set('chat_completions_api');
          await page.reload();
          await playground.waitForReady();
          await playground.openModelParameters(0);
          const range = await playground.readSliderRange(CONTROL);
          expect(range.min, 'Chat Completions accepts 1, so the floor drops back').toBe(1);
        });
      },
    );
  },
);
