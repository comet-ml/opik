import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';
import { loadEnvConfig } from '../../config/env.config';

/**
 * The compare route's pathname for one dataset, built the way the POMs build
 * every other URL in the estate (`${baseUrl}/${workspace}/projects/...`).
 *
 * Not assembled from the pathname the browser happens to be on: the number of
 * segments before `projects` differs by deployment — `/default/projects/...` on
 * local OSS, `/opik/opik-testing/projects/...` on cloud, where the app is served
 * under a mount AND a workspace — so a spec that took "the first segment" was
 * asserting the OSS shape and failed on staging for the path rather than for the
 * navigation it is about.
 */
const compareRoutePath = (projectId: string, datasetId: string): string => {
  const env = loadEnvConfig();
  return new URL(
    `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments/${datasetId}/compare`,
  ).pathname;
};

/**
 * The Compare button's three branches on the experiments list (opik#8612,
 * OPIK-8594).
 *
 * `handleCompareClick` does one of three things, decided by how many rows are
 * ticked and whether they share a dataset: one selected opens the same-dataset
 * picker pre-checked on it; two or more on one dataset navigate straight to the
 * compare view; a selection spanning two datasets opens the mixed-dataset guard
 * instead.
 *
 * WHAT THIS ADDS. `experiments.compare-side-by-side` is marked covered, but
 * every spec under `tests/experiments/` reaches the compare view by
 * deep-linking `?experiments=[...]` through `CompareExperimentsPage.gotoResults`
 * — no test clicks Compare at all, so the gesture this PR rewrote is
 * unasserted. Each branch also fails silently rather than loudly: an
 * over-eager selection-count check interposes the picker on a three-experiment
 * selection, or sends a one-experiment selection to a compare view with nothing
 * to compare against, and neither produces an error.
 *
 * Deterministic and fully API-seedable: the handler branches on the experiment
 * rows' ids and `dataset_id`, so the fixture seeds no traces and no runs.
 */
