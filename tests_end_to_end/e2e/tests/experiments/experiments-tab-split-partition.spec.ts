import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * The Experiments page splits into Test suites and Datasets tabs (opik#9611),
 * each scoped server-side by an `evaluation_method` filter. Together they must
 * be an exact PARTITION of the project's experiments.
 *
 * Why a partition and not just "my rows show up": both failure modes are
 * silent. An experiment that falls out of both tabs leaves two tables that look
 * perfectly healthy between them, and one that shows in both inflates every
 * count a reader takes off the page. Neither raises an error, and nothing in
 * the estate opened either tab before this — experiments-smoke.spec.ts seeds
 * one experiment and counts one row, which would pass with the Test suites tab
 * listing nothing at all.
 *
 * The two tabs are deliberately not mirror images in the product and so are not
 * asserted as mirror images here: Test suites sends `= evaluation_suite` while
 * Datasets sends `!= evaluation_suite`, so that an experiment stored under any
 * other method — including one nobody ever classified — is still listed
 * somewhere. The seed's third experiment is created with no `evaluation_method`
 * at all for exactly that leg.
 */
test.describe('Experiments — Test suites / Datasets tab partition', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'Each tab lists only its own evaluation method, and the two together are the whole project',
    { tag: ['@cap:experiments.tab-split-partition'] },
    async ({ experimentsTabSplit, backendClient, page }) => {
      const experiments = new ExperimentsPage(page);
      const { mixed } = experimentsTabSplit;
      const suiteIds = [mixed.suiteExperiment.id];
      const datasetIds = [mixed.datasetExperiment.id, mixed.legacyExperiment.id];
      const allIds = [...suiteIds, ...datasetIds];

      await test.step('The project holds exactly the three seeded experiments', async () => {
        const { total, experiments: rows } = await backendClient.listExperimentsForProject({
          projectId: mixed.projectId,
        });
        expect(total, 'unfiltered total for the project').toBe(allIds.length);
        expect(rows.map((row) => row.id).sort(), 'unfiltered ids for the project').toEqual(
          [...allIds].sort(),
        );
      });

      await test.step('The tabs’ own server-side filters already partition the project', async () => {
        const suite = await backendClient.listExperimentsForProject({
          projectId: mixed.projectId,
          evaluationMethod: { operator: '=', value: 'evaluation_suite' },
        });
        expect(suite.total, '= evaluation_suite total').toBe(suiteIds.length);
        expect(suite.experiments.map((row) => row.id).sort(), '= evaluation_suite ids').toEqual(
          [...suiteIds].sort(),
        );

        const notSuite = await backendClient.listExperimentsForProject({
          projectId: mixed.projectId,
          evaluationMethod: { operator: '!=', value: 'evaluation_suite' },
        });
        expect(notSuite.total, '!= evaluation_suite total').toBe(datasetIds.length);
        expect(
          notSuite.experiments.map((row) => row.id).sort(),
          '!= evaluation_suite ids — a real negation, which is what keeps an ' +
            'unclassified experiment listed rather than hidden',
        ).toEqual([...datasetIds].sort());
      });

      const listedOnSuitesTab = await test.step(
        'The Test suites tab lists the evaluation-suite experiment and nothing else',
        async () => {
          await experiments.gotoTab(mixed.projectId, 'testSuites');
          await experiments.expectActiveTab('testSuites');
          await experiments.expectListedExperiments(suiteIds, 'the Test suites tab');
          return experiments.listedExperimentIds();
        },
      );

      const listedOnDatasetsTab = await test.step(
        'The Datasets tab lists both non-suite experiments, including the unclassified one',
        async () => {
          await experiments.gotoTab(mixed.projectId, 'datasets');
          await experiments.expectActiveTab('datasets');
          await experiments.expectListedExperiments(datasetIds, 'the Datasets tab');
          return experiments.listedExperimentIds();
        },
      );

      await test.step('The two tabs are disjoint and their union is the whole project', async () => {
        // Over what the two tables actually rendered, not over the seed's own
        // grouping of the ids: the point of this step is that the two row sets
        // compose, which only the rendered sets can say.
        expect(
          listedOnSuitesTab.filter((id) => listedOnDatasetsTab.includes(id)),
          'experiments rendered by BOTH tabs',
        ).toEqual([]);
        expect(
          [...listedOnSuitesTab, ...listedOnDatasetsTab].sort(),
          'the union of what the two tabs rendered',
        ).toEqual([...allIds].sort());
      });
    },
  );

  test(
    'Clicking between the tabs partitions the same way a deep link does',
    { tag: ['@cap:experiments.tab-split-partition'] },
    async ({ experimentsTabSplit, page }) => {
      const experiments = new ExperimentsPage(page);
      const { mixed } = experimentsTabSplit;
      const suiteId = mixed.suiteExperiment.id;
      const datasetIds = [mixed.datasetExperiment.id, mixed.legacyExperiment.id];

      /**
       * Asserted per experiment id rather than by enumerating rows, because a
       * clicked tab does not stay on the flat list: `setTab` clears the URL's
       * table params, and the arriving tab applies its OWN saved grouping —
       * which in a fresh browser profile is the default Item-source grouping.
       * `toHaveCount` over each of the three seeded ids makes the same closed
       * claim as an enumeration (present once, or absent) while tolerating the
       * group rows between them.
       */
      const expectPartition = async (
        what: string,
        present: string[],
        absent: string[],
      ): Promise<void> => {
        await test.step(`${what} holds ${present.length} of the three experiments`, async () => {
          for (const id of present) {
            await expect(
              experiments.experimentRowAnywhere(id),
              `${what}: row for experiment ${id}`,
            ).toHaveCount(1);
          }
          for (const id of absent) {
            await expect(
              experiments.experimentRowAnywhere(id),
              `${what}: experiment ${id} must not be listed here`,
            ).toHaveCount(0);
          }
        });
      };

      await test.step('Open the Test suites tab by URL', async () => {
        await experiments.gotoTab(mixed.projectId, 'testSuites');
        await experiments.expectActiveTab('testSuites');
        await experiments.expectListedExperiments([suiteId], 'the Test suites tab');
      });

      await test.step('Clicking Datasets swaps the listing for the other half', async () => {
        await experiments.clickTab('datasets');
        await experiments.expectActiveTab('datasets');
        await expectPartition('the clicked Datasets tab', datasetIds, [suiteId]);
      });

      await test.step('Clicking back to Test suites swaps it back', async () => {
        await experiments.clickTab('testSuites');
        await experiments.expectActiveTab('testSuites');
        await expectPartition('the clicked Test suites tab', [suiteId], datasetIds);
      });
    },
  );
});
