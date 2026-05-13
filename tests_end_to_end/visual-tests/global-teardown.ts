import { TestHelperClient } from '../typescript-tests/helpers/test-helper-client';
import * as path from 'path';
import * as fs from 'fs';

const STATE_FILE = path.join(__dirname, '.test-state.json');

async function globalTeardown() {
  if (process.env.SKIP_TEARDOWN === '1') {
    console.log('Skipping teardown (SKIP_TEARDOWN=1)');
    return;
  }

  if (!fs.existsSync(STATE_FILE)) {
    console.log('No test state file found, nothing to clean up');
    return;
  }

  const state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8'));
  const client = new TestHelperClient();

  console.log(`Cleaning up test data: ${state.projectName}`);
  try { await client.deleteExperiment(state.testSuiteExperimentId); } catch { /* ignore */ }
  try { await client.deleteExperiment(state.experimentId); } catch { /* ignore */ }
  try { await client.deleteDataset(state.testSuiteName); } catch { /* ignore */ }
  try { await client.deleteDataset(state.datasetName); } catch { /* ignore */ }
  try { await client.deleteProject(state.projectName); } catch { /* ignore */ }

  fs.unlinkSync(STATE_FILE);
}

export default globalTeardown;
