import { chromium, FullConfig } from '@playwright/test';
import { getEnvironmentConfig } from './config/env.config';
import { TestHelperClient } from './helpers/test-helper-client';
import * as path from 'path';
import * as fs from 'fs';

export const AUTH_STATE_FILE = '.auth/user.json';
const authFile = path.join(__dirname, AUTH_STATE_FILE);

// Fixed names — no timestamp suffix so screenshots are identical across runs
const PROJECT_NAME = 'visual-project';
const EMPTY_PROJECT_NAME = 'visual-empty-project';
const DATASET_NAME = 'visual-dataset';
const TEST_SUITE_NAME = 'visual-testsuite';

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
  try { await client.deleteDataset(TEST_SUITE_NAME); } catch { /* ignore */ }
  try { await client.deleteDataset(DATASET_NAME); } catch { /* ignore */ }
  try {
    await client.deleteProject(PROJECT_NAME);
    await client.waitForProjectDeleted(PROJECT_NAME, 30);
  } catch { /* ignore */ }
  try {
    await client.deleteProject(EMPTY_PROJECT_NAME);
    await client.waitForProjectDeleted(EMPTY_PROJECT_NAME, 30);
  } catch { /* ignore */ }

  console.log('Creating projects...');
  await client.createProject(PROJECT_NAME);
  await client.waitForProjectVisible(PROJECT_NAME, 15);

  await client.createProject(EMPTY_PROJECT_NAME);
  await client.waitForProjectVisible(EMPTY_PROJECT_NAME, 15);

  process.env.VISUAL_PROJECT_NAME = PROJECT_NAME;
  process.env.VISUAL_EMPTY_PROJECT_NAME = EMPTY_PROJECT_NAME;

  console.log(`Projects ready: ${PROJECT_NAME}, ${EMPTY_PROJECT_NAME}`);
}

export default globalSetup;
