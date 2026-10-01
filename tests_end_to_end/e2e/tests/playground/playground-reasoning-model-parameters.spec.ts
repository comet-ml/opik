import { test, expect, PROVIDER_GROUP, REGISTRY_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * The playground must not send model parameters the provider rejects
 * (OPIK-8565, opik#8603).
 *
 * Two halves that can regress apart, which is why both are asserted in one
 * test per model family:
 *
 *   - the PANEL decides which controls to mount (`visibleControls.ts`);
 *   - the REQUEST BUILDER decides which keys the body carries
 *     (`sanitizeConfigForRequest`).
 *
 * A build where they disagree is silently wrong in the expensive direction: the
 * panel shows a Temperature the request drops (the user tunes nothing), or the
 * body carries a `top_p` the panel never offered and OpenAI answers
 * "Unsupported parameter". Neither surface alone can see it.
 *
 * `playground-model-parameters.spec.ts` covers the same contract for Anthropic
 * and is deliberately Anthropic-only — the sampling pair there is a single
 * exclusive choice. These are the other two gates the same PR introduced, and
 * neither is reachable from that spec.
 *
 * Deterministic and provider-free despite naming real models: the panel renders
 * off the model identifier, the provider list is answered in the browser
 * (see the `modelRegistryProviders` fixture), and the completion is
 * short-circuited at the browser so nothing is ever generated or billed.
 */

/** The completion proxy, on both the `/opik/api` and bare `/api` mounts. */
function isChatCompletion(url: string): boolean {
  return new URL(url).pathname.endsWith('/v1/private/chat/completions');
}

/** The four parameters an OpenAI reasoning model takes none of. */
const SAMPLING_AND_PENALTY_KEYS = [
  'temperature',
  'top_p',
  'frequency_penalty',
  'presence_penalty',
] as const;

/** The control ids behind those four, as the panel mounts them. */
const SAMPLING_AND_PENALTY_CONTROLS = [
  'temperature',
  'topP',
  'frequencyPenalty',
  'presencePenalty',
] as const;

test.describe(
  'Playground — model parameters the provider rejects',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'an OpenAI reasoning model offers no sampling or penalty control and sends none',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${REGISTRY_MODEL.openAiReasoning}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.openAiReasoning);
        });

        await test.step('The panel mounts the reasoning controls and none of the rejected ones', async () => {
          await playground.openModelParameters(0);

          for (const control of SAMPLING_AND_PENALTY_CONTROLS) {
            // Presence, not enabled-ness: a control that was merely dimmed
            // would still hold a value the request builder could pick up.
            await expect(
              playground.sliderInput(control),
              `"${control}" must not be mounted for a reasoning model`,
            ).toHaveCount(0);
          }

          await expect(
            playground.sliderInput('maxCompletionTokens'),
            'Max output tokens is still tunable',
          ).toHaveCount(1);
          await expect(
            playground.modelParameterLabel('Reasoning effort'),
            'and the reasoning model gets its own effort control',
          ).toHaveCount(1);

          // The whole mounted set, so a control reappearing anywhere in the
          // panel fails rather than being missed by the four lookups above.
          expect(
            await playground.mountedModelParameterIds(),
            'every slider the reasoning panel mounts',
          ).toEqual(['maxCompletionTokens', 'throttling', 'maxConcurrentRequests']);

          await playground.closeModelParameters();
        });

        const body = await test.step('Capture the outbound completion body', async () => {
          await playground.fillFirstMessage('Reply with the single word OK.');
          return captureCompletionBody(page, () => playground.clickRun());
        });

        await test.step('The body carries the effort and none of the rejected parameters', async () => {
          expect(body.model, 'the request names the selected model').toBe('gpt-5-mini');
          expect(
            Object.keys(body),
            'reasoning_effort is what this model tunes instead',
          ).toContain('reasoning_effort');
          expect(Object.keys(body), 'and the token cap still goes').toContain(
            'max_completion_tokens',
          );
          for (const key of SAMPLING_AND_PENALTY_KEYS) {
            expect(
              Object.keys(body),
              `"${key}" is rejected by a reasoning model and must not be sent`,
            ).not.toContain(key);
          }
        });
      },
    );

    test(
      'a non-reasoning OpenAI model keeps every sampling and penalty parameter',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        // The control that makes the test above mean something: without it,
        // "the reasoning model sends none of the four" is equally satisfied by
        // a build that sends none of them for anybody.
        expect(modelRegistryProviders.providers).toContain('openai');

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${REGISTRY_MODEL.openAiStandard}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.openAiStandard);
        });

        await test.step('All four controls are mounted', async () => {
          await playground.openModelParameters(0);
          for (const control of SAMPLING_AND_PENALTY_CONTROLS) {
            await expect(
              playground.sliderInput(control),
              `"${control}" is tunable on a non-reasoning model`,
            ).toHaveCount(1);
          }
          await expect(
            playground.modelParameterLabel('Reasoning effort'),
            'and there is no effort control on a non-reasoning model',
          ).toHaveCount(0);
          await playground.closeModelParameters();
        });

        await test.step('And all four are in the body', async () => {
          await playground.fillFirstMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model).toBe('gpt-4o-mini');
          for (const key of SAMPLING_AND_PENALTY_KEYS) {
            expect(Object.keys(body), `"${key}" is sent for a non-reasoning model`).toContain(key);
          }
          expect(
            Object.keys(body),
            'no reasoning_effort for a model that has no effort control',
          ).not.toContain('reasoning_effort');
        });
      },
    );

    // The Gemini gate is a regex over the model id AFTER the `vertex_ai/`
    // prefix is stripped, so the two provider spellings of one model are the
    // pair that can regress apart — and they are offered under the SAME display
    // name, which is why each case names its provider group explicitly.
    for (const [groupLabel, group] of [
      ['Gemini', PROVIDER_GROUP.gemini],
      ['Vertex AI', PROVIDER_GROUP.vertexAi],
    ] as const) {
      test(
        `a Gemini 3 model on ${groupLabel} drops temperature and top_p while Gemini 2.x keeps them`,
        { tag: ['@cap:playground.configure-model-settings'] },
        async ({ modelRegistryProviders, project, page }) => {
          expect(modelRegistryProviders.providers).toContain(
            group === PROVIDER_GROUP.vertexAi ? 'vertex-ai' : 'gemini',
          );
          const prefix = group === PROVIDER_GROUP.vertexAi ? 'vertex_ai/' : '';
          const playground = new PlaygroundPage(page, project.id);

          await test.step('Open the playground', async () => {
            await playground.goto();
            await playground.waitForReady();
          });

          await test.step(`${REGISTRY_MODEL.gemini3} offers neither sampling control`, async () => {
            await playground.selectModelFromProvider(0, group, REGISTRY_MODEL.gemini3);
            await playground.openModelParameters(0);
            await expect(
              playground.sliderInput('temperature'),
              'no Temperature on the Gemini 3 generation',
            ).toHaveCount(0);
            await expect(
              playground.sliderInput('topP'),
              'and no Top P either',
            ).toHaveCount(0);
            await expect(
              playground.sliderInput('maxOutputTokens'),
              'the panel is not empty — Max output tokens is still there',
            ).toHaveCount(1);
            await playground.closeModelParameters();
          });

          await test.step('…and its request carries neither', async () => {
            await playground.fillFirstMessage('Reply with the single word OK.');
            const body = await captureCompletionBody(page, () => playground.clickRun());

            expect(body.model, 'the Gemini 3 id, with this provider’s spelling').toBe(
              `${prefix}gemini-3-flash-preview`,
            );
            expect(Object.keys(body), 'no temperature').not.toContain('temperature');
            expect(Object.keys(body), 'no top_p').not.toContain('top_p');
          });

          await test.step(`${REGISTRY_MODEL.gemini2} on the same provider keeps both`, async () => {
            await playground.selectModelFromProvider(0, group, REGISTRY_MODEL.gemini2);
            await playground.openModelParameters(0);
            await expect(
              playground.sliderInput('temperature'),
              'Temperature is tunable on Gemini 2.x',
            ).toHaveCount(1);
            await expect(playground.sliderInput('topP'), 'and so is Top P').toHaveCount(1);
            await playground.closeModelParameters();

            const body = await captureCompletionBody(page, () => playground.clickRun());
            expect(body.model).toBe(`${prefix}gemini-2.5-flash`);
            expect(Object.keys(body), 'temperature is sent for Gemini 2.x').toContain(
              'temperature',
            );
            expect(Object.keys(body), 'and so is top_p').toContain('top_p');
          });
        },
      );
    }
  },
);

/**
 * Run `act` and return the body of the completion request the browser sent.
 *
 * The route is installed (idempotently — Playwright keeps the most recent
 * matching handler) so the request is answered in the browser and never reaches
 * the backend proxy: every assertion here is on what was SENT, and letting it
 * through would have a provider generate a completion nothing reads.
 *
 * The waiter is armed before `act` because the POST is in flight the moment Run
 * is pressed, so subscribing afterwards would race it.
 */
async function captureCompletionBody(
  page: import('@playwright/test').Page,
  act: () => Promise<void>,
): Promise<Record<string, unknown>> {
  await page.route(
    (url) => isChatCompletion(url.toString()),
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'data: [DONE]\n\n',
      }),
  );
  const sent = page.waitForRequest(
    (r) => r.method() === 'POST' && isChatCompletion(r.url()),
    { timeout: 60_000 },
  );
  await act();
  const body = (await sent).postDataJSON() as Record<string, unknown> | null;
  // Asserted, not defaulted: a Run that sent no body at all would otherwise
  // read as a body with none of the forbidden keys, which is the exact shape
  // every assertion above is looking for.
  expect(body, 'the Run posted a JSON completion body').not.toBeNull();
  return body!;
}
