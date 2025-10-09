import { satisfies } from 'semver';
import chalk from 'chalk';
import clack from './utils/clack';
import { existsSync, readFileSync } from 'fs';
import { join } from 'path';
import { OPIK_ENV_VARS, OPIK_ENV_VAR_DESCRIPTIONS } from './lib/env-constants';
import {
  readEnvFiles,
  getEnvVarWithSource,
  type EnvFileCache,
} from './utils/environment';
import { maskApiKey } from './utils/mask';

const NODE_VERSION_RANGE = '>=18.17.0';

interface HealthCheck {
  name: string;
  status: boolean;
  message: string;
}

enum DeploymentType {
  LOCAL = 'local',
  CLOUD_OR_SELF_HOSTED = 'cloud-or-self-hosted',
  UNKNOWN = 'unknown',
}

type EnvVarCondition = DeploymentType | 'always' | 'optional';

interface EnvVarCheckConfig {
  required: boolean;
  condition: EnvVarCondition;
}

const ENV_VAR_CHECKS_CONFIG: Record<string, EnvVarCheckConfig> = {
  [OPIK_ENV_VARS.URL_OVERRIDE]: {
    required: true,
    condition: 'always',
  },
  [OPIK_ENV_VARS.API_KEY]: {
    required: true,
    condition: DeploymentType.CLOUD_OR_SELF_HOSTED,
  },
  [OPIK_ENV_VARS.WORKSPACE]: {
    required: true,
    condition: DeploymentType.CLOUD_OR_SELF_HOSTED,
  },
  [OPIK_ENV_VARS.PROJECT_NAME]: {
    required: false,
    condition: 'optional',
  },
};

function detectDeploymentType(urlOverride: string | undefined): DeploymentType {
  if (!urlOverride) {
    return DeploymentType.UNKNOWN;
  }

  const url = urlOverride.toLowerCase();
  const isLocal =
    url.includes('localhost') ||
    url.includes('127.0.0.1') ||
    url.includes('0.0.0.0');

  return isLocal ? DeploymentType.LOCAL : DeploymentType.CLOUD_OR_SELF_HOSTED;
}

function getDeploymentTypeLabel(deploymentType: DeploymentType): string {
  switch (deploymentType) {
    case DeploymentType.LOCAL:
      return 'Local deployment';
    case DeploymentType.CLOUD_OR_SELF_HOSTED:
      return 'Cloud/Self-hosted deployment';
    case DeploymentType.UNKNOWN:
      return 'Unknown deployment';
  }
}

function checkNodeVersion(): HealthCheck {
  const isValid = satisfies(process.version, NODE_VERSION_RANGE);
  return {
    name: 'Node.js version',
    status: isValid,
    message: isValid
      ? `${process.version} (meets requirement: ${NODE_VERSION_RANGE})`
      : `${process.version} (requires: ${NODE_VERSION_RANGE})`,
  };
}

function checkOpikInPackageJson(): HealthCheck {
  const packageJsonPath = join(process.cwd(), 'package.json');

  if (!existsSync(packageJsonPath)) {
    return {
      name: 'Opik SDK in package.json',
      status: false,
      message: 'package.json not found',
    };
  }

  try {
    const packageJson = JSON.parse(readFileSync(packageJsonPath, 'utf-8'));
    const dependencies = {
      ...packageJson.dependencies,
      ...packageJson.devDependencies,
    };

    const opikVersion = dependencies['opik'];
    if (opikVersion) {
      return {
        name: 'Opik SDK in package.json',
        status: true,
        message: `Installed (${opikVersion})`,
      };
    }

    return {
      name: 'Opik SDK in package.json',
      status: false,
      message: 'Not installed',
    };
  } catch {
    return {
      name: 'Opik SDK in package.json',
      status: false,
      message: 'Failed to parse package.json',
    };
  }
}

