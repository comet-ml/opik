import * as childProcess from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import { basename, isAbsolute, join, relative } from 'node:path';
import chalk from 'chalk';
import { debug } from './debug';
import { type PackageDotJson, hasPackageInstalled } from './package-json';
import {
  type PackageManager,
  detectAllPackageManagers,
  packageManagers,
} from './package-manager';
import { fulfillsVersionRange } from './semver';
import type { WizardOptions } from './types';
import { ISSUES_URL, type Integration } from '../lib/constants';
import { OPIK_ENV_VARS } from '../lib/env-constants';
import clack from './clack';
import { getCloudUrl } from './urls';
import { INTEGRATION_CONFIG } from '../lib/config';
import {
  getDefaultWorkspace,
  isOpikAccessible,
  DEFAULT_LOCAL_URL,
  normalizeOpikUrl,
  MAX_URL_VALIDATION_RETRIES,
} from './api-helpers';
import { analytics } from './analytics';
import { maskApiKey } from './mask';

// interface ProjectData {
//   projectApiKey: string;
//   host: string;
//   wizardHash: string;
//   distinctId: string;
// }

export enum DeploymentType {
  CLOUD = 'cloud',
  SELF_HOSTED = 'self-hosted',
  LOCAL = 'local',
}

export interface CliSetupConfig {
  filename: string;
  name: string;
  gitignore: boolean;

  likelyAlreadyHasAuthToken(contents: string): boolean;
  tokenContent(authToken: string): string;

  likelyAlreadyHasOrgAndProject(contents: string): boolean;
  orgAndProjContent(org: string, project: string): string;

  likelyAlreadyHasUrl?(contents: string): boolean;
  urlContent?(url: string): string;
}

export interface CliSetupConfigContent {
  authToken: string;
  org?: string;
  project?: string;
  url?: string;
}

export async function abort(message?: string, status?: number): Promise<never> {
  await analytics.shutdown('cancelled');
  clack.outro(message ?? 'Setup cancelled.');
  return process.exit(status ?? 1);
}

export async function abortIfCancelled<T>(
  input: T | Promise<T>,
  integration?: Integration,
): Promise<Exclude<T, symbol>> {
  const resolvedInput = await input;

  if (
    clack.isCancel(resolvedInput) ||
    (typeof resolvedInput === 'symbol' &&
      resolvedInput.description === 'clack:cancel')
  ) {
    const docsUrl = integration
      ? INTEGRATION_CONFIG[integration].docsUrl
      : 'https://www.comet.com/docs/opik/reference/typescript-sdk/overview';

    await analytics.shutdown('cancelled');

    clack.cancel(
      `Setup cancelled. You can read the documentation for ${
        integration ?? 'Opik'
      } at ${chalk.cyan(docsUrl)} to continue with the setup manually.`,
    );
    process.exit(0);
  } else {
    return input as Exclude<T, symbol>;
  }
}

export function printWelcome(options: {
  wizardName: string;
  message?: string;
}): void {
  console.log('');
  clack.intro(chalk.inverse(` ${options.wizardName} `));

  const welcomeText =
    options.message ||
    `The ${options.wizardName} will help you set up Opik for your application.\nThank you for using Opik :)`;

  clack.note(welcomeText);
}

export async function confirmContinueIfNoOrDirtyGitRepo(
  options: Pick<WizardOptions, 'default'>,
): Promise<void> {
  if (!isInGitRepo()) {
    const continueWithoutGit = options.default
      ? true
      : await abortIfCancelled(
          clack.confirm({
            message:
              'You are not inside a git repository. The CLI will create and update files. Do you want to continue anyway?',
          }),
        );

    if (!continueWithoutGit) {
      await abort(undefined, 0);
    }
    // return early to avoid checking for uncommitted files
    return;
  }

  const uncommittedOrUntrackedFiles = getUncommittedOrUntrackedFiles();
  if (uncommittedOrUntrackedFiles.length) {
    clack.log.warn(
      `You have uncommitted or untracked files in your repo:

${uncommittedOrUntrackedFiles.join('\n')}

The CLI will create and update files.`,
    );
    const continueWithDirtyRepo = await abortIfCancelled(
      clack.confirm({
        message: 'Do you want to continue anyway?',
      }),
    );

    if (!continueWithDirtyRepo) {
      await abort(undefined, 0);
    }
  }
}

export function isInGitRepo() {
  try {
    childProcess.execSync('git rev-parse --is-inside-work-tree', {
      stdio: 'ignore',
    });
    return true;
  } catch {
    return false;
  }
}

export function getUncommittedOrUntrackedFiles(): string[] {
  try {
    const gitStatus = childProcess
      .execSync('git status --porcelain=v1', {
        // we only care about stdout
        stdio: ['ignore', 'pipe', 'ignore'],
      })
      .toString();

    const files = gitStatus
      .split(os.EOL)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((f) => `- ${f.split(/\s+/)[1]}`);

    return files;
  } catch {
    return [];
  }
}

