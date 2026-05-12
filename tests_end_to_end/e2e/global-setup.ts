import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { loadEnvConfig, printEnvBanner } from './config/env.config';

const E2E_DIR = __dirname;
const RUN_ID_MARKER = path.resolve(E2E_DIR, '.e2e-run-id');

async function globalSetup() {
  const env = loadEnvConfig();
  printEnvBanner(env);

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
}

export default globalSetup;
