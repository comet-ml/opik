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

  /** Sweep order: experiments → datasets → projects. Experiments reference datasets which reference projects. */
  try {
    const experiments = await backend.listExperimentsWithPrefix(prefix);
    if (experiments.length === 0) {
      console.log('  no experiments to sweep');
    }
    for (const e of experiments) {
      try {
        await backend.deleteExperiment(e.id);
        console.log(`  deleted experiment ${e.name}`);
      } catch (err) {
        console.warn(`  experiment ${e.name} delete warning:`, err);
      }
    }
  } catch (err) {
    console.warn('[global-teardown] experiment sweep warning:', err);
  }

  try {
    const datasets = await backend.listDatasetsWithPrefix(prefix);
    if (datasets.length === 0) {
      console.log('  no datasets to sweep');
    }
    for (const d of datasets) {
      try {
        await backend.deleteDataset(d.id);
        console.log(`  deleted dataset ${d.name}`);
      } catch (e) {
        console.warn(`  dataset ${d.name} delete warning:`, e);
      }
    }
  } catch (e) {
    console.warn('[global-teardown] dataset sweep warning:', e);
  }

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
