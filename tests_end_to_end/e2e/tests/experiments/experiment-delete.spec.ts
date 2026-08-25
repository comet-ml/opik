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
    async ({ experiment, bystanderExperiment, project, backendClient, page }) => {
      const experiments = new ExperimentsPage(page);

      await test.step('Both experiments are listed', async () => {
        await experiments.goto(project.id);
        await experiments.waitForReady();
        await expect(experiments.rowById(experiment.experimentId)).toBeVisible();
        await expect(experiments.rowById(bystanderExperiment.experimentId)).toBeVisible();
      });

      await test.step('Delete the target via the row actions menu', async () => {
        await experiments.deleteExperimentById(experiment.experimentId);
      });

      await test.step('The target is gone and the bystander is untouched', async () => {
        await expect(experiments.rowById(experiment.experimentId)).toHaveCount(0);
        await expect(experiments.rowById(bystanderExperiment.experimentId)).toBeVisible();
      });

      await test.step('Both facts survive a reload', async () => {
        await page.reload();
        await experiments.waitForReady();
        await expect(experiments.rowById(experiment.experimentId)).toHaveCount(0);
        await expect(experiments.rowById(bystanderExperiment.experimentId)).toBeVisible();
      });

      await test.step('Server-side: the target is gone, the bystander remains', async () => {
        // By id: the list is paginated and name-filtered, and a name lookup
        // is not project-scoped, so neither proves this row was the one hit.
        expect(await backendClient.experimentExists(experiment.experimentId)).toBe(false);
        expect(await backendClient.experimentExists(bystanderExperiment.experimentId)).toBe(true);
      });
    },
  );
});
