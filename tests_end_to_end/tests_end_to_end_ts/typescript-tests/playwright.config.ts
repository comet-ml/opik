import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';

dotenv.config();

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : 1,

  reporter: [
    ['html', { open: 'never' }],
    ['list'],
    ['json', { outputFile: 'test-results/results.json' }]
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
  },

  globalSetup: require.resolve('./global-setup.ts'),

  webServer: {
    command: 'cd ../test-helper-service && python app.py',
    url: 'http://localhost:5555/health',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
    stdout: 'pipe',
    stderr: 'pipe',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  outputDir: 'test-results/',
});
