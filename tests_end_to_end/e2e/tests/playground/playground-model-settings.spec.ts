import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';

/**
 * Slider controls every provider's settings panel renders unconditionally
 * (Anthropic, OpenAI, Gemini, VertexAI, OpenRouter and Custom all mount them
 * outside any capability check).
 *
 * They are the non-vacuity anchor for the uniqueness assertions below: without
 * them, a panel that rendered nothing at all would satisfy "no id appears
 * twice" and the spec would report coverage forever. Everything else in the
 * panel is provider- and model-conditional (`supportsSamplingParams`,
 * `supportsAnthropicThinkingEffort`, …), so this deliberately stops short of a
 * per-provider expected set — see the PR description.
 */
const UNIVERSAL_SLIDER_CONTROLS = ['throttling', 'maxConcurrentRequests'];

/** Values appearing more than once in `values`, deduplicated. */
function duplicates(values: string[]): string[] {
  const seen = new Set<string>();
  const dupes = new Set<string>();
  for (const value of values) {
    if (seen.has(value)) dupes.add(value);
    seen.add(value);
  }
  return [...dupes].sort();
}

test.describe('Playground — model settings', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  /**
   * The regression class #7929 fixed: OpenRouter's "Frequency penalty" shipped
   * `id="topK"`, so `SliderInputControl` derived a duplicate `topK-slider` id
   * and a duplicate `topK-input` testid, and the Label's `htmlFor` resolved to
   * the wrong slider. Asserting uniqueness per panel catches that for whichever
   * provider a run has configured, rather than only for OpenRouter.
   *
   * Note this cannot be checked through focus. `SliderInputControl` points
   * `<Label htmlFor>` at the Radix slider ROOT, which is a `div` — a
   * `<label for>` has no activation behaviour against a non-labelable element,
   * so clicking any label in this panel moves focus nowhere, on a fixed build
   * as much as on a broken one. Assert ids, not focus.
   */
  test('The model-settings panel has no duplicate control ids and every label resolves', { tag: ['@cap:playground.configure-model-settings'] }, async ({
    project,
    page,
  }) => {
    test.setTimeout(120_000);

    const modelDisplayName = await test.step(
      'Ensure an LLM provider is available (Anthropic or OpenAI from env)',
      async () => ensureModelAvailable(page),
    );

    const playground = new PlaygroundPage(page, project.id);

    const wiring = await test.step('Open the Playground and its model-settings panel', async () => {
      await playground.goto();
      await playground.waitForReady();
      await playground.selectModel(0, modelDisplayName);
      const panel = await playground.openModelSettings(0);
      return playground.readModelSettingsWiring(panel);
    });

    await test.step('The panel really rendered its controls', async () => {
      expect(
        wiring.sliderControlIds,
        `every provider panel mounts ${UNIVERSAL_SLIDER_CONTROLS.join(' and ')} unconditionally; ` +
          `got [${wiring.sliderControlIds.join(', ')}]`,
      ).toEqual(expect.arrayContaining(UNIVERSAL_SLIDER_CONTROLS));
    });

    await test.step('No id and no data-testid appears twice in the panel', async () => {
      expect(duplicates(wiring.ids), 'duplicate element ids in the model-settings panel').toEqual(
        [],
      );
      expect(
        duplicates(wiring.testIds),
        'duplicate data-testids in the model-settings panel',
      ).toEqual([]);
    });

    await test.step('Every label points at exactly one element', async () => {
      expect(wiring.labels.length, 'the panel labels its controls').toBeGreaterThan(0);
      expect(
        wiring.labels.filter((l) => l.matchingElements !== 1),
        'each label[for] must resolve to exactly one element (0 = dangling, >1 = ambiguous)',
      ).toEqual([]);
      expect(
        duplicates(wiring.labels.map((l) => l.text)),
        'two controls must not share a label',
      ).toEqual([]);
      expect(
        duplicates(wiring.labels.map((l) => l.htmlFor)),
        'two labels must not point at the same control',
      ).toEqual([]);
    });

    await test.step('Every slider is paired with its own number input', async () => {
      // SliderInputControl always emits both `<id>-slider` and a
      // `<id>-input` testid. A control id that lost one of the two is exactly
      // what a copy-pasted `id` prop produces.
      expect([...wiring.sliderControlIds].sort(), 'slider ↔ input pairing').toEqual(
        [...wiring.inputControlIds].sort(),
      );
    });
  });
});
