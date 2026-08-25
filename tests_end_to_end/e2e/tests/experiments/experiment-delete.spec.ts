import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * Delete is a hard delete server-side, and the UI shows no success toast — so
 * the only evidence it worked is the row leaving and staying gone. Hence the
 * reload: it separates a real delete from a client-side cache eviction.
 *
 * The bystander bounds the blast radius. Absence assertions alone would also
 * pass if the delete removed every experiment in the project.
 */
test.describe('Experiments — delete', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'Deleting an experiment removes only that experiment, and it stays deleted',
    { tag: ['@cap:experiments.delete-experiment'] },
    async ({ experiment, project, sdkClient, backendClient, page, testNamespace }, testInfo) => {
      const experiments = new ExperimentsPage(page);
      const siblingName = `${testNamespace}-exp-bystander`;

      const sibling = await test.step('Seed a second experiment as a bystander', async () => {
        const created = await sdkClient.python.evaluateExperiment({
          project_name: project.name,
          dataset_name: `${testNamespace}-exp-bystander-ds`,
          experiment_name: siblingName,
          items: [{ input: 'What is 2 + 2?', expected_output: '4', task_output: '4' }],
        });
        testInfo.attach('opik.bystander-experiment', {
          body: JSON.stringify(created, null, 2),
          contentType: 'application/json',
        });
        return created;
      });

      try {
        await test.step('Both experiments are listed', async () => {
          await experiments.goto(project.id);
          await experiments.waitForReady();
          await expect(experiments.rowById(experiment.experimentId)).toBeVisible();
          await expect(experiments.rowById(sibling.experiment_id)).toBeVisible();
        });

        await test.step('Delete the target via the row actions menu', async () => {
          await experiments.deleteExperimentById(experiment.experimentId);
        });

        await test.step('The target is gone and the bystander is untouched', async () => {
          await expect(experiments.rowById(experiment.experimentId)).toHaveCount(0);
          await expect(experiments.rowById(sibling.experiment_id)).toBeVisible();
        });

        await test.step('Both facts survive a reload', async () => {
          await page.reload();
          await experiments.waitForReady();
          await expect(experiments.rowById(experiment.experimentId)).toHaveCount(0);
          await expect(experiments.rowById(sibling.experiment_id)).toBeVisible();
        });

        await test.step('Server-side: the target is gone, the bystander remains', async () => {
          // The list is paginated and name-filtered, so an absent row alone
          // does not prove deletion.
          expect(await backendClient.findExperimentByName(experiment.experimentName)).toBeNull();
          expect(await backendClient.findExperimentByName(siblingName)).not.toBeNull();
        });
      } finally {
        // Experiment before dataset: it holds the reference.
        await backendClient.deleteExperiment(sibling.experiment_id).catch(() => {});
        await backendClient.deleteDataset(sibling.dataset_id).catch(() => {});
      }
    },
  );
});
