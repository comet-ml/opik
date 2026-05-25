import { chromium, FullConfig } from '@playwright/test';
import { getEnvironmentConfig } from '../typescript-tests/config/env.config';
import { TestHelperClient } from '../typescript-tests/helpers/test-helper-client';
import * as path from 'path';
import * as fs from 'fs';

export const AUTH_STATE_FILE = '.auth/user.json';
const STATE_FILE = path.join(__dirname, '.test-state.json');
const authFile = path.join(__dirname, AUTH_STATE_FILE);

// Fixed names — no timestamp suffix so screenshots are identical across runs
const PROJECT_NAME = 'visual-project';
const DATASET_NAME = 'visual-dataset';
const TEST_SUITE_NAME = 'visual-testsuite';
const EXPERIMENT_NAME = 'visual-experiment';
const TEST_SUITE_EXP_NAME = 'visual-testsuite-exp';

interface TestState {
  experimentId: string;
  testSuiteExperimentId: string;
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

  const client = new TestHelperClient();

  // Clean up any leftover data from a previous run
  console.log('Cleaning up any existing test data...');
  try { await client.deleteDataset(DATASET_NAME); } catch { /* ignore */ }
  try { await client.deleteDataset(TEST_SUITE_NAME); } catch { /* ignore */ }
  try {
    await client.deleteProject(PROJECT_NAME);
    await client.waitForProjectDeleted(PROJECT_NAME, 30);
  } catch { /* ignore */ }

  console.log('Creating test data...');
  await client.createProject(PROJECT_NAME);
  await client.waitForProjectVisible(PROJECT_NAME, 15);

  await client.createTracesWithSpansClient(
    PROJECT_NAME,
    { count: 3, prefix: 'visual-trace-', tags: ['visual'], metadata: { env: 'visual-test' } },
    { count: 2, prefix: 'visual-span-', tags: ['visual'], metadata: { step: '1' } },
  );

  await client.createThreadsClient(PROJECT_NAME, [
    { thread_id: 'visual-thread-1', inputs: ['Hello, what is Opik?'], outputs: ['Opik is an LLM observability platform.'] },
    { thread_id: 'visual-thread-2', inputs: ['How do traces work?'], outputs: ['Traces capture the full execution of an LLM call.'] },
  ]);

  await client.createDatasetForProject(DATASET_NAME, PROJECT_NAME);
  await client.insertDatasetItems(DATASET_NAME, [
    { input: 'What is 2 + 2?', output: '4' },
    { input: 'What is the capital of France?', output: 'Paris' },
    { input: 'Name a primary color.', output: 'Red' },
  ]);
  await client.waitForDatasetItemsCount(DATASET_NAME, 3, 15);

  await client.createTestSuiteDatasetForProject(TEST_SUITE_NAME, PROJECT_NAME);
  await client.insertDatasetItems(TEST_SUITE_NAME, [
    { input: 'Test input A', output: 'Test output A' },
    { input: 'Test input B', output: 'Test output B' },
  ]);
  await client.waitForDatasetItemsCount(TEST_SUITE_NAME, 2, 15);

  const experiment = await client.createExperimentForProject(EXPERIMENT_NAME, DATASET_NAME, PROJECT_NAME);
  const testSuiteExperiment = await client.createTestSuiteExperimentForProject(TEST_SUITE_EXP_NAME, TEST_SUITE_NAME, PROJECT_NAME);

  const state: TestState = {
    experimentId: experiment.id,
    testSuiteExperimentId: testSuiteExperiment.id,
  };
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));

  process.env.VISUAL_PROJECT_NAME = PROJECT_NAME;
  process.env.VISUAL_EXPERIMENT_NAME = EXPERIMENT_NAME;

  console.log(`Test data ready: ${PROJECT_NAME}`);
}

export default globalSetup;
