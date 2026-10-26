import { test, expect, SAVED_MODEL_PARAMETERS } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

/**
 * Rerunning a Studio optimization whose saved prompt model this deployment
 * cannot serve (OPIK-8636, opik#8850).
 *
 * The form swaps in a model the workspace CAN run, and has to do two things a
 * user would care about being wrong:
 *
 *   1. Say so. A silent swap means the user reruns on a different model than
 *      the one the row says it used, and compares the two scores as if they
 *      were the same experiment.
 *   2. Start the replacement from its OWN defaults, not the saved run's. The
 *      saved settings were made for the old model and some only its provider
 *      accepts — OpenRouter's nested `reasoning` makes OpenAI answer 400 — so
 *      inheriting them turns a rerun into a failed run.
 *
 * ## Why this is e2e and not covered by the unit tests
 *
 * `NewRunSidebar.test.tsx` asserts the same shape with `useProviderKeys` and
 * the registry mocked, so nothing there holds the notice against a real
 * provider-keys response or a real `GET /v1/private/llm/models`. And nothing in
 * the estate exercises this form at all: `optimization-studio.spec.ts` is
 * `describe.skip`'d on LLM cost. A seeded row plus the rerun sidebar needs no
 * LLM call, so this reaches the form without that spend.
 *
 * ## Why nothing is pinned to a named model
 *
 * The replacement is `availableModels[0]` — whatever this deployment's registry
 * offers first — so hard-coding it would make the spec a registry-order test
 * that fails on the next provider list regeneration. Instead the replacement is
 * read off a BLANK new-run form, which resolves its default model by the same
 * code path, and the rerun is held to that. Same reason the parameter
 * assertions compare panels rather than numbers: the invariant is "a rerun on a
 * swapped model is seeded exactly like a fresh run on it", which is stronger
 * than any single value and survives a default changing.
 *
 * Deterministic and LLM-free: the run is seeded, "Optimize prompt" is never
 * clicked, and the saved model's unavailability is a property of the id rather
 * than of which keys this workspace holds (see the fixture).
 *
 * ## The replacement is a flagship, and that is safe here — do not make it run
 *
 * `availableModels[0]` is the first entry of the first configured provider's
 * list, which is by construction the newest and most expensive model the
 * workspace can reach (on the local harvest deployment, "Claude Opus 5.5").
 * `llm-model-policy`'s guard does not fire, because the form SEEDS that model
 * rather than this spec selecting it through a POM. Nothing is billed because
 * nothing is run: the last step asserts Optimize is ENABLED and never clicks
 * it. Anyone extending this must keep it that way — a `startOptimization()`
 * here would launch a GEPA run on a flagship, which is exactly the spend
 * `optimization-studio.spec.ts` is skipped for.
 */

/** The notice's exact sentence shape, with the replacement label captured. */
const REPLACEMENT_NOTICE =
  /^The saved model (?<saved>\S+) isn't available here, so this run uses (?<replacement>.+) with its default settings\.$/;

test.describe(
  'Optimization Studio — rerun on an unavailable saved model',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test(
      'the rerun names the replacement model and seeds its defaults, not the saved run’s settings',
      { tag: ['@cap:optimization-studio.new-run-form-validation'] },
      async ({
        modelRegistryProviders,
        studioRerunOptimization,
        backendClient,
        project,
        page,
      }) => {
        test.setTimeout(180_000);

        const studio = new OptimizationStudioPage(page, project.id);

        await test.step('The saved model really is unknown to this deployment’s registry', async () => {
          // The premise, as a server-side fact. Without it, "the form swapped
          // the model" would pass equally well on a build that swaps a model it
          // could have run — and the whole spec would be testing nothing.
          const everyId = await backendClient.listRegistryModelIds();
          expect(
            everyId.length,
            'the registry is not empty — an empty one would make every model look unavailable',
          ).toBeGreaterThan(0);
          expect(
            everyId,
            'no provider serves the saved model, on any key this workspace could hold',
          ).not.toContain(studioRerunOptimization.savedModel);
          expect(
            modelRegistryProviders.providers,
            'and the workspace has providers whose models CAN be swapped in',
          ).toContain('openai');
        });

        const blank = await test.step('Read what a blank new run seeds on this deployment', async () => {
          await studio.gotoNewBlank();
          return {
            model: await studio.readPromptModel(),
            parameters: await studio.readModelParameters(),
          };
        });

        await test.step('A blank form shows no replacement notice', async () => {
          // The control for the assertion below: without it, "the notice names
          // the replacement" is equally satisfied by a build that shows the
          // notice on every new run.
          await expect(
            studio.savedModelReplacementNotice(),
            'nothing was swapped, so there is nothing to announce',
          ).toHaveCount(0);
        });

        await test.step('Open the same run as a rerun', async () => {
          await studio.gotoRerun(studioRerunOptimization.optimizationId);
        });

        const replacementLabel = await test.step(
          'The notice names the saved model, the replacement, and the defaults claim',
          async () => {
            const notice = studio.savedModelReplacementNotice();
            await expect(notice, 'exactly one replacement notice').toHaveCount(1);

            const text = ((await notice.textContent()) ?? '').trim();
            const match = REPLACEMENT_NOTICE.exec(text);
            // Matched, then read — not optional-chained into the comparisons
            // below, which would turn a reworded notice into a silent pass.
            expect(
              match,
              `the notice reads as one sentence naming both models: got "${text}"`,
            ).not.toBeNull();
            const { saved, replacement } = match!.groups!;

            expect(saved, 'it names the model the run was saved on').toBe(
              studioRerunOptimization.savedModel,
            );
            expect(
              replacement,
              'and names what replaced it — a blank here leaves the user guessing',
            ).not.toBe('');
            expect(
              replacement,
              'the replacement cannot be the model that is unavailable',
            ).not.toBe(studioRerunOptimization.savedModel);
            return replacement;
          },
        );

        await test.step('The replacement it names is the model the form will actually run', async () => {
          // The notice and the picker are built from different sources
          // (`replacementLabel` off `pickerModels`, the trigger off the form's
          // `modelName`), so a disagreement between them is a real failure
          // mode: the user is told one model and runs another.
          expect(
            await studio.readPromptModel(),
            'the prompt model picker shows the replacement the notice named',
          ).toBe(replacementLabel);
          expect(
            replacementLabel,
            'which is the same model a fresh run would have defaulted to',
          ).toBe(blank.model);
        });

        await test.step('Its settings are its own defaults, not the saved run’s', async () => {
          const parameters = await studio.readModelParameters();

          expect(
            parameters,
            'a rerun on a swapped model is seeded exactly as a fresh run on it would be',
          ).toEqual(blank.parameters);

          // Named explicitly as well as compared, because these are the values
          // the saved run actually held: a build that inherited one would fail
          // the comparison above with a diff nobody could read without this.
          for (const saved of new Set(Object.values(SAVED_MODEL_PARAMETERS))) {
            expect(
              Object.values(parameters),
              `no control may read ${saved}, which only the saved run's settings hold`,
            ).not.toContain(String(saved));
          }
        });

        await test.step('And the run is startable on the replacement', async () => {
          // The healthy-registry path clears an unavailable ALGORITHM model
          // instead of keeping it, so there is no optimizer key to be missing
          // and nothing should be guarding Start here.
          await expect(
            studio.optimizerKeyWarning(),
            'the algorithm model was cleared, so no key for it can be missing',
          ).toHaveCount(0);
          await studio.expectOptimizeEnabled();
        });
      },
    );
  },
);
