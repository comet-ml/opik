import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';
import type { Page } from '@playwright/test';

/**
 * The reasoning effort `max`, offered only on an OpenAI key set to the
 * Responses API (opik#8682 / OPIK-8575).
 *
 * OpenAI accepts `reasoning_effort: "max"` for the gpt-5.6 and gpt-6 families
 * through the Responses API only; Chat Completions answers 400. So the panel
 * now learns the OpenAI key's `openai_pipeline_mode` from the provider-keys
 * query and gates the option on it, and anything already stored as `max` is
 * shown and sent as `high` while the key is on Chat Completions.
 *
 * Both failure directions are user-visible and neither is loud: offering Max on
 * a Chat Completions key gives every run a 400, and dropping it on a Responses
 * API key silently downgrades the model the user is paying for.
 *
 * ## Why this is not already covered
 *
 * The PR's own unit tests are thorough but mock BOTH `useProviderKeys` and the
 * proxy, so nothing there asserts the control against a real provider-keys
 * response or a really-built request body. In e2e,
 * `configure-model-settings` is Anthropic-only by its own taxonomy note,
 * `playground-reasoning-model-parameters.spec.ts` asserts which parameters a
 * reasoning model sends but never the effort's VALUE, and a grep of the estate
 * for `reasoningEffort` / `openai_pipeline_mode` / `responses_api` returned
 * nothing before this spec.
 *
 * ## How the pipeline mode is set, and why not on the real key
 *
 * The workspace's OpenAI key is shared and pre-existing. Flipping its stored
 * `openai_pipeline_mode` would change behaviour for every other spec and every
 * human on this deployment, and there is no second key to use instead — a
 * built-in provider is one row per workspace. So the real response is fetched
 * and ONE field rewritten on its way to the browser: every other provider, the
 * key's id, its status and the response's shape are the deployment's own.
 *
 * That is deliberately not the browser-side STUB the `modelRegistryProviders`
 * fixture warns against. That warning is about answering the query with a
 * fabricated body, which leaves `useModelSelection` on an empty provider config
 * in about a third of parallel runs; here the body is the real one and the
 * provider list it carries is untouched.
 *
 * ## RECORDED GAP, not a claim
 *
 * The PR's hardest case is not asserted here: a stored `max` surviving a model
 * change made WHILE the provider-keys request is still in flight (the race that
 * needed three follow-up commits). Reaching it means holding the keys response
 * open, but the model picker is itself downstream of that response — with it
 * pending there is no picker to change the model in — so the only way to drive
 * it is a timed gap, which is a flake generator rather than a test. It stays
 * covered by `modelUtils.test.ts`'s "keeps the stored max, then coerces it once
 * the key turns out to be on Chat Completions". Nothing below should be read as
 * evidence about it.
 *
 * Deterministic and billing-free: every completion is short-circuited at the
 * browser, and the models are registered unbilled by `modelRegistryProviders`.
 */

type OpenAiPipelineMode = 'chat_completions_api' | 'responses_api';

/** The five efforts gpt-5.5 and gpt-5.6-sol both take on Chat Completions. */
const CHAT_COMPLETIONS_EFFORTS = ['None', 'Low', 'Medium', 'High', 'xHigh'] as const;

/** The one the Responses API adds, for a model that declares it. */
const RESPONSES_ONLY_EFFORT = 'Max';

/** `useProviderKeys`' read — `PROVIDER_KEYS_REST_ENDPOINT`, trailing slash and all. */
function isProviderKeysRead(url: string): boolean {
  return /\/v1\/private\/llm-provider-key\/?$/.test(new URL(url).pathname);
}

