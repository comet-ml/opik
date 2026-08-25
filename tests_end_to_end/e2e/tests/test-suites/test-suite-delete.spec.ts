import { test, expect } from '@e2e/fixtures';
import { TestSuitesPage } from '@e2e/pom/test-suites.page';

/**
 * Deleting a test suite is destructive and permanent, and it is the last
 * uncovered functional capability in this area.
 *
 * The recreate step is the part worth having. Test suites share the datasets
 * table, which carries a UNIQUE (workspace_id, name) constraint — so reusing
 * the name after a delete is the one action that can distinguish the three
 * outcomes a passing "row disappeared" check cannot:
 *
 *   - a real hard delete  → create succeeds with a NEW id (asserted here)
 *   - a resurrected row   → create succeeds but returns the OLD id
 *   - a delete that never happened → create fails with 409 and the suite keeps
 *     its original id
 *
 * Asserting the new id differs from the old is therefore a genuine claim about
 * delete semantics, not a restatement of the previous step.
 */
test.describe('Test suites — delete', { tag: ['@t2-cuj', '@area:test-suites'] }, () => {
  test(
    'Deleting a test suite removes it, and the name is free for a genuinely new suite',
    { tag: ['@cap:test-suites.delete-suite'] },
    async ({ testSuite, project, sdkClient, backendClient, page }) => {
      const suites = new TestSuitesPage(page);

      await test.step('The seeded suite is listed', async () => {
        await suites.goto(project.id);
        await suites.waitForReady();
        await expect(suites.testSuiteRow(testSuite.name)).toBeVisible();
      });

      await test.step('Delete it via the row actions menu', async () => {
        await suites.deleteTestSuiteByName(testSuite.name);
      });

      await test.step('The row is gone and the suite no longer exists server-side', async () => {
        await expect(suites.testSuiteRow(testSuite.name)).toHaveCount(0);
        expect(
          await backendClient.findTestSuiteByName(testSuite.name, project.name),
        ).toBeNull();
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
      });

      // The fixture tears down by the ORIGINAL id, which this test already
      // deleted; the suite recreated above owns a different id and would
      // otherwise leak into the workspace.
      await test.step('Clean up the recreated suite', async () => {
        await backendClient.deleteDataset(recreatedId);
      });
    },
  );
});
