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

function resolveRunId(envOverride: string | undefined): string {
  // Honour an explicit OPIK_RUN_ID first (lets globalSetup propagate the same id to
  // worker processes via env). Otherwise cache a single per-process stamp so the
  // setup → tests → teardown chain inside one node process agrees on cujPrefix.
  if (envOverride) return envOverride;
  if (processRunId === null) processRunId = stampRunId();
  return processRunId;
}

let processRunId: string | null = null;

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

  const apiKey = env.OPIK_API_KEY ?? null;
  if (deployment === 'cloud' && !apiKey) {
    throw new Error('cloud deployment requires OPIK_API_KEY (teardown sweeps the workspace via authenticated REST)');
  }

  const rawBaseUrl = env.OPIK_BASE_URL ?? defaults.baseUrl;
  if (!rawBaseUrl) {
    throw new Error(`OPIK_BASE_URL is required for deployment=${deployment}`);
  }
  const trimmed = rawBaseUrl.replace(/\/+$/, '');
  const baseUrl = trimmed.endsWith('/api') ? trimmed.slice(0, -'/api'.length) : trimmed;

  const workspace = env.OPIK_WORKSPACE ?? (deployment === 'oss' ? 'default' : (userName ?? ''));
  if (deployment !== 'oss' && !workspace) {
    throw new Error(
      `${deployment} deployment requires a workspace — set OPIK_WORKSPACE or OPIK_TEST_USER_NAME (Comet-Workspace header must be present for private REST calls)`,
    );
  }

  const skipLlmJudges = boolFromEnv(env.SKIP_LLM_JUDGES, false);
  const hasAnthropicKey = !!env.ANTHROPIC_API_KEY;

  const runId = resolveRunId(env.OPIK_RUN_ID);

  return {
    deployment,
    baseUrl,
    apiBaseUrl: `${baseUrl}/api`,
    workspace,
    userEmail,
    userPassword,
    userName,
    apiKey,
    features: {
      ollie: boolFromEnv(env.OLLIE_ENABLED, defaults.ollie),
      opikConnect: boolFromEnv(env.OPIK_CONNECT_ENABLED, defaults.opikConnect),
      llmJudges: hasAnthropicKey && !skipLlmJudges,
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

