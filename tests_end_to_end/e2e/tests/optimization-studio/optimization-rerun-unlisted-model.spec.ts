import { test, expect } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

/**
 * Rerunning a Studio optimization whose saved prompt model the pickers no
 * longer LIST, but the deployment still serves (opik#9277, 2.2.132).
 *
 * The provider-model sync dropped the `label` from eight ids in one commit —
 * `openai/gpt-4-turbo`, `openai/o1`, `openai/o1-pro`, `openai/o3-mini`,
 * `openai/o3-mini-high`, `openai/o4-mini`, `openai/o4-mini-high`,
 * `qwen/qwen3-next-80b-a3b-thinking` — and `useLLMProviderModelsData` filters
 * every dropdown with `isDropdownVisible = Boolean(m.label)`. They stay
 * resolvable and stay runnable; they simply disappear from every option list.
 * So a run saved on one of them is a config no picker offers, which is exactly
 * the state `NewRunSidebar` has a REPLACEMENT path for.
 *
 * That path must not fire here. It swaps the model and tells the user it did,
 * and `optimization-rerun-unavailable-model.spec.ts` asserts it does exactly
 * that for an id no registry knows. The failure this spec exists for is the
 * same code reaching for the dropdown-visible list instead of the full
 * registry: every saved run on one of the eight would then silently re-point at
 * another model at another price, and the next Optimize press would run it.
 *
 * `online-evaluation-orphaned-judge-model.spec.ts` guards this shape for the
 * RULE EDITOR only, and `taxonomy.yaml`'s own `select-model-provider` note
 * records the Optimization Studio picker as not covered — so nothing in the
 * estate reaches this form with an unlisted-but-served id.
 *
 * ## Three runs, because one cannot tell the three verdicts apart
 *
 * A single rerun that kept its model would pass equally well on a build that
 * keeps every saved model and on one whose availability check never runs. So
 * the fixture seeds three configs over one dataset, differing in the saved
 * model and nothing else:
 *
 *  - `unlisted` — served, no label. Must be KEPT, with no notice.
 *  - `listed` — served and offered. The "nothing was swapped" baseline.
 *  - `retired` — a fabricated id no registry knows. Must be REPLACED, with a
 *    notice. This is the positive control that proves the check is live in
 *    this run, which is what turns the other two assertions into verdicts.
 *
 * ## Why no model id is pinned
 *
 * Which rows carry a label is regenerated on every provider-model sync, so a
 * named id would be asserting the artifact and would go silently vacuous the
 * first time the sync labelled it. The pair is read off `GET
 * /v1/private/llm/models` at runtime — see `pickRegistryListingPair`.
 *
 * ## Nothing is run and nothing is billed
 *
 * Every run is seeded completed and "Optimize prompt" is never clicked. The
 * last step asserts it is ENABLED and stops there: a `startOptimization()`
 * here would launch a GEPA run on whichever model the catalog happened to
 * offer first, which is the spend `optimization-studio.spec.ts` is skipped for.
 *
 * NOT covered here: whether a kept unlisted model also keeps the run's saved
 * `llm_model.parameters`. Which controls the panel mounts depends on the
 * runtime-picked row's `supported_parameters`, so a panel comparison would be
 * asserting the catalog rather than the form.
 */

/** What the picker's trigger reads when nothing is selected — the failure, not the expectation. */
const EMPTY_PICKER_TEXT = 'Select an LLM model';

test.describe(
  'Optimization Studio — rerun on a saved model the pickers do not list',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test(
      'keeps a saved model the registry still serves, and announces no replacement',
      { tag: ['@cap:optimization-studio.new-run-form-validation'] },
      async ({ studioUnlistedModelRuns, unlistedRegistryModel, project, page }) => {
        test.setTimeout(180_000);

        const studio = new OptimizationStudioPage(page, project.id);
        const { unlisted, listed, retired } = studioUnlistedModelRuns;

        await test.step('The unlisted run’s model is the one the picker omits and the registry serves', async () => {
          // Both facts come from the fixture's read of the registry, and both
          // are restated here because they are what the next three steps mean:
          // without the first, "the form kept it" is unremarkable; without the
          // second, keeping it would be a bug rather than the expectation.
          expect(
            unlisted.savedModel,
            'the unlisted run is saved on the id the registry serves without a label',
          ).toBe(unlistedRegistryModel.unlisted);
          expect(
            listed.savedModel,
            'and the control is saved on the id the pickers do offer',
          ).toBe(unlistedRegistryModel.listed);
        });

        await test.step('The retired control IS replaced, so the availability check is live', async () => {
          // The positive control, first: if this passes, an absent notice below
          // is a verdict. If it fails, nothing below means anything, and a
          // reader should see that failure rather than a green run.
          await studio.gotoRerun(retired.optimizationId);
          const notice = studio.savedModelReplacementNotice();
          await expect(
            notice,
            'a saved id no registry knows is replaced, and the panel says so',
          ).toHaveCount(1);
          await expect(
            notice,
            'and the notice names the model it could not serve',
          ).toContainText(retired.savedModel);
          expect(
            await studio.readPromptModel(),
            'the picker shows something other than the id that cannot be served',
          ).not.toBe(retired.savedModel);
        });

        await test.step('The unlisted run keeps its own saved model, verbatim', async () => {
          await studio.gotoRerun(unlisted.optimizationId);
          // Exact equality, not a containment: in the compact picker the
          // trigger renders `modelName` alone, which is the option's label when
          // the picker knows the value and the raw stored value when it does
          // not (`getSelectedModelInfo`). So for an unlisted id the whole
          // trigger IS the id — and exactness is what tells "went blank",
          // "substituted another model" and "truncated the id" apart, where a
          // substring check would pass for two of the three.
          expect(
            await studio.readPromptModel(),
            `an id the registry serves must survive the rerun, not fall back to ` +
              `'${EMPTY_PICKER_TEXT}' and not be swapped for another model`,
          ).toBe(unlisted.savedModel);
          await expect(
            studio.savedModelReplacementNotice(),
            'nothing was swapped, so there is nothing to announce',
          ).toHaveCount(0);
        });

        await test.step('And so does the listed control', async () => {
          await studio.gotoRerun(listed.optimizationId);
          expect(
            await studio.readPromptModel(),
            'a listed model is shown by its label, which on OpenRouter is the id itself',
          ).toBe(unlistedRegistryModel.listedLabel);
          await expect(
            studio.savedModelReplacementNotice(),
            'and a run on an offered model is never a replacement',
          ).toHaveCount(0);
        });

        await test.step('The kept unlisted model is startable, not merely displayed', async () => {
          // A form that rendered the id while refusing to run it would be a
          // different and equally bad outcome: the user is shown their own
          // config and cannot rerun it. Asserted ENABLED and never clicked —
          // see the file header.
          await studio.gotoRerun(unlisted.optimizationId);
          expect(await studio.readPromptModel()).toBe(unlisted.savedModel);
          await studio.expectOptimizeEnabled();
        });
      },
    );
  },
);
