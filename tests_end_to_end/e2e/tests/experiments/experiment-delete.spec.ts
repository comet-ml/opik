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
 */
test.describe('Experiments — delete', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'Deleting an experiment removes it from the list and it stays deleted',
    { tag: ['@cap:experiments.delete-experiment'] },
    async ({ experiment, project, backendClient, page }) => {
      const experiments = new ExperimentsPage(page);

      await test.step('The seeded experiment is listed', async () => {
        await experiments.goto(project.id);
        await experiments.waitForReady();
        await expect(experiments.rowById(experiment.experimentId)).toBeVisible();
      });

      await test.step('Delete it via the row actions menu', async () => {
        await experiments.deleteExperimentById(experiment.experimentId);
      });

      await test.step('The row is gone from the list', async () => {
        await expect(experiments.rowById(experiment.experimentId)).toHaveCount(0);
      });

      await test.step('It is still gone after a reload', async () => {
        await page.reload();
        await experiments.waitForReady();
        await expect(experiments.rowById(experiment.experimentId)).toHaveCount(0);
      });

      await test.step('The experiment no longer exists server-side', async () => {
        // The list is paginated and name-filtered, so an absent row alone does
        // not prove deletion — ask the backend directly.
        expect(await backendClient.findExperimentByName(experiment.experimentName)).toBeNull();
      });
    },
  );
});
