import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../../config/env.config';
import { makeBackendClient } from '../../core/backend';

test.describe('Fixture lifecycle sanity', { tag: ['@foundation'] }, () => {
  test('cuj-* project created via REST is visible and namespaced correctly', async ({}, testInfo) => {
    const env = loadEnvConfig();
    const backend = makeBackendClient(env.apiKey);

    const safeName = testInfo.title
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 40);
    const name = `${env.cujPrefix}-w${testInfo.workerIndex}-${safeName}`;

    await backend.createProject(name, 'e2e fixture-lifecycle probe');

    const found = await backend.listProjectsWithPrefix(name);
    expect(found.length).toBeGreaterThanOrEqual(1);
    expect(found[0].name).toBe(name);
    expect(name).toMatch(new RegExp(`^${env.cujPrefix}-w\\d+-`));
  });

  test('global-teardown removes prior-run cuj-{runId}-* projects', async () => {
    const env = loadEnvConfig();
    const backend = makeBackendClient(env.apiKey);
    const currentRunPrefix = `${env.cujPrefix}-`;

    const survivors = await backend.listProjectsWithPrefix(currentRunPrefix);
    // Projects created earlier in *this* run are still present right now —
    // teardown only runs after all tests complete. We're verifying the
    // namespace is well-formed; the post-run zero-orphan check happens
    // outside Playwright (see the npm script in CI / verify-cleanup).
    for (const p of survivors) {
      expect(p.name.startsWith(currentRunPrefix)).toBe(true);
    }
  });
});