export async function askForItemSelection(
  items: string[],
  message: string,
): Promise<{ value: string; index: number }> {
  const selection: { value: string; index: number } = await abortIfCancelled(
    clack.select({
      maxItems: 12,
      message: message,
      options: items.map((item, index) => {
        return {
          value: { value: item, index: index },
          label: item,
        };
      }),
    }),
  );

  return selection;
}

export async function confirmContinueIfPackageVersionNotSupported({
  packageId,
  packageName,
  packageVersion,
  acceptableVersions,
  note,
}: {
  packageId: string;
  packageName: string;
  packageVersion: string;
  acceptableVersions: string;
  note?: string;
}): Promise<void> {
  const isSupportedVersion = fulfillsVersionRange({
    acceptableVersions,
    version: packageVersion,
    canBeLatest: true,
  });

  if (isSupportedVersion) {
    return;
  }

  clack.log.warn(
    `You have an unsupported version of ${packageName} installed:

  ${packageId}@${packageVersion}`,
  );

  clack.note(
    note ??
      `Please upgrade to ${acceptableVersions} if you wish to use the Opik configure CLI.`,
  );
  const continueWithUnsupportedVersion = await abortIfCancelled(
    clack.confirm({
      message: 'Do you want to continue anyway?',
    }),
  );

  if (!continueWithUnsupportedVersion) {
    await abort(undefined, 0);
  }
}

/**
 * Installs or updates a package with the user's package manager.
 *
 * IMPORTANT: This function modifies the `package.json`! Be sure to re-read
 * it if you make additional modifications to it after calling this function!
 */
