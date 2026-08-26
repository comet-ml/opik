import { test as baseTest } from './builtin-provider-key.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface BystanderExperimentRef {
  experimentId: string;
  experimentName: string;
  datasetId: string;
  datasetName: string;
}

export interface BystanderTestSuiteRef {
  id: string;
  name: string;
}

export interface BystanderFixtures {
  bystanderExperiment: BystanderExperimentRef;
  bystanderTestSuite: BystanderTestSuiteRef;
  registerDatasetCleanup: (id: string, name: string) => void;
}

/**
 * A destructive-action test needs a second entity it does NOT touch: asserting
 * only that the target disappeared would pass just as well if the delete took
 * everything in the project with it.
 */
export const test = baseTest.extend<BystanderFixtures>({
  bystanderExperiment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const experimentName = `${testNamespace}-exp-bystander`;
    const datasetName = `${testNamespace}-exp-bystander-ds`;
    const created = await sdkClient.python.evaluateExperiment({
      project_name: project.name,
      dataset_name: datasetName,
      experiment_name: experimentName,
      items: [{ input: 'What is 2 + 2?', expected_output: '4', task_output: '4' }],
    });

    const ref: BystanderExperimentRef = {
      experimentId: created.experiment_id,
      experimentName: created.experiment_name,
      datasetId: created.dataset_id,
      datasetName,
    };
    await testInfo.attach('opik.bystander-experiment', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** Experiment before dataset: it holds the reference. */
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteExperiment(ref.experimentId);
      } catch (err) {
        console.warn(`[bystanderExperiment fixture] delete warning for ${experimentName}:`, err);
      }
      try {
        await backendClient.deleteDataset(ref.datasetId);
      } catch (err) {
        console.warn(`[bystanderExperiment fixture] delete warning for ${datasetName}:`, err);
      }
    }
  },

  bystanderTestSuite: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-suite-bystander`;
    const created = await sdkClient.python.createTestSuite({
      name,
      project_name: project.name,
      description: 'bystander — must survive the delete',
      items: [{ data: { question: 'bystander question' } }],
    });
    const ref: BystanderTestSuiteRef = { id: created.id, name: created.name };
    await testInfo.attach('opik.bystander-test-suite', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });
    await use(ref);
    /** Test suites share the datasets table, so they delete as datasets. */
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(ref.id);
      } catch (err) {
        console.warn(`[bystanderTestSuite fixture] delete warning for ${name}:`, err);
      }
    }
  },

  /**
   * For datasets and test suites whose ids aren't known until mid-test — a
   * suite recreated after a delete, say. Register as soon as the id exists and
   * teardown runs even when a later assertion throws. Mirrors
   * `registerPromptCleanup`; test suites share the datasets table, so both go
   * through `deleteDataset`.
   */
  registerDatasetCleanup: async ({ backendClient }, use, testInfo) => {
    const registry: Array<{ id: string; name: string }> = [];
    await use((id, name) => {
      registry.push({ id, name });
    });
    if (!shouldLeaveArtifacts(testInfo)) {
      for (const { id, name } of registry) {
        try {
          await backendClient.deleteDataset(id);
        } catch (err) {
          console.warn(`[registerDatasetCleanup] delete warning for ${name}:`, err);
        }
      }
    }
  },
});

export { expect } from './builtin-provider-key.fixture';
