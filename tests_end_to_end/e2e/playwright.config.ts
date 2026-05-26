import * as path from 'node:path';
import { defineConfig, devices } from '@playwright/test';
import { loadEnvConfig } from './config/env.config';

const env = loadEnvConfig();

// OSS deployments have no auth wall; cloud/self-hosted use the storage state
// minted by global-setup at .auth/user.json (created on every run from
// OPIK_TEST_USER_EMAIL + OPIK_TEST_USER_PASSWORD, never checked in).
const storageState =
  env.deployment === 'oss' ? undefined : path.resolve(__dirname, '.auth/user.json');

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: parseInt(process.env.WORKERS ?? (process.env.CI ? '2' : '4'), 10),
  globalSetup: require.resolve('./global-setup'),
  globalTeardown: require.resolve('./global-teardown'),
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['line'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  use: {
    baseURL: env.baseUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ...(storageState ? { storageState } : {}),
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: [
    {
      command: 'uv run uvicorn opik_sdk_driver.main:app --port 5175',
      cwd: 'services/opik-sdk-driver',
      url: 'http://localhost:5175/health',
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
      stdout: 'pipe',
      stderr: 'pipe',
      env: {
        ...(process.env as Record<string, string>),
        OPIK_URL_OVERRIDE: env.apiBaseUrl,
        OPIK_WORKSPACE: env.workspace,
      },
    },
  ],
});