export async function installPackage({
  packageName,
  alreadyInstalled,
  askBeforeUpdating = true,
  packageNameDisplayLabel,
  packageManager,
  forceInstall = false,
  installDir,
}: {
  /** The string that is passed to the package manager CLI as identifier to install (e.g. `posthog-js`, or `posthog-js@^1.100.0`) */
  packageName: string;
  alreadyInstalled: boolean;
  askBeforeUpdating?: boolean;
  /** Overrides what is shown in the installation logs in place of the `packageName` option. Useful if the `packageName` is ugly */
  packageNameDisplayLabel?: string;
  packageManager?: PackageManager;
  /** Add force install flag to command to skip install precondition fails */
  forceInstall?: boolean;
  /** The directory to install the package in */
  installDir: string;
}): Promise<{ packageManager?: PackageManager }> {
  if (alreadyInstalled && askBeforeUpdating) {
    const shouldUpdatePackage = await abortIfCancelled(
      clack.confirm({
        message: `The ${chalk.bold.cyan(
          packageNameDisplayLabel ?? packageName,
        )} package is already installed. Do you want to update it to the latest version?`,
      }),
    );

    if (!shouldUpdatePackage) {
      return {};
    }
  }

  const sdkInstallSpinner = clack.spinner();

  const pkgManager =
    packageManager || (await getPackageManager({ installDir }));

  // Track package manager for analytics
  analytics.setTag('packageManager', pkgManager.label);

  const legacyPeerDepsFlag =
    pkgManager.name === 'npm' ? '--legacy-peer-deps' : '';

  sdkInstallSpinner.start(
    `${alreadyInstalled ? 'Updating' : 'Installing'} ${chalk.bold.cyan(
      packageNameDisplayLabel ?? packageName,
    )} with ${chalk.bold(pkgManager.label)}.`,
  );

  try {
    await new Promise<void>((resolve, reject) => {
      childProcess.exec(
        `${pkgManager.installCommand} ${packageName} ${pkgManager.flags} ${
          forceInstall ? pkgManager.forceInstallFlag : ''
        } ${legacyPeerDepsFlag}`.trim(),
        { cwd: installDir },
        (err, stdout, stderr) => {
          if (err) {
            // Write a log file so we can better troubleshoot issues
            fs.writeFileSync(
              join(
                process.cwd(),
                `opik-ts-installation-error-${Date.now()}.log`,
              ),
              JSON.stringify({
                stdout,
                stderr,
              }),
              { encoding: 'utf8' },
            );

            reject(err);
          } else {
            resolve();
          }
        },
      );
    });
  } catch (e) {
    sdkInstallSpinner.stop('Installation failed.');
    clack.log.error(
      `${chalk.red(
        'Encountered the following error during installation:',
      )}\n\n${String(e)}\n\n${chalk.dim(
        `The opik-ts has created a \`opik-ts-installation-error-*.log\` file. If you think this issue is caused by the opik-ts, create an issue on GitHub and include the log file's content:\n${ISSUES_URL}`,
      )}`,
    );
    await abort();
  }

  sdkInstallSpinner.stop(
    `${alreadyInstalled ? 'Updated' : 'Installed'} ${chalk.bold.cyan(
      packageNameDisplayLabel ?? packageName,
    )} with ${chalk.bold(pkgManager.label)}.`,
  );

  analytics.capture('package installed', {
    packageName: packageNameDisplayLabel ?? packageName,
    packageManager: pkgManager.label,
    wasAlreadyInstalled: alreadyInstalled,
    forceInstall,
  });

  return { packageManager: pkgManager };
}

/**
 * Checks if @param packageId is listed as a dependency in @param packageJson.
 * If not, it will ask users if they want to continue without the package.
 *
 * Use this function to check if e.g. a the framework of the SDK is installed
 *
 * @param packageJson the package.json object
 * @param packageId the npm name of the package
 * @param packageName a human readable name of the package
 */
export async function ensurePackageIsInstalled(
  packageJson: PackageDotJson,
  packageId: string,
  packageName: string,
): Promise<void> {
  const installed = hasPackageInstalled(packageId, packageJson);

  if (!installed) {
    const continueWithoutPackage = await abortIfCancelled(
      clack.confirm({
        message: `${packageName} does not seem to be installed. Do you still want to continue?`,
        initialValue: false,
      }),
    );

    if (!continueWithoutPackage) {
      await abort(undefined, 0);
    }
  }
}

/**
 * Checks if Node.js is installed on the system.
 * If not, it will ask users if they want to continue without Node.js.
 *
 * Use this function to check if Node.js runtime is available on the system
 */
export async function ensureNodejsIsInstalled(): Promise<void> {
  const installed = isNodejsInstalled();

  if (!installed) {
    analytics.capture('wrong environment detected', {
      reason: 'node.js not installed',
      errorType: 'missing_nodejs',
    });

    const continueWithoutNodejs = await abortIfCancelled(
      clack.confirm({
        message:
          'Node.js does not seem to be installed. Do you still want to continue?',
        initialValue: false,
      }),
    );

    analytics.capture('wrong environment decision', {
      errorType: 'missing_nodejs',
      continued: continueWithoutNodejs,
    });

    if (!continueWithoutNodejs) {
      await abort(undefined, 0);
    }
  }
}

export async function getPackageDotJson({
  installDir,
}: Pick<WizardOptions, 'installDir'>): Promise<PackageDotJson> {
  const packageJsonFileContents = await fs.promises
    .readFile(join(installDir, 'package.json'), 'utf8')
    .catch(() => {
      analytics.capture('wrong environment detected', {
        reason: 'package.json not found',
        errorType: 'missing_package_json',
        installDir,
      });

      clack.log.error(
        'Could not find package.json. Make sure to run the Opik configure CLI in the root of your app!',
      );
      return abort();
    });

  let packageJson: PackageDotJson | undefined = undefined;

  try {
    packageJson = JSON.parse(packageJsonFileContents) as PackageDotJson;
  } catch {
    analytics.capture('wrong environment detected', {
      reason: 'invalid package.json format',
      errorType: 'invalid_package_json',
      installDir,
    });

    clack.log.error(
      `Unable to parse your ${chalk.cyan(
        'package.json',
      )}. Make sure it has a valid format!`,
    );

    await abort();
  }

  return packageJson || {};
}

export async function updatePackageDotJson(
  packageDotJson: PackageDotJson,
  { installDir }: Pick<WizardOptions, 'installDir'>,
): Promise<void> {
  try {
    await fs.promises.writeFile(
      join(installDir, 'package.json'),
      // TODO: maybe figure out the original indentation
      JSON.stringify(packageDotJson, null, 2),
      {
        encoding: 'utf8',
        flag: 'w',
      },
    );
  } catch {
    clack.log.error(`Unable to update your ${chalk.cyan('package.json')}.`);

    await abort();
  }
}

export async function getPackageManager({
  installDir,
}: Pick<WizardOptions, 'installDir'>): Promise<PackageManager> {
  const detectedPackageManagers = detectAllPackageManagers({ installDir });

  // If exactly one package manager detected, use it automatically
  if (detectedPackageManagers.length === 1) {
    const detectedPackageManager = detectedPackageManagers[0];
    return detectedPackageManager;
  }

  // If multiple or no package managers detected, prompt user to select
  const options =
    detectedPackageManagers.length > 0
      ? detectedPackageManagers
      : packageManagers;

  const message =
    detectedPackageManagers.length > 1
      ? 'Multiple package managers detected. Please select one:'
      : 'Please select your package manager.';

  const selectedPackageManager: PackageManager = await abortIfCancelled(
    clack.select({
      message,
      options: options.map((packageManager: PackageManager) => ({
        value: packageManager,
        label: packageManager.label,
      })),
    }),
  );

  return selectedPackageManager;
}

export function isUsingTypeScript({
  installDir,
}: Pick<WizardOptions, 'installDir'>) {
  try {
    return fs.existsSync(join(installDir, 'tsconfig.json'));
  } catch {
    return false;
  }
}

/**
 * Checks if Node.js is installed on the system by attempting to run `node --version`
 * @returns true if Node.js is installed and accessible, false otherwise
 */
export function isNodejsInstalled(): boolean {
  try {
    childProcess.execSync('node --version', {
      stdio: 'ignore',
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Handles URL configuration for local deployment with validation and retries
 * @returns The validated and normalized Opik URL
 */
async function handleLocalDeploymentConfig(): Promise<string> {
  // Check if default local URL is already running
  const isDefaultLocalRunning = await isOpikAccessible(DEFAULT_LOCAL_URL, 3000);

  if (isDefaultLocalRunning) {
    return normalizeOpikUrl(DEFAULT_LOCAL_URL);
  }

  // If not running, ask user for custom URL with retry logic
  clack.log.warn(`Local Opik instance not found at ${DEFAULT_LOCAL_URL}`);

  let attemptCount = 0;
  while (attemptCount < MAX_URL_VALIDATION_RETRIES) {
    const customUrl = await abortIfCancelled(
      clack.text({
        message: 'Please enter your Opik instance URL',
        placeholder: 'http://localhost:5173/',
        validate: (value: string | undefined) => {
          if (!value || value.trim() === '') {
            return 'URL cannot be empty. Please enter a valid URL...';
          }
          return undefined;
        },
      }),
      'nodejs' as Integration,
    );

    // Validate URL format
    try {
      const normalizedUrl = normalizeOpikUrl(customUrl);

      // Check if URL is accessible
      const isAccessible = await isOpikAccessible(normalizedUrl, 5000);

      if (isAccessible) {
        return normalizedUrl;
      } else {
        attemptCount++;
        if (attemptCount < MAX_URL_VALIDATION_RETRIES) {
          clack.log.error(
            `⚠ Opik is not accessible at ${normalizedUrl}. Please try again. (Attempt ${attemptCount}/${MAX_URL_VALIDATION_RETRIES})`,
          );
        }
      }
    } catch (error) {
      attemptCount++;
      if (attemptCount < MAX_URL_VALIDATION_RETRIES) {
        clack.log.error(
          `⚠ ${
            error instanceof Error ? error.message : 'Invalid URL'
          }. Please try again. (Attempt ${attemptCount}/${MAX_URL_VALIDATION_RETRIES})`,
        );
      }
    }
  }

  // After max retries, abort
  await abort(
    `Failed to connect to Opik after ${MAX_URL_VALIDATION_RETRIES} attempts. Please check your URL and try again.`,
  );
  throw new Error('unreachable'); // This line is unreachable but needed for TypeScript
}

/**
 * Handles URL configuration for self-hosted deployment with validation and retries
 * @returns The validated and normalized Opik URL
 */
async function handleSelfHostedDeploymentConfig(): Promise<string> {
  let attemptCount = 0;

  while (attemptCount < MAX_URL_VALIDATION_RETRIES) {
    const customUrl = await abortIfCancelled(
      clack.text({
        message: 'Please enter your Opik instance URL',
        placeholder: 'https://your-opik-instance.com/',
        validate: (value: string | undefined) => {
          if (!value || value.trim() === '') {
            return 'URL cannot be empty. Please enter a valid URL...';
          }
          return undefined;
        },
      }),
      'nodejs' as Integration,
    );

    // Validate URL format
    try {
      const normalizedUrl = normalizeOpikUrl(customUrl);

      // Check if URL is accessible
      const isAccessible = await isOpikAccessible(normalizedUrl, 5000);

      if (isAccessible) {
        return normalizedUrl;
      } else {
        attemptCount++;
        if (attemptCount < MAX_URL_VALIDATION_RETRIES) {
          clack.log.error(
            `⚠ Opik is not accessible at ${normalizedUrl}. Please try again, the URL should follow a format similar to http://localhost:5173/. (Attempt ${attemptCount}/${MAX_URL_VALIDATION_RETRIES})`,
          );
        }
      }
    } catch (error) {
      attemptCount++;
      if (attemptCount < MAX_URL_VALIDATION_RETRIES) {
        clack.log.error(
          `⚠ ${
            error instanceof Error ? error.message : 'Invalid URL'
          }. Please try again. (Attempt ${attemptCount}/${MAX_URL_VALIDATION_RETRIES})`,
        );
      }
    }
  }

  // After max retries, abort
  await abort(
    `Failed to connect to Opik after ${MAX_URL_VALIDATION_RETRIES} attempts. Please check your URL and try again.`,
  );
  throw new Error('unreachable'); // This line is unreachable but needed for TypeScript
}

