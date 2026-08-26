import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { chromium } from '@playwright/test';
import { loadEnvConfig, type EnvConfig } from './config/env.config';
import { makeBackendClient } from './core/backend';
import { loginCometUserRaw } from './core/comet/client';

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

/** One entity type's list+delete-one pair, shared by the sweep loop below. */
async function sweepStaleWithPrefix(
  label: string,
  cutoff: number,
  list: () => Promise<Array<{ id: string; name: string }>>,
  deleteOne: (id: string) => Promise<void>,
): Promise<void> {
  try {
    const stale = await list();
    let sweptCount = 0;
    for (const item of stale) {
      const ts = parseRunIdTimestamp(item.name);
      if (ts === null || ts >= cutoff) continue;
      try {
        await deleteOne(item.id);
        sweptCount++;
      } catch {
        // Best-effort; another runner may have just deleted it.
      }
    }
    if (sweptCount > 0) {
      console.log(`[global-setup] Swept ${sweptCount} orphaned ${label} (>6h old)`);
    }
  } catch (e) {
    console.warn(`[global-setup] ${label} orphan sweep warning (continuing):`, e);
  }
}

/**
 * Sweep order: experiments/dashboards/queues/prompts/rules/alerts/
 * optimizations → datasets → projects, since the former all reference a
 * dataset or project by id. Traces aren't swept here — they're project-scoped
 * and expected to cascade with their project's own deletion (unlike
 * automation rule evaluators, which `automationRulesCleanup`'s doc comment
 * documents as the one exception to that).
 */
async function sweepOrphans(apiKey: string | null, workspaceName: string | null = null): Promise<void> {
  const backend = makeBackendClient(apiKey, workspaceName);
  const cutoff = Date.now() - ORPHAN_MAX_AGE_MS;
  const sweep = (label: string, list: () => Promise<Array<{ id: string; name: string }>>, deleteOne: (id: string) => Promise<void>) =>
    sweepStaleWithPrefix(label, cutoff, list, deleteOne);

  await sweep('experiments', () => backend.listExperimentsWithPrefix('cuj-'), (id) => backend.deleteExperiment(id));
  await sweep('dashboards', () => backend.listDashboardsWithPrefix('cuj-'), (id) => backend.deleteDashboardsBatch([id]));
  await sweep('annotation queues', () => backend.listAnnotationQueuesWithPrefix('cuj-'), (id) => backend.deleteAnnotationQueuesBatch([id]));
  await sweep('prompts', () => backend.listPromptsWithPrefix('cuj-'), (id) => backend.deletePromptsBatch([id]));
  await sweep('automation rule evaluators', () => backend.listAutomationRuleEvaluatorsWithPrefix('cuj-'), (id) => backend.deleteAutomationRuleEvaluatorsBatch([id]));
  await sweep('alerts', () => backend.listAlertsWithPrefix('cuj-'), (id) => backend.deleteAlertsBatch([id]));
  await sweep('optimizations', () => backend.listOptimizationsWithPrefix('cuj-'), (id) => backend.deleteOptimizationsBatch([id]));
  await sweep('datasets', () => backend.listDatasetsWithPrefix('cuj-'), (id) => backend.deleteDataset(id));
  await sweep('projects', () => backend.listProjectsWithPrefix('cuj-'), (id) => backend.deleteProject(id));
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

async function authenticateAndPersist(env: EnvConfig): Promise<void> {
  // OSS deployments have no auth wall, but playwright.config.ts passes
  // storageState unconditionally, so the file has to exist. Write an empty one
  // here rather than in globalSetup: a stub present before the branch below
  // would make haveStorageState true and send an apiKey-configured cloud run
  // down the "trust existing state" path with an empty session.
  if (env.deployment === 'oss') {
    console.log('[global-setup] OSS deployment: no auth needed');
    await fs.writeFile(
      AUTH_STATE_FILE,
      JSON.stringify({ cookies: [], origins: [] }, null, 2),
      'utf-8',
    );
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

    // OPIK_TEST_USER_EMAIL/PASSWORD and OPIK_WORKSPACE are two independently
    // supplied config values with no structural link between them — nothing
    // stops someone from pointing them at accounts that don't share a
    // workspace. Left unchecked, that mismatch doesn't fail here: it shows up
    // later as unrelated-looking 401s (welcome-wizard dismiss, orphan sweep)
    // that are easy to miss, and then as confusing failures deep inside
    // whichever test first navigates into env.workspace. Failing fast here
    // with the actual cause is much cheaper to debug.
    const workspacesRes = await context.request.get(`${rootBase}/api/workspaces`);
    if (workspacesRes.ok()) {
      const workspaces = (await workspacesRes.json()) as Array<{ workspaceName: string }>;
      const isMember = workspaces.some((w) => w.workspaceName === env.workspace);
      if (!isMember) {
        throw new Error(
          `global-setup: OPIK_TEST_USER_EMAIL ("${env.userEmail}") is not a member of ` +
            `OPIK_WORKSPACE ("${env.workspace}") — it can only see: ${workspaces.map((w) => w.workspaceName).join(', ')}. ` +
            'OPIK_TEST_USER_EMAIL/PASSWORD and OPIK_WORKSPACE must refer to the same workspace.',
        );
      }
    }

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
  await dismissWelcomeWizard(finalEnv);
  await sweepOrphans(finalEnv.apiKey);

  // The workspace-role permission suite seeds its resources in a second,
  // separate org/workspace under its own admin credentials — sweepOrphans
  // above never sees them. Mirrors hasWorkspaceRoleTestCredentials in
  // fixtures/workspace-role-member.fixture.ts; kept independent here so
  // global-setup doesn't have to import a Playwright fixtures module.
  if (finalEnv.adminEmail && finalEnv.adminPassword && finalEnv.deleteUserApiKey && finalEnv.deleteUserBaseUrl && finalEnv.adminWorkspace) {
    try {
      const adminApiKey = await loginCometUserRaw(finalEnv.adminEmail, finalEnv.adminPassword);
      await sweepOrphans(adminApiKey, finalEnv.adminWorkspace);
    } catch (e) {
      console.warn('[global-setup] admin-workspace orphan sweep warning (continuing):', e);
    }
  }
}

export default globalSetup;
