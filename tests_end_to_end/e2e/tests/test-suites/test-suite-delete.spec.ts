import { test, expect } from '@e2e/fixtures';
import { TestSuitesPage } from '@e2e/pom/test-suites.page';

/**
 * Test suites live on the datasets table, under UNIQUE (workspace_id, name).
 * That makes reusing the name after the delete the only check that separates:
 *
 *   - a real hard delete           → create returns a NEW id
 *   - a resurrected row            → create returns the OLD id
 *   - a delete that never happened → create fails with 409
 *
 * The bystander covers the other axis: the recreate check says nothing about
 * suites under other names, so a delete that took them all would still pass.
 */
test.describe('Test suites — delete', { tag: ['@t2-cuj', '@area:test-suites'] }, () => {
  test(
    'Deleting a test suite removes only it, and frees the name for a genuinely new suite',
    { tag: ['@cap:test-suites.delete-suite'] },
    async ({
      testSuite,
      bystanderTestSuite,
      project,
      sdkClient,
      backendClient,
      registerDatasetCleanup,
      page,
    }) => {
      const suites = new TestSuitesPage(page);

      await test.step('Both suites are listed', async () => {
        await suites.goto(project.id);
        await suites.waitForReady();
        await expect(suites.testSuiteRow(testSuite.name)).toBeVisible();
        await expect(suites.testSuiteRow(bystanderTestSuite.name)).toBeVisible();
      });

      await test.step('Delete the target via the row actions menu', async () => {
        await suites.deleteTestSuiteByName(testSuite.name);
      });

      await test.step('The target is gone and the bystander is untouched', async () => {
        await expect(suites.testSuiteRow(testSuite.name)).toHaveCount(0);
        await expect(suites.testSuiteRow(bystanderTestSuite.name)).toBeVisible();
        expect(await backendClient.findTestSuiteByName(testSuite.name, project.name)).toBeNull();
        expect(
          await backendClient.findTestSuiteByName(bystanderTestSuite.name, project.name),
        ).not.toBeNull();
      });

      const recreatedId = await test.step(
        'A new suite with the same name gets a new id, not the old one',
        async () => {
          const recreated = await sdkClient.python.createTestSuite({
            name: testSuite.name,
            project_name: project.name,
            description: 'recreated after delete',
            items: [{ data: { question: 'first question' } }],
          });
          // The `testSuite` fixture tears down by the id this test just
          // deleted, so the recreated suite needs an owner of its own.
          registerDatasetCleanup(recreated.id, recreated.name);
          expect(recreated.id).not.toBe(testSuite.id);
          return recreated.id;
        },
      );

      await test.step('The recreated suite is the one the list now shows', async () => {
        await page.reload();
        await suites.waitForReady();
        await expect(suites.testSuiteRow(testSuite.name)).toBeVisible();
        await expect(suites.testSuiteRow(testSuite.name)).toHaveAttribute(
          'data-row-id',
          recreatedId,
        );
        await expect(suites.testSuiteRow(bystanderTestSuite.name)).toBeVisible();
      });
    },
  );
});
