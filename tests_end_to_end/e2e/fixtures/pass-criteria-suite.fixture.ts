import { test as baseTest } from './guardrail-trigger-alert.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend/uuid7';

/**
 * The suite's own pass criteria: a 3-of-5 policy.
 *
 * Deliberately not 1-of-1, which is `DEFAULT_EXECUTION_POLICY` on the frontend
 * and what the dialog fell back to before opik#9482. A seed at the default
 * would make the regression invisible — the dialog would show 1 and 1 whether
 * it had read the suite or not. Both numbers differ from the default, and from
 * each other, so a dialog that read one field and defaulted the other also
 * fails.
 */
export const SUITE_RUNS_PER_ITEM = 5;
export const SUITE_PASS_THRESHOLD = 3;

/**
 * The runs the test types over the inherited value on the second trace.
 *
 * Below `SUITE_PASS_THRESHOLD`, which is the point: the threshold input's `max`
 * is the current runs value, so lowering runs to 2 has to drag the threshold
 * down from 3 with it. A number above the threshold would leave it untouched
 * and the clamp would go untested.
 */
export const OVERRIDE_RUNS_PER_ITEM = 2;

export interface PassCriteriaSuiteRef {
  /** The TEST_SUITE dataset, holding no items. */
  suiteId: string;
  suiteName: string;
  /** Two traces in `project`, in the order they were written. */
  traceIds: [string, string];
}

export interface PassCriteriaSuiteFixtures {
  /**
   * An EMPTY test suite with a 3-of-5 pass policy, and two traces to add to it.
   *
   * Empty on purpose. The suite items page is read at the end to see what each
   * added item inherited, and a suite seeded with items of its own would put
   * rows beside them that read the same inherited string — so "the two rows say
   * 3 of 5 and 2 of 2" would be satisfied by a page that had rendered the wrong
   * rows. With an empty suite the only items are the two the test creates.
   *
   * Two traces rather than one because the dialog is opened twice: once left
   * untouched, once overridden. One trace cannot carry both outcomes, and
   * adding the same trace twice would dedupe.
   *
   * The suite goes through the Python bridge's `createTestSuite`, which is the
   * public SDK surface for a suite-level policy; the traces go through the
   * batch endpoint. Nothing is created through the UI — the UI is what the test
   * drives.
   */
  passCriteriaSuite: PassCriteriaSuiteRef;
}

export const test = baseTest.extend<PassCriteriaSuiteFixtures>({
  passCriteriaSuite: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const suiteName = `${testNamespace}-criteria-suite`;
    const created = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name: suiteName,
      description: `3-of-5 suite seeded by ${testInfo.title}`,
      runs_per_item: SUITE_RUNS_PER_ITEM,
      pass_threshold: SUITE_PASS_THRESHOLD,
      // No items and no global assertions: the dialog reads the suite's latest
      // VERSION for its policy, which the create writes either way, and an
      // assertion list would add rows to the Evaluation criteria section that
      // have nothing to do with the policy under test.
      items: [],
    });

    const traceIds: [string, string] = [uuid7(), uuid7()];
    await backendClient.createTracesBatch({
      projectName: project.name,
      traces: traceIds.map((id, index) => ({
        id,
        name: `${testNamespace}-trace-${index + 1}`,
        input: { question: `question ${index + 1}` },
        output: { answer: `answer ${index + 1}` },
      })),
    });

    await testInfo.attach('opik.pass-criteria-suite', {
      body: JSON.stringify(
        {
          suiteId: created.id,
          suiteName,
          runsPerItem: SUITE_RUNS_PER_ITEM,
          passThreshold: SUITE_PASS_THRESHOLD,
          traceIds,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await use({ suiteId: created.id, suiteName, traceIds });

    if (shouldLeaveArtifacts(testInfo)) return;
    // A test suite shares storage with datasets and does not cascade with the
    // project, so it needs an explicit delete — the same reason
    // `test-suite.fixture.ts` gives. Its items go with it. The traces do not
    // cascade either, but the run-prefix sweep in global-teardown reaches them
    // through the project, which this fixture does not own.
    try {
      await backendClient.deleteDataset(created.id);
    } catch (err) {
      console.warn(`[passCriteriaSuite] delete warning for ${suiteName}:`, err);
    }
  },
});

export { expect } from './guardrail-trigger-alert.fixture';
