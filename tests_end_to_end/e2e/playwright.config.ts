import * as path from 'node:path';
import { defineConfig, devices } from '@playwright/test';
import { loadEnvConfig } from './config/env.config';

const env = loadEnvConfig();

// Storage state minted by global-setup at .auth/user.json. For cloud/self-
// hosted deployments it carries the auth cookies + storage from the login
// round-trip; for OSS it's a minimal file holding the localStorage entries
// (e.g. opik-version-override=v2) the suite needs the FE to read before any
// API call. Never checked in.
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
  ],
});
