import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';

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
    // Load saved auth state for non-local environments (cookies from API login)
    storageState: process.env.OPIK_BASE_URL && !process.env.OPIK_BASE_URL.startsWith('http://localhost')
      ? '.auth/user.json'
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
      OPIK_URL_OVERRIDE: process.env.OPIK_URL_OVERRIDE || 'http://localhost:5173/api',
      OPIK_WORKSPACE: process.env.OPIK_WORKSPACE || 'default',
      OPIK_API_KEY: process.env.OPIK_API_KEY || '',
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
