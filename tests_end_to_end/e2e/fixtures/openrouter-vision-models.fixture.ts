import { test as baseTest, expect } from './image-only-prompt.fixture';
import { registerUnbilledModel } from '../core/llm-model-policy';
import { registryModelIds, type RegistryModelsMap } from '../core/model-registry';

/**
 * Two OpenRouter models whose rows in `model_prices_openrouter.json` disagree
 * about `supports_vision`, named for what each stands for.
 *
 * On OpenRouter the picker's display name IS the model id, so these double as
 * both — the same convention `OPEN_ROUTER_MODEL` uses.
 */
export const OPEN_ROUTER_VISION_MODEL = {
  /**
   * `supports_vision: true` on its row, and a row whose `max_output_tokens`
   * (5120) is BELOW the flat 10000 OpenRouter ceiling — which is what makes
   * the row's presence in the deployed build observable from the panel. See
   * the fixture header.
   */
  visionCapable: 'amazon/nova-lite-v1',
  /** `supports_vision: false` on its row: an image here is correctly refused. */
  nonVision: 'aion-labs/aion-2.0',
} as const;

/**
 * The flat ceiling `getOpenRouterMaxTokensLimit` falls back to when it finds no
 * row for the selected model, and the cap it clamps every row's own limit to.
 *
 * `MAX_COMPLETION_TOKENS_FALLBACK` is pinned in the max-output-tokens spec for
 * the same reason this is: it lives in the frontend's own code
 * (`modelUtils.ts`), not in the generated price artifact.
 */
export const OPEN_ROUTER_MAX_TOKENS_LIMIT = 10_000;

export interface OpenRouterVisionModelsRef {
  visionCapable: string;
  nonVision: string;
  /** The whole registry answer, for a spec that wants to assert more of it. */
  registry: RegistryModelsMap;
}

export interface OpenRouterVisionModelsFixtures {
  openRouterVisionModels: OpenRouterVisionModelsRef;
}

/**
 * Makes the Playground's model picker offer the two OpenRouter models a media
 * gate spec needs, and proves it does before a browser opens.
 *
 * ## Why it rides on `openRouterNativeModels`
 *
 * That fixture already ensures the workspace's single OpenRouter key and reads
 * `GET /v1/private/llm/models`, and a built-in provider is ONE row per
 * workspace — so a second fixture writing its own key would be writing the same
 * row. What this adds is the two ids, which are not in `OPEN_ROUTER_MODEL`:
 * those are picked for the reasoning/parameter gate and say nothing about
 * modality flags.
 *
 * ## Why naming real models here bills nothing
 *
 * The media gate renders off the model identifier and the shipped price file:
 * `hasUnsupportedMedia` is evaluated as the picker's selection commits, and the
 * only observable is whether the Run control is enabled. The spec using this
 * never presses Run and never submits a dialog, so nothing reaches OpenRouter
 * even where the workspace holds a real key. The ids are registered unbilled on
 * exactly that basis — the same basis `openRouterNativeModels` registers its
 * nine.
 *
 * ## What it asserts, and what it leaves to the spec
 *
 * It asserts the half that is the same for every case: both ids really are
 * offered under OpenRouter on this deployment, so a missing model fails here
 * naming the registry rather than surfacing as a picker that would not select.
 *
 * It deliberately does NOT assert either model's `supports_vision` flag. That
 * flag lives in a generated artifact the e2e suite does not read, and the
 * deployed frontend's copy of it is not this checkout's — so the only honest
 * way to state the precondition is through the product, which the spec does:
 * the SAME row supplies `max_output_tokens`, and `getOpenRouterMaxOutputTokens`
 * reaches it by prepending `openrouter/` (the sibling lookup
 * `supportsImageInput` lacks). A ceiling below the flat 10000 cap is therefore
 * the deployed build saying "I found this row", which is the premise the gate
 * assertions rest on.
 */
export const test = baseTest.extend<OpenRouterVisionModelsFixtures>({
  openRouterVisionModels: async ({ openRouterNativeModels }, use) => {
    for (const model of Object.values(OPEN_ROUTER_VISION_MODEL)) registerUnbilledModel(model);

    const openRouterIds = registryModelIds(openRouterNativeModels.registry, 'openrouter');
    for (const model of Object.values(OPEN_ROUTER_VISION_MODEL)) {
      expect(
        openRouterIds.has(model),
        `this deployment's registry offers "${model}" under OpenRouter`,
      ).toBe(true);
    }

    await use({
      visionCapable: OPEN_ROUTER_VISION_MODEL.visionCapable,
      nonVision: OPEN_ROUTER_VISION_MODEL.nonVision,
      registry: openRouterNativeModels.registry,
    });
  },
});

export { expect };
