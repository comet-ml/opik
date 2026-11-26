import { test, expect } from '@e2e/fixtures';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';

/**
 * The Compare guard's third tooltip branch — "item source" — on the only
 * surface that can reach it (opik#9747, OPIK-9128).
 *
 * `ExperimentsActionsPanel` has two hosts. The project Experiments list is
 * one; `PromptPage/ExperimentsTab` is the other, and it is the only list that
 * can hold a DATASET experiment and a TEST SUITE experiment in one selection:
 * the Experiments page's two tabs each filter on `evaluation_method`, so its
 * Datasets tab only ever holds non-suite rows and its Test suites tab only
 * suite rows, and the "item source" label is dead there. The prompt tab
 * queries by prompt id with no such filter.
 *
 * WHAT THIS ADDS. This host has no spec of its own for the panel at all, so
 * without this the mixed-type branch is asserted nowhere outside vitest, and a
 * regression that re-enabled Compare here would route the selection to
 * `experiments[0].dataset_id`'s compare view — mounting the items panel for
 * one source type over rows belonging to the other, which renders rather than
 * errors.
 *
 * This spec also pins that the host was NOT given the selection action bar the
 * Experiments list got in the same change: it keeps its own toolbar and the
 * `outline` button variant, so search and Columns must still be there while
 * rows are ticked and no "Selected: N" bar may appear. The bar is sized and
 * padded for the page body's sticky container, which this tab does not use.
 *
 * Placed under `tests/experiments/` rather than `tests/prompts/` because the
 * subject is the experiments Compare guard — the prompt page is where it is
 * reachable, not what is being asserted. `prompts.experiments-tab` covers that
 * tab's own listing, paging and pinning.
 *
 * Deterministic and API-seedable in four writes: the panel reads only the
 * selected rows' `dataset_id` and `evaluation_method`.
 */
test.describe(
  'Experiments — the Compare guard across item source types',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      "the prompt's Experiments tab disables Compare for a dataset + test-suite selection",
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ promptMixedSourceExperiments, page }) => {
        const seed = promptMixedSourceExperiments;
        const prompt = new PromptDetailPage(page);

        await test.step('Open the prompt\'s Experiments tab ungrouped', async () => {
          await prompt.gotoExperimentsTab(seed.projectId, seed.promptId);
          // The whole list, not just "both of mine are there": the two rows
          // are the only experiments linked to this prompt version, and a
          // third from somewhere else would change which branch the selection
          // takes while every assertion below still read as being about a
          // dataset-plus-suite mix.
          await prompt.waitForExperimentRows(2);
          await expect(
            prompt.experimentRow(seed.datasetExperiment.id),
            'the dataset experiment must be listed exactly once',
          ).toHaveCount(1);
          await expect(
            prompt.experimentRow(seed.suiteExperiment.id),
            'the test-suite experiment must be listed exactly once',
          ).toHaveCount(1);
        });

        await test.step('Tick both rows', async () => {
          await prompt.selectExperiments([
            seed.datasetExperiment.id,
            seed.suiteExperiment.id,
          ]);
        });

        await test.step('Compare is disabled and its tooltip names the item source', async () => {
          await expect(
            prompt.experimentActions.compareButton,
            'a selection mixing a dataset and a test suite must disable Compare',
          ).toBeDisabled();
          await prompt.experimentActions.hoverCompare();
          // "item source" rather than either type's own name: the selection
          // shares neither, and naming one of them would send the user looking
          // for a dataset they do not have.
          await expect(
            prompt.experimentActions.compareTooltip,
            'the tooltip must fall back to the generic item source label',
          ).toHaveText('Select experiments from the same item source to compare them');
          await expect(
            prompt.experimentActions.openDialogs,
            'the mixed-source selection must raise no dialog',
          ).toHaveCount(0);
        });

        await test.step('This host keeps its own toolbar and its other actions', async () => {
          await expect(
            prompt.selectedCountLabel,
            'the prompt tab must not grow the Experiments list\'s selection bar',
          ).toHaveCount(0);
          await expect(
            prompt.experimentsSearchInput,
            'the tab keeps its search box while rows are selected',
          ).toBeVisible();
          await expect(
            prompt.experimentsColumnsButton,
            'the tab keeps its Columns control while rows are selected',
          ).toBeVisible();
          await expect(
            prompt.experimentActions.manageTagsButton,
            'Manage tags applies per experiment and must survive a mixed selection',
          ).toBeEnabled();
          await expect(
            prompt.experimentActions.deleteButton,
            'Delete applies per experiment and must survive a mixed selection',
          ).toBeEnabled();
        });
      },
    );
  },
);
