import { expect } from '@playwright/test';
import { test as baseTest } from './thread-cost-order-search.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * The one schema item the suite is given, and the only one.
 *
 * `INTEGER`, and numeric-ONLY, is what makes this fixture usable at all: with
 * no `BOOLEAN` item beside it, `LLMJudge.score` returns
 * `_merge_config_schema_results([])` before `_agenerate_and_parse` is reached,
 * so the run needs no judge model, no provider key, no cost and has no
 * stochasticity — while still driving the real suite-run path end to end. A
 * mixed boolean+numeric schema would reach the model and is deliberately not
 * what this seeds; the exploration that proposed this spec could not verify
 * that mixture for the same reason.
 *
 * `name` and `description` differ on purpose. The failed result's `name` is the
 * item's DESCRIPTION and the reason text names the item's NAME — so a seed
 * where the two were equal could not tell a build that had swapped them from a
 * correct one.
 */
export const NUMERIC_ITEM = {
  name: 'helpfulness',
  type: 'INTEGER' as const,
  description: 'Rate the helpfulness of the response from 1 to 5.',
};

/** Two items, so "one result per item, in schema order" is a real claim. */
const SUITE_ITEMS = [
  { data: { question: 'how do I request a refund?' } },
  { data: { question: 'how do I change my address?' } },
] as const;

export interface NumericAssertionSuiteRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /** The item the suite's evaluator schema carries, as requested. */
  item: typeof NUMERIC_ITEM;
  /** How many test cases the suite holds, and so how many results a run owes. */
  itemCount: number;
  /** The suite version the typed write landed on, as the backend named it. */
  versionName: string | null;
  /** `get_global_assertions()` over the suite re-read after the write. */
  globalAssertions: string[];
  /** The evaluator schema the backend answers, so a spec asserts the round trip. */
  schemaItems: Array<{ name: string; type: string; description: string }>;
}

export interface NumericAssertionSuiteFixtures {
  numericAssertionSuite: NumericAssertionSuiteRef;
}

export const test = baseTest.extend<NumericAssertionSuiteFixtures>({
  /**
   * A real test suite on the backend whose suite-level evaluator schema holds a
   * single `INTEGER` item.
   *
   * Seeded in two writes, because one is all the public SDK can express. The
   * suite and its items go through `create_test_suite`, which takes assertions
   * as plain strings and writes every schema item as `BOOLEAN`; the typed item
   * then replaces that schema through the bridge's `evaluator-schema` route,
   * which builds the config from the same strings the shorthand would and
   * changes nothing but each item's `type`. There is no public parameter for a
   * typed schema at all, which is exactly why the branch opik#8557 added lives
   * in no e2e test.
   *
   * The fixture asserts the write LANDED — the schema the backend answers, and
   * `get_global_assertions()` over a suite re-read rather than the object that
   * was written through. Without that, a run whose results looked right would be
   * attributable to a default `BOOLEAN` schema the write never replaced, and the
   * spec would be green having verified nothing about the numeric branch.
   *
   * Teardown deletes the suite. Suites share storage with datasets and do NOT
   * cascade with the project, and `global-teardown.ts`'s run-prefix sweep does
   * know about datasets — but the delete is explicit here anyway, so the suite
   * is gone by the end of the test that created it rather than by the end of the
   * whole run.
   */
  numericAssertionSuite: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-numeric-suite`;

    const created = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name,
      description: `seeded by ${testInfo.title}`,
      // No `global_assertions` here deliberately: the shorthand would write a
      // BOOLEAN schema that the typed write then has to replace, and a replace
      // that silently failed would leave a judge-able schema behind — so the
      // run would reach a model and the failure would look like a provider
      // problem rather than a write that did not land.
      runs_per_item: 1,
      pass_threshold: 1,
      items: SUITE_ITEMS.map((item) => ({ data: { ...item.data } })),
    });

    const typed = await sdkClient.python.setTestSuiteEvaluatorSchema({
      suite_name: created.name,
      project_name: project.name,
      schema_items: [{ ...NUMERIC_ITEM }],
    });

    // ---- the write is proven before the spec runs anything ----

    expect(
      typed.schema_items,
      'the backend answers exactly the one typed schema item that was written',
    ).toEqual([
      { name: NUMERIC_ITEM.name, type: NUMERIC_ITEM.type, description: NUMERIC_ITEM.description },
    ]);
    expect(
      typed.global_assertions,
      "get_global_assertions() reports the numeric item's description — the suite's " +
        'assertion list does not hide an item it cannot score',
    ).toEqual([NUMERIC_ITEM.description]);
    expect(
      typed.suite_id,
      'the typed write landed on the suite that was created, not a second one',
    ).toBe(created.id);

    const ref: NumericAssertionSuiteRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      projectName: project.name,
      item: NUMERIC_ITEM,
      itemCount: SUITE_ITEMS.length,
      versionName: typed.version_name,
      globalAssertions: typed.global_assertions,
      schemaItems: typed.schema_items,
    };

    await testInfo.attach('opik.numericAssertionSuite', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        // Never rethrow from teardown: a cleanup failure must not replace the
        // test's own error.
        console.warn(`[numericAssertionSuite fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './thread-cost-order-search.fixture';
