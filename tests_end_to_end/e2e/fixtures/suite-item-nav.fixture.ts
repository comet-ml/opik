import { test as baseTest } from './moved-trace-threads.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One seeded suite item and the trace the experiment recorded against it. */
export interface SuiteNavItem {
  datasetItemId: string;
  traceId: string;
  /** The item's `question` field, unique per item so a panel can be told apart. */
  question: string;
}

export interface SuiteItemNavRef {
  /** The TEST_SUITE dataset's id — the `suiteId` the items route takes. */
  suiteId: string;
  suiteName: string;
  experimentId: string;
  experimentName: string;
  projectId: string;
  /** Both items, in the order the suite was seeded. */
  items: [SuiteNavItem, SuiteNavItem];
}

export interface SuiteItemNavFixtures {
  suiteItemNav: SuiteItemNavRef;
}

const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 2_000;

/** Two items, so the sidebar's Previous/Next has somewhere to step to. */
const SEED_QUESTIONS = ['suite-nav-first', 'suite-nav-second'] as const;

/**
 * An `evaluation_suite` experiment over a TWO-item test suite, one run per item.
 *
 * Why this is not `suiteExperimentRunMedia`: that fixture seeds ONE item run
 * twice, which is what `MultiRunTabs` needs. This one needs the opposite shape —
 * two distinct dataset items, each run once — because what it exists to support
 * is stepping the item sidebar from one item to its neighbour and asserting the
 * "Experiment" button follows the panel rather than the `from` it arrived with.
 * One item has no neighbour to step to, so the run-media fixture cannot serve
 * this spec, and seeding two runs of two items would put run tabs in the way of
 * the navigation under test for no gain.
 *
 * Why a real TEST_SUITE dataset rather than the plain one the run-media fixture
 * writes: the landing page here is `/test-suites/$suiteId/items`, and that route
 * refuses a dataset whose `type` is not TEST_SUITE. `createTestSuite` creates
 * the suite and its items and does NOT run it, so no LLM judge is involved —
 * the run is forged below instead, exactly as the run-media fixture argues.
 *
 * Why `evaluation_method: 'evaluation_suite'`: it is the only thing that decides
 * which sidebar the compare route's Items tab mounts (`isTestSuiteExperiment`
 * reads it off the first experiment). Without it the Items tab renders
 * `CompareExperimentsPanel` and the spec would be driving the dataset-side call
 * site it is deliberately paired against.
 *
 * ONE experiment, deliberately: it pins the SINGULAR branch of the button's
 * tooltip ("in experiment:", one name), which the dataset-side spec — always two
 * experiments — never reaches.
 *
 * Teardown deletes the experiment, then the suite, then the traces; none of the
 * three cascades with the project.
 */
