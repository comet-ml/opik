import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';

dotenv.config();

const AUTH_STATE_FILE = '.auth/user.json';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,

  reporter: [
    ['html', { open: 'never', outputFolder: 'visual-report' }],
    ['list'],
    ['json', { outputFile: 'test-results/results.json' }],
    ['allure-playwright', {
      outputFolder: process.env.ALLURE_RESULTS || 'allure-results',
      detail: true,
      suiteTitle: true
    }],
  ],

  timeout: 120000,

  expect: {
    timeout: 15000,
    toHaveScreenshot: {
      maxDiffPixels: 200,
      animations: 'disabled',
    },
  },

  use: {
    baseURL: process.env.OPIK_BASE_URL || 'http://localhost:5173',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'on',
    headless: true,
    viewport: { width: 1440, height: 900 },
    navigationTimeout: 30000,
    actionTimeout: 15000,
    storageState: process.env.OPIK_BASE_URL && !process.env.OPIK_BASE_URL.startsWith('http://localhost')
      ? AUTH_STATE_FILE
      : undefined,
  },

  globalSetup: require.resolve('./global-setup.ts'),
  globalTeardown: require.resolve('./global-teardown.ts'),

  webServer: {
    command: 'cd ../test-helper-service && python app.py',
    url: 'http://localhost:5555/health',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: {
      OPIK_BASE_URL: process.env.OPIK_BASE_URL || 'http://localhost:5173',
      OPIK_TEST_USER_EMAIL: process.env.OPIK_TEST_USER_EMAIL || '',
      OPIK_TEST_USER_PASSWORD: process.env.OPIK_TEST_USER_PASSWORD || '',
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

  snapshotDir: './screenshots',
  snapshotPathTemplate: '{snapshotDir}/baseline/{arg}{ext}',

  outputDir: 'test-results/',
});