test.describe(
  'Experiments — the Compare button by selection',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'one selected opens the same-dataset picker, and picking a second lands on the compare view',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ compareButtonExperiments, page }) => {
        const seed = compareButtonExperiments;
        const [first, second] = seed.datasetA;
        const experiments = new ExperimentsPage(page);

        await test.step('Tick exactly one experiment and click Compare', async () => {
          await experiments.goto(seed.projectId);
          await experiments.waitForReady();
          await experiments.selectExperiments([first.id]);
          await experiments.clickCompare();
        });

        const urlBeforeSubmit = page.url();

        await test.step('The picker opens, pre-checked, with no navigation', async () => {
          await expect(
            experiments.comparePickerDialog,
            'one selected experiment must raise the same-dataset picker',
          ).toBeVisible();
          // The URL is asserted because the alternative branch — navigating to
          // a one-experiment compare view — also "works" and shows a page. The
          // dialog being open is only half the claim.
          expect(urlBeforeSubmit, 'clicking Compare must not navigate').toContain('/experiments');
          expect(urlBeforeSubmit, 'clicking Compare must not navigate').not.toContain('/compare');

          const preChecked = experiments.comparePickerCheckbox(first.name);
          await expect(preChecked, `exactly one row for ${first.name}`).toHaveCount(1);
          await expect(
            preChecked,
            'the picker must open seeded with the row that was selected',
          ).toBeChecked();
        });

        await test.step('It lists only this dataset\'s experiments', async () => {
          // The whole list, not just "mine are there": the picker is scoped to
          // the selected experiment's dataset, and an entry from the other
          // dataset would mean the scoping is gone — which the user only finds
          // out about when the compare view rejects the mix.
          await expect(
            experiments.comparePickerDialog.getByRole('checkbox', { name: 'Select experiment' }),
            "the picker must list exactly dataset A's experiments",
          ).toHaveCount(seed.datasetA.length);
          await expect(
            experiments.comparePickerCheckbox(seed.datasetB.name),
            "the other dataset's experiment must not be offered",
          ).toHaveCount(0);
        });

        await test.step('The footer counts the selection, and submitting compares both', async () => {
          await expect(
            experiments.comparePickerSubmit,
            'the footer must count the one pre-checked experiment',
          ).toHaveText('Compare 1 experiment');

          await experiments.comparePickerCheckbox(second.name).click();
          await expect(
            experiments.comparePickerSubmit,
            'the footer must follow the selection',
          ).toHaveText('Compare 2 experiments');

          await experiments.comparePickerSubmit.click();

          await page.waitForURL((url) => url.pathname.endsWith('/compare'));
          const url = new URL(page.url());
          expect(
            url.pathname,
            'submitting must land on this dataset\'s compare view',
          ).toBe(compareRoutePath(seed.projectId, seed.datasetAId));
          // Both ids, as a set: the param is a JSON array whose order is the
          // picker's, which is not part of the contract.
          expect(
            [...selectedExperimentIds(url)].sort(),
            'the compare view must carry both experiments',
          ).toEqual([first.id, second.id].sort());
        });
      },
    );

    test(
      'two or more on one dataset navigate directly, and a mixed selection opens the guard',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ compareButtonExperiments, page }) => {
        const seed = compareButtonExperiments;
        const experiments = new ExperimentsPage(page);
        const allThree = seed.datasetA.map((e) => e.id);

        await test.step('Select three experiments on one dataset and click Compare', async () => {
          await experiments.goto(seed.projectId);
          await experiments.waitForReady();
          await experiments.selectExperiments(allThree);
          await experiments.clickCompare();
        });

        await test.step('It navigated, with no dialog interposed', async () => {
          await page.waitForURL((url) => url.pathname.endsWith('/compare'));
          const url = new URL(page.url());
          expect(
            [...selectedExperimentIds(url)].sort(),
            'all three ids must reach the compare view',
          ).toEqual([...allThree].sort());
          // Asserted after the navigation, not instead of it: an over-eager
          // count check would interpose the picker here, and the symptom is an
          // extra click a user did not ask for rather than an error.
          await expect(
            experiments.comparePickerDialog,
            'a same-dataset selection of 2+ must not raise the picker',
          ).toHaveCount(0);
          await expect(
            experiments.datasetFilterDialog,
            'a same-dataset selection must not raise the mixed-dataset guard',
          ).toHaveCount(0);
        });

        await test.step('Back on the list, select across two datasets and click Compare', async () => {
          await experiments.goto(seed.projectId);
          await experiments.waitForReady();
          await experiments.selectExperiments([seed.datasetA[0].id, seed.datasetB.id]);
          await experiments.clickCompare();
        });

        await test.step('The mixed-dataset guard opens, and nothing navigates', async () => {
          await expect(
            experiments.datasetFilterDialog,
            'a selection spanning two datasets must raise "Select experiments to compare"',
          ).toBeVisible();
          // The other dialog explicitly, not just "a dialog is open": the two
          // branches raise different dialogs and confusing them is precisely
          // the regression — the same-dataset picker here would silently drop
          // the second dataset's experiment.
          await expect(
            experiments.comparePickerDialog,
            'the mixed-dataset case must not raise the same-dataset picker',
          ).toHaveCount(0);
          expect(
            new URL(page.url()).pathname,
            'the guard must be raised without navigating',
          ).not.toContain('/compare');
        });
      },
    );

    test(
      "the compare page's own Compare button adds an experiment in place",
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ compareButtonExperiments, page }) => {
        const seed = compareButtonExperiments;
        const [first, second, third] = seed.datasetA;
        const experiments = new ExperimentsPage(page);

        await test.step('Reach the compare view with two experiments', async () => {
          // Through the gesture rather than by deep-linking the URL: the
          // button under test is fed `datasetId`/`experimentsIds` as props by
          // the page it sits on, so arriving the way a user does is what puts
          // the real values in front of it.
          await experiments.goto(seed.projectId);
          await experiments.waitForReady();
          await experiments.selectExperiments([first.id, second.id]);
          await experiments.clickCompare();
          await page.waitForURL((u) => u.pathname.endsWith('/compare'));
        });

        const pathnameBefore = new URL(page.url()).pathname;

        await test.step('Its Compare button opens the picker seeded from the URL', async () => {
          await experiments.clickCompare();
          await expect(
            experiments.comparePickerDialog,
            "the compare page's Compare button must raise the picker",
          ).toBeVisible();
          // Seeded from the URL, not empty. This PR rewired the button from a
          // dialog that read the URL itself to one fed `experimentsIds`
          // through props, and "opens with nothing pre-checked" is exactly how
          // that prop-threading breaks.
          await expect(
            experiments.comparePickerSubmit,
            'the picker must open holding the two experiments already being compared',
          ).toHaveText('Compare 2 experiments');
        });

        await test.step('Adding a third updates the view in place', async () => {
          await experiments.comparePickerCheckbox(third.name).click();
          await expect(experiments.comparePickerSubmit).toHaveText('Compare 3 experiments');
          await experiments.comparePickerSubmit.click();

          await expect
            .poll(() => selectedExperimentIds(new URL(page.url())).length, {
              message: 'experiments in the URL after submitting',
            })
            .toBe(3);
          expect(
            [...selectedExperimentIds(new URL(page.url()))].sort(),
            'all three ids must be in the experiments param',
          ).toEqual([first.id, second.id, third.id].sort());
          // The pathname is the other half: the button owns a `useQueryParam`
          // write, so a regression that navigated instead would lose the tab
          // and sorting state a user had set — silently, because the grid
          // looks the same afterwards.
          expect(
            new URL(page.url()).pathname,
            'adding an experiment must not navigate away',
          ).toBe(pathnameBefore);
        });
      },
    );
  },
);

/**
 * The experiment ids in a compare URL's `experiments` param.
 *
 * The param is a JSON array, so it is parsed rather than substring-matched: an
 * id that merely appeared somewhere in the query string — in a `row` param,
 * say — would satisfy a `toContain` and mean nothing.
 */
function selectedExperimentIds(url: URL): string[] {
  const raw = url.searchParams.get('experiments');
  if (raw === null) {
    throw new Error(`the compare URL carried no experiments param: ${url.toString()}`);
  }
  const parsed: unknown = JSON.parse(raw);
  if (!Array.isArray(parsed)) {
    throw new Error(`the experiments param is not a JSON array: ${raw}`);
  }
  return parsed.map(String);
}
