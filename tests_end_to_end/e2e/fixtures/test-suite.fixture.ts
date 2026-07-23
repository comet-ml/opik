import { test as baseTest } from './comparison-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface TestSuiteItemSeed {
  data: Record<string, unknown>;
  assertions?: string[];
  description?: string;
}

export interface TestSuiteRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  description: string | null;
  assertions: string[];
  runsPerItem: number;
  passThreshold: number;
  items: TestSuiteItemSeed[];
}

export interface TestSuiteFixtures {
  testSuite: TestSuiteRef;
}

/**
 * Tautological assertions paired with a task function that returns the
 * literal text "PASS" make the LLM judge's verdict mechanically predictable.
 * The entire LLM-judging code path is still exercised on every run.
 */
const SEED_ASSERTIONS: string[] = [
  'The response contains the literal text PASS.',
  'The response is non-empty.',
];

const SEED_ITEMS: TestSuiteItemSeed[] = [
  { data: { question: 'first question' } },
  { data: { question: 'second question' } },
  { data: { question: 'third question' } },
];

const RUNS_PER_ITEM = 1;
const PASS_THRESHOLD = 1;

export const test = baseTest.extend<TestSuiteFixtures>({
  testSuite: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-suite`;
    const description = `seeded by ${testInfo.title}`;
    const created = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name,
      description,
      global_assertions: SEED_ASSERTIONS,
      runs_per_item: RUNS_PER_ITEM,
      pass_threshold: PASS_THRESHOLD,
      items: SEED_ITEMS,
    });
    const ref: TestSuiteRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      projectName: project.name,
      description,
      assertions: SEED_ASSERTIONS,
      runsPerItem: RUNS_PER_ITEM,
      passThreshold: PASS_THRESHOLD,
      items: SEED_ITEMS,
    };
    await testInfo.attach('opik.test-suite', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });
    await use(ref);
    /** Test suites share storage with datasets and don't cascade with project deletion — explicit delete required. */
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        console.warn(`[test-suite fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './experiment.fixture';
