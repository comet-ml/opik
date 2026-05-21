import * as fs from 'node:fs';
import * as path from 'node:path';
import { defineConfig, devices } from '@playwright/test';
import { loadEnvConfig } from './config/env.config';

const env = loadEnvConfig();

const storageStatePath = process.env.OPIK_STORAGE_STATE
  ? path.resolve(__dirname, process.env.OPIK_STORAGE_STATE)
  : path.resolve(__dirname, '.auth', `${env.deployment}.json`);
const storageState = fs.existsSync(storageStatePath) ? storageStatePath : undefined;

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
