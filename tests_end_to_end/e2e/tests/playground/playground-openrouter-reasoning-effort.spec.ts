import { test, expect, OPEN_ROUTER_GROUP, OPEN_ROUTER_EFFORT_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody, expectPlaygroundIdle } from '@e2e/core/playground-completions';

/**
 * The OpenRouter panel's Reasoning effort control, and the nested shape the
 * picked level travels in (OPIK-8636, opik#8850).
 *
 * The backend now carries each OpenRouter model's `supported_parameters` and
 * `reasoning_efforts` on `GET /v1/private/llm/models`, and the panel is built
 * from them: a model that lists `reasoning` gets an effort dropdown offering
 * exactly the levels OpenRouter published for it, and a model that lists no
 * `temperature` gets no Temperature slider.
 *
 * ## Why the wire half matters as much as the panel
 *
 * On OpenRouter the effort does NOT go flat. `ChatCompletionRequest` has no
 * field for it, so a flat `reasoning_effort` is dropped by the backend and the
 * run silently ignores the level the user picked — paying for a model's default
 * thinking instead of the chosen one. It travels as
 * `custom_parameters.reasoning.effort`, because `custom_parameters` entries
 * reach OpenRouter as top-level keys. Panel and request builder are separate
 * code (`visibleControls.ts` / `sanitizeConfigForRequest`) and can regress
 * apart, so both are asserted here against one selection.
 *
 * ## Why this is not already covered
 *
 * `playground-openrouter-model-parameters.spec.ts` covers the
 * supported-parameters half, but deliberately on `openai/gpt-4o-mini` — a
 * NON-reasoning model, picked so it gets its own Temperature slider — so it
 * never mounts an effort control at all. `playground-model-parameters.spec.ts`
 * is Anthropic-native by its own taxonomy note, and
 * `playground-openai-effort-pipeline-mode.spec.ts` drives OpenAI-native, where
 * the effort goes FLAT as `reasoning_effort`. Nothing exercised the OpenRouter
 * effort dropdown or its nesting before this spec.
 *
 * Deterministic and billing-free: the levels come from the backend registry
 * rather than from a model, and every completion is short-circuited in the
 * browser, so nothing is generated or billed even on a workspace holding a real
 * OpenRouter key (see the `openRouterProvider` fixture).
 */

/** The model's published `reasoning_efforts`, in `OPEN_ROUTER_EFFORT_ORDER`, after "Default". */
const PUBLISHED_EFFORTS = ['Default', 'Minimal', 'Low', 'Medium', 'High'] as const;

/** The level this spec picks, and the value it must reach the wire as. */
const PICKED_EFFORT = { label: 'Low', wire: 'low' } as const;

/** The sliders a model listing neither `temperature` nor `top_p` may mount. */
const EXPECTED_SLIDERS = ['maxTokens', 'throttling', 'maxConcurrentRequests'] as const;

