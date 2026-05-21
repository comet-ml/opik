/**
 * Global Setup for Playwright Tests
 * Handles authentication for non-local environments
 *
 * Follows the same pattern as Python tests:
 * 1. Authenticate via REST API to get API key
 * 2. API response sets cookies in browser context
 * 3. Save cookies/storage state for tests to reuse
 */

import { chromium, FullConfig } from '@playwright/test';
import { getEnvironmentConfig } from './config/env.config';
import * as path from 'path';
import * as fs from 'fs';

// Shared constant for authentication state storage
export const AUTH_STATE_FILE = '.auth/user.json';
const authFile = path.join(__dirname, AUTH_STATE_FILE);

async function globalSetup(config: FullConfig) {
  console.log('🔧 Running global setup...');

  const envConfig = getEnvironmentConfig();
  const envData = envConfig.getConfig();

  console.log(`📍 Base URL: ${envData.baseUrl}`);
  console.log(`📂 Workspace: ${envData.workspace}`);
  console.log(`🏗️  Project: ${envData.projectName}`);

  if (!envConfig.isLocal()) {
    console.log('🔐 Non-local environment detected, performing authentication...');

    // Ensure .auth directory exists
    const authDir = path.dirname(authFile);
    if (!fs.existsSync(authDir)) {
      fs.mkdirSync(authDir, { recursive: true });
    }

    const browser = await chromium.launch();
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const baseUrl = envData.baseUrl.replace(/\/opik$/, '');
      const authUrl = `${baseUrl}/api/auth/login`;

      console.log(`🔑 Authenticating at: ${authUrl}`);

      // This API call sets cookies in the browser context automatically
      const response = await page.request.post(authUrl, {
        data: {
          email: envData.testUserEmail,
          plainTextPassword: envData.testUserPassword,
        },
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok()) {
        const text = await response.text();
        throw new Error(`Login failed with status ${response.status()}: ${text}`);
      }

      const responseData = await response.json();

      if (!responseData.apiKeys || responseData.apiKeys.length === 0) {
        throw new Error('No API keys found in login response');
      }

      process.env.OPIK_API_KEY = responseData.apiKeys[0];

      console.log('✅ Authentication successful, API key obtained');

      // Save the authenticated state (including cookies) for tests to reuse
      await context.storageState({ path: authFile });
      console.log(`✅ Authentication state saved to ${authFile}`);

      // Honor OPIK_VERSION_OVERRIDE by seeding the FE's localStorage key.
      const versionOverride = process.env.OPIK_VERSION_OVERRIDE;
      if (versionOverride) {
        const state = JSON.parse(fs.readFileSync(authFile, 'utf-8'));
        const origin = new URL(envData.baseUrl).origin;
        const entry = { name: 'opik-version-override', value: versionOverride };
        const origins: Array<{ origin: string; localStorage: Array<{ name: string; value: string }> }> = state.origins ?? [];
        const existing = origins.find((o) => o.origin === origin);
        if (existing) {
          existing.localStorage = (existing.localStorage ?? []).filter((e) => e.name !== entry.name).concat(entry);
        } else {
          origins.push({ origin, localStorage: [entry] });
        }
        state.origins = origins;
        fs.writeFileSync(authFile, JSON.stringify(state, null, 2));
        console.log(`✅ OPIK_VERSION_OVERRIDE='${versionOverride}' seeded into ${authFile}`);
      }

    } catch (error) {
      console.error('❌ Authentication failed:', error);
      throw error;
    } finally {
      await page.close();
      await context.close();
      await browser.close();
    }
  } else {
    console.log('🏠 Local environment detected, skipping authentication');
  }

  // Set environment variables for tests (not for webServer which already started)
  process.env.OPIK_BASE_URL = envData.baseUrl;
  process.env.OPIK_URL_OVERRIDE = envConfig.getApiUrl();
  process.env.OPIK_TEST_WORKSPACE = envData.workspace;

  console.log('✅ Global setup complete');
}

export default globalSetup;
