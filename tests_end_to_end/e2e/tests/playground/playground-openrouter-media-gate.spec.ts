import {
  test,
  expect,
  REGISTRY_MODEL,
  OPEN_ROUTER_MAX_TOKENS_LIMIT,
  OPEN_ROUTER_GROUP,
  IMAGE_ONLY_PROMPT_CONTENT,
} from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * The Playground's media gate must read an OpenRouter model's modality flags
 * from the same price file it reads its token ceiling from (opik#9132).
 *
 * That PR added `model_prices_openrouter.json` to `modelCapabilities.ts`
 * specifically to supply "max-output limits and modality flags" for OpenRouter
 * models, now that LiteLLM's table no longer lists them. Only half of it
 * landed. `getMaxOutputTokens` got a dedicated `getOpenRouterMaxOutputTokens`
 * that prepends `openrouter/` before looking the row up; `supportsImageInput`
 * has no such sibling, so it looks `amazon/nova-lite-v1` and `nova-lite-v1` up
 * against a file keyed `openrouter/amazon/nova-lite-v1`, finds nothing, and
 * defaults to false.
 *
 * The user-visible result is a vision model that will not take a picture: Run
 * is disabled with "the selected model doesn't support media input", and there
 * is nothing the user can do about it and no hint that the refusal is wrong.
 *
 * ## What each test is for
 *
 * The first is the half that works, and it carries the two controls the second
 * one needs to mean anything:
 *
 *  - a NATIVE vision model with the identical image is enabled, so "Run is
 *    disabled" can be told apart from a gate that refuses every image;
 *  - a genuinely non-vision OpenRouter model with the identical image is
 *    refused, so the gate is shown to be doing its job rather than being off;
 *  - and the vision model's own token ceiling is read from the panel and shown
 *    to be BELOW the flat OpenRouter cap, which is the deployed build saying it
 *    found that model's row. Without that, "the modality flag was not read"
 *    could not be told apart from "this deployment's price file has no row for
 *    this model at all", and the second test would be pinning the wrong thing.
 *
 * The second test is the refusal itself, marked expected-to-fail.
 *
 * ## Why the image is seeded as an image and nothing else
 *
 * `hasUnsupportedMedia` is a disjunction over images, videos and audio
 * independently, so the existing `mediaPrompt` fixture — which carries all
 * three — is refused by every model alive, including the native control. The
 * `imageOnlyPrompt` fixture exists for exactly this: with one image part,
 * "enabled" is reachable and the control can exist.
 *
 * ## Deterministic and provider-free
 *
 * The gate renders off the model identifier and the shipped price file, and
 * nothing here presses Run or submits a dialog — the models are only SELECTED.
 * No request reaches a provider, nothing is generated and nothing is billed.
 */

/** `runDisabledReason`'s media branch, verbatim. */
const MEDIA_REFUSED_REASON =
  "Some prompts contain media but the selected model doesn't support media input";

/** The header's tooltip when a free-mode run is allowed. */
const RUNNABLE_REASON = 'Run your prompts';

/** The row the default variant card opens as a User message. */
const USER_ROW = 1;

/** The control id the OpenRouter panel mounts its output ceiling as. */
const MAX_TOKENS_CONTROL = 'maxTokens';

/**
 * Open the Playground with the seeded image sitting in its User message, and
 * prove the image is really there.
 *
 * The proof is load-bearing rather than decorative: every assertion in both
 * tests is about how a model reacts to an image in the message, and a message
 * that never received one would satisfy "Run is enabled" having tested nothing
 * — permanently, and invisibly.
 */
async function openPlaygroundWithImage(
  page: Page,
  projectId: string,
  prompt: { name: string; versionLabel: string; imageUrl: string },
): Promise<PlaygroundPage> {
  const playground = new PlaygroundPage(page, projectId);

  await test.step('Open the Playground', async () => {
    await playground.goto();
    await playground.waitForReady();
    expect(
      await playground.messageRoleAt(USER_ROW),
      'the row the image is loaded into starts out as a User message',
    ).toBe('User');
  });

  await test.step('Load the image prompt into the User row', async () => {
    await playground.loadTextPromptVersionIntoMessage(
      USER_ROW,
      prompt.name,
      prompt.versionLabel,
    );
    await playground.waitForLoadedPromptVersionInMessage(
      USER_ROW,
      prompt.name,
      prompt.versionLabel,
    );
  });

  await test.step('The image really is in the message', async () => {
    const messages = await playground.storedMessages();
    expect(
      messages[USER_ROW].content,
      'the user row holds the text part and the image part, and nothing else',
    ).toEqual(IMAGE_ONLY_PROMPT_CONTENT);
    await playground.hoverMessageRow(USER_ROW);
    await expect(
      playground.mediaTag(USER_ROW, prompt.imageUrl),
      'the image tag is on screen',
    ).toBeVisible();
  });

  return playground;
}

test.describe(
  'Playground — the media gate on an OpenRouter model',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'a native vision model takes the image, a non-vision OpenRouter model does not, and the vision model’s own row is reachable',
      { tag: ['@cap:playground.message-role-media'] },
      async ({
        modelRegistryProviders,
        openRouterVisionModels,
        imageOnlyPrompt,
        project,
        page,
      }) => {
        test.setTimeout(240_000);
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable, for the native control',
        ).toContain('openai');

        const playground = await openPlaygroundWithImage(page, project.id, imageOnlyPrompt);

        await test.step(`${REGISTRY_MODEL.openAiStandard} accepts the image`, async () => {
          // The positive control, and the reason the refusals below mean
          // anything: a gate that refused every image would satisfy both of
          // them. Native, so its modality flag comes from the LiteLLM table
          // this release did not touch.
          await playground.selectModel(0, REGISTRY_MODEL.openAiStandard);
          await expect(
            playground.headerRunButton(),
            'a native vision model with an image in the message is runnable',
          ).toBeEnabled();
          // The generic reason, not merely the absence of the media one: a
          // build that cleared the reason without enabling the button, or
          // enabled it while still claiming the media was unsupported, would
          // pass a bare "not disabled" check.
          expect(await playground.headerRunTooltipText()).toBe(RUNNABLE_REASON);
        });

        await test.step(`${openRouterVisionModels.nonVision} refuses it, correctly`, async () => {
          // The gate doing its job. This model's row carries
          // `supports_vision: false`, so a refusal here is the right answer —
          // which is what separates "the flag is not read" from "the gate is
          // broken" in the test below.
          await playground.selectModelFromProvider(
            0,
            OPEN_ROUTER_GROUP,
            openRouterVisionModels.nonVision,
          );
          await expect(
            playground.headerRunButton(),
            'an OpenRouter model whose row claims no vision must refuse an image',
          ).toBeDisabled();
          expect(await playground.headerRunTooltipText()).toBe(MEDIA_REFUSED_REASON);
        });

        await test.step(`${openRouterVisionModels.visionCapable}'s price row is present in this build`, async () => {
          // The premise the expected-to-fail test below rests on, asserted here
          // where a failure is loud rather than swallowed by `test.fail()`.
          //
          // The token ceiling and the modality flags come from the SAME row of
          // the SAME file. `getOpenRouterMaxTokensLimit` is
          // `min(10000, row.max_output_tokens)` when it finds a row and a flat
          // 10000 when it does not — so a ceiling strictly below 10000 is the
          // deployed build stating that it found this model's row and read a
          // field out of it. The modality half of that row is what the gate
          // fails to reach.
          //
          // Compared rather than pinned to 5120: the price file is a generated
          // artifact refreshed by automation, so asserting its current number
          // would make this fail the day OpenRouter publishes a different
          // limit, for a reason that has nothing to do with the gate.
          await playground.selectModelFromProvider(
            0,
            OPEN_ROUTER_GROUP,
            openRouterVisionModels.visionCapable,
          );
          await playground.openModelParameters(0);
          const range = await playground.readSliderRange(MAX_TOKENS_CONTROL);
          await playground.closeModelParameters();
          expect(
            range.max,
            `${openRouterVisionModels.visionCapable} must take its ceiling from its own price ` +
              `row rather than the flat ${OPEN_ROUTER_MAX_TOKENS_LIMIT} fallback — otherwise the ` +
              'row is absent from this build and the gate has nothing to read',
          ).toBeLessThan(OPEN_ROUTER_MAX_TOKENS_LIMIT);
        });
      },
    );

    test(
      'a vision-capable OpenRouter model takes the same image',
      { tag: ['@cap:playground.message-role-media'] },
      async ({
        modelRegistryProviders,
        openRouterVisionModels,
        imageOnlyPrompt,
        project,
        page,
      }) => {
        // QA: failing on staging for 2.2.119, and reproduced in the code.
        // `amazon/nova-lite-v1` carries `supports_vision: true` on its row in
        // `model_prices_openrouter.json`, but `supportsImageInput` has no
        // OpenRouter-aware sibling to `getOpenRouterMaxOutputTokens`: it looks
        // up `amazon/nova-lite-v1` and `nova-lite-v1` against a file keyed
        // `openrouter/amazon/nova-lite-v1`, finds neither, and defaults to
        // false. So the Playground disables Run and tells the user the model
        // does not support media input.
        //
        // The test above establishes that this build DOES hold the row — it
        // reads the row's own token ceiling out of the panel — and that the
        // gate both passes a native vision model and refuses a genuinely
        // non-vision OpenRouter one, so neither "no row" nor "the gate is off"
        // explains this.
        //
        // The assertions below are the CORRECT behaviour and are left intact.
        // Remove test.fail() once the modality lookup prepends `openrouter/`;
        // Playwright reports it the moment this passes.
        test.fail();
        test.setTimeout(240_000);
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable, as in the test above',
        ).toContain('openai');

        const playground = await openPlaygroundWithImage(page, project.id, imageOnlyPrompt);

        await test.step(`${openRouterVisionModels.visionCapable} is runnable with the image`, async () => {
          await playground.selectModelFromProvider(
            0,
            OPEN_ROUTER_GROUP,
            openRouterVisionModels.visionCapable,
          );
          await expect(
            playground.headerRunButton(),
            'an OpenRouter model whose row claims supports_vision must accept an image',
          ).toBeEnabled();
          expect(
            await playground.headerRunTooltipText(),
            'and must not be telling the user its model has no media support',
          ).toBe(RUNNABLE_REASON);
        });
      },
    );
  },
);
