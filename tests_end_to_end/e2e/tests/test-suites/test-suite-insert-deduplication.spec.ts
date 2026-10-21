import { test, expect } from '@e2e/fixtures';
import { TestSuitesPage } from '@e2e/pom/test-suites.page';

/**
 * `TestSuite.insert()` is a separate public entry point from
 * `Dataset.insert()`, but both funnel into the same
 * `__internal_api__insert_items_as_dataclasses__` — and the result renders on a
 * different page. `test-suites-smoke.spec.ts` only ever counts rows from a
 * default-dedup seed, so neither state of the flag is asserted on the suite
 * side today.
 *
 * Paired against a control suite seeded identically with the default, so the
 * flag is the only variable.
 *
 * Note on strength: the Test cases table renders only ID / Tags / Created — the
 * item `data` keys do not become columns the way they do on the dataset
 * Records tab. So the UI half of this test asserts row identity and count, not
 * content; the content assertion is API-side.
 */
const TEST_CASE = { data: { question: 'Is the deduplication flag honoured?' } };

test.describe('Test suite insert — deduplication flag', { tag: ['@area:test-suites'] }, () => {
  test(
    'deduplication=false stores both identical test cases and the Test cases tab renders both; the default stores one',
    { tag: ['@t2-cuj', '@cap:test-suites.view-suite-items'] },
    async ({
      project,
      sdkClient,
      backendClient,
      registerDatasetCleanup,
      testNamespace,
      page,
    }) => {
      const offName = `${testNamespace}-suite-dedup-off`;
      const onName = `${testNamespace}-suite-dedup-on`;

      const { offId, onId } = await test.step(
        'Insert the same test case twice into two suites — once with deduplication off, once with the default',
        async () => {
          const off = await sdkClient.python.createTestSuite({
            project_name: project.name,
            name: offName,
            description: 'deduplication=false',
          });
          // Suites are created mid-test and share storage with datasets, which
          // do not cascade with their project — register both for teardown the
          // moment their ids exist.
          registerDatasetCleanup(off.id, offName);
          const on = await sdkClient.python.createTestSuite({
            project_name: project.name,
            name: onName,
            description: 'deduplication default (true)',
          });
          registerDatasetCleanup(on.id, onName);

          for (let call = 0; call < 2; call++) {
            await sdkClient.python.insertTestSuiteItems({
              project_name: project.name,
              suite_name: offName,
              items: [TEST_CASE],
              deduplication: false,
            });
            await sdkClient.python.insertTestSuiteItems({
              project_name: project.name,
              suite_name: onName,
              items: [TEST_CASE],
            });
          }

          return { offId: off.id, onId: on.id };
        },
      );

      const offItemIds = await test.step(
        'With deduplication off the suite holds both test cases, under distinct ids',
        async () => {
          const items = await backendClient.getTestSuiteItems(offId);
          expect(items, 'both inserts were stored').toHaveLength(2);
          expect(
            items.map((item) => item.data.question),
            'and both carry the content sent',
          ).toEqual([TEST_CASE.data.question, TEST_CASE.data.question]);
          const ids = items.map((item) => item.id);
          expect(new Set(ids).size, 'two separate items, not one overwritten').toBe(2);
          return ids;
        },
      );

      const onItemIds = await test.step('With the default the duplicate is dropped', async () => {
        const items = await backendClient.getTestSuiteItems(onId);
        expect(items, 'the duplicate never reached the backend').toHaveLength(1);
        expect(items[0].data.question).toBe(TEST_CASE.data.question);
        return items.map((item) => item.id);
      });

      await test.step('The Test cases tab renders both rows of the non-deduplicated suite', async () => {
        const suites = new TestSuitesPage(page);
        await suites.goto(project.id);
        await suites.waitForReady();
        const items = await suites.openTestSuiteByName(offName);
        await items.waitForReady();

        await expect(items.itemRows(), 'two rows render').toHaveCount(2);
        // The two rows are content-identical and the table shows no data
        // columns, so `data-row-id` is the only thing that distinguishes
        // "both stored items rendered" from "one item rendered twice".
        expect((await items.itemRowIds()).sort()).toEqual([...offItemIds].sort());
      });

      await test.step('And exactly one row for the deduplicated suite', async () => {
        const suites = new TestSuitesPage(page);
        await suites.goto(project.id);
        await suites.waitForReady();
        const items = await suites.openTestSuiteByName(onName);
        await items.waitForReady();

        await expect(items.itemRows(), 'one row renders').toHaveCount(1);
        expect(await items.itemRowIds()).toEqual(onItemIds);
      });
    },
  );
});
