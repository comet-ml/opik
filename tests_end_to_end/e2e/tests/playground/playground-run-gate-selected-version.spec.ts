import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * opik#8813 — the Playground's run gate reads the item count of the version the
 * user SELECTED, not the dataset's live one, and it lets go the moment a
 * version with items is picked.
 *
 * This is the assertion that separates the two readings of the change.
 * `playground-empty-version-run-guard.spec.ts` shows a run being refused on an
 * empty version, but a build that had regressed to reading the dataset's live
 * item count would pass it just as well — the dataset there holds nothing at
 * either version anyone looks at. Here ONE dataset holds two versions with
 * opposite counts, v1 with two items and the latest empty, and the same page
 * must answer differently depending only on which of them is selected. It also
 * pins that the guard does not STICK: nothing in the shipped unit tests covers
 * `itemsTotal` changing under a mounted component.
 *
 * The Shift+Enter run here is deliberately the same gesture
 * `playground-empty-version-run-guard.spec.ts` asserts does nothing on an empty
 * version. That spec's negative is only worth anything if the shortcut is shown
 * to work somewhere, and this is where: a shortcut deleted outright would leave
 * that spec green and fail this one.
 *
 * The provider is a `custom-llm` one whose base URL refuses every connection,
 * for the reasons `playground-empty-version-run-guard.spec.ts` sets out — the
 * model has to be selectable for the gate to be reachable at all, and nothing
 * here depends on what a model would answer. A refused connection still fills
 * each output cell with its own failure text, which is what makes the cells
 * countable, and the experiments are posted when the run STARTS, so the run is
 * observable end to end without a provider key.
 */
