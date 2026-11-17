import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PromptsPage } from '@e2e/pom/prompts.page';

/**
 * What the Playground's model picker does with a persisted model id that is in
 * no option list (opik#9277, 2.2.132).
 *
 * The provider-model sync dropped the `label` from eight ids in one commit —
 * `openai/gpt-4-turbo`, `openai/o1`, `openai/o1-pro`, `openai/o3-mini`,
 * `openai/o3-mini-high`, `openai/o4-mini`, `openai/o4-mini-high`,
 * `qwen/qwen3-next-80b-a3b-thinking` — and `useLLMProviderModelsData` filters
 * every dropdown with `isDropdownVisible = Boolean(m.label)`. They stay
 * resolvable and stay runnable; they simply stop being offered. So any user
 * whose stored Playground card names one of them is carrying a model the
 * picker cannot find in its own options.
 *
 * `PromptModelSelect` handles that by falling back to the raw stored value:
 * `groupOptions.find(m => m.value === selectedValue)?.label ?? value`. If that
 * fallback went away the trigger would read "Select an LLM model", and the
 * user's next ordinary edit — one they believe leaves the model alone — would
 * write whatever the form had instead. The card silently starts running on a
 * different model, at a different price, and nothing anywhere says so.
 *
 * ## Why this spec and not the rule-editor one
 *
 * `online-evaluation-orphaned-judge-model.spec.ts` asserts exactly this shape
 * for the online-evaluation rule editor. The Playground mounts the same
 * component from different state — zustand's persisted `PLAYGROUND_STATE`
 * rather than a server-stored rule — and it is the highest-traffic surface in
 * the product. `taxonomy.yaml`'s `select-model-provider` note records the
 * picker's non-playground variants as uncovered without claiming this case for
 * the playground either, and 2.2.132's release report calls the gap out by
 * name.
 *
 * ## Why the state is planted
 *
 * "A card written before this id lost its label" is, by construction, not
 * reachable from this build's UI: the picker will not offer the id, so no
 * gesture can select it. The blob is the only way to express it, which is the
 * same reason `playground-prompt-state-hydration.spec.ts` plants — and the
 * plant is edited in place, so the state keeps the exact shape this build
 * writes rather than a hand-rolled approximation of it.
 *
 * ## The two halves, in the order they matter
 *
 *  1. The picker HYDRATES the id. End-anchored on the stored string, which is
 *     what tells "went blank", "substituted another model" and "truncated the
 *     id" apart — a non-emptiness check would pass for two of the three. The
 *     trigger renders `title` here (`<provider label> <modelName>`, the
 *     non-compact branch of `getSelectedModelInfo`), so the id is anchored at
 *     the END rather than compared whole.
 *  2. The stored value is UNCHANGED after the mount. This is the half that
 *     matters for the user's data: a picker that fell back to the placeholder
 *     would make the next ordinary edit write a different model.
 *
 * And the control that makes the first half a verdict: with the picker open,
 * the list must offer NO option equal to the planted id while offering the
 * listed id exactly once. One without the other proves nothing — "no option
 * matches" is equally true of a list that failed to load.
 *
 * Deterministic and provider-free: the picker renders off the stored value, no
 * completion is ever issued, and Run is never pressed. Nothing is pinned to a
 * named model, because which rows carry a label is regenerated on every
 * provider-model sync — see `pickRegistryListingPair`.
 */

/** What the trigger reads when nothing is selected — the failure, not the expectation. */
const EMPTY_PICKER_TEXT = 'Select an LLM model';

/**
 * The trigger text a stored-but-unlisted model id must produce.
 *
 * `getSelectedModelInfo` renders `<provider label> <modelName>` in the
 * non-compact picker the Playground mounts, and whether it can name a provider
 * group for the value is not the fact under test — so the id is anchored at the
 * END, which admits either shape while staying exact about the id itself.
 *
 * Anchoring is load-bearing rather than tidiness: OpenRouter ids are prefixes
 * of one another (`openai/o3-mini` of `openai/o3-mini-high`), so a
 * `toContainText` would be satisfied by a picker that had silently substituted
 * the longer id — which is the exact rewrite this spec exists to catch.
 */
function pickerShows(model: string): RegExp {
  const escaped = model.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|\\s)${escaped}$`);
}

test.describe(
  'Playground — a stored model id the picker no longer lists',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'renders a persisted model id that is in no option list, rather than going blank',
      { tag: ['@cap:playground.select-model-provider'] },
      async ({ project, page, unlistedRegistryModel }) => {
        const playground = new PlaygroundPage(page, project.id);
        const prompts = new PromptsPage(page);
        const { unlisted, listed, listedLabel } = unlistedRegistryModel;

        await test.step('Open the Playground once, so a real stored blob exists', async () => {
          // The plant edits the blob this build has just written. Opening first
          // is what makes that possible, and it is also what proves the
          // workspace's OpenRouter key has reached the picker: `waitForReady`
          // waits on the variant-0 trigger, which the page does not mount
          // without a configured provider.
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step(`Plant '${unlisted}' as the card's model`, async () => {
          // From the Prompt library, where the Playground store is not mounted:
          // it persists on every change, so a plant made while the Playground
          // is live races the app's own next write. `plantStoredModel` reads the
          // blob back and asserts the plant landed.
          await prompts.goto(project.id);
          await prompts.waitForReady();
          await playground.plantStoredModel({ model: unlisted, provider: 'openrouter' });
        });

        await test.step('Return to the Playground on the planted state', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        await test.step(`The picker reads exactly '${unlisted}'`, async () => {
          // `toHaveText` retries, so a picker that hydrates once the registry
          // and provider-key queries resolve is waited for rather than raced —
          // the trigger genuinely holds no model before `providersReady`.
          await expect(
            playground.modelPickerTrigger(0),
            `an id in no option list must fall back to the stored value, not to ` +
              `'${EMPTY_PICKER_TEXT}' and not to another model`,
          ).toHaveText(pickerShows(unlisted));
        });

        await test.step('And the mount did not rewrite the stored model', async () => {
          // The half that matters for the user's data. Asserted as the whole
          // pair, because a hydration that kept the id while dropping the
          // provider would leave the next save routing it somewhere else.
          expect(await playground.readStoredModel()).toEqual({
            model: unlisted,
            provider: 'openrouter',
          });
        });

        await test.step('The open list offers no such option — while offering the control', async () => {
          // What makes the hydration a FALLBACK rather than an ordinary
          // selection. The listed id is searched first and must be found: "no
          // option matches the unlisted id" is equally true of a list that
          // never loaded, so without this the assertion below is vacuous.
          await playground.openModelPicker(0);

          await playground.searchModelOptions(listedLabel);
          await expect(
            playground.modelOption(listedLabel),
            `the list offers '${listedLabel}', so it really is populated`,
          ).toHaveCount(1);

          await playground.searchModelOptions(unlisted);
          await expect(
            playground.modelOption(unlisted),
            `'${unlisted}' is served by this deployment and offered by no picker, ` +
              'which is what makes the trigger above a fallback',
          ).toHaveCount(0);
        });
      },
    );
  },
);
