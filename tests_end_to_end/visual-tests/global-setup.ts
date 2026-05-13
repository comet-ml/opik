import { chromium, FullConfig } from '@playwright/test';
import { getEnvironmentConfig } from '../typescript-tests/config/env.config';
import { TestHelperClient } from '../typescript-tests/helpers/test-helper-client';
import * as path from 'path';
import * as fs from 'fs';

export const AUTH_STATE_FILE = '.auth/user.json';
const STATE_FILE = path.join(__dirname, '.test-state.json');
const authFile = path.join(__dirname, AUTH_STATE_FILE);

interface TestState {
  projectName: string;
  datasetName: string;
  testSuiteName: string;
  experimentName: string;
  experimentId: string;
  testSuiteExperimentId: string;
}

async function createTestData(): Promise<TestState> {
  const client = new TestHelperClient();
  const ts = Date.now();
  const projectName = `visual-project-${ts}`;
  const datasetName = `visual-dataset-${ts}`;
  const testSuiteName = `visual-testsuite-${ts}`;
  const experimentName = `visual-experiment-${ts}`;

  await client.createProject(projectName);
  await client.waitForProjectVisible(projectName, 15);

  await client.createTracesWithSpansClient(
    projectName,
    { count: 3, prefix: 'visual-trace-', tags: ['visual'], metadata: { env: 'visual-test' } },
    { count: 2, prefix: 'visual-span-', tags: ['visual'], metadata: { step: '1' } },
  );

  await client.createThreadsClient(projectName, [
    { thread_id: 'visual-thread-1', inputs: ['Hello, what is Opik?'], outputs: ['Opik is an LLM observability platform.'] },
    { thread_id: 'visual-thread-2', inputs: ['How do traces work?'], outputs: ['Traces capture the full execution of an LLM call.'] },
  ]);

  await client.createDatasetForProject(datasetName, projectName);
  await client.insertDatasetItems(datasetName, [
    { input: 'What is 2 + 2?', output: '4' },
    { input: 'What is the capital of France?', output: 'Paris' },
    { input: 'Name a primary color.', output: 'Red' },
  ]);
  await client.waitForDatasetItemsCount(datasetName, 3, 15);

  await client.createTestSuiteDataset(testSuiteName, projectName);
  await client.insertDatasetItems(testSuiteName, [
    { input: 'Test input A', output: 'Test output A' },
    { input: 'Test input B', output: 'Test output B' },
  ]);
  await client.waitForDatasetItemsCount(testSuiteName, 2, 15);

  const experiment = await client.createExperimentForProject(experimentName, datasetName, projectName);

  const testSuiteExperimentName = `visual-testsuite-exp-${ts}`;
  const testSuiteExperiment = await client.createTestSuiteExperiment(testSuiteExperimentName, testSuiteName, projectName);

  return { projectName, datasetName, testSuiteName, experimentName, experimentId: experiment.id, testSuiteExperimentId: testSuiteExperiment.id };
}

async function globalSetup(_config: FullConfig) {
  const envConfig = getEnvironmentConfig();
  const envData = envConfig.getConfig();

  if (!envConfig.isLocal()) {
    const authDir = path.dirname(authFile);
    if (!fs.existsSync(authDir)) {
      fs.mkdirSync(authDir, { recursive: true });
    }

    const browser = await chromium.launch();
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const baseUrl = envData.baseUrl.replace(/\/opik$/, '');
      const response = await page.request.post(`${baseUrl}/api/auth/login`, {
        data: {
          email: envData.testUserEmail,
          plainTextPassword: envData.testUserPassword,
        },
        headers: { 'Content-Type': 'application/json' },
      });

      if (!response.ok()) {
        throw new Error(`Login failed with status ${response.status()}: ${await response.text()}`);
      }

      const responseData = await response.json();
      if (!responseData.apiKeys || responseData.apiKeys.length === 0) {
        throw new Error('No API keys found in login response');
      }

      process.env.OPIK_API_KEY = responseData.apiKeys[0];
      await context.storageState({ path: authFile });
    } finally {
      await page.close();
      await context.close();
      await browser.close();
    }
  }

  process.env.OPIK_BASE_URL = envData.baseUrl;
  process.env.OPIK_URL_OVERRIDE = envConfig.getApiUrl();
  process.env.OPIK_TEST_WORKSPACE = envData.workspace;

  let state: TestState;
  if (fs.existsSync(STATE_FILE)) {
    state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8'));
    console.log(`Reusing test data: ${state.projectName}`);
  } else {
    state = await createTestData();
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
    console.log(`Created test data: ${state.projectName}`);
  }

  process.env.VISUAL_PROJECT_NAME = state.projectName;
  process.env.VISUAL_DATASET_NAME = state.datasetName;
  process.env.VISUAL_TEST_SUITE_NAME = state.testSuiteName;
  process.env.VISUAL_EXPERIMENT_NAME = state.experimentName;
  process.env.VISUAL_EXPERIMENT_ID = state.experimentId;
}

export default globalSetup;
