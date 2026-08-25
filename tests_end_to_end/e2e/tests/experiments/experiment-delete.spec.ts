import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * Deleting an experiment is destructive and permanent: the backend issues a
 * hard delete, so a regression here either loses data the user meant to keep
 * or — the quieter failure — reports success while leaving the row behind.
 *
 * The UI shows no success toast, so "it worked" can only be read from the row
 * leaving the list and staying gone. Both assertions below are therefore about
 * absence, checked twice: once in the live DOM, once after a reload so a
 * client-side cache eviction can't masquerade as a server-side delete.
 *
 * A second experiment is seeded purely as a bystander. Absence assertions
 * alone would pass just as happily if the delete removed every experiment in
 * the project, so the bystander is what pins the blast radius to exactly one
 * row — the target goes, the sibling stays, in the DOM and server-side.
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
        // Registered before any assertion runs: a failure below must not strand
        // these in the workspace. The `experiment` fixture cleans up its own
        // pair, not this one.
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
          // The list is paginated and name-filtered, so an absent row alone does
          // not prove deletion — ask the backend directly.
          expect(await backendClient.findExperimentByName(experiment.experimentName)).toBeNull();
          expect(await backendClient.findExperimentByName(siblingName)).not.toBeNull();
        });
      } finally {
        // Runs even when an assertion above fails, so the bystander pair never
        // outlives the test. Teardown order mirrors the experiment fixture:
        // experiment first (it references the dataset), then the dataset.
        await backendClient.deleteExperiment(sibling.experiment_id).catch(() => {});
        await backendClient.deleteDataset(sibling.dataset_id).catch(() => {});
      }
    },
  );
});
