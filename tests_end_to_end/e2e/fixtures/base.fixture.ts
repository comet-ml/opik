import { test as baseTest, expect } from '@playwright/test';
import type { EnvConfig } from '../config/env.config';
import { loadEnvConfig } from '../config/env.config';
import { makeSdkClient, type SdkClient } from '../core/sdk';
import { makeBackendClient, type BackendClient } from '../core/backend';

export interface WorkerFixtures {
  envConfig: EnvConfig;
  sdkClient: SdkClient;
  backendClient: BackendClient;
}

export interface TestFixtures {
  testNamespace: string;
}

export const test = baseTest.extend<TestFixtures, WorkerFixtures>({
  envConfig: [
    async ({}, use) => {
      await use(loadEnvConfig());
    },
    { scope: 'worker' },
  ],

  sdkClient: [
    async ({ envConfig }, use) => {
      await use(makeSdkClient(envConfig));
    },
    { scope: 'worker' },
  ],

  backendClient: [
    async ({ envConfig }, use) => {
      await use(makeBackendClient(envConfig.apiKey));
    },
    { scope: 'worker' },
  ],

  testNamespace: async ({ envConfig }, use, testInfo) => {
    const slug = testInfo.title
      .toLowerCase()
      .replace(/[^a-z0-9-]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 40);
    const namespace = `${envConfig.cujPrefix}-w${testInfo.workerIndex}-${slug}`;
    await use(namespace);
  },
});

export { expect };