/**
 *
 * Use this function to get project data for the CLI.
 *
 * @param options CLI options
 * @param options.useLocal If true, skips prompts and configures for local deployment
 * @returns project data (token, url)
 */
export async function getOrAskForProjectData(options?: {
  useLocal?: boolean;
}): Promise<{
  wizardHash: string;
  host: string;
  projectApiKey: string;
  workspaceName: string;
  projectName: string;
  deploymentType: DeploymentType;
}> {
  const cloudUrl = getCloudUrl();

  // If --use-local flag is set, skip prompts and use local defaults
  if (options?.useLocal) {
    const projectName = await abortIfCancelled(
      clack.text({
        message: 'Enter your project name (optional)',
        placeholder: 'Default Project',
        defaultValue: 'Default Project',
      }),
      'nodejs' as Integration,
    );

    const wizardHash = 'terminal-input-' + Date.now();

    analytics.setDistinctId(wizardHash);
    analytics.capture('project data configured', {
      hasApiKey: false,
      deploymentType: DeploymentType.LOCAL,
      workspaceName: 'default',
      useLocal: true,
    });

    return {
      wizardHash,
      host: DEFAULT_LOCAL_URL,
      projectApiKey: '',
      workspaceName: 'default',
      projectName: projectName || 'Default Project',
      deploymentType: DeploymentType.LOCAL,
    };
  }

  // Step 1: Check if local Opik is running
  const isLocalRunning = await isOpikAccessible(DEFAULT_LOCAL_URL, 3000);

  // Step 2: Deployment Type Selection
  const deploymentChoices = [
    {
      value: DeploymentType.CLOUD,
      label: 'Opik Cloud',
      hint: 'https://www.comet.com',
    },
    {
      value: DeploymentType.SELF_HOSTED,
      label: 'Self-hosted Comet platform',
      hint: 'Custom Opik instance',
    },
    {
      value: DeploymentType.LOCAL,
      label: isLocalRunning
        ? `Local deployment ${chalk.dim(`(detected at ${DEFAULT_LOCAL_URL})`)}`
        : 'Local deployment',
      hint: isLocalRunning ? '✓ Running' : 'http://localhost:5173',
    },
  ];

  const deploymentType = (await abortIfCancelled(
    clack.select({
      message: 'Which Opik deployment do you want to log your traces to?',
      options: deploymentChoices,
      initialValue: isLocalRunning
        ? deploymentChoices[2].value
        : deploymentChoices[0].value,
    }),
    'nodejs' as Integration,
  )) as DeploymentType;

  analytics.capture('deployment type selected', {
    deploymentType,
    localDetected: isLocalRunning,
  });

  // Step 3: Handle deployment type specific configuration
  let host: string;
  let projectApiKey: string = '';

  if (deploymentType === DeploymentType.LOCAL) {
    // Local deployment configuration
    host = await handleLocalDeploymentConfig();
  } else if (deploymentType === DeploymentType.SELF_HOSTED) {
    // Self-hosted deployment configuration
    host = await handleSelfHostedDeploymentConfig();
  } else {
    // Cloud deployment configuration
    host = cloudUrl;
  }

  // Step 4 & 5: API Key and Workspace Name validation (only for cloud and self-hosted)
  let workspaceName: string;

  if (deploymentType === DeploymentType.LOCAL) {
    // Local deployment uses default workspace
    workspaceName = 'default';
  } else {
    // Loop until we get a valid API key that returns a workspace name
    let apiKeyValidated = false;
    let defaultWorkspaceName: string | undefined;

    while (!apiKeyValidated) {
      clack.log.info(
        `${chalk.bold('You can find your Opik API key here:')}\n${chalk.cyan(
          `${host}account-settings/apiKeys`,
        )}`,
      );

      projectApiKey = await abortIfCancelled(
        clack.password({
          message: 'Enter your Opik API key',
          validate: (value: string | undefined) => {
            if (!value || value.trim() === '') {
              return 'API key is required';
            }
            return undefined;
          },
        }),
        'nodejs' as Integration,
      );

      // Try to fetch the default workspace to validate the API key
      try {
        defaultWorkspaceName = await getDefaultWorkspace(projectApiKey, host);
        apiKeyValidated = true; // API key is valid, we got the workspace name
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        debug(`Failed to fetch default workspace: ${errorMessage}`);

        clack.log.error(
          `${chalk.red('Invalid API key')}\n${chalk.dim(
            'Please check your API key and try again.',
          )}`,
        );

        // Loop will continue, asking for API key again
      }
    }

    // Ask for workspace name with default if available
    workspaceName = await abortIfCancelled(
      clack.text({
        message: defaultWorkspaceName
          ? `Enter your workspace name (press Enter to use: ${chalk.cyan(
              defaultWorkspaceName,
            )})`
          : 'Enter your workspace name',
        placeholder: defaultWorkspaceName || 'your-workspace-name',
        defaultValue: defaultWorkspaceName,
        validate: (value: string | undefined) => {
          // Allow empty input if defaultValue is set (Enter key will use default)
          if ((!value || value.trim() === '') && !defaultWorkspaceName) {
            return 'Workspace name is required';
          }
          return undefined;
        },
      }),
      'nodejs' as Integration,
    );
  }

  const projectName = await abortIfCancelled(
    clack.text({
      message: 'Enter your project name (optional)',
      placeholder: 'Default Project',
      defaultValue: 'Default Project',
    }),
    'nodejs' as Integration,
  );

  const wizardHash = 'terminal-input-' + Date.now(); // Simple hash for tracking

  analytics.setDistinctId(wizardHash);
  analytics.capture('project data configured', {
    hasApiKey: !!projectApiKey,
    deploymentType,
    workspaceName,
  });

  return {
    wizardHash,
    host,
    projectApiKey,
    workspaceName,
    projectName: projectName || 'Default Project',
    deploymentType,
  };

  /* COMMENTED OUT: Browser-based authentication flow
  const { host, projectApiKey, wizardHash } = await askForWizardLogin({
    url: cloudUrl,
  });

  if (!projectApiKey) {
    clack.log.error(`Didn't receive a project API key. This shouldn't happen :(

Please let us know if you think this is a bug in the CLI:
${chalk.cyan(ISSUES_URL)}`);

    clack.log
      .info(`In the meantime, we'll add a dummy project API key (${chalk.cyan(
      `"${DUMMY_PROJECT_API_KEY}"`,
    )}) for you to replace later.
You can find your Project API key here:
${chalk.cyan(`${cloudUrl}/settings/project#variables`)}`);
  }

  return {
    wizardHash,
    host: host || DEFAULT_HOST_URL,
    projectApiKey: projectApiKey || DUMMY_PROJECT_API_KEY,
  };
  */
}

