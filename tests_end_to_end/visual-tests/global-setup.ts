import { chromium, FullConfig } from '@playwright/test';
import { getEnvironmentConfig } from './config/env.config';
import { TestHelperClient } from './helpers/test-helper-client';
import * as path from 'path';
import * as fs from 'fs';

// Deleting a project only guarantees its row disappears from the list API — trace/span
// cascade deletion happens asynchronously afterwards (spans are themselves a second-order
// cascade off trace deletion, so they can lag even further behind). Recreating a project
// with the same name (needed so screenshots stay stable across runs) can race that
// cascade, leaving stale traces/spans attached to what looks like a freshly created
// project. Rather than trust the delete+recreate timing, explicitly verify each project
// is empty of both before any spec seeds real data into it, purging and re-checking until
// it is or we time out.
async function ensureProjectHasNoLeftoverData(
  client: TestHelperClient,
  projectName: string,
  timeoutMs: number = 30000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const traces = await client.getTraces(projectName, 1000);
    const spans = await client.searchSpans(projectName, { maxResults: 1000 });

    if (traces.length === 0 && spans.length === 0) {
      return;
    }

    if (traces.length > 0) {
      console.log(`Found ${traces.length} leftover trace(s) in "${projectName}" from a previous run — purging`);
      await client.deleteTraces(traces.map((trace) => trace.id));
    }
    if (spans.length > 0) {
      console.log(`Found ${spans.length} leftover span(s) in "${projectName}" from a previous run — purging`);
      await client.deleteSpansByProject(projectName);
    }

    await new Promise((resolve) => setTimeout(resolve, 1000));
  }

  throw new Error(
    `Project "${projectName}" still has leftover traces/spans after ${timeoutMs}ms — refusing to seed new data on top of stale state`,
  );
}

export const AUTH_STATE_FILE = '.auth/user.json';
const authFile = path.join(__dirname, AUTH_STATE_FILE);

// Fixed names — no timestamp suffix so screenshots are identical across runs
const PROJECT_NAME = 'visual-project';
const EMPTY_PROJECT_NAME = 'visual-empty-project';
const SIDEBAR_PROJECT_NAME = 'visual-sidebar-project';
const DATASET_NAME = 'visual-dataset';
const TEST_SUITE_NAME = 'visual-testsuite';
const EXPERIMENT_NAME = 'visual-experiment';
const TEST_SUITE_EXP_NAME = 'visual-testsuite-exp';
const FEEDBACK_DEF_NAMES = ['visual-config-quality', 'visual-config-sentiment'];
// 'development'/'staging'/'production' are seeded by Liquibase for every workspace
// (migration 000066_seed_default_environments.sql) — deletable, but present on any
// fresh instance, so they must be cleared too or the environments tab is never empty.
const ENVIRONMENT_NAMES = [
  'visual-config-env-staging',
  'visual-config-env-production',
  'development',
  'staging',
  'production',
];
const AI_PROVIDERS = ['openai', 'anthropic'];

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

  // Clean up any leftover data from a previous run. Experiments aren't scoped to a
  // project/dataset deletion cascade and are otherwise only cleaned up by
  // global-teardown, which never runs if a previous CI run crashed, timed out, or
  // was cancelled — so they must be swept here by name too, or they accumulate
  // indefinitely across runs.
  console.log('Cleaning up any existing test data...');
  try { await client.deleteExperimentsByName(TEST_SUITE_EXP_NAME); } catch { /* ignore */ }
  try { await client.deleteExperimentsByName(EXPERIMENT_NAME); } catch { /* ignore */ }
  try { await client.deleteDataset(TEST_SUITE_NAME); } catch { /* ignore - not found */ }
  await client.waitForDatasetDeleted(TEST_SUITE_NAME, 30);
  try { await client.deleteDataset(DATASET_NAME); } catch { /* ignore - not found */ }
  await client.waitForDatasetDeleted(DATASET_NAME, 30);
  try {
    await client.deleteProject(PROJECT_NAME);
    await client.waitForProjectDeleted(PROJECT_NAME, 30);
  } catch { /* ignore */ }
  try {
    await client.deleteProject(EMPTY_PROJECT_NAME);
    await client.waitForProjectDeleted(EMPTY_PROJECT_NAME, 30);
  } catch { /* ignore */ }
  try {
    await client.deleteProject(SIDEBAR_PROJECT_NAME);
    await client.waitForProjectDeleted(SIDEBAR_PROJECT_NAME, 30);
  } catch { /* ignore */ }

  for (const name of FEEDBACK_DEF_NAMES) {
    try {
      const definition = await client.getFeedbackDefinition(name);
      await client.deleteFeedbackDefinition(definition.id);
    } catch { /* ignore */ }
  }
  try {
    const environments = await client.findEnvironments();
    for (const env of environments) {
      if (ENVIRONMENT_NAMES.includes(env.name)) {
        await client.deleteEnvironment(env.id);
      }
    }
  } catch { /* ignore */ }
  try {
    const providerKeys = await client.findProviderApiKeys();
    for (const key of providerKeys) {
      if (AI_PROVIDERS.includes(key.provider)) {
        await client.deleteProviderApiKey(key.id);
      }
    }
  } catch { /* ignore */ }

  console.log('Creating projects...');
  await client.createProject(PROJECT_NAME);
  await client.waitForProjectVisible(PROJECT_NAME, 15);
  await ensureProjectHasNoLeftoverData(client, PROJECT_NAME);

  await client.createProject(EMPTY_PROJECT_NAME);
  await client.waitForProjectVisible(EMPTY_PROJECT_NAME, 15);
  await ensureProjectHasNoLeftoverData(client, EMPTY_PROJECT_NAME);

  await client.createProject(SIDEBAR_PROJECT_NAME);
  await client.waitForProjectVisible(SIDEBAR_PROJECT_NAME, 15);
  await ensureProjectHasNoLeftoverData(client, SIDEBAR_PROJECT_NAME);

  process.env.VISUAL_PROJECT_NAME = PROJECT_NAME;
  process.env.VISUAL_EMPTY_PROJECT_NAME = EMPTY_PROJECT_NAME;
  process.env.VISUAL_SIDEBAR_PROJECT_NAME = SIDEBAR_PROJECT_NAME;

  console.log(`Projects ready: ${PROJECT_NAME}, ${EMPTY_PROJECT_NAME}, ${SIDEBAR_PROJECT_NAME}`);
}

export default globalSetup;
