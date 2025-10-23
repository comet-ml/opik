/**
 * Global Setup for Playwright Tests
 * Handles authentication for non-local environments
 */

import { chromium, FullConfig } from '@playwright/test';
import { getEnvironmentConfig } from './config/env.config';

async function globalSetup(config: FullConfig) {
  console.log('🔧 Running global setup...');

  const envConfig = getEnvironmentConfig();
  const envData = envConfig.getConfig();

  console.log(`📍 Base URL: ${envData.baseUrl}`);
  console.log(`📂 Workspace: ${envData.workspace}`);
  console.log(`🏗️  Project: ${envData.projectName}`);

  if (!envConfig.isLocal()) {
    console.log('🔐 Non-local environment detected, performing authentication...');

    const browser = await chromium.launch();
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const baseUrl = envData.baseUrl.replace(/\/opik$/, '');
      const authUrl = `${baseUrl}/api/auth/login`;

      console.log(`🔑 Authenticating at: ${authUrl}`);

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

  process.env.OPIK_BASE_URL = envData.baseUrl;
  process.env.OPIK_URL_OVERRIDE = envConfig.getApiUrl();
  process.env.OPIK_WORKSPACE = envData.workspace;

  console.log('✅ Global setup complete');
}

export default globalSetup;
