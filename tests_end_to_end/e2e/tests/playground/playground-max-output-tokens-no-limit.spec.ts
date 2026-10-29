import { test, expect, PROVIDER_GROUP, REGISTRY_MODEL } from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';

/**
 * On the providers whose Max output tokens slider starts at 0, a 0 means "no
 * limit": the panel says so and the request leaves the key out entirely
 * (OPIK-8636, opik#8847 and opik#8879).
 *
 * `sanitizeConfigForRequest` drops a 0 cap for OpenRouter, Gemini, Vertex AI
 * and a custom gateway; `getNoOutputLimitHint` renders the matching note under
 * the control. This change added the last three — and the one spec that asserts
 * the shape, `playground-openrouter-model-parameters.spec.ts`, drives only
 * OpenRouter, where it was already the behaviour, and skips entirely without an
 * `OPENROUTER_API_KEY`. So the branches this change introduced have no coverage
 * on any runner today.
 *
 * Silent wrongness, not a loud failure, which is why it is worth a spec: a
 * Gemini run that goes out as `max_completion_tokens: 0` is not an error — the
 * provider answers with an empty completion, and the user sees a model that
 * said nothing.
 *
 * Both surfaces per case, because the hint and the omission are built by
 * different code and regress apart: the note comes from `getNoOutputLimitHint`
 * in the panel, the omission from `sanitizeConfigForRequest` in the request
 * builder. A panel promising no limit while the request pins 0 is exactly the
 * disagreement neither surface can see alone.
 *
 * The same walk also asserts the whole-number guarantee the second PR added: a
 * typed `20.5` commits as `20` and reaches the body as `20`, never as a
 * fraction the backend would truncate on its own.
 *
 * Deterministic and provider-free: the panel renders off the model identifier,
 * the Gemini and Vertex AI registries come from the `modelRegistryProviders`
 * fixture's placeholder keys, the custom provider is REST-seeded against a base
 * URL that refuses every connection, and every completion is fulfilled in the
 * browser by `captureCompletionBody` — so nothing is generated or billed, and
 * nothing depends on a provider's own answer.
 */

/** The hint `getNoOutputLimitHint` renders at 0, verbatim. */
const NO_LIMIT_HINT = "No limit: the provider's own default applies.";

/** Both spellings of the cap, so a key moving between them cannot be missed. */
const CAP_KEYS = ['max_completion_tokens', 'max_tokens'] as const;

/** A positive cap, well inside every panel's ceiling. */
const SET_CAP = 64;

/** Typed to prove the control commits whole numbers. */
const FRACTIONAL_CAP = '20.5';
const FRACTIONAL_CAP_FLOORED = 20;

/**
 * Drive one provider's Max output tokens control through 0, a positive value
 * and a fraction, asserting the panel and the outbound body at each stop.
 *
 * `controlId` is a parameter because the panels disagree on it: Gemini and
 * Vertex AI mount the control as `maxOutputTokens` while the custom panel
 * mounts it as `maxCompletionTokens`, though all three write the same
 * `maxCompletionTokens` config field. A helper hard-coding one of them would
 * silently assert nothing on the other — the lookup would find no control, and
 * `readSliderRange`'s uniqueness assertion is what makes that fail loudly.
 */
