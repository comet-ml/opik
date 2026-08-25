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
 *
 * A bystander suite covers the other axis: the recreate check constrains what
 * happened to *this* name, and says nothing about the rest of the project, so
 * a delete that took every suite with it would still pass. The bystander pins
 * the blast radius to one row.
 *
 * Both the bystander and the recreated suite are cleaned up in a `finally`:
 * the `testSuite` fixture tears down by the ORIGINAL id, which this test has
 * already deleted, so anything else created here owns an id no fixture knows.
 */
test.describe('Test suites — delete', { tag: ['@t2-cuj', '@area:test-suites'] }, () => {
  test(
    'Deleting a test suite removes only it, and frees the name for a genuinely new suite',
    { tag: ['@cap:test-suites.delete-suite'] },
    async ({ testSuite, project, sdkClient, backendClient, page, testNamespace }) => {
      const suites = new TestSuitesPage(page);
      const siblingName = `${testNamespace}-suite-bystander`;
      // Ids of suites this test owns, recorded the moment each is created so the
      // cleanup below covers them even if a later assertion throws.
      const ownedSuiteIds: string[] = [];

      try {
        await test.step('Seed a second suite as a bystander', async () => {
          const sibling = await sdkClient.python.createTestSuite({
            name: siblingName,
            project_name: project.name,
            description: 'bystander — must survive the delete',
            items: [{ data: { question: 'bystander question' } }],
          });
          ownedSuiteIds.push(sibling.id);
        });

        await test.step('Both suites are listed', async () => {
          await suites.goto(project.id);
          await suites.waitForReady();
          await expect(suites.testSuiteRow(testSuite.name)).toBeVisible();
          await expect(suites.testSuiteRow(siblingName)).toBeVisible();
        });

        await test.step('Delete the target via the row actions menu', async () => {
          await suites.deleteTestSuiteByName(testSuite.name);
        });

        await test.step('The target is gone and the bystander is untouched', async () => {
          await expect(suites.testSuiteRow(testSuite.name)).toHaveCount(0);
          await expect(suites.testSuiteRow(siblingName)).toBeVisible();
          expect(
            await backendClient.findTestSuiteByName(testSuite.name, project.name),
          ).toBeNull();
          expect(
            await backendClient.findTestSuiteByName(siblingName, project.name),
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
            // Recorded before the assertion, so a failing comparison still
            // leaves a cleanable id behind.
            ownedSuiteIds.push(recreated.id);
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
          await expect(suites.testSuiteRow(siblingName)).toBeVisible();
        });
      } finally {
        for (const id of ownedSuiteIds) {
          // Test suites live on the datasets table, so this is the same call the
          // fixture teardown makes.
          await backendClient.deleteDataset(id).catch(() => {});
        }
      }
    },
  );
});
