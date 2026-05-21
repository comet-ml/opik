import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { chromium } from '@playwright/test';
import { loadEnvConfig, type EnvConfig } from './config/env.config';
import { makeBackendClient } from './core/backend';

const E2E_DIR = __dirname;
const RUN_ID_MARKER = path.resolve(E2E_DIR, '.e2e-run-id');
const ORPHAN_MAX_AGE_MS = 6 * 60 * 60 * 1000;
export const AUTH_STATE_FILE = path.resolve(E2E_DIR, '.auth/user.json');

function parseRunIdTimestamp(name: string): number | null {
  const match = name.match(/^cuj-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})-(\d{3})/);
  if (!match) return null;
  const [, y, mo, d, h, mi, s, ms] = match;
  return Date.UTC(
    parseInt(y, 10),
    parseInt(mo, 10) - 1,
    parseInt(d, 10),
    parseInt(h, 10),
    parseInt(mi, 10),
    parseInt(s, 10),
    parseInt(ms, 10),
  );
}

async function sweepOrphans(apiKey: string | null): Promise<void> {
  const backend = makeBackendClient(apiKey);
  const cutoff = Date.now() - ORPHAN_MAX_AGE_MS;
  try {
    const stale = await backend.listProjectsWithPrefix('cuj-');
    let sweptCount = 0;
    for (const p of stale) {
      const ts = parseRunIdTimestamp(p.name);
      if (ts === null) continue;
      if (ts < cutoff) {
        try {
          await backend.deleteProject(p.id);
          sweptCount++;
        } catch {
          // Best-effort; another runner may have just deleted it.
        }
      }
    }
    if (sweptCount > 0) {
      console.log(`[global-setup] Swept ${sweptCount} orphaned projects (>6h old)`);
    }
  } catch (e) {
    console.warn('[global-setup] orphan sweep warning (continuing):', e);
  }
}

async function authenticateAndPersist(env: EnvConfig): Promise<void> {
  // OSS deployments have no auth wall — skip.
  if (env.deployment === 'oss') {
    console.log('[global-setup] OSS deployment: no auth needed');
    return;
  }

  // If the caller already has an API key AND a captured storage state, trust
  // them and skip the login round-trip. (Useful for debugging against a
  // captured session.)
  const haveStorageState = await fileExists(AUTH_STATE_FILE);
  if (env.apiKey && haveStorageState) {
    console.log('[global-setup] using pre-set OPIK_API_KEY + existing .auth/user.json');
    return;
  }

  if (!env.userEmail || !env.userPassword) {
    throw new Error(
      'global-setup: cloud auth requires OPIK_TEST_USER_EMAIL + OPIK_TEST_USER_PASSWORD',
    );
  }

  // Auth lives at the root Comet domain, not under /opik — strip any trailing
  // /opik path segment before hitting /api/auth/login. This mirrors the legacy
  // tests_end_to_end/typescript-tests/ pattern.
  const rootBase = env.baseUrl.replace(/\/opik$/, '');
  const loginUrl = `${rootBase}/api/auth/login`;
  console.log(`[global-setup] authenticating at ${loginUrl}`);

  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    const response = await page.request.post(loginUrl, {
      data: { email: env.userEmail, plainTextPassword: env.userPassword },
      headers: { 'Content-Type': 'application/json' },
    });
    if (!response.ok()) {
      const body = await response.text();
      throw new Error(`Login failed (${response.status()}): ${body.slice(0, 200)}`);
    }
    const json = (await response.json()) as { apiKeys?: string[] };
    const mintedKey = json.apiKeys?.[0];
    if (!mintedKey) {
      throw new Error('Login response did not include any apiKeys');
    }
    // Propagate to workers via env so backend client + bridge see it.
    process.env.OPIK_API_KEY = mintedKey;
    await context.storageState({ path: AUTH_STATE_FILE });
    console.log(`[global-setup] auth state saved to ${path.relative(E2E_DIR, AUTH_STATE_FILE)}`);
  } finally {
    await page.close();
    await context.close();
    await browser.close();
  }
}

async function fileExists(p: string): Promise<boolean> {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

async function globalSetup() {
  const env = loadEnvConfig();
  // Propagate the runId to worker processes so every loadEnvConfig() call
  // across the run agrees on cujPrefix — otherwise each worker would re-stamp
  // its own timestamp and teardown would sweep the wrong prefix.
  process.env.OPIK_RUN_ID = env.runId;

  await fs.writeFile(RUN_ID_MARKER, env.runId, 'utf-8');

  await fs.mkdir(path.resolve(E2E_DIR, env.scratchRoot), { recursive: true });
  await fs.mkdir(path.resolve(E2E_DIR, '.runners'), { recursive: true });
  await fs.mkdir(path.resolve(E2E_DIR, '.auth'), { recursive: true });

  const signals: NodeJS.Signals[] = ['SIGINT', 'SIGTERM'];
  for (const sig of signals) {
    process.on(sig, () => {
      console.log(`\n[global-setup] Received ${sig}, teardown will run via Playwright hook`);
      process.exit(130);
    });
  }

  console.log(`[global-setup] runId stamped: ${env.runId}`);

  await authenticateAndPersist(env);

  // After auth, env.apiKey may be stale (we just set process.env.OPIK_API_KEY).
  // Reload to pick up the minted key for the sweep.
  const finalEnv = loadEnvConfig();
  await sweepOrphans(finalEnv.apiKey);
}

export default globalSetup;
