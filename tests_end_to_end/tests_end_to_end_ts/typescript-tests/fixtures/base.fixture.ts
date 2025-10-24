/**
 * Base Fixtures
 * Core fixtures that provide environment config and test helper client
 */

import { test as base } from '@playwright/test';
import { getEnvironmentConfig } from '../config/env.config';
import { TestHelperClient } from '../helpers/test-helper-client';
import { generateProjectName, generateDatasetName } from '../helpers/random';

export type BaseFixtures = {
  envConfig: ReturnType<typeof getEnvironmentConfig>;
  helperClient: TestHelperClient;
  projectName: string;
  datasetName: string;
};

export const test = base.extend<BaseFixtures>({
  envConfig: async ({}, use) => {
    const config = getEnvironmentConfig();
    await use(config);
  },

  helperClient: async ({}, use) => {
    const client = new TestHelperClient();

    const isHealthy = await client.healthCheck();
    if (!isHealthy) {
      throw new Error(
        'Flask test helper service is not responding. ' +
        'Ensure the service is running at http://localhost:5555'
      );
    }

    await use(client);
  },

  projectName: async ({}, use) => {
    const name = generateProjectName();
    process.env.OPIK_PROJECT_NAME = name;
    await use(name);
  },

  datasetName: async ({}, use) => {
    const name = generateDatasetName();
    await use(name);
  },
});

export { expect } from '@playwright/test';
