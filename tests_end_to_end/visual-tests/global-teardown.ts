import { TestHelperClient } from './helpers/test-helper-client';
import * as path from 'path';
import * as fs from 'fs';

const PROJECT_NAME = 'visual-project';
const EMPTY_PROJECT_NAME = 'visual-empty-project';
const SIDEBAR_PROJECT_NAME = 'visual-sidebar-project';
const DATASET_NAME = 'visual-dataset';
const TEST_SUITE_NAME = 'visual-testsuite';
const FEEDBACK_DEF_NAMES = ['visual-config-quality', 'visual-config-sentiment'];
// 'development'/'staging'/'production' are seeded by Liquibase for every workspace
// (migration 000066_seed_default_environments.sql) — deletable, but present on any
// fresh instance, so they must be cleared too or the environments tab is never empty.
const ENVIRONMENT_NAMES = [
  'visual-config-env-staging',
  'visual-config-env-production',
  'development',
  'staging',
  'production',
];
const AI_PROVIDERS = ['openai', 'anthropic'];

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
  try { await client.deleteProject(SIDEBAR_PROJECT_NAME); } catch { /* ignore */ }

  for (const name of FEEDBACK_DEF_NAMES) {
    try {
      const definition = await client.getFeedbackDefinition(name);
      await client.deleteFeedbackDefinition(definition.id);
    } catch { /* ignore */ }
  }
  try {
    const environments = await client.findEnvironments();
    for (const env of environments) {
      if (ENVIRONMENT_NAMES.includes(env.name)) {
        await client.deleteEnvironment(env.id);
      }
    }
  } catch { /* ignore */ }
  try {
    const providerKeys = await client.findProviderApiKeys();
    for (const key of providerKeys) {
      if (AI_PROVIDERS.includes(key.provider)) {
        await client.deleteProviderApiKey(key.id);
      }
    }
  } catch { /* ignore */ }
}

export default globalTeardown;
