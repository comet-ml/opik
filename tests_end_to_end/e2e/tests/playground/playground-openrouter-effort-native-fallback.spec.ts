import { test, expect, OPEN_ROUTER_GROUP, OPEN_ROUTER_EFFORT_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';

/**
 * The fallback branch of the OpenRouter effort control (OPIK-8636, opik#8850):
 * a model that lists `reasoning` but for which OpenRouter publishes NO
 * `reasoning_efforts` borrows the levels of the native OpenAI model behind its
 * id.
 *
 * ## Why this branch and not just the published one
 *
 * It is the half most likely to rot silently. With published levels, a broken
 * lookup shows up as a wrong list. Here, `getOpenRouterReasoningEffortOptions`
 * returns `[]` the moment `getNativeModelBehindOpenRouter` stops resolving —
 * and an empty list makes `visibleControls` drop the control entirely rather
 * than error. The user then has no way to set an effort on a reasoning model
 * at all, and nothing in the UI says why. 21 of this deployment's OpenRouter
 * models are in that set.
 *
 * Two independent things have to hold for the fallback to resolve, and a
 * regression in either produces the same empty list: the id has to match
 * `/^(openai|google)\/([^:]+)/`, and the native provider's own registry list
 * has to still carry that bare id. So the assertion is the whole offered list,
 * which pins both.
 *
 * ## Why these are the native levels and not a coincidence
 *
 * `openai/o3-mini` publishes nothing, and the native `o3-mini` row offers
 * `low / medium / high` — one level FEWER than the published-levels model
 * `openai/gpt-5-mini` in `playground-openrouter-reasoning-effort.spec.ts`,
 * which has `minimal` too. That difference is what makes this a test of the
 * fallback rather than of a hardcoded list: a build that answered every
 * OpenRouter reasoning model with one fixed set would pass one spec and fail
 * the other.
 *
 * Deterministic and billing-free: the fallback levels are pinned in
 * `OPENAI_MODEL_CAPABILITIES` rather than fetched, and the completion is
 * short-circuited in the browser (see the `openRouterProvider` fixture).
 */

/** The native `o3-mini` levels, in panel order, after "Default". */
const NATIVE_EFFORTS = ['Default', 'Low', 'Medium', 'High'] as const;

/** The level this spec picks, and the value it must reach the wire as. */
const PICKED_EFFORT = { label: 'Medium', wire: 'medium' } as const;

/**
 * A level the published-levels model offers and the native `o3-mini` row does
 * not. Asserted absent, so "the control offers four values" cannot be satisfied
 * by a build that serves the gpt-5 set to everything.
 */
const NON_NATIVE_EFFORT = 'Minimal';

test.describe(
  'Playground — OpenRouter effort levels borrowed from the native model',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'a reasoning model with no published levels keeps its control and offers the native OpenAI set',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ openRouterProvider, project, page }) => {
        expect(
          openRouterProvider.configured,
          'the workspace must have an OpenRouter key for the group to be offered',
        ).toBe(true);

        const playground = new PlaygroundPage(page, project.id);
        const model = OPEN_ROUTER_EFFORT_MODEL.nativeFallbackEfforts;

        await test.step(`Open the playground on ${model}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModelFromProvider(0, OPEN_ROUTER_GROUP, model);
        });

        await test.step('The control is still mounted, offering the native levels', async () => {
          await playground.openModelParameters(0);

          // Count, not visibility: the failure mode is the control being
          // dropped from the panel altogether, which no assertion about its
          // contents would report as anything but a timeout.
          await expect(
            playground.modelParameterLabel('Reasoning effort'),
            'no published levels must fall back to the native set, not lose the control',
          ).toHaveCount(1);

          const offered = await playground.reasoningEffortOptions();
          expect(
            offered,
            'the native o3-mini levels, plus Default',
          ).toEqual([...NATIVE_EFFORTS]);
          expect(
            offered,
            `${NON_NATIVE_EFFORT} belongs to the gpt-5 set — offering it here would mean ` +
              'the levels came from somewhere other than the native row',
          ).not.toContain(NON_NATIVE_EFFORT);

          await playground.closeModelParameters();
        });

        await test.step(`Pick ${PICKED_EFFORT.label} and it reaches the wire nested`, async () => {
          await playground.openModelParameters(0);
          await playground.selectReasoningEffort(PICKED_EFFORT.label);
          expect(
            await playground.readReasoningEffort(),
            'the panel keeps the effort that was just picked',
          ).toBe(PICKED_EFFORT.label);
          await playground.closeModelParameters();

          await playground.fillUserMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model, 'the request names the OpenRouter model').toBe(model);
          expect(
            body.custom_parameters,
            'a borrowed level travels exactly as a published one does — nested, so the ' +
              'backend does not drop it',
          ).toEqual({ reasoning: { effort: PICKED_EFFORT.wire } });
          expect(
            Object.keys(body),
            'and never flat, which the backend would drop',
          ).not.toContain('reasoning_effort');
        });
      },
    );
  },
);
