import { TestHelperClient } from './helpers/test-helper-client';
import * as path from 'path';
import * as fs from 'fs';

const PROJECT_NAME = 'visual-project';
const EMPTY_PROJECT_NAME = 'visual-empty-project';
const DATASET_NAME = 'visual-dataset';
const TEST_SUITE_NAME = 'visual-testsuite';

const STATE_FILE = path.join(__dirname, '.test-state.json');

async function globalTeardown() {
  if (process.env.SKIP_TEARDOWN === '1') {
    console.log('Skipping teardown (SKIP_TEARDOWN=1)');
    return;
  }

  const client = new TestHelperClient();
  console.log(`Cleaning up test data: ${PROJECT_NAME}`);

  if (fs.existsSync(STATE_FILE)) {
    const state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8'));
    try { await client.deleteExperiment(state.testSuiteExperimentId); } catch { /* ignore */ }
    try { await client.deleteExperiment(state.experimentId); } catch { /* ignore */ }
    fs.unlinkSync(STATE_FILE);
  }

  try { await client.deleteDataset(TEST_SUITE_NAME); } catch { /* ignore */ }
  try { await client.deleteDataset(DATASET_NAME); } catch { /* ignore */ }
  try { await client.deleteProject(PROJECT_NAME); } catch { /* ignore */ }
  try { await client.deleteProject(EMPTY_PROJECT_NAME); } catch { /* ignore */ }
}

export default globalTeardown;