test.describe(
  'Playground — OpenRouter reasoning effort',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'the panel offers the registry’s published levels and the picked one reaches the wire nested',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ openRouterProvider, project, page }) => {
        test.setTimeout(180_000);

        expect(
          openRouterProvider.configured,
          'the workspace must have an OpenRouter key for the group to be offered',
        ).toBe(true);

        const playground = new PlaygroundPage(page, project.id);
        const model = OPEN_ROUTER_EFFORT_MODEL.publishedEfforts;

        await test.step(`Open the playground on ${model}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          // From the BUILT-IN group explicitly: a Custom provider someone
          // called "openrouter" offers the same ids under its own label, and
          // only the built-in one is routed through the registry this change
          // reads.
          await playground.selectModelFromProvider(0, OPEN_ROUTER_GROUP, model);
        });

        await test.step('The panel offers exactly the published levels and no sampling control', async () => {
          await playground.openModelParameters(0);

          expect(
            await playground.reasoningEffortOptions(),
            'the levels OpenRouter publishes for this model, plus Default — not the ' +
              'native OpenAI set and not an empty list',
          ).toEqual([...PUBLISHED_EFFORTS]);

          // Presence, not enabled-ness: a control that was merely dimmed would
          // still hold a value the request builder could pick up.
          await expect(
            playground.sliderInput('temperature'),
            'the model lists no temperature in supported_parameters',
          ).toHaveCount(0);
          await expect(
            playground.sliderInput('topP'),
            'and no top_p either',
          ).toHaveCount(0);
          await expect(
            playground.samplingOption('Temperature'),
            'a non-Claude model gets no one-of Temperature / Top P choice to hide them behind',
          ).toHaveCount(0);

          // The whole mounted set, so a slider reappearing anywhere in the
          // panel fails here rather than being missed by the lookups above.
          expect(
            await playground.mountedModelParameterIds(),
            'every slider this model’s panel mounts',
          ).toEqual([...EXPECTED_SLIDERS]);

          expect(
            await playground.readReasoningEffort(),
            'an untouched panel starts on Default',
          ).toBe('Default');

          await playground.closeModelParameters();
        });

        await test.step('On the untouched Default the body carries no effort at all', async () => {
          await playground.fillUserMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model, 'the request names the OpenRouter model').toBe(model);
          expect(
            Object.keys(body),
            'Default means "send no effort", so no flat reasoning_effort',
          ).not.toContain('reasoning_effort');
          expect(
            readNestedReasoning(body),
            'and no nested reasoning object either',
          ).toBeUndefined();

          await expectPlaygroundIdle(page);
        });

        await test.step(`Pick ${PICKED_EFFORT.label} and the body nests it under custom_parameters`, async () => {
          await playground.openModelParameters(0);
          await playground.selectReasoningEffort(PICKED_EFFORT.label);
          expect(
            await playground.readReasoningEffort(),
            'the panel keeps the effort that was just picked',
          ).toBe(PICKED_EFFORT.label);
          await playground.closeModelParameters();

          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(
            readNestedReasoning(body),
            'OpenRouter takes the effort inside its reasoning object, and nothing else ' +
              'belongs in it',
          ).toEqual({ effort: PICKED_EFFORT.wire });
          expect(
            Object.keys(body),
            'a flat reasoning_effort is dropped by the backend, so sending it instead ' +
              'would silently ignore the level the user picked',
          ).not.toContain('reasoning_effort');

          await expectPlaygroundIdle(page);
        });

        await test.step('Going back to Default clears the nested object rather than leaving it stale', async () => {
          await playground.openModelParameters(0);
          await playground.selectReasoningEffort('Default');
          expect(await playground.readReasoningEffort()).toBe('Default');
          await playground.closeModelParameters();

          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(
            readNestedReasoning(body),
            `a stale { effort: "${PICKED_EFFORT.wire}" } here would keep charging for ` +
              'thinking the user has switched off',
          ).toBeUndefined();
          expect(Object.keys(body), 'and still nothing flat').not.toContain(
            'reasoning_effort',
          );
        });
      },
    );
  },
);

/**
 * The `reasoning` object the body nests under `custom_parameters`, or
 * `undefined` when the body carries none.
 *
 * Reads both levels without optional chaining on the assertion side: a spec
 * that compared `body.custom_parameters?.reasoning?.effort` would read
 * `undefined` for a body with no `custom_parameters` AND for one whose
 * reasoning object is the wrong shape, so "no effort was sent" and "the effort
 * was sent somewhere else" would pass the same negative.
 */
function readNestedReasoning(body: Record<string, unknown>): unknown {
  if (!('custom_parameters' in body)) return undefined;
  const custom = body.custom_parameters;
  expect(
    custom,
    'custom_parameters, when present, is the object the nested keys live in',
  ).toEqual(expect.any(Object));
  return (custom as Record<string, unknown>).reasoning;
}