/* COMMENTED OUT: Browser-based authentication with polling
async function askForWizardLogin(options: {
  url: string;
}): Promise<ProjectData> {
  let wizardHash: string;

  try {
    wizardHash = (
      await axios.post<{ hash: string }>(`${options.url}/api/wizard/initialize`)
    ).data.hash;
  } catch (e: unknown) {
    clack.log.error('Loading CLI failed.');
    clack.log.info(JSON.stringify(e, null, 2));
    await abort(
      chalk.red(
        `Please try again in a few minutes and let us know if this issue persists: ${ISSUES_URL}`,
      ),
    );
    throw e;
  }

  const loginUrl = new URL(`${options.url}/wizard?hash=${wizardHash}`);

  const urlToOpen = loginUrl.toString();

  clack.log.info(
    `${chalk.bold(
      `If the browser window didn't open automatically, please open the following link to login into Opik:`,
    )}\n\n${chalk.cyan(
      urlToOpen,
    )}${`\n\nIf you already have an account, you can use this link:\n\n${chalk.cyan(
      loginUrl.toString(),
    )}`}`,
  );

  if (process.env.NODE_ENV !== 'test') {
    opn(urlToOpen, { wait: false }).catch(() => {
      // opn throws in environments that don't have a browser (e.g. remote shells) so we just noop here
    });
  }

  const loginSpinner = clack.spinner();

  loginSpinner.start('Waiting for you to log in using the link above');

  const data = await new Promise<ProjectData>((resolve) => {
    const pollingInterval = setInterval(() => {
      axios
        .get<{
          project_api_key: string;
          host: string;
          user_distinct_id: string;
          personal_api_key?: string;
        }>(`${options.url}/api/wizard/data`, {
          headers: {
            'Accept-Encoding': 'deflate',
            'X-PostHog-Wizard-Hash': wizardHash,
          },
        })
        .then((result) => {
          const data: ProjectData = {
            wizardHash,
            projectApiKey: result.data.project_api_key,
            host: result.data.host,
            distinctId: result.data.user_distinct_id,
          };

          resolve(data);
          clearTimeout(timeout);
          clearInterval(pollingInterval);
        })
        .catch(() => {
          // noop - just try again
        });
    }, 500);

    const timeout = setTimeout(() => {
      clearInterval(pollingInterval);
      loginSpinner.stop(
        'Login timed out. No worries - it happens to the best of us.',
      );

      void abort('Please restart the CLI and log in to complete the setup.');
    }, 180_000);
  });

  loginSpinner.stop(`Login complete. Welcome to Opik! 🎉`);

  return data;
}
*/

