import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * Which tab the Experiments page opens on when the URL names none (opik#9611).
 *
 * This matters because almost nothing links to the page WITH a tab: the
 * sidebar, the breadcrumbs and the project pages all navigate to a bare
 * `/experiments`, so the tab the page picks for itself is the tab most users
 * ever see. The precedence is URL > the tab last opened in THIS project >
 * Test suites if the project has any suite experiments > Datasets.
 *
 * The failure is user-visible and silent: a project whose experiments are all
 * dataset-method, opening on an empty Test suites table, reads as "my
 * experiments are gone". The vitest suite pins the precedence function itself,
 * but cannot see the skeleton that holds the strip until the tab is resolved,
 * nor the per-project localStorage key — both of which are what the last push
 * on the PR (ba716ec) changed.
 */
test.describe('Experiments — default tab and per-project memory', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'With no ?tab=, each project opens on the tab its own experiments call for',
    { tag: ['@cap:experiments.tab-default-and-memory'] },
    async ({ experimentsTabSplit, page }) => {
      const experiments = new ExperimentsPage(page);
      const { suiteOnly, datasetOnly } = experimentsTabSplit;

      await test.step('A dataset-only project opens on Datasets, listing its experiment', async () => {
        await experiments.gotoDefaultTab(datasetOnly.projectId);
        await experiments.expectActiveTab('datasets');
        await experiments.expectListedExperiments(
          [datasetOnly.experiment.id],
          'the dataset-only project’s default tab',
        );
      });

      await test.step('A suite-only project opens on Test suites, listing its experiment', async () => {
        // Second deliberately, and from the same browser profile: the dataset-
        // only project has just written its own remembered tab, so a page that
        // remembered the tab GLOBALLY rather than per project would open this
        // one on Datasets and show an empty table.
        await experiments.gotoDefaultTab(suiteOnly.projectId);
        await experiments.expectActiveTab('testSuites');
        await experiments.expectListedExperiments(
          [suiteOnly.experiment.id],
          'the suite-only project’s default tab',
        );
      });
    },
  );

  test(
    'The tab strip is never rendered with the wrong tab selected on the way to the default',
    { tag: ['@cap:experiments.tab-default-and-memory'] },
    async ({ experimentsTabSplit, page }) => {
      const experiments = new ExperimentsPage(page);
      const { datasetOnly } = experimentsTabSplit;

      // Armed before the navigation: the whole subject is a state that exists
      // for one network round trip and is gone before any `expect` could look.
      await experiments.armTabStripRecorder();

      await test.step('Open the dataset-only project with no ?tab=', async () => {
        await experiments.gotoDefaultTab(datasetOnly.projectId);
        await experiments.expectActiveTab('datasets');
        await experiments.expectListedExperiments(
          [datasetOnly.experiment.id],
          'the dataset-only project’s default tab',
        );
      });

      await test.step('Every committed tab strip had Datasets selected', async () => {
        const samples = await experiments.tabStripSamples();
        const withStrip = samples.filter((sample) => sample !== null);

        // The recorder has to have seen something, or the assertion below is
        // over an empty list and cannot fail.
        expect(
          withStrip.length,
          'tab-strip states the recorder observed — zero would mean it never ran',
        ).toBeGreaterThan(0);
        expect(
          samples.some((sample) => sample === null),
          'a sample taken while no strip was in the DOM (the skeleton phase, ' +
            'or the document before the page mounted)',
        ).toBe(true);

        for (const [index, sample] of withStrip.entries()) {
          expect(
            sample.filter((tab) => tab.active).map((tab) => tab.label),
            `tab-strip sample ${index + 1} of ${withStrip.length}: the selected tab — ` +
              'a flash of Test suites before the default resolves fails here',
          ).toEqual(['Datasets']);
        }
      });
    },
  );

  test(
    'A chosen tab is remembered for that project alone, and the URL still wins',
    { tag: ['@cap:experiments.tab-default-and-memory'] },
    async ({ experimentsTabSplit, page }) => {
      const experiments = new ExperimentsPage(page);
      const { suiteOnly, datasetOnly } = experimentsTabSplit;

      await test.step('Switch the dataset-only project onto Test suites', async () => {
        await experiments.gotoDefaultTab(datasetOnly.projectId);
        await experiments.expectActiveTab('datasets');
        await experiments.clickTab('testSuites');
        await experiments.expectActiveTab('testSuites');
      });

      await test.step('Reopening it with no ?tab= returns to Test suites, not to its data default', async () => {
        // Discriminating by construction: this project's own experiments would
        // resolve to Datasets, so Test suites here can only have come from the
        // remembered tab.
        await experiments.gotoDefaultTab(datasetOnly.projectId);
        await experiments.expectActiveTab('testSuites');
      });

      await test.step('Switch the suite-only project the other way', async () => {
        await experiments.gotoDefaultTab(suiteOnly.projectId);
        await experiments.expectActiveTab('testSuites');
        await experiments.clickTab('datasets');
        await experiments.expectActiveTab('datasets');
      });

      await test.step('The first project still remembers its own tab', async () => {
        // The strongest leg: a shared key would now answer Datasets (the other
        // project's choice), and so would this project's own data. Test suites
        // is the only answer that can come from a per-project key.
        await experiments.gotoDefaultTab(datasetOnly.projectId);
        await experiments.expectActiveTab('testSuites');
      });

      await test.step('?tab= in the URL beats the remembered tab', async () => {
        await experiments.gotoTab(datasetOnly.projectId, 'datasets');
        await experiments.expectActiveTab('datasets');
        await experiments.expectListedExperiments(
          [datasetOnly.experiment.id],
          'the dataset-only project on the URL’s tab',
        );
      });

      await test.step('And the URL’s tab becomes the remembered one', async () => {
        await experiments.gotoDefaultTab(datasetOnly.projectId);
        await experiments.expectActiveTab('datasets');
      });
    },
  );
});
