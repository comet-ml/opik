import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { loadEnvConfig } from './config/env.config';
import { makeBackendClient } from './core/backend';

const E2E_DIR = __dirname;
const RUN_ID_MARKER = path.resolve(E2E_DIR, '.e2e-run-id');

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
  const backend = makeBackendClient(env.apiKey);

  console.log(`[global-teardown] Sweeping entities with prefix ${prefix}`);

  try {
    const projects = await backend.listProjectsWithPrefix(prefix);
    if (projects.length === 0) {
      console.log('  no projects to sweep');
    }
    for (const p of projects) {
      try {
        await backend.deleteProject(p.id);
        console.log(`  deleted project ${p.name}`);
      } catch (e) {
        console.warn(`  project ${p.name} delete warning:`, e);
      }
    }
  } catch (e) {
    console.warn('[global-teardown] project sweep warning:', e);
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