function checkOpikInNodeModules(): HealthCheck {
  const opikModulePath = join(process.cwd(), 'node_modules', 'opik');
  const exists = existsSync(opikModulePath);

  return {
    name: 'Opik SDK in node_modules',
    status: exists,
    message: exists ? 'Present' : 'Not found',
  };
}

function checkEnvironmentVariables(
  deploymentType: DeploymentType,
  envFileCache: EnvFileCache,
): HealthCheck[] {
  const checks: HealthCheck[] = [];

  for (const [varName, config] of Object.entries(ENV_VAR_CHECKS_CONFIG)) {
    // Skip checks that don't apply to current deployment type
    if (
      config.condition !== 'always' &&
      config.condition !== 'optional' &&
      config.condition !== deploymentType
    ) {
      continue;
    }

    const envVar = getEnvVarWithSource(varName, envFileCache);
    const hasValue = !!envVar.value;

    // Determine if this variable is required based on deployment type
    const isRequired =
      config.condition === 'always' || config.condition === deploymentType;

    // Build status and message
    let status: boolean;
    let message: string;

    if (hasValue) {
      status = true;
      if (varName === OPIK_ENV_VARS.URL_OVERRIDE) {
        const typeLabel = getDeploymentTypeLabel(deploymentType);
        message = `Set (${typeLabel}) [from ${envVar.source}]`;
      } else if (varName === OPIK_ENV_VARS.PROJECT_NAME) {
        message = `Set to "${envVar.value}" [from ${envVar.source}]`;
      } else if (varName === OPIK_ENV_VARS.API_KEY) {
        message = `Set to ${maskApiKey(envVar.value)} [from ${envVar.source}]`;
      } else {
        message = `Set [from ${envVar.source}]`;
      }
    } else {
      status = !isRequired;
      const description =
        OPIK_ENV_VAR_DESCRIPTIONS[
          varName as keyof typeof OPIK_ENV_VAR_DESCRIPTIONS
        ];
      if (isRequired) {
        const requiredContext =
          config.condition === DeploymentType.CLOUD_OR_SELF_HOSTED
            ? ' (required for Cloud/Self-hosted)'
            : '';
        message = `Not set - ${description}${requiredContext}`;
      } else {
        message = 'Not set (will use default)';
      }
    }

    checks.push({
      name: `Environment variable: ${varName}`,
      status,
      message,
    });
  }

  return checks;
}

function displayResults(checks: HealthCheck[]): void {
  clack.log.step('Health Check Results:\n');

  for (const check of checks) {
    if (check.status) {
      clack.log.success(`✓ ${check.name}: ${check.message}`);
    } else {
      clack.log.error(`✗ ${check.name}: ${check.message}`);
    }
  }
}

function determineOutcome(checks: HealthCheck[]): void {
  const allPassed = checks.every((check) => check.status);

  if (allPassed) {
    clack.outro(chalk.green('All checks passed!'));
  } else {
    clack.outro(
      chalk.yellow(
        'Some checks failed. Run "npx opik-ts configure" to set up Opik SDK.',
      ),
    );
    process.exit(1);
  }
}

export async function runDoctor(): Promise<void> {
  clack.intro(chalk.inverse(`Opik TS Doctor`));

  const checks: HealthCheck[] = [];

  // Read .env files once at the start
  const envFileCache = readEnvFiles();

  // System checks
  checks.push(checkNodeVersion());

  // SDK installation checks
  checks.push(checkOpikInPackageJson());
  checks.push(checkOpikInNodeModules());

  // Environment variable checks
  const urlOverrideVar = getEnvVarWithSource(
    OPIK_ENV_VARS.URL_OVERRIDE,
    envFileCache,
  );
  const deploymentType = detectDeploymentType(
    urlOverrideVar.value || undefined,
  );
  checks.push(...checkEnvironmentVariables(deploymentType, envFileCache));

  // Display and determine outcome
  displayResults(checks);
  determineOutcome(checks);
}
