import { expect, type Page } from '@playwright/test';

/**
 * Making the browser see the workspace's OpenAI key on a chosen
 * `openai_pipeline_mode`.
 *
 * Shared because two specs now assert on behaviour the panel takes from that
 * one field: which reasoning efforts the control offers
 * (`playground-openai-effort-pipeline-mode`, opik#8682) and what floor Max
 * output tokens is held to (`playground-max-output-tokens-range`, opik#8847 —
 * the Responses API rejects `max_output_tokens` below 16).
 *
 * ## Why the field is rewritten in flight and not on the real key
 *
 * The workspace's OpenAI key is shared and pre-existing. Flipping its stored
 * `openai_pipeline_mode` would change behaviour for every other spec and every
 * human on the deployment, and there is no second key to use instead — a
 * built-in provider is one row per workspace. So the real response is fetched
 * and ONE field rewritten on its way to the browser: every other provider, the
 * key's id, its status and the response's shape are the deployment's own.
 *
 * That is deliberately not the browser-side STUB the `modelRegistryProviders`
 * fixture warns against. That warning is about answering the query with a
 * fabricated body, which leaves `useModelSelection` on an empty provider config
 * in about a third of parallel runs; here the body is the real one and the
 * provider list it carries is untouched.
 */

export type OpenAiPipelineMode = 'chat_completions_api' | 'responses_api';

/** `useProviderKeys`' read — `PROVIDER_KEYS_REST_ENDPOINT`, trailing slash and all. */
export function isProviderKeysRead(url: string): boolean {
  return /\/v1\/private\/llm-provider-key\/?$/.test(new URL(url).pathname);
}

export interface PipelineModePin {
  /** Change the mode the next provider-keys read will report. */
  set(mode: OpenAiPipelineMode): void;
  /** How many OpenAI key rows have been rewritten so far. */
  rewrites(): number;
}

/**
 * Make the browser see the workspace's OpenAI key on a chosen pipeline mode.
 *
 * One route for the whole test, with the mode behind a mutable cell, so a test
 * that flips the mode and reloads does not depend on Playwright's
 * last-handler-wins ordering to undo an earlier pin.
 */
export async function pinOpenAiPipelineMode(
  page: Page,
  initial: OpenAiPipelineMode,
): Promise<PipelineModePin> {
  let mode = initial;
  let rewrites = 0;

  await page.route(
    (url) => isProviderKeysRead(url.toString()),
    async (route) => {
      const response = await route.fetch();
      const body = (await response.json()) as {
        content?: Array<Record<string, unknown>>;
      };
      body.content = (body.content ?? []).map((key) => {
        if (key.provider !== 'openai') return key;
        rewrites += 1;
        return {
          ...key,
          configuration: {
            ...((key.configuration as Record<string, unknown>) ?? {}),
            openai_pipeline_mode: mode,
          },
        };
      });
      await route.fulfill({ response, json: body });
    },
  );

  return {
    set: (next) => {
      mode = next;
    },
    rewrites: () => rewrites,
  };
}

/**
 * Assert the pin actually had something to rewrite.
 *
 * Without this a spec is vacuous on a workspace with no OpenAI key: the map
 * would be a no-op, the panel would fall back to the Chat Completions default,
 * and both "Max is not offered" and "the floor is still 1" would pass for a
 * reason that has nothing to do with the gate under test.
 */
export function expectPinApplied(pin: PipelineModePin): void {
  expect(
    pin.rewrites(),
    'the workspace must have an OpenAI key for the pipeline mode to be pinned on — ' +
      'with none, every assertion built on it passes by default',
  ).toBeGreaterThan(0);
}