export const test = baseTest.extend<SuiteItemNavFixtures>({
  suiteItemNav: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const suiteName = `${testNamespace}-nav-suite`;
    const experimentName = `${testNamespace}-nav-suite-exp`;
    const experimentId = uuid7();

    const suite = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name: suiteName,
      description: 'return-to-experiment navigation from the suite item sidebar',
      items: SEED_QUESTIONS.map((question) => ({ data: { question } })),
    });

    let seededTraces = false;
    let traceIds: string[] = [];
    try {
      // The suite's own items, read back for their server-issued ids — the
      // route's `row` param and the experiment items' join both need them, and
      // `createTestSuite` answers with the suite alone.
      const stored = await backendClient.listDatasetItemsWithData(suite.id);
      const byQuestion = new Map(
        stored.map((item) => [String((item.data as { question?: unknown }).question), item.id]),
      );
      const items = SEED_QUESTIONS.map((question) => {
        const datasetItemId = byQuestion.get(question);
        // Asserted, not defaulted: an item that did not land would otherwise
        // surface as a `row=undefined` URL and read as the navigation defect
        // this fixture exists to test for.
        if (!datasetItemId) {
          throw new Error(
            `[suiteItemNav fixture] suite ${suite.id} has no item for question ` +
              `${JSON.stringify(question)} — stored questions were ` +
              `${JSON.stringify([...byQuestion.keys()])}`,
          );
        }
        return { datasetItemId, traceId: uuid7(), question };
      }) as [SuiteNavItem, SuiteNavItem];
      traceIds = items.map((item) => item.traceId);

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: items.map((item) => ({
          id: item.traceId,
          name: `${testNamespace}-nav-suite-trace-${item.question}`,
          input: { question: item.question },
          output: { output: `answer for ${item.question}` },
        })),
      });
      seededTraces = true;

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName: suiteName,
        projectName: project.name,
        evaluationMethod: 'evaluation_suite',
      });

      await backendClient.createExperimentItems(
        items.map((item) => ({
          experimentId,
          datasetItemId: item.datasetItemId,
          traceId: item.traceId,
        })),
      );

      // Prove the seed can discriminate before any browser opens.
      //
      // `evaluation_method` decides which of the two sidebars mounts. If it did
      // not land, every assertion below would be made against
      // `CompareExperimentsPanel` — the OTHER call site — and would pass or
      // fail for reasons that say nothing about the one under test.
      const rendered = await backendClient.getExperimentRenderFields(experimentId);
      if (rendered.evaluationMethod !== 'evaluation_suite') {
        throw new Error(
          `[suiteItemNav fixture] experiment ${experimentId} stored ` +
            `evaluation_method=${rendered.evaluationMethod}, not 'evaluation_suite' — the Items ` +
            'tab would mount CompareExperimentsPanel instead of the suite item sidebar',
        );
      }
      if (rendered.projectId !== project.id) {
        throw new Error(
          `[suiteItemNav fixture] experiment ${experimentId} stored ` +
            `project_id=${rendered.projectId}, expected ${project.id}`,
        );
      }

      await waitForBothRows(backendClient, suite.id, experimentId, items);

      const ref: SuiteItemNavRef = {
        suiteId: suite.id,
        suiteName,
        experimentId,
        experimentName,
        projectId: project.id,
        items,
      };

      await testInfo.attach('opik.suiteItemNav', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[suiteItemNav fixture] delete warning for ${what}:`, err);
          }
        };
        await safe(`experiment ${experimentName}`, () =>
          backendClient.deleteExperiment(experimentId),
        );
        await safe(`suite ${suiteName}`, () => backendClient.deleteDataset(suite.id));
        if (seededTraces) {
          await safe(`${traceIds.length} traces`, () => backendClient.deleteTraces(traceIds));
        }
      }
    }
  },
});

/**
 * Block until the compare read reports BOTH items, each carrying its own run.
 *
 * Both halves matter. A missing row means the sidebar has nothing to step to,
 * so the spec would fail on a disabled Next button rather than on the
 * navigation it is asserting; a row whose `experimentItems` is empty means the
 * run has not joined yet, and the sidebar renders no output and no Experiment
 * button at all.
 */
async function waitForBothRows(
  backendClient: {
    compareItemsPairedPage: (args: {
      datasetId: string;
      experimentIds: string[];
      page: number;
      size: number;
    }) => Promise<{ rows: Array<{ id: string; experimentItems: Array<{ traceId: string }> }> }>;
  },
  suiteId: string,
  experimentId: string,
  items: readonly SuiteNavItem[],
): Promise<void> {
  const start = Date.now();
  let seen = 'no read yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    const answer = await backendClient.compareItemsPairedPage({
      datasetId: suiteId,
      experimentIds: [experimentId],
      page: 1,
      size: 10,
    });
    const resolved = items.map((item) => {
      const row = answer.rows.find((r) => r.id === item.datasetItemId);
      return row?.experimentItems.some((entry) => entry.traceId === item.traceId) ?? false;
    });
    seen = JSON.stringify(
      items.map((item, index) => ({ question: item.question, joined: resolved[index] })),
    );
    if (resolved.every(Boolean)) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[suiteItemNav fixture] suite ${suiteId} did not report both items with their own run ` +
      `after ${Date.now() - start}ms; last read was ${seen}`,
  );
}

export { expect } from './moved-trace-threads.fixture';
