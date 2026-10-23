import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';

/**
 * A Max output tokens value already stored on a variant is re-clamped when a
 * model switch LOWERS the ceiling (OPIK-8636, opik#8847).
 *
 * This is the one direction the control can get wrong silently. Every other
 * case announces itself: a value typed past the ceiling is clamped as you type,
 * and a ceiling that is wrong for the selected model is visible in the panel.
 * But a value that was perfectly legal when it was typed, left behind by a
 * switch to a model with a smaller limit, looks completely ordinary — the panel
 * shows a number inside a plausible range and the user has no reason to revisit
 * it. The request then carries a `max_completion_tokens` above what the model
 * accepts, which OpenAI answers with a 400 on every run.
 *
 * ## Why this is not already covered
 *
 * `playground-max-output-tokens-range.spec.ts` covers the ceiling and the
 * clamp, and it switches models repeatedly — but its helper types a value above
 * the ceiling AFTER each switch, so the value it clamps was never valid for the
 * previous model. Nothing in the estate carries a VALID value across a switch,
 * which is the only way the stale-value path is reached. A build that computed
 * the new ceiling correctly for the panel and simply never re-applied it to the
 * stored value would pass that spec in full.
 *
 * `maxOutputTokens.test.tsx` covers `resolveMaxCompletionTokens` as a unit, and
 * release-report.md records opik#8847 as covered by that alone. A unit test over
 * the resolver cannot see whether the panel and the request builder are both
 * wired to it.
 *
 * ## Why the numbers are read rather than pinned
 *
 * `model_prices_and_context_window.json` is a generated artifact refreshed by
 * automation, so pinning 128000 and 16384 would assert the artifact's contents
 * and would fail the day a vendor moves a limit. Both ceilings are read from
 * the panel at runtime, and the value carried across the switch is derived to
 * sit strictly between them — so the scenario reconstructs itself whatever the
 * two models' limits become. The relation `high > low` is asserted explicitly:
 * if the artifact ever made these two models' ceilings equal, this test would
 * say so loudly rather than passing while exercising nothing.
 *
 * ## Why these two models
 *
 * `GPT 5.5` and `GPT 4o Mini`, which is the pair the release exploration
 * verified by hand on staging. Deliberately NOT
 * `REGISTRY_MODEL.openAiWithoutPricingCap` (`GPT Live 1`), the obvious
 * high-ceiling choice and the one `playground-max-output-tokens-range.spec.ts`
 * uses: that model is absent from the 2.2.106 frontend deployed to staging, so
 * selecting it there fails in the picker before any ceiling can be read. It
 * exists only in the newer `main` this branch also carries. Both models here
 * are present in both builds.
 *
 * Deterministic and provider-free: the registries come from the
 * `modelRegistryProviders` fixture's placeholder keys and the completion is
 * fulfilled in the browser by `captureCompletionBody`, so nothing is generated
 * or billed.
 */

/** The control id the OpenAI panel mounts Max output tokens as. */
const CONTROL = 'maxCompletionTokens';

test.describe(
  'Playground — Max output tokens across a model switch',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'a stored value is re-clamped when the switched-to model has a lower ceiling, in the panel and in the request',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        test.setTimeout(240_000);
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Open the playground and write a prompt', async () => {
          await playground.goto();
          await playground.waitForReady();
          // Run does nothing without a message, and this one survives the
          // switch below.
          await playground.fillUserMessage('Reply with the single word OK.');
        });

        const lowCeiling = await test.step(`Read the ceiling ${REGISTRY_MODEL.openAiStandard} offers`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.openAiStandard);
          await playground.openModelParameters(0);
          const range = await playground.readSliderRange(CONTROL);
          await playground.closeModelParameters();
          return range.max;
        });

        const highCeiling = await test.step(`Read the ceiling ${REGISTRY_MODEL.openAiEffortWithoutMax} offers`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.openAiEffortWithoutMax);
          await playground.openModelParameters(0);
          const range = await playground.readSliderRange(CONTROL);
          await playground.closeModelParameters();
          return range.max;
        });

        // The precondition the whole test rests on, asserted rather than
        // assumed. If these two ceilings were equal there would be no "lower
        // ceiling" to switch to, every assertion below would hold trivially,
        // and the spec would read as coverage forever while exercising nothing.
        expect(
          highCeiling,
          `${REGISTRY_MODEL.openAiEffortWithoutMax} must offer a HIGHER ceiling than ${REGISTRY_MODEL.openAiStandard} for this scenario to exist at all — got ${highCeiling} and ${lowCeiling}`,
        ).toBeGreaterThan(lowCeiling);

        /**
         * Strictly between the two ceilings: legal on the high model, illegal on
         * the low one.
         *
         * Not the high ceiling itself, deliberately — a value equal to the max
         * cannot tell "the value I typed was kept" apart from "the value I typed
         * was clamped to this model's maximum", and the first of those is what
         * makes the re-clamp after the switch a change rather than a no-op.
         */
        const carried = Math.floor((lowCeiling + highCeiling) / 2);
        expect(
          carried,
          'the carried value must be legal on the high-ceiling model',
        ).toBeLessThan(highCeiling);
        expect(
          carried,
          'the carried value must be ILLEGAL on the low-ceiling model, or the switch has nothing to re-clamp',
        ).toBeGreaterThan(lowCeiling);

        await test.step(`Store ${carried} on ${REGISTRY_MODEL.openAiEffortWithoutMax}, where it is legal`, async () => {
          await playground.openModelParameters(0);
          await playground.setSliderValue(CONTROL, String(carried));
          await expect(
            playground.sliderInput(CONTROL),
            'a value inside the ceiling is kept as typed, not clamped',
          ).toHaveValue(String(carried));
          await playground.closeModelParameters();
        });

        await test.step(`Switch the variant to ${REGISTRY_MODEL.openAiStandard}, whose ceiling is lower`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.openAiStandard);
        });

        await test.step('The panel shows the new ceiling, not the value that is now out of range', async () => {
          await playground.openModelParameters(0);
          await expect(
            playground.sliderThumb(CONTROL),
            'the panel offers the switched-to model\'s own ceiling',
          ).toHaveAttribute('aria-valuemax', String(lowCeiling));
          // The headline assertion. Stated as the value it must now be rather
          // than only as "not `carried`", so a build that reset the control to
          // some unrelated default also fails here instead of passing a
          // not-equal check.
          await expect(
            playground.sliderInput(CONTROL),
            `the stored ${carried} is above ${REGISTRY_MODEL.openAiStandard}'s ceiling, so it must re-clamp to ${lowCeiling} rather than stay where it was`,
          ).toHaveValue(String(lowCeiling));
          await playground.closeModelParameters();
        });

        await test.step('The request carries the re-clamped value, not the stale one', async () => {
          // The half the panel cannot show. `sanitizeConfigForRequest` clamps
          // independently of the control's display, so a build that re-clamped
          // one and not the other is invisible from either surface alone — and
          // it is this surface that decides whether the provider returns a 400.
          const body = await captureCompletionBody(page, () => playground.clickRun());
          expect(body.model, 'the request names the switched-to model').toBe('gpt-4o-mini');
          expect(
            body.max_completion_tokens,
            `the outbound request must carry ${lowCeiling}; ${carried} is what the provider would reject`,
          ).toBe(lowCeiling);
          await playground.waitForFreeRunIdle();
        });
      },
    );
  },
);
