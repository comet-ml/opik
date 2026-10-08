import {
  test,
  expect,
  OPEN_ROUTER_GROUP,
  OPEN_ROUTER_MODEL,
  SAMPLING_AND_PENALTY_KEYS,
  SAMPLING_CONTROLS,
  PENALTY_CONTROLS,
} from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';
import {
  registryModelIds,
  registryReasoningFlag,
  type RegistryModelsMap,
} from '@e2e/core/model-registry';

/**
 * OpenRouter must not offer — or send — the sampling and penalty parameters the
 * model behind it ignores (OPIK-8636, opik#8830).
 *
 * OpenRouter names OpenAI's and Google's models `<vendor>/<native id>`, and the
 * native tables already know which of those take temperature, top_p and the two
 * penalties. `resolveSamplingParams` and `supportsPenaltyParams` read the
 * native id when that provider's list has the row, so the PANEL
 * (`getOpenRouterVisibleControls`) and the REQUEST BUILDER
 * (`sanitizeConfigForRequest`) agree. Every case asserts both halves, because a
 * build where they disagree is silently wrong in the expensive direction: a
 * slider the user tunes that the request then drops, or a key the request sends
 * that the panel never offered. Neither surface alone can see that.
 *
 * `playground-reasoning-model-parameters.spec.ts` makes the same claim for the
 * NATIVE OpenAI and Gemini groups, and `playground-openrouter-model-parameters.spec.ts`
 * drives only `openai/gpt-4o-mini` — the one id this change deliberately leaves
 * alone. Neither can reach this gate: if it stopped matching entirely, every
 * assertion in both would still pass.
 *
 * Deterministic and provider-free despite naming real models: the panel renders
 * off the model identifier, and the completion is fulfilled in the browser by
 * `captureCompletionBody`, so nothing is ever generated or billed.
 */

/**
 * The native row the gate consults for a model, and whether it must be there.
 *
 * Carried per case rather than checked once, because this IS the thing under
 * test: `gpt-5-nano` being IN OpenAI's list is exactly why its OpenRouter id is
 * gated, and `o3-mini-high` being ABSENT from it is exactly why its id is not.
 * Asserted from the registry before the browser is driven, so a deployment
 * whose registry moved fails naming the registry instead of reporting a
 * frontend regression — the one caveat the exploration of #8830 called out.
 */
type NativeRow =
  | { provider: 'openai' | 'gemini'; id: string; listed: boolean }
  /** A vendor `OPEN_ROUTER_NATIVE_ID_PATTERN` never matches: no row is read. */
  | 'vendor-not-matched';

/** What one model's panel and request must look like. */
interface GateCase {
  model: string;
  /** Temperature and Top P: mounted in the panel, and present in the body. */
  sampling: boolean;
  /** Frequency penalty and Presence penalty: likewise. */
  penalties: boolean;
  native: NativeRow;
  /** Why this model takes that branch — read out in the step title. */
  why: string;
}

/**
 * Every slider the OpenRouter panel mounts for a case, in DOM order
 * (`OpenRouterModelConfigs`).
 *
 * Derived from the two booleans rather than written out per case: the
 * whole-panel assertion then comes free, and it is what catches a control
 * reappearing somewhere the four named lookups do not check.
 */
function expectedMountedControls({ sampling, penalties }: GateCase): string[] {
  return [
    ...(sampling ? ['temperature'] : []),
    'maxTokens',
    ...(sampling ? ['topP'] : []),
    'topK',
    ...(penalties ? ['frequencyPenalty', 'presencePenalty'] : []),
    'repetitionPenalty',
    'minP',
    'topA',
    'throttling',
    'maxConcurrentRequests',
  ];
}

/**
 * Select the model, read its panel, run once, read the outbound body — and
 * assert both surfaces against the same expectation.
 *
 * One helper rather than two because neither surface is the claim on its own:
 * the point is that they agree, and asserting them apart would let a build with
 * a panel and a request that disagree pass twice.
 */
async function assertGate(
  page: Page,
  playground: PlaygroundPage,
  registry: RegistryModelsMap,
  gateCase: GateCase,
): Promise<void> {
  const { model, sampling, penalties, native, why } = gateCase;

  await test.step(`${model} — ${why}`, async () => {
    if (native !== 'vendor-not-matched') {
      expect(
        registryModelIds(registry, native.provider).has(native.id),
        native.listed
          ? `"${native.id}" is in ${native.provider}'s native list, which is what gates ${model}`
          : `"${native.id}" is NOT in ${native.provider}'s native list, which is why ${model} keeps its sliders`,
      ).toBe(native.listed);
    }

    await playground.selectModelFromProvider(0, OPEN_ROUTER_GROUP, model);
    await playground.openModelParameters(0);

    const expectedControls = [
      ...SAMPLING_CONTROLS.map((control) => ({ control, shown: sampling })),
      ...PENALTY_CONTROLS.map((control) => ({ control, shown: penalties })),
    ];
    for (const { control, shown } of expectedControls) {
      // Presence, not enabled-ness: a control that was merely dimmed would
      // still hold a value the request builder could pick up.
      await expect(
        playground.sliderInput(control),
        `"${control}" is ${shown ? 'tunable' : 'not mounted'} for ${model}`,
      ).toHaveCount(shown ? 1 : 0);
    }

    await expect(
      playground.samplingOption('Temperature'),
      'a non-Claude model gets no one-of Temperature / Top P choice in place of the sliders',
    ).toHaveCount(0);

    expect(
      await playground.mountedModelParameterIds(),
      `every slider ${model}'s panel mounts`,
    ).toEqual(expectedMountedControls(gateCase));

    await playground.closeModelParameters();

    const body = await captureCompletionBody(page, () => playground.clickRun());
    const sentKeys = Object.keys(body);

    expect(body.model, 'the request names the OpenRouter id exactly as selected').toBe(model);
    for (const { control, shown } of expectedControls) {
      const wireKey = SAMPLING_AND_PENALTY_KEYS[control];
      const reason = `"${wireKey}" is ${shown ? 'sent' : 'dropped'} for ${model}`;
      if (shown) {
        expect(sentKeys, reason).toContain(wireKey);
      } else {
        expect(sentKeys, reason).not.toContain(wireKey);
      }
    }

    // The POST goes out as the run STARTS, so capturing the body says nothing
    // about the run being over — and the next case touches the model picker.
    await playground.waitForFreeRunIdle();
  });
}

