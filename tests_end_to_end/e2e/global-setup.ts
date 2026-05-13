import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { loadEnvConfig } from './config/env.config';
import { makeBackendClient } from './core/backend';

const E2E_DIR = __dirname;
const RUN_ID_MARKER = path.resolve(E2E_DIR, '.e2e-run-id');
const ORPHAN_MAX_AGE_MS = 6 * 60 * 60 * 1000;

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

  await sweepOrphans(env.apiKey);
}

export default globalSetup;