/**
 * Asks users if they have a config file for @param tool (e.g. Vite).
 * If yes, asks users to specify the path to their config file.
 *
 * Use this helper function as a fallback mechanism if the lookup for
 * a config file with its most usual location/name fails.
 *
 * @param toolName Name of the tool for which we're looking for the config file
 * @param configFileName Name of the most common config file name (e.g. vite.config.js)
 *
 * @returns a user path to the config file or undefined if the user doesn't have a config file
 */
export async function askForToolConfigPath(
  toolName: string,
  configFileName: string,
): Promise<string | undefined> {
  const hasConfig = await abortIfCancelled(
    clack.confirm({
      message: `Do you have a ${toolName} config file (e.g. ${chalk.cyan(
        configFileName,
      )})?`,
      initialValue: true,
    }),
  );

  if (!hasConfig) {
    return undefined;
  }

  return await abortIfCancelled(
    clack.text({
      message: `Please enter the path to your ${toolName} config file:`,
      placeholder: join('.', configFileName),
      validate: (value: string | undefined) => {
        if (!value) {
          return 'Please enter a path.';
        }

        try {
          fs.accessSync(value);
        } catch {
          return 'Could not access the file at this path.';
        }
      },
    }),
  );
}

/**
 * Prints copy/paste-able instructions to the console.
 * Afterwards asks the user if they added the code snippet to their file.
 *
 * While there's no point in providing a "no" answer here, it gives users time to fulfill the
 * task before the CLI continues with additional steps.
 *
 * Use this function if you want to show users instructions on how to add/modify
 * code in their file. This is helpful if automatic insertion failed or is not possible/feasible.
 *
 * @param filename the name of the file to which the code snippet should be applied.
 * If a path is provided, only the filename will be used.
 *
 * @param codeSnippet the snippet to be printed. Use {@link makeCodeSnippet}  to create the
 * diff-like format for visually highlighting unchanged or modified lines of code.
 *
 * @param hint (optional) a hint to be printed after the main instruction to add
 * the code from @param codeSnippet to their @param filename.
 *
 * TODO: refactor copy paste instructions across different CLI flows to use this function.
 *       this might require adding a custom message parameter to the function
 */
