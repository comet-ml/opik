import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import type { ProviderScopedModel } from '@e2e/pom/model-picker';

/**
 * The Thinking level control in the Playground's Model parameters form is a
 * pure lookup off the selected model (`getThinkingLevelOptions` /
 * `getDefaultThinkingLevel` in `lib/modelUtils.ts`): same model in, same option
 * list and same preselected level out, with no LLM call and no wall clock
 * involved. That is what makes it worth pinning here rather than leaving to the
 * unit tests — the table is only correct if the *rendered* control agrees with
 * it, and nothing else in the product surfaces a wrong entry. A level that
 * silently drifts changes what the user is billed for thinking on every
 * subsequent run.
 *
 * Both providers are seeded with throwaway keys: the option list depends on the
 * model, so a spec that only ever saw one provider's copy of "Gemini 2.5 Pro"
 * would not notice the two diverging. No completion is ever run, so no real
 * credential is needed — see the `builtInProviderKeys` fixture.
 */

/** Every level the Gemini 2.5 family accepts, in render order. */
const LEVELS_2_5 = ['Off', 'Low', 'Medium', 'High'];

interface ThinkingLevelCase {
  model: ProviderScopedModel;
  expectedOptions: string[];
  expectedDefault: string;
  why: string;
}

/**
 * Ordered so consecutive picks alternate providers.
 *
 * `PlaygroundPrompt` resets a variant's config only when the PROVIDER changes,
 * so two same-provider picks in a row would leave the previous model's level in
 * `configs.thinkingLevel` and the control would render that instead of the new
 * model's default — the assertions below would then be reading carried state,
 * not the lookup they are about. Crossing the provider boundary each time is
 * what guarantees every case starts from a clean default.
 */
const CASES_2_5: ThinkingLevelCase[] = [
  {
    model: { provider: 'Gemini', model: 'Gemini 2.5 Pro' },
    expectedOptions: LEVELS_2_5,
    expectedDefault: 'High',
    why: 'thinking on by default, as Google ships it',
  },
  {
    model: { provider: 'Vertex AI', model: 'Gemini 2.5 Pro' },
    expectedOptions: LEVELS_2_5,
    expectedDefault: 'High',
    why: 'the Vertex copy must match the AI Studio one',
  },
  {
    model: { provider: 'Gemini', model: 'Gemini 2.5 Flash' },
    expectedOptions: LEVELS_2_5,
    expectedDefault: 'High',
    why: 'thinking on by default',
  },
  {
    model: { provider: 'Vertex AI', model: 'Gemini 2.5 Flash' },
    expectedOptions: LEVELS_2_5,
    expectedDefault: 'High',
    why: 'the Vertex copy must match the AI Studio one',
  },
  {
    model: { provider: 'Gemini', model: 'Gemini 2.5 Flash-Lite' },
    expectedOptions: LEVELS_2_5,
    expectedDefault: 'Off',
    // The one case in the family that is not "High", and the reason `Off` is
    // in the option list at all: Google ships Flash-Lite with thinking
    // disabled, so any other default turns it on — and bills for it — for a
    // user who never asked.
    why: 'Google ships Flash-Lite with thinking disabled',
  },
];

const CASES_GEMINI_3: ThinkingLevelCase[] = [
  {
    model: { provider: 'Vertex AI', model: 'Gemini 3 Pro Preview' },
    expectedOptions: ['Low', 'High (Default)'],
    expectedDefault: 'High (Default)',
    why: 'Pro has no minimal tier, and its default is High',
  },
  {
    model: { provider: 'Gemini', model: 'Gemini 3 Flash Preview' },
    expectedOptions: ['Minimal', 'Low', 'Medium', 'High (Default)'],
    expectedDefault: 'High (Default)',
    why: 'Flash is the only family offering Minimal',
  },
];

test.describe('Playground — Gemini thinking level', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test.beforeEach(async ({ builtInProviderKeys }) => {
    // Both providers, because half the assertions are about the two agreeing.
    await builtInProviderKeys.ensure('gemini');
    await builtInProviderKeys.ensure('vertex-ai');
  });

  test('Every Gemini 2.5 model offers Off/Low/Medium/High, and only Flash-Lite preselects Off', { tag: ['@cap:playground.configure-model-settings'] }, async ({
    project,
    page,
  }) => {
    const playground = new PlaygroundPage(page, project.id);

    await test.step('Open the Playground', async () => {
      await playground.goto();
      await playground.waitForReady();
    });

    for (const { model, expectedOptions, expectedDefault, why } of CASES_2_5) {
      await test.step(`${model.provider} ${model.model} offers ${expectedOptions.join('/')} and preselects ${expectedDefault} (${why})`, async () => {
        await playground.selectModelForProvider(0, model);
        const thinking = await playground.modelParameters(0).readThinkingLevel();

        // Compared as whole lists, not membership: a level the model rejects
        // appearing in the dropdown is as much a defect as one going missing,
        // and the request is refused outright when the wrong one is sent.
        expect(
          thinking.options,
          `${model.provider} ${model.model} must offer exactly these thinking levels`,
        ).toEqual(expectedOptions);
        expect(
          thinking.selected,
          `${model.provider} ${model.model} must preselect ${expectedDefault} — ${why}`,
        ).toBe(expectedDefault);
      });
    }
  });

  test('Gemini 3 models keep their own level lists, distinct from the 2.5 family', { tag: ['@cap:playground.configure-model-settings'] }, async ({
    project,
    page,
  }) => {
    const playground = new PlaygroundPage(page, project.id);

    await test.step('Open the Playground', async () => {
      await playground.goto();
      await playground.waitForReady();
    });

    for (const { model, expectedOptions, expectedDefault, why } of CASES_GEMINI_3) {
      await test.step(`${model.provider} ${model.model} offers ${expectedOptions.join('/')} and preselects ${expectedDefault} (${why})`, async () => {
        await playground.selectModelForProvider(0, model);
        const thinking = await playground.modelParameters(0).readThinkingLevel();

        expect(
          thinking.options,
          `${model.provider} ${model.model} must offer exactly these thinking levels`,
        ).toEqual(expectedOptions);
        expect(
          thinking.selected,
          `${model.provider} ${model.model} must preselect ${expectedDefault} — ${why}`,
        ).toBe(expectedDefault);

        // Guards the specific way these could regress into each other: the 2.5
        // family gained an `Off` level, and leaking it onto a Gemini 3 model
        // would offer a level that model rejects.
        expect(
          thinking.options,
          `${model.model} does not accept an "Off" level`,
        ).not.toContain('Off');
      });
    }
  });
});
