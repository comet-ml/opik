import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../../config/env.config';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';

const E2E_DIR = path.resolve(__dirname, '../..');

test.describe('Foundation sanity', { tag: ['@foundation'] }, () => {
  test('env config loads and runId is stamped', async () => {
    const env = loadEnvConfig();
    expect(env.runId).toMatch(/^\d{8}-\d{6}-\d{3}$/);
  });

  test('runId marker file exists', async () => {
    const contents = await fs.readFile(path.join(E2E_DIR, '.e2e-run-id'), 'utf-8');
    expect(contents).toMatch(/^\d{8}-\d{6}-\d{3}$/);
  });

  test('scratch and runners directories exist', async () => {
    const env = loadEnvConfig();
    await expect(fs.stat(path.resolve(E2E_DIR, env.scratchRoot))).resolves.toBeDefined();
    await expect(fs.stat(path.join(E2E_DIR, '.runners'))).resolves.toBeDefined();
  });
});