export async function showCopyPasteInstructions(
  filename: string,
  codeSnippet: string,
  hint?: string,
): Promise<void> {
  clack.log.step(
    `Add the following code to your ${chalk.cyan(basename(filename))} file:${
      hint ? chalk.dim(` (${chalk.dim(hint)})`) : ''
    }`,
  );

  // Padding the code snippet to be printed with a \n at the beginning and end
  // This makes it easier to distinguish the snippet from the rest of the output
  // Intentionally logging directly to console here so that the code can be copied/pasted directly
  console.log(`\n${codeSnippet}\n`);

  await abortIfCancelled(
    clack.select({
      message: 'Did you apply the snippet above?',
      options: [{ label: 'Yes, continue!', value: true }],
      initialValue: true,
    }),
  );
}

/**
 * Callback that exposes formatting helpers for a code snippet.
 * @param unchanged - Formats text as old code.
 * @param plus - Formats text as new code.
 * @param minus - Formats text as removed code.
 */
type CodeSnippetFormatter = (
  unchanged: (txt: string) => string,
  plus: (txt: string) => string,
  minus: (txt: string) => string,
) => string;

/**
 * Crafts a code snippet that can be used to e.g.
 * - print copy/paste instructions to the console
 * - create a new config file.
 *
 * @param colors set this to true if you want the final snippet to be colored.
 * This is useful for printing the snippet to the console as part of copy/paste instructions.
 *
 * @param callback the callback that returns the formatted code snippet.
 * It exposes takes the helper functions for marking code as unchanged, new or removed.
 * These functions no-op if no special formatting should be applied
 * and otherwise apply the appropriate formatting/coloring.
 * (@see {@link CodeSnippetFormatter})
 *
 * @see {@link showCopyPasteInstructions} for the helper with which to display the snippet in the console.
 *
 * @returns a string containing the final, formatted code snippet.
 */
export function makeCodeSnippet(
  colors: boolean,
  callback: CodeSnippetFormatter,
): string {
  const unchanged = (txt: string) => (colors ? chalk.grey(txt) : txt);
  const plus = (txt: string) => (colors ? chalk.greenBright(txt) : txt);
  const minus = (txt: string) => (colors ? chalk.redBright(txt) : txt);

  return callback(unchanged, plus, minus);
}

/**
 * Creates a new config file with the given @param filepath and @param codeSnippet.
 *
 * Use this function to create a new config file for users. This is useful
 * when users answered that they don't yet have a config file for a tool.
 *
 * (This doesn't mean that they don't yet have some other way of configuring
 * their tool but we can leave it up to them to figure out how to merge configs
 * here.)
 *
 * @param filepath absolute path to the new config file
 * @param codeSnippet the snippet to be inserted into the file
 * @param moreInformation (optional) the message to be printed after the file was created
 * For example, this can be a link to more information about configuring the tool.
 *
 * @returns true on success, false otherwise
 */
