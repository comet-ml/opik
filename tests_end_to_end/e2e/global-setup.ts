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

  /** Sweep order: experiments → datasets → projects. Experiments reference datasets which reference projects. */
  try {
    const staleExperiments = await backend.listExperimentsWithPrefix('cuj-');
    let sweptCount = 0;
    for (const e of staleExperiments) {
      const ts = parseRunIdTimestamp(e.name);
      if (ts === null) continue;
      if (ts < cutoff) {
        try {
          await backend.deleteExperiment(e.id);
          sweptCount++;
        } catch {
          // Best-effort; another runner may have just deleted it.
        }
      }
    }
    if (sweptCount > 0) {
      console.log(`[global-setup] Swept ${sweptCount} orphaned experiments (>6h old)`);
    }
  } catch (e) {
    console.warn('[global-setup] experiment orphan sweep warning (continuing):', e);
  }

  try {
    const staleDatasets = await backend.listDatasetsWithPrefix('cuj-');
    let sweptCount = 0;
    for (const d of staleDatasets) {
      const ts = parseRunIdTimestamp(d.name);
      if (ts === null) continue;
      if (ts < cutoff) {
        try {
          await backend.deleteDataset(d.id);
          sweptCount++;
        } catch {
          // Best-effort; another runner may have just deleted it.
        }
      }
    }
    if (sweptCount > 0) {
      console.log(`[global-setup] Swept ${sweptCount} orphaned datasets (>6h old)`);
    }
  } catch (e) {
    console.warn('[global-setup] dataset orphan sweep warning (continuing):', e);
  }

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

/**
 * Mark the WelcomeWizard ("Welcome to Opik 🚀" first-run survey) as completed
 * via the REST API. On a fresh OSS deploy the wizard renders a modal overlay
 * that intercepts pointer events on every page until dismissed. Dismissing
 * it programmatically here means every test starts against a clean page;
 * doing it inside each test would couple test logic to first-run UX.
 *
 * The POST is idempotent — calling it on a workspace where the wizard is
 * already completed is a 204 no-op.
 */
async function dismissWelcomeWizard(env: EnvConfig): Promise<void> {
  try {
    const url = `${env.apiBaseUrl}/v1/private/welcome-wizard`;
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Comet-Workspace': env.workspace,
    };
    if (env.apiKey) headers['Authorization'] = env.apiKey;
    const res = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify({ role: 'engineer', integrations: [] }),
    });
    if (!res.ok && res.status !== 204) {
      console.warn(`[global-setup] welcome-wizard dismiss returned ${res.status}`);
      return;
    }
    console.log('[global-setup] welcome wizard dismissed');
  } catch (err) {
    console.warn('[global-setup] could not dismiss welcome wizard:', err);
  }
}

/**
 * The 2.0 E2E suite targets the v2 SPA. Workspaces (fresh accounts on staging,
 * the default OSS workspace) are flagged v1 by `/workspaces/versions` — the
 * FE's WorkspaceVersionGate would then mount the V1App, which is missing 2.0
 * routes like `/projects/<id>/online-evaluation` and renders "Not Found".
 *
 * The Gate honors a localStorage override key (`opik-version-override`) before
 * consulting the API. We write that key into the storage state file so it's
 * present on every page load in the suite, side-stepping the v1 fallback
 * without depending on workspace-side flag flips.
 *
 * Works for both auth-bearing deployments (extends the existing auth state)
 * and OSS deployments (creates a minimal storage-state file from scratch).
 */
async function injectV2OverrideIntoStorageState(env: EnvConfig): Promise<void> {
  try {
    const origin = new URL(env.baseUrl).origin;
    let state: {
      cookies?: unknown[];
      origins?: Array<{ origin: string; localStorage: Array<{ name: string; value: string }> }>;
    };
    try {
      const raw = await fs.readFile(AUTH_STATE_FILE, 'utf-8');
      state = JSON.parse(raw);
    } catch {
      state = { cookies: [], origins: [] };
    }
    const origins = state.origins ?? [];
    let entry = origins.find((o) => o.origin === origin);
    if (!entry) {
      entry = { origin, localStorage: [] };
      origins.push(entry);
    }
    entry.localStorage = entry.localStorage.filter((kv) => kv.name !== 'opik-version-override');
    entry.localStorage.push({ name: 'opik-version-override', value: 'v2' });
    state.origins = origins;
    await fs.writeFile(AUTH_STATE_FILE, JSON.stringify(state, null, 2), 'utf-8');
    console.log('[global-setup] injected opik-version-override=v2 into auth state');
  } catch (err) {
    console.warn('[global-setup] could not inject v2 override into storage state:', err);
  }
}

async function authenticateAndPersist(env: EnvConfig): Promise<void> {
  // OSS deployments have no auth wall — skip.
  if (env.deployment === 'oss') {
    console.log('[global-setup] OSS deployment: no auth needed');
    return;
  }

  const haveStorageState = await fileExists(AUTH_STATE_FILE);
  const haveLoginCreds = Boolean(env.userEmail && env.userPassword);

  // Power-user debug path: API key plus a pre-captured storage state on disk.
  // Trust both and skip the login round-trip.
  if (env.apiKey && haveStorageState) {
    console.log('[global-setup] using pre-set OPIK_API_KEY + existing .auth/user.json');
    return;
  }

  // Canonical CI path requires email+password to mint fresh storage state.
  if (!haveLoginCreds) {
    if (env.apiKey && !haveStorageState) {
      throw new Error(
        'global-setup: OPIK_API_KEY is set but no .auth/user.json was captured; ' +
          'the UI tests need a browser session. Either supply ' +
          'OPIK_TEST_USER_EMAIL + OPIK_TEST_USER_PASSWORD so global-setup can log in, ' +
          'or pre-capture .auth/user.json locally and commit-ignore it.',
      );
    }
    throw new Error(
      'global-setup: cloud auth requires OPIK_TEST_USER_EMAIL + OPIK_TEST_USER_PASSWORD',
    );
  }

  // Auth lives at the root Comet domain, not under /opik — strip any trailing
  // /opik path segment before hitting /api/auth/login.
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
  // Reload to pick up the minted key for the sweep + downstream calls.
  const finalEnv = loadEnvConfig();
  await injectV2OverrideIntoStorageState(finalEnv);
  await dismissWelcomeWizard(finalEnv);
  await sweepOrphans(finalEnv.apiKey);
}

export default globalSetup;
