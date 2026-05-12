import * as fs from 'node:fs/promises';
import * as path from 'node:path';

const E2E_DIR = __dirname;
const RUN_ID_MARKER = path.resolve(E2E_DIR, '.e2e-run-id');

async function globalTeardown() {
  try {
    await fs.rm(RUN_ID_MARKER, { force: true });
    console.log('[global-teardown] Removed runId marker');
  } catch (e) {
    console.warn('[global-teardown] Cleanup warning:', e);
  }
}

export default globalTeardown;