test.describe(
  'Playground — the run gate follows the selected version',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    const EMPTY_VERSION_REASON =
      'This dataset version has no items. Add items or select another version to run an experiment';
    const RUNNABLE_REASON = 'Run experiment on dataset';

    test(
      'switching from the emptied version to a populated one re-enables every Run surface',
      {
        tag: [
          '@cap:playground.empty-source-run-guard',
          '@cap:playground.run-against-dataset',
        ],
      },
      async ({
        project,
        emptiedDataset,
        providerKeys,
        backendClient,
        registerExperimentCleanup,
        testNamespace,
        page,
      }) => {
        test.setTimeout(240_000);
        const modelDisplayName = 'unreachable-model';
        const providerName = `${testNamespace}-provider`;
        const seededItems = emptiedDataset.populated.itemsTotal;

        await test.step('Seed a selectable provider that refuses every connection', async () => {
          await providerKeys.createUnreachable({ providerName, modelName: modelDisplayName });
        });

        const playground = new PlaygroundPage(page, project.id);

        /**
         * List the dataset's experiments, registering each id the first time it
         * appears.
         *
         * Registered from inside the lister rather than after the assertions:
         * the ids do not exist until the run creates them, the run auto-names
         * them server-side so `global-teardown.ts`'s run-prefix sweep cannot
         * reach them, and the assertions below can fail — which is exactly when
         * the rows would otherwise be left behind.
         */
        const seen = new Set<string>();
        const listExperiments = async (): Promise<string[]> => {
          const found = await backendClient.listExperimentsForDataset(emptiedDataset.id);
          for (const experiment of found) {
            if (seen.has(experiment.id)) continue;
            seen.add(experiment.id);
            registerExperimentCleanup(experiment.id, experiment.name);
          }
          return found.map((e) => e.name);
        };

        await test.step('Load the dataset; it opens on its emptied latest version', async () => {
          await playground.startRecordingToasts();
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            userPrompt: '{{input}}',
            modelDisplayName,
          });
          // A second variant, because the per-variant Run control only renders
          // once there is more than one (`showPerPromptRunControls`) — so a
          // single-variant page cannot exercise that half of the gate at all.
          await playground.duplicateVariant(0);
          await playground.clickRunExperiment();
          await playground.selectRunExperimentSource({
            mode: 'dataset',
            entityName: emptiedDataset.name,
          });
          // Clicking the source row takes its LATEST version, which the fixture
          // left empty. Asserted, because everything below is about the gate
          // changing its mind — which needs it to start out closed.
          await expect(playground.loadedSourceVersionLabel()).toHaveText(
            emptiedDataset.emptied.name,
          );
        });

        await test.step('On the emptied version every Run surface is closed', async () => {
          await expect(playground.headerRunButton()).toBeDisabled();
          expect(await playground.headerRunTooltipText()).toBe(EMPTY_VERSION_REASON);
          for (const index of [0, 1]) {
            await expect(playground.variantRunButton(index)).toBeDisabled();
          }
          expect(await playground.mountedRowIds()).toEqual([]);
        });

        await test.step('Select the populated version from the same picker', async () => {
          await playground.selectSourceVersion({
            entityName: emptiedDataset.name,
            versionName: emptiedDataset.populated.name,
          });
        });

        await test.step('The gate opens, on the header and on every variant', async () => {
          await expect(playground.headerRunButton()).toBeEnabled();
          // The generic tooltip, not merely the absence of the empty one: a
          // build that cleared the reason without re-enabling the button, or
          // enabled it while still claiming the version was empty, would pass a
          // bare "not disabled" check.
          expect(await playground.headerRunTooltipText()).toBe(RUNNABLE_REASON);
          for (const index of [0, 1]) {
            await expect(playground.variantRunButton(index)).toBeEnabled();
          }
        });

        await test.step("The populated version's items are what the grid now holds", async () => {
          await playground.waitForRunReady({ expectedRows: seededItems });
          // Exactly the seeded count, from the version the user selected. A grid
          // that had kept the previous selection's rows, or fallen back to the
          // dataset's live items, would differ here.
          expect(await playground.mountedRowIds()).toHaveLength(seededItems);
        });

        await test.step('Shift+Enter now starts a run, over every item and variant', async () => {
          await playground.pressRunAllHotkey();
          await playground.waitForRunSettled();
          // Two items times two variants. A run that had used the emptied
          // version would fill nothing, and one that leaked across versions
          // would not land on exactly this number.
          await expect
            .poll(() => playground.countCompletedOutputCells(), {
              timeout: 120_000,
              intervals: [500, 1000, 2000],
            })
            .toBe(seededItems * 2);
        });

        // Before the assertion that can fail, not after it: the run has already
        // written its experiments by the time the cells fill.
        await test.step('Register whatever the run created', async () => {
          await listExperiments();
        });

        await test.step('The header offers a Re-run, so the run was recorded', async () => {
          // `hasRunExperiment` is `createdExperiments.length > 0`, so the label
          // flipping is the page's own statement that the run it just made
          // produced experiments — the user-visible half of a dataset run
          // having happened at all.
          await expect(playground.headerRunButton()).toContainText('Re-run');
        });

        await test.step('execute agrees: the populated version runs, the emptied one does not', async () => {
          // The same request twice, differing only in the version hash, so the
          // answer cannot be about anything else. Server-side, this is the half
          // the UI cannot see — the gate above is client-side, and a backend
          // that accepted an empty version would leave the UI assertions
          // untouched.
          const model = `custom-llm/${providerName}/${modelDisplayName}`;
          const messages = [{ role: 'user', content: '{{input}}' }];

          const accepted = await backendClient.executeExperiments({
            datasetName: emptiedDataset.name,
            datasetId: emptiedDataset.id,
            projectName: project.name,
            versionHash: emptiedDataset.populated.hash,
            datasetVersionId: emptiedDataset.populated.id,
            prompts: [
              { model, messages, experimentName: `${testNamespace}-populated-version` },
            ],
          });
          expect(accepted.status).toBe(202);
          expect(accepted.experiments).toHaveLength(1);
          for (const entry of accepted.experiments) {
            registerExperimentCleanup(entry.experimentId, `${testNamespace}-populated-version`);
            seen.add(entry.experimentId);
          }
          // The count the version carries, not just that it was accepted: this
          // is the number `requireDatasetItems` streamed, and it is what says
          // the hash selected the version holding items rather than being
          // ignored.
          expect(accepted.totalItems).toBe(seededItems);

          const rejected = await backendClient.executeExperiments({
            datasetName: emptiedDataset.name,
            datasetId: emptiedDataset.id,
            projectName: project.name,
            versionHash: emptiedDataset.emptied.hash,
            datasetVersionId: emptiedDataset.emptied.id,
            prompts: [{ model, messages, experimentName: `${testNamespace}-must-not-exist` }],
          });
          expect(rejected.status).toBe(400);
          expect(rejected.message).toBe(
            `Dataset '${emptiedDataset.name}' has no items in the selected version. ` +
              'Add items to it before running an experiment',
          );
          expect(rejected.experiments).toEqual([]);
        });

        await test.step('Nothing was created under the name the rejection used', async () => {
          // The rejected call named an experiment; if the 400 had arrived after
          // the write, that name would be in the dataset's list. Asserted over
          // the whole list, since a rejection that wrote under a server-chosen
          // name instead would escape a lookup by name.
          expect(await listExperiments()).not.toContain(`${testNamespace}-must-not-exist`);
        });
      },
    );
  },
);
