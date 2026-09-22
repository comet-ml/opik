import * as path from 'node:path';
import { defineConfig, devices } from '@playwright/test';
import { loadEnvConfig } from './config/env.config';

const env = loadEnvConfig();

// Storage state minted by global-setup at .auth/user.json. For cloud/self-
// hosted deployments it carries the auth cookies + storage from the login
// round-trip; for OSS it's an empty state file, written so context creation has
// something to read. Never checked in.
const storageState = path.resolve(__dirname, '.auth/user.json');

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
    ['allure-playwright', {
      outputFolder: process.env.ALLURE_RESULTS || 'allure-results',
      detail: true,
      suiteTitle: true,
    }],
  ],
  use: {
    baseURL: env.baseUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    storageState,
    // Default upper bound for every Playwright action (click/fill/waitFor/etc.)
    // when the call site doesn't pass an explicit `timeout:`. Without this,
    // unmarked `locator.waitFor()` inherits the *test* timeout, so a missing
    // FE testid can burn the full test budget on a single locator. POM calls
    // that legitimately need longer (e.g. trace panel cold-load = 30s, async
    // polling = 90-120s) pass their own `timeout:` and override this.
    actionTimeout: 15_000,
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
    {
      // Mock OAuth2 token service + bearer-validating LLM gateway for the
      // dynamic token auth specs (OPIK-7940). Hermetic: no external keys.
      // In CI the backend runs inside docker-compose, so the mock binds 0.0.0.0 and the
      // backend reaches it through the host (MOCK_AUTH_URL_FOR_BACKEND, set by the workflow).
      command: `uv run --no-project python mock_token_auth_service.py --port ${process.env.MOCK_AUTH_PORT ?? '9878'} --host ${process.env.CI ? '0.0.0.0' : '127.0.0.1'}`,
      cwd: 'services/mock-token-auth',
      url: `http://localhost:${process.env.MOCK_AUTH_PORT ?? '9878'}/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 15_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
});
