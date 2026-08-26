import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { loadEnvConfig, type EnvConfig } from './config/env.config';
import { makeBackendClient } from './core/backend';
import { loginCometUserRaw } from './core/comet/client';

const E2E_DIR = __dirname;
const RUN_ID_MARKER = path.resolve(E2E_DIR, '.e2e-run-id');

/** Mirrors hasWorkspaceRoleTestCredentials in fixtures/workspace-role-member.fixture.ts — duplicated rather than imported so this script doesn't pull in a Playwright fixtures module. */
function hasWorkspaceRoleTestCredentials(env: EnvConfig): boolean {
  return Boolean(env.adminEmail && env.adminPassword && env.deleteUserApiKey && env.deleteUserBaseUrl && env.adminWorkspace);
}

/** One entity type's list+delete-one pair, shared by the sweep loop below. */
async function sweepWithPrefix(
  label: string,
  prefix: string,
  list: (prefix: string) => Promise<Array<{ id: string; name: string }>>,
  deleteOne: (id: string) => Promise<void>,
): Promise<void> {
  try {
    const found = await list(prefix);
    if (found.length === 0) {
      console.log(`  no ${label} to sweep`);
    }
    for (const item of found) {
      try {
        await deleteOne(item.id);
        console.log(`  deleted ${label.replace(/s$/, '')} ${item.name}`);
      } catch (err) {
        console.warn(`  ${label} ${item.name} delete warning:`, err);
      }
    }
  } catch (err) {
    console.warn(`[global-teardown] ${label} sweep warning:`, err);
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
async function sweepWorkspace(apiKey: string | null, workspaceName: string | null, prefix: string): Promise<void> {
  const backend = makeBackendClient(apiKey, workspaceName);

  await sweepWithPrefix('experiments', prefix, (p) => backend.listExperimentsWithPrefix(p), (id) => backend.deleteExperiment(id));
  await sweepWithPrefix('dashboards', prefix, (p) => backend.listDashboardsWithPrefix(p), (id) => backend.deleteDashboardsBatch([id]));
  await sweepWithPrefix('annotation queues', prefix, (p) => backend.listAnnotationQueuesWithPrefix(p), (id) => backend.deleteAnnotationQueuesBatch([id]));
  await sweepWithPrefix('prompts', prefix, (p) => backend.listPromptsWithPrefix(p), (id) => backend.deletePromptsBatch([id]));
  await sweepWithPrefix('automation rule evaluators', prefix, (p) => backend.listAutomationRuleEvaluatorsWithPrefix(p), (id) => backend.deleteAutomationRuleEvaluatorsBatch([id]));
  await sweepWithPrefix('alerts', prefix, (p) => backend.listAlertsWithPrefix(p), (id) => backend.deleteAlertsBatch([id]));
  await sweepWithPrefix('optimizations', prefix, (p) => backend.listOptimizationsWithPrefix(p), (id) => backend.deleteOptimizationsBatch([id]));
  await sweepWithPrefix('datasets', prefix, (p) => backend.listDatasetsWithPrefix(p), (id) => backend.deleteDataset(id));
  await sweepWithPrefix('projects', prefix, (p) => backend.listProjectsWithPrefix(p), (id) => backend.deleteProject(id));
}

async function globalTeardown() {
  const env = loadEnvConfig();
  let runId = env.runId;
  try {
    runId = (await fs.readFile(RUN_ID_MARKER, 'utf-8')).trim();
  } catch {
    // Marker missing (early crash before setup, or already cleaned). Fall back
    // to the env-derived runId — sweep will then be a no-op rather than wrong.
  }

  const prefix = `cuj-${runId}-`;

  console.log(`[global-teardown] Sweeping entities with prefix ${prefix}`);
  await sweepWorkspace(env.apiKey, null, prefix);

  // The workspace-role permission suite seeds its resources in a second,
  // separate org/workspace under its own admin credentials — the sweep above
  // never sees them. Mirrors hasWorkspaceRoleTestCredentials in
  // fixtures/workspace-role-member.fixture.ts; kept independent here so
  // global-teardown doesn't have to import a Playwright fixtures module.
  if (hasWorkspaceRoleTestCredentials(env)) {
    try {
      console.log(`[global-teardown] Sweeping admin workspace "${env.adminWorkspace}" with prefix ${prefix}`);
      const adminApiKey = await loginCometUserRaw(env.adminEmail!, env.adminPassword!);
      await sweepWorkspace(adminApiKey, env.adminWorkspace, prefix);
    } catch (err) {
      console.warn('[global-teardown] admin-workspace sweep warning (continuing):', err);
    }
  }

  if (!env.leaveFailures) {
    try {
      await fs.rm(path.resolve(E2E_DIR, env.scratchRoot, runId), { recursive: true, force: true });
    } catch {
      // Best-effort
    }
  }

  try {
    await fs.rm(RUN_ID_MARKER, { force: true });
  } catch {
    // Best-effort
  }
}

export default globalTeardown;
