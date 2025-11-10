import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';
import { AUTH_STATE_FILE } from './global-setup';

dotenv.config();

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : 1,
  grep: process.env.TEST_SUITE ? new RegExp(`@${process.env.TEST_SUITE}`) : undefined,

  reporter: [
    ['html', { open: 'never' }],
    ['list'],
    ['json', { outputFile: 'test-results/results.json' }],
    ['allure-playwright', {
      outputFolder: process.env.ALLURE_RESULTS || 'allure-results',
      detail: true,
      suiteTitle: true
    }]
  ],

  timeout: 60000,

  expect: {
    timeout: 10000
  },

  use: {
    baseURL: process.env.OPIK_BASE_URL || 'http://localhost:5173',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    headless: true,
    viewport: { width: 1280, height: 720 },
    navigationTimeout: 30000,
    actionTimeout: 10000,
    permissions: ['clipboard-read', 'clipboard-write'],
    storageState: process.env.OPIK_BASE_URL && !process.env.OPIK_BASE_URL.startsWith('http://localhost')
      ? AUTH_STATE_FILE
      : undefined,
  },

  globalSetup: require.resolve('./global-setup.ts'),

  webServer: {
    command: 'cd ../test-helper-service && python app.py',
    url: 'http://localhost:5555/health',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: {
      // Pass environment variables to Flask service
      // Flask service will authenticate itself on startup for cloud environments
      OPIK_BASE_URL: process.env.OPIK_BASE_URL || 'http://localhost:5173',
      OPIK_TEST_USER_EMAIL: process.env.OPIK_TEST_USER_EMAIL || '',
      OPIK_TEST_USER_PASSWORD: process.env.OPIK_TEST_USER_PASSWORD || '',
      // Workspace: 'default' for local, username for cloud (never 'default' on cloud)
      OPIK_WORKSPACE: process.env.OPIK_BASE_URL?.startsWith('http://localhost')
        ? 'default'
        : (process.env.OPIK_TEST_USER_NAME || 'default'),
      TEST_HELPER_PORT: process.env.TEST_HELPER_PORT || '5555',
    },
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  outputDir: 'test-results/',
});
