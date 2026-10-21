import { test, expect } from '@e2e/fixtures';

/**
 * `TestSuite.insert` deduplicates on a content hash the suite keeps locally, so
 * whether an insert can recognise an item the suite already holds depends
 * entirely on how the suite object was built.
 *
 * Three factories build one:
 *   - `create_test_suite` — the suite is new, so every hash is known;
 *   - `get_test_suite` — one suite by name;
 *   - `get_test_suites` — the listing, which is what this covers.
 *
 * The listing path is the one nothing in this estate reaches: every other spec
 * and fixture goes through `get_or_create_test_suite`. That gap is why a suite
 * that came back from `get_test_suites()` was able to ship with its hashes
 * marked synced when they had never been fetched — so the first insert skipped
 * the lazy sync, matched nothing, and wrote a second copy of an item the suite
 * already held.
 *
 * The failure is a silently grown suite, not an error: the insert succeeds, the
 * page renders, and every later run evaluates the duplicate too. So the
 * assertions are on which items the suite ends up holding.
 *
 * API-level throughout, with no browser: what is under test is the SDK's own
 * dedup bookkeeping, and driving a UI to observe an item count second-hand
 * would only add flake between the assertion and the thing it asserts.
 *
 * Each bridge call constructs its own `Opik` client, so no call inherits
 * content hashes another one happened to compute — the seed cannot mask a
 * broken sync.
 */

/** Exactly the items `testSuite` seeds. Re-inserting one of these must be a no-op. */
const HELD_QUESTION = 'first question';

const NEW_QUESTION = 'fourth question';

/** The `question` values of a suite's items, in a stable order for comparison. */
function questionsOf(items: Array<{ data: Record<string, unknown> }>): string[] {
  return items.map((item) => String(item.data.question)).sort();
}

test.describe('Test suites — insert dedup', { tag: ['@t2-cuj', '@area:test-suites'] }, () => {
  test(
    'Inserting an item a listed suite already holds does not duplicate it, while a new item still lands',
    { tag: ['@cap:test-suites.list-suites'] },
    async ({ testSuite, sdkClient, backendClient }) => {
      const seededQuestions = questionsOf(testSuite.items);

      await test.step('The seeded suite holds exactly its three items', async () => {
        // Guards the premise of the whole test: if the fixture's seed ever
        // stops containing HELD_QUESTION, the "duplicate" insert below becomes
        // an insert of a new item and every assertion still passes.
        expect(seededQuestions, 'the fixture seeds the item re-inserted below').toContain(
          HELD_QUESTION,
        );

        // Read server-side rather than trusting the fixture's own record: a
        // dedup assertion over a suite that never received the items would pass
        // having proved nothing.
        const items = await backendClient.getTestSuiteItems(testSuite.id);
        expect(questionsOf(items), 'the suite as the backend holds it').toEqual(
          seededQuestions,
        );
      });

      await test.step('Re-insert an item the suite already holds, through the listing path', async () => {
        const result = await sdkClient.python.insertTestSuiteItems({
          suite_name: testSuite.name,
          project_name: testSuite.projectName,
          items: [{ data: { question: HELD_QUESTION } }],
          resolve_via: 'list',
        });
        // The bridge selects the suite out of `get_test_suites()` and fails the
        // call unless exactly one matches, so reaching here already proves the
        // listing returned the suite under test and not a same-named other.
        expect(result.suite_id, 'the listing resolved the suite under test').toBe(
          testSuite.id,
        );
      });

      await test.step('The suite still holds only its three items', async () => {
        const items = await backendClient.getTestSuiteItems(testSuite.id);
        expect(
          questionsOf(items),
          'an item the suite already holds must not be written a second time',
        ).toEqual(seededQuestions);
      });

      await test.step('A genuinely new item, through the same listing path, does land', async () => {
        // The control for the assertion above. Without it, an insert that wrote
        // nothing at all — a suite resolved but never synced, a silently
        // dropped batch — would look exactly like correct deduplication.
        await sdkClient.python.insertTestSuiteItems({
          suite_name: testSuite.name,
          project_name: testSuite.projectName,
          items: [{ data: { question: NEW_QUESTION } }],
          resolve_via: 'list',
        });

        const items = await backendClient.getTestSuiteItems(testSuite.id);
        expect(
          questionsOf(items),
          'the new item is added, and the duplicate still is not there',
        ).toEqual([...seededQuestions, NEW_QUESTION].sort());
      });
    },
  );
});