export async function createNewConfigFile(
  filepath: string,
  codeSnippet: string,
  { installDir }: Pick<WizardOptions, 'installDir'>,
  moreInformation?: string,
): Promise<boolean> {
  if (!isAbsolute(filepath)) {
    debug(`createNewConfigFile: filepath is not absolute: ${filepath}`);
    return false;
  }

  const prettyFilename = chalk.cyan(relative(installDir, filepath));

  try {
    await fs.promises.writeFile(filepath, codeSnippet);

    clack.log.success(`Added new ${prettyFilename} file.`);

    if (moreInformation) {
      clack.log.info(chalk.gray(moreInformation));
    }

    return true;
  } catch (e) {
    debug(e);
    clack.log.warn(
      `Could not create a new ${prettyFilename} file. Please create one manually and follow the instructions below.`,
    );
  }

  return false;
}

export async function askShouldInstallPackage(
  pkgName: string,
): Promise<boolean> {
  return abortIfCancelled(
    clack.confirm({
      message: `Do you want to install ${chalk.cyan(pkgName)}?`,
    }),
  );
}

export async function askShouldAddPackageOverride(
  pkgName: string,
  pkgVersion: string,
): Promise<boolean> {
  return abortIfCancelled(
    clack.confirm({
      message: `Do you want to add an override for ${chalk.cyan(
        pkgName,
      )} version ${chalk.cyan(pkgVersion)}?`,
    }),
  );
}

export async function askForAIConsent(options: Pick<WizardOptions, 'default'>) {
  const aiConsent = options.default
    ? true
    : await abortIfCancelled(
        clack.select({
          message: 'This setup CLI uses AI, are you happy to continue? ✨',
          options: [
            {
              label: 'Yes',
              value: true,
              hint: 'We will use AI to help you setup Opik quickly',
            },
            {
              label: 'No',
              value: false,
              hint: "I don't like AI",
            },
          ],
          initialValue: true,
        }),
      );

  analytics.capture('ai consent', {
    consent: aiConsent,
    defaultMode: options.default,
  });

  return aiConsent;
}

export async function checkAndAskToUpdateConfig(
  options: Pick<WizardOptions, 'installDir'>,
): Promise<boolean> {
  const opikVariables = [
    OPIK_ENV_VARS.API_KEY,
    OPIK_ENV_VARS.URL_OVERRIDE,
    OPIK_ENV_VARS.WORKSPACE,
    OPIK_ENV_VARS.PROJECT_NAME,
  ];

  const dotEnvLocalFilePath = join(options.installDir, '.env.local');
  const dotEnvFilePath = join(options.installDir, '.env');

  let envFilePath: string | undefined;
  let envContent = '';

  if (fs.existsSync(dotEnvLocalFilePath)) {
    envFilePath = dotEnvLocalFilePath;
    envContent = fs.readFileSync(dotEnvLocalFilePath, 'utf8');
  } else if (fs.existsSync(dotEnvFilePath)) {
    envFilePath = dotEnvFilePath;
    envContent = fs.readFileSync(dotEnvFilePath, 'utf8');
  }

  const foundVariables = opikVariables.filter((variable) => {
    const regex = new RegExp(`^${variable}=`, 'm');
    return regex.test(envContent);
  });

  const hasConfig = foundVariables.length > 0;

  if (!hasConfig) {
    return true;
  }

  const relativeEnvPath = envFilePath
    ? relative(options.installDir, envFilePath)
    : '.env';

  clack.log.warning(
    `Found existing Opik configuration in ${chalk.bold.cyan(relativeEnvPath)}`,
  );

  // Display the found environment variables
  const variableValues: Record<string, string> = {};
  foundVariables.forEach((variable) => {
    const regex = new RegExp(`^${variable}=(.*)$`, 'm');
    const match = envContent.match(regex);
    if (match) {
      variableValues[variable] = match[1];
    }
  });

  if (Object.keys(variableValues).length > 0) {
    clack.log.message('');
    clack.log.message(chalk.bold('Current configuration:'));
    Object.entries(variableValues).forEach(([key, value]) => {
      // Mask sensitive values (API keys) but show workspace and project name
      const displayValue =
        key === OPIK_ENV_VARS.API_KEY ? maskApiKey(value) : value;
      clack.log.message(
        `  ${chalk.cyan(key)}: ${chalk.dim(displayValue || '(empty)')}`,
      );
    });
    clack.log.message('');
  }

  const shouldUpdate = await abortIfCancelled(
    clack.confirm({
      message: 'Do you want to update it with new configuration?',
      initialValue: false,
    }),
  );

  if (!shouldUpdate) {
    clack.log.info('Keeping existing configuration');
  }

  return shouldUpdate;
}