interface PipelineModePin {
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
async function pinOpenAiPipelineMode(
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

  return { set: (next) => { mode = next; }, rewrites: () => rewrites };
}

/**
 * Assert the pin actually had something to rewrite.
 *
 * Without this the whole spec is vacuous on a workspace with no OpenAI key: the
 * map would be a no-op, the panel would fall back to the Chat Completions
 * default, and "Max is not offered" would pass for a reason that has nothing to
 * do with the gate.
 */
function expectPinApplied(pin: PipelineModePin): void {
  expect(
    pin.rewrites(),
    'the workspace must have an OpenAI key for the pipeline mode to be pinned on — ' +
      'with none, every assertion here passes by default',
  ).toBeGreaterThan(0);
}

test.describe(
  'Playground — OpenAI reasoning effort and the key’s pipeline mode',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'Max is offered only while the OpenAI key is on the Responses API',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const pin = await pinOpenAiPipelineMode(page, 'chat_completions_api');
        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${REGISTRY_MODEL.openAiResponsesOnlyEffort}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.openAiResponsesOnlyEffort);
        });

        await test.step('On a Chat Completions key the control stops at xHigh', async () => {
          await playground.openModelParameters(0);
          // The whole offered list, in order — not just "Max is absent". A
          // build that dropped the entire Responses branch and one that dropped
          // every effort option look the same through a single negative.
          expect(
            await playground.reasoningEffortOptions(),
            'Chat Completions offers exactly the five values it accepts',
          ).toEqual([...CHAT_COMPLETIONS_EFFORTS]);
          await playground.closeModelParameters();
          expectPinApplied(pin);
        });

        await test.step('Flip the same key to the Responses API and reload', async () => {
          pin.set('responses_api');
          await page.reload();
          await playground.waitForReady();
        });

        await test.step('…and Max appears, on top of the same five', async () => {
          await playground.openModelParameters(0);
          expect(
            await playground.reasoningEffortOptions(),
            'the Responses API adds Max and takes nothing away',
          ).toEqual([...CHAT_COMPLETIONS_EFFORTS, RESPONSES_ONLY_EFFORT]);
          await playground.closeModelParameters();
        });
      },
    );

    test(
      'a reasoning model that declares no Responses-only effort never offers Max',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        // The control that makes the test above mean something: without it,
        // "Max appears on responses_api" is equally satisfied by a build that
        // offers Max to every OpenAI reasoning model in that mode, which is the
        // 400 this change exists to prevent.
        expect(modelRegistryProviders.providers).toContain('openai');

        const pin = await pinOpenAiPipelineMode(page, 'responses_api');
        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${REGISTRY_MODEL.openAiEffortWithoutMax}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.openAiEffortWithoutMax);
        });

        for (const mode of ['responses_api', 'chat_completions_api'] as const) {
          await test.step(`Under ${mode} it offers the five and only the five`, async () => {
            pin.set(mode);
            await page.reload();
            await playground.waitForReady();
            await playground.openModelParameters(0);
            expect(
              await playground.reasoningEffortOptions(),
              `${REGISTRY_MODEL.openAiEffortWithoutMax} declares no responsesApiOnlyEffortOptions, ` +
                'so the pipeline mode must change nothing for it',
            ).toEqual([...CHAT_COMPLETIONS_EFFORTS]);
            await playground.closeModelParameters();
          });
        }

        expectPinApplied(pin);
      },
    );

    test(
      'a stored max is shown and sent as high once the key is back on Chat Completions',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        expect(modelRegistryProviders.providers).toContain('openai');

        const pin = await pinOpenAiPipelineMode(page, 'responses_api');
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Store Max against the model, with the key on the Responses API', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.openAiResponsesOnlyEffort);
          await playground.openModelParameters(0);
          await playground.selectReasoningEffort(RESPONSES_ONLY_EFFORT);
          expect(
            await playground.readReasoningEffort(),
            'the panel keeps the effort that was just picked',
          ).toBe(RESPONSES_ONLY_EFFORT);
          await playground.closeModelParameters();
          expectPinApplied(pin);
        });

        await test.step('In Responses mode the request really carries max', async () => {
          // The positive control for the coercion below: without it, "the body
          // says high" is equally satisfied by a build that can never send max
          // at all, which would be the silent downgrade rather than the fix.
          await playground.fillUserMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());
          expect(body.model, 'the request names the selected model').toBe('gpt-5.6-sol');
          expect(body.reasoning_effort, 'a Responses API key may send max').toBe('max');
        });

        await test.step('Move the key back to Chat Completions and reload', async () => {
          pin.set('chat_completions_api');
          await page.reload();
          await playground.waitForReady();
        });

        await test.step('The panel reads High — the stored max is masked, not rejected', async () => {
          await playground.openModelParameters(0);
          expect(
            await playground.readReasoningEffort(),
            'resolveEffort must fall back to High for a stored value the key cannot take',
          ).toBe('High');
          await playground.closeModelParameters();
        });

        await test.step('…and the request carries high, not max and not a 400', async () => {
          const body = await captureCompletionBody(page, () => playground.clickRun());
          expect(body.model).toBe('gpt-5.6-sol');
          expect(
            body.reasoning_effort,
            'sending the stored max to Chat Completions is the 400 this change prevents',
          ).toBe('high');
        });
      },
    );
  },
);