async function assertZeroMeansNoLimit(
  page: Page,
  playground: PlaygroundPage,
  opts: { controlId: string; expectedModelId: string },
): Promise<void> {
  const { controlId, expectedModelId } = opts;

  await test.step(`0 means no limit on ${expectedModelId}`, async () => {
    await playground.openModelParameters(0);

    const range = await playground.readSliderRange(controlId);
    expect(
      range.min,
      'this control starts at 0 — that is what makes "no limit" expressible here',
    ).toBe(0);

    await test.step('At 0 the panel says there is no limit', async () => {
      await playground.setSliderValue(controlId, '0');
      await expect(
        playground.sliderInput(controlId),
        'the 0 is committed, not clamped to a floor of 1',
      ).toHaveValue('0');
      await expect(
        playground.sliderHint(controlId),
        'the hint under the control is the panel\'s own claim that nothing is capped',
      ).toHaveText(NO_LIMIT_HINT);
      await playground.closeModelParameters();
    });

    await test.step('…and the request carries no cap at all', async () => {
      const body = await captureCompletionBody(page, () => playground.clickRun());
      expect(body.model, 'the request names the selected model').toBe(expectedModelId);
      for (const key of CAP_KEYS) {
        // Key absence, not a falsy value: `max_completion_tokens: 0` is the
        // exact payload this change exists to stop sending, and it would
        // satisfy any assertion written as "no truthy cap".
        expect(
          Object.keys(body),
          `a 0 cap must be left out, not sent as ${key}: 0`,
        ).not.toContain(key);
      }
      await playground.waitForFreeRunIdle();
    });

    await test.step(`A cap of ${SET_CAP} is shown without the hint and really sent`, async () => {
      // The control that makes the omission mean something: without it, "no cap
      // in the body" is equally satisfied by a build that never sends one,
      // which would silently ignore every limit the user sets.
      await playground.openModelParameters(0);
      await playground.setSliderValue(controlId, String(SET_CAP));
      await expect(playground.sliderInput(controlId)).toHaveValue(String(SET_CAP));
      await expect(
        playground.sliderHint(controlId),
        'the no-limit hint is gone once a limit is set',
      ).toHaveCount(0);
      await playground.closeModelParameters();

      const body = await captureCompletionBody(page, () => playground.clickRun());
      expect(
        body.max_completion_tokens,
        'the cap the panel shows is the cap that goes out',
      ).toBe(SET_CAP);
      await playground.waitForFreeRunIdle();
    });

    await test.step(`A typed ${FRACTIONAL_CAP} commits and is sent as ${FRACTIONAL_CAP_FLOORED}`, async () => {
      await playground.openModelParameters(0);
      await playground.setSliderValue(controlId, FRACTIONAL_CAP);
      await expect(
        playground.sliderInput(controlId),
        'the control commits whole numbers, so the fraction never reaches the config',
      ).toHaveValue(String(FRACTIONAL_CAP_FLOORED));
      await playground.closeModelParameters();

      const body = await captureCompletionBody(page, () => playground.clickRun());
      expect(
        body.max_completion_tokens,
        'and the request carries the whole number, not the fraction',
      ).toBe(FRACTIONAL_CAP_FLOORED);
      await playground.waitForFreeRunIdle();
    });
  });
}

async function openPlaygroundOn(page: Page, projectId: string): Promise<PlaygroundPage> {
  const playground = new PlaygroundPage(page, projectId);
  await test.step('Open the playground and write a prompt', async () => {
    await playground.goto();
    await playground.waitForReady();
    await playground.fillUserMessage('Reply with the single word OK.');
  });
  return playground;
}

test.describe(
  'Playground — Max output tokens 0 means no limit',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    // Gemini and Vertex AI offer the SAME model under the same display name
    // (`gemini-2.5-flash` and `vertex_ai/gemini-2.5-flash`), and
    // `sanitizeConfigForRequest` reaches them through two separate provider
    // branches — so they are a pair that can regress apart, and each case has
    // to name its provider group rather than take whichever option comes first.
    for (const [groupLabel, group, modelId] of [
      ['Gemini', PROVIDER_GROUP.gemini, 'gemini-2.5-flash'],
      ['Vertex AI', PROVIDER_GROUP.vertexAi, 'vertex_ai/gemini-2.5-flash'],
    ] as const) {
      test(
        `a 0 cap is dropped on ${groupLabel}`,
        { tag: ['@cap:playground.configure-model-settings'] },
        async ({ modelRegistryProviders, project, page }) => {
          test.setTimeout(180_000);
          expect(
            modelRegistryProviders.providers,
            `the ${groupLabel} registry is selectable`,
          ).toContain(group === PROVIDER_GROUP.vertexAi ? 'vertex-ai' : 'gemini');

          const playground = await openPlaygroundOn(page, project.id);
          await playground.selectModelFromProvider(0, group, REGISTRY_MODEL.gemini2);

          await assertZeroMeansNoLimit(page, playground, {
            // Both Gemini panels mount it under this id, which is NOT the
            // config field it writes.
            controlId: 'maxOutputTokens',
            expectedModelId: modelId,
          });
        },
      );
    }

    test(
      'a 0 cap is dropped on a custom provider',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ providerKeys, project, page, testNamespace }) => {
        test.setTimeout(180_000);

        // Seeded unreachable rather than against the mock gateway: the
        // completion is short-circuited in the browser anyway, so the base URL
        // is never dialled, and an unreachable one needs no mock process the
        // backend has to be able to reach. The fixture owns the delete.
        const modelId = await test.step('Seed a custom provider', async () => {
          return providerKeys.createUnreachable({
            providerName: `${testNamespace}-no-limit`,
            modelName: `${testNamespace}-no-limit-model`,
          });
        });

        const playground = await openPlaygroundOn(page, project.id);
        // The picker renders a custom provider's models by the model segment of
        // the qualified id, while the request carries the qualified id itself.
        await playground.selectModel(0, modelId.split('/').pop()!);

        await assertZeroMeansNoLimit(page, playground, {
          // The custom panel mounts it under the config field's own name,
          // unlike the two Gemini panels above.
          controlId: 'maxCompletionTokens',
          expectedModelId: modelId,
        });
      },
    );
  },
);
