import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * The Compare guard on the Experiments page's TEST SUITES tab (opik#9747,
 * OPIK-9128).
 *
 * A selection spanning two item sources disables Compare and explains why in a
 * tooltip that NAMES the source type. The label is chosen from the selected
 * experiments' `evaluation_method`, so it has three branches:
 * "dataset" when none is a test suite, "test suite" when all are, and
 * "item source" when they are mixed.
 *
 * WHAT THIS ADDS. `compare-button-selection.spec.ts` drives the "dataset"
 * branch on the Datasets tab. The "test suite" branch is reachable only with
 * `evaluation_method: 'evaluation_suite'` experiments, which that spec's
 * fixture does not seed and that tab does not list — so nothing outside vitest
 * asserts it, and nothing at all asserts that the OTHER two actions stay
 * usable on a selection Compare refuses.
 *
 * Why each half matters:
 *
 *  - A Compare left ENABLED on a mix fails silently. `handleCompareClick`
 *    navigates to `experiments[0].dataset_id`'s compare view carrying every
 *    selected id, so the user lands on one suite's compare route with the
 *    other suite's experiment in the `experiments` param — a view that cannot
 *    render the mix, with no error anywhere.
 *  - A tooltip naming the WRONG type is just as silent: it tells a user
 *    looking at two test suites to "select experiments from the same dataset",
 *    on a tab that has no datasets on it.
 *  - Manage tags and Delete are gated on the selection being non-empty, not on
 *    its sources agreeing. Folding them into the Compare guard would take two
 *    working bulk actions away from the only selection that can reach them.
 *
 * Deterministic and fully API-seedable: the panel reads nothing but the
 * selected rows' `dataset_id` and `evaluation_method`, so the fixture seeds no
 * runs and no experiment items.
 */
test.describe(
  'Experiments — the Compare guard on the Test suites tab',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'a selection spanning two test suites disables Compare and names the test suite',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ mixedSuiteExperiments, page }) => {
        const seed = mixedSuiteExperiments;
        const experiments = new ExperimentsPage(page);

        await test.step('Open the Test suites tab and tick one row from each suite', async () => {
          await experiments.goto(seed.projectId, { tab: 'test_suites' });
          await experiments.waitForReady();
          // The whole list, not just "mine are there": this tab filters on
          // `evaluation_method = evaluation_suite`, and a dataset experiment
          // leaking onto it would change which tooltip branch the selection
          // takes while the assertion below still read as being about mixed
          // suites.
          await expect(
            experiments.rows,
            'the Test suites tab must list exactly the three seeded suite experiments',
          ).toHaveCount(3);
          await experiments.selectExperiments([seed.suiteOne[0].id, seed.suiteTwo.id]);
        });

        await test.step('Compare is disabled and its tooltip names the test suite', async () => {
          await expect(
            experiments.compareButton,
            'a selection spanning two test suites must disable Compare',
          ).toBeDisabled();
          await experiments.hoverCompare();
          await expect(
            experiments.compareTooltip,
            'the tooltip must name the test suite, not the dataset',
          ).toHaveText('Select experiments from the same test suite to compare them');
        });

        await test.step('Nothing is raised and nothing navigates', async () => {
          // Dispatched on the button rather than clicked: a mouse click cannot
          // reach a disabled control, so it would assert nothing about
          // `handleCompareClick`'s own `if (compareDisabled) return;` — the
          // second of the two layers that keep this selection off the compare
          // route.
          const urlBefore = page.url();
          await experiments.dispatchCompareClick();
          await expect(
            experiments.openDialogs,
            'a mixed-suite selection must raise no dialog — neither the removed ' +
              'mixed-source guard nor the same-dataset picker',
          ).toHaveCount(0);
          expect(page.url(), 'clicking a disabled Compare must not navigate').toBe(urlBefore);
          expect(new URL(page.url()).pathname, 'still on the experiments list').toMatch(
            /\/experiments$/,
          );
        });

        await test.step('Manage tags and Delete stay usable on the same selection', async () => {
          await expect(
            experiments.selectedCountLabel,
            'the selection bar must still be up, holding both rows',
          ).toHaveText('Selected: 2');
          await expect(
            experiments.manageTagsButton,
            'Manage tags applies per experiment and must survive a mixed selection',
          ).toBeEnabled();
          await expect(
            experiments.deleteButton,
            'Delete applies per experiment and must survive a mixed selection',
          ).toBeEnabled();
        });
      },
    );

    test(
      'two experiments on one test suite leave Compare enabled',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ mixedSuiteExperiments, page }) => {
        const seed = mixedSuiteExperiments;
        const experiments = new ExperimentsPage(page);

        // The discriminator for the test above. Without it, "Compare is
        // disabled for a mixed selection" is satisfied just as well by a build
        // that disables Compare for EVERY selection on this tab — which is a
        // regression that removes the feature, and one a mixed-only spec reads
        // as a pass forever.
        await test.step('Tick both experiments on the same test suite', async () => {
          await experiments.goto(seed.projectId, { tab: 'test_suites' });
          await experiments.waitForReady();
          await experiments.selectExperiments(seed.suiteOne.map((e) => e.id));
        });

        await test.step('Compare is enabled, with its ordinary tooltip', async () => {
          await expect(
            experiments.compareButton,
            'two experiments on one test suite are comparable',
          ).toBeEnabled();
          await experiments.hoverCompare();
          await expect(
            experiments.compareTooltip,
            'a same-suite selection gets the plain Compare tooltip, not the guard',
          ).toHaveText('Compare experiments');
        });
      },
    );
  },
);
