import { test, expect } from '@playwright/test';
import { getEnvironmentConfig } from '../../config/env.config';

test.describe('Optimization URL redirects', () => {
  test('Legacy /compare URL redirects to new optimization detail URL @sanity @fullregression @optimizations', async ({
    page,
  }) => {
    const envConfig = getEnvironmentConfig();
    const { workspace, baseUrl } = envConfig.getConfig();
    const fakeDatasetId = 'fake-dataset-id';
    const fakeOptimizationId = '019c9a62-c3da-718d-b60a-f29b5cc75e21';

    const legacyUrl =
      `${baseUrl}/${workspace}/optimizations/${fakeDatasetId}/compare` +
      `?optimizations=${encodeURIComponent(JSON.stringify([fakeOptimizationId]))}`;

    await page.goto(legacyUrl);

    await expect(page).toHaveURL(
      new RegExp(`/${workspace}/optimizations/${fakeOptimizationId}`),
      { timeout: 15000 },
    );

    expect(page.url()).not.toContain('/compare');
  });

  test('Legacy /compare URL with no optimization ID redirects to optimizations list @fullregression @optimizations', async ({
    page,
  }) => {
    const envConfig = getEnvironmentConfig();
    const { workspace, baseUrl } = envConfig.getConfig();
    const fakeDatasetId = 'fake-dataset-id';

    const legacyUrl =
      `${baseUrl}/${workspace}/optimizations/${fakeDatasetId}/compare`;

    await page.goto(legacyUrl);

    await expect(page).toHaveURL(
      new RegExp(`^.*/${workspace}/optimizations/?$`),
      { timeout: 15000 },
    );
  });
});
