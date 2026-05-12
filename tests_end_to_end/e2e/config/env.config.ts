export type Deployment = 'cloud' | 'oss' | 'self-hosted';

export interface EnvConfig {
  deployment: Deployment;
  baseUrl: string;
  apiBaseUrl: string;
  workspace: string;

  userEmail: string | null;
  userPassword: string | null;
  userName: string | null;
  apiKey: string | null;

  features: {
    ollie: boolean;
    opikConnect: boolean;
    llmJudges: boolean;
    testSuites: boolean;
    agentConfig: boolean;
  };

  runId: string;
  cujPrefix: string;

  leaveFailures: boolean;
  skipLlmJudges: boolean;

  scratchRoot: string;
  artifactsRoot: string;

  productionReadOnly: null;
}

function stampRunId(): string {
  const now = new Date();
  const pad = (n: number, w: number) => String(n).padStart(w, '0');
  return `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1, 2)}${pad(now.getUTCDate(), 2)}-${pad(now.getUTCHours(), 2)}${pad(now.getUTCMinutes(), 2)}${pad(now.getUTCSeconds(), 2)}-${pad(now.getUTCMilliseconds(), 3)}`;
}

function boolFromEnv(v: string | undefined, fallback: boolean): boolean {
  if (v === undefined) return fallback;
  return v.toLowerCase() === 'true' || v === '1';
}

type DeploymentDefaults = {
  baseUrl: string;
  workspace: string;
  ollie: boolean;
  opikConnect: boolean;
};

const DEPLOYMENT_DEFAULTS: Record<Deployment, DeploymentDefaults> = {
  cloud: { baseUrl: '', workspace: '', ollie: true, opikConnect: true },
  oss: { baseUrl: 'http://localhost:5173', workspace: 'default', ollie: false, opikConnect: false },
  'self-hosted': { baseUrl: '', workspace: '', ollie: false, opikConnect: true },
};

export function loadEnvConfig(env: NodeJS.ProcessEnv = process.env): EnvConfig {
  const deployment = (env.OPIK_DEPLOYMENT ?? 'oss') as Deployment;
  if (!['cloud', 'oss', 'self-hosted'].includes(deployment)) {
    throw new Error(`Invalid OPIK_DEPLOYMENT: ${deployment}`);
  }

  const defaults = DEPLOYMENT_DEFAULTS[deployment];

  const userEmail = env.OPIK_TEST_USER_EMAIL ?? null;
  const userPassword = env.OPIK_TEST_USER_PASSWORD ?? null;
  const userName = env.OPIK_TEST_USER_NAME ?? null;

  if (deployment === 'cloud' && (!userEmail || !userPassword)) {
    throw new Error('cloud deployment requires OPIK_TEST_USER_EMAIL and OPIK_TEST_USER_PASSWORD');
  }

  const baseUrl = env.OPIK_BASE_URL ?? defaults.baseUrl;
  if (!baseUrl) {
    throw new Error(`OPIK_BASE_URL is required for deployment=${deployment}`);
  }

  const workspace = env.OPIK_WORKSPACE ?? (deployment === 'oss' ? 'default' : (userName ?? ''));

  const skipLlmJudges = boolFromEnv(env.SKIP_LLM_JUDGES, false);
  const hasAnthropicKey = !!env.ANTHROPIC_API_KEY;

  const runId = stampRunId();

  return {
    deployment,
    baseUrl,
    apiBaseUrl: `${baseUrl}/api`,
    workspace,
    userEmail,
    userPassword,
    userName,
    apiKey: env.OPIK_API_KEY ?? null,
    features: {
      ollie: boolFromEnv(env.OLLIE_ENABLED, defaults.ollie),
      opikConnect: boolFromEnv(env.OPIK_CONNECT_ENABLED, defaults.opikConnect),
      llmJudges: hasAnthropicKey && !skipLlmJudges,
      testSuites: true,
      agentConfig: true,
    },
    runId,
    cujPrefix: `cuj-${runId}`,
    leaveFailures: boolFromEnv(env.OPIK_LEAVE_FAILURES, false),
    skipLlmJudges,
    scratchRoot: env.OPIK_SCRATCH_ROOT ?? './.test-scratch',
    artifactsRoot: env.OPIK_ARTIFACTS_ROOT ?? './test-results',
    productionReadOnly: null,
  };
}

export function printEnvBanner(env: EnvConfig): void {
  const lines = [
    '═══════════════════════════════════════════════════════════',
    `  Opik 2.0 E2E — ${env.deployment.toUpperCase()}`,
    `  Base URL:      ${env.baseUrl}`,
    `  Workspace:     ${env.workspace}`,
    `  Run ID:        ${env.runId}`,
    `  Features:      ollie=${env.features.ollie}  opikConnect=${env.features.opikConnect}  llmJudges=${env.features.llmJudges}`,
    `  Leave failures: ${env.leaveFailures}`,
    '═══════════════════════════════════════════════════════════',
  ];
  console.log(lines.join('\n'));
}