/**
 * Several models per page session, the way the Gemini case in
 * `playground-reasoning-model-parameters.spec.ts` drives two: re-selecting the
 * model is the cheap part, and a fresh playground load per model would multiply
 * the wall-clock for no extra coverage.
 */
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
  'Playground — OpenRouter parameters gated by the native model',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'an OpenRouter OpenAI reasoning model drops all four while the ids OpenRouter alone serves keep them',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ openRouterNativeModels, project, page }) => {
        test.setTimeout(240_000);
        const { registry } = openRouterNativeModels;
        const playground = await openPlaygroundOn(page, project.id);

        // The NEGATIVE control first, deliberately: if the panel mounted
        // nothing at all — a picker that never selected, a panel that failed to
        // open — this fails before any absence below has been read as meaningful.
        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.openAiStandard,
          sampling: true,
          penalties: true,
          native: { provider: 'openai', id: 'gpt-4o-mini', listed: true },
          why: 'a native OpenAI row that is not a reasoning model, so nothing is gated',
        });
        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.openAiReasoning,
          sampling: false,
          penalties: false,
          native: { provider: 'openai', id: 'gpt-5-nano', listed: true },
          why: 'a native OpenAI reasoning row, which takes neither sampling param nor either penalty',
        });
        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.openAiOnlyOnOpenRouter,
          sampling: true,
          penalties: true,
          native: { provider: 'openai', id: 'gpt-5-chat', listed: false },
          why: 'an openai/ id with no native row — there is nothing to read, so the sliders stay',
        });
      },
    );

    test(
      'an OpenRouter Gemini 3 model drops the sampling pair and keeps both penalties',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ openRouterNativeModels, project, page }) => {
        test.setTimeout(180_000);
        const playground = await openPlaygroundOn(page, project.id);

        // The asymmetry IS the assertion: a gate that keyed off "this model is
        // reasoning-ish" rather than off the native provider's own capability
        // tables would strip the penalties here too, and Gemini takes both.
        await assertGate(page, playground, openRouterNativeModels.registry, {
          model: OPEN_ROUTER_MODEL.gemini3,
          sampling: false,
          penalties: true,
          native: { provider: 'gemini', id: 'gemini-3-flash-preview', listed: true },
          why: 'a native Gemini 3 row: no temperature or top_p, but Gemini takes both penalties',
        });
      },
    );

    test(
      'the gate keys off the native model list, not the registry’s reasoning flag',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ openRouterNativeModels, project, page }) => {
        test.setTimeout(240_000);
        const { registry } = openRouterNativeModels;

        // This change REVERSES the earlier "keep sampling on OpenRouter even
        // when the registry flags the model reasoning" rule for the ids that
        // have a native row — so a gate still reading that flag would satisfy
        // every other test in this file and still be wrong. These three
        // separate the two readings only while all three carry the flag, which
        // is therefore asserted rather than assumed.
        for (const model of [
          OPEN_ROUTER_MODEL.nativeRowReasoning,
          OPEN_ROUTER_MODEL.noNativeRowReasoning,
          OPEN_ROUTER_MODEL.unmatchedVendorReasoning,
        ]) {
          expect(
            registryReasoningFlag(registry, 'openrouter', model),
            `the registry flags "${model}" reasoning, so the native-row lookup is the only ` +
              'thing that can tell these three apart',
          ).toBe(true);
        }

        const playground = await openPlaygroundOn(page, project.id);

        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.nativeRowReasoning,
          sampling: false,
          penalties: false,
          native: { provider: 'openai', id: 'o3', listed: true },
          why: 'registry-flagged reasoning AND in the native OpenAI list, so it is gated',
        });
        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.noNativeRowReasoning,
          sampling: true,
          penalties: true,
          native: { provider: 'openai', id: 'o3-mini-high', listed: false },
          why: 'the same vendor and the same registry flag, but no native row — not gated',
        });
        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.unmatchedVendorReasoning,
          sampling: true,
          penalties: true,
          native: 'vendor-not-matched',
          why: 'registry-flagged reasoning under a vendor the pattern never matches',
        });
      },
    );

    test(
      'a :batch routing suffix is stripped for the native lookup and kept in the request',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ openRouterNativeModels, project, page }) => {
        test.setTimeout(180_000);
        const { registry } = openRouterNativeModels;
        const playground = await openPlaygroundOn(page, project.id);

        // `assertGate` asserts `body.model` is the id exactly as selected, so
        // the "kept in the request" half is covered by both cases: a build that
        // stripped the suffix before sending would route to the wrong variant.
        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.openAiReasoningBatch,
          sampling: false,
          penalties: false,
          native: { provider: 'openai', id: 'gpt-5-nano', listed: true },
          why: 'the suffix is dropped for the lookup, so the native reasoning row still gates it',
        });
        await assertGate(page, playground, registry, {
          model: OPEN_ROUTER_MODEL.openAiStandardBatch,
          sampling: true,
          penalties: true,
          native: { provider: 'openai', id: 'gpt-4o-mini', listed: true },
          why: 'and a suffix alone must not turn the gate on',
        });
      },
    );
  },
);
