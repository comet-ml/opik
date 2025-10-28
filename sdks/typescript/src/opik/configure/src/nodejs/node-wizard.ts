import {
  checkAndAskToUpdateConfig,
  confirmContinueIfNoOrDirtyGitRepo,
  DeploymentType,
  ensureNodejsIsInstalled,
  getOrAskForProjectData,
  getPackageDotJson,
  getPackageManager,
  installPackage,
  isUsingTypeScript,
  printWelcome,
} from '../utils/clack-utils';
import clack from '../utils/clack';
import { Integration } from '../lib/constants';
import { OPIK_ENV_VARS } from '../lib/env-constants';
import { debug } from '../utils/debug';
import type { WizardOptions } from '../utils/types';
import { getOutroMessage } from '../lib/messages';
import { addOrUpdateEnvironmentVariablesStep, runPrettierStep } from '../steps';
import { uploadEnvironmentVariablesStep } from '../steps/upload-environment-variables';
import { buildOpikApiUrl } from '../utils/urls';
import { analytics } from '../utils/analytics';

export async function runNodejsWizard(options: WizardOptions): Promise<void> {
  debug('Starting Node.js CLI');

  printWelcome({
    wizardName: 'Opik Node.js CLI',
  });

  debug('Detecting TypeScript usage');
  const typeScriptDetected = isUsingTypeScript(options);
  debug(`TypeScript detected: ${typeScriptDetected}`);

  analytics.setTag('typescript', typeScriptDetected);

  debug('Confirming git repo status');
  await confirmContinueIfNoOrDirtyGitRepo(options);

  debug('Ensuring Node.js is installed');
  await ensureNodejsIsInstalled();

  debug('Reading package.json');
  const packageJson = await getPackageDotJson(options);

  debug('Installing opik package');
  const { packageManager: packageManagerFromInstallStep } =
    await installPackage({
      packageName: 'opik',
      packageNameDisplayLabel: 'opik',
      alreadyInstalled: !!packageJson?.dependencies?.['opik'],
      forceInstall: options.forceInstall,
      askBeforeUpdating: false,
      installDir: options.installDir,
    });
  debug('Opik package installed successfully');

  debug('Checking for existing Opik configuration');
  const shouldUpdateConfig = await checkAndAskToUpdateConfig(options);

  if (!shouldUpdateConfig) {
    debug('User chose to keep existing configuration, finishing CLI');
    analytics.capture('kept existing config');
    clack.outro(
      'Opik setup complete! Your existing configuration has been preserved.',
    );

    return;
  }

  debug('Getting project data');
  const { projectApiKey, host, workspaceName, projectName, deploymentType } =
    await getOrAskForProjectData({ useLocal: options.useLocal });
  debug(
    `Project data obtained: deploymentType=${deploymentType}, workspace=${workspaceName}, project=${projectName}`,
  );

  // TODO: AI-powered LLM setup (commented out - backend endpoint not ready)
  // This will be enabled once the backend endpoint for LLM analysis is available
  /*
  // Ask if user wants AI-powered LLM setup (optional)
  let setupLLMIntegration = false;
  if (!options.default) {
    setupLLMIntegration = await abortIfCancelled(
      clack.confirm({
        message:
          'Do you want to set up automatic LLM tracing? This will analyze your code and add Opik decorators to LLM functions.',
        initialValue: false,
      }),
    );
  }

  // Only run AI-powered file analysis if user opted in
  if (setupLLMIntegration) {
    const aiConsent = await askForAIConsent(options);

    if (!aiConsent) {
      clack.log.info(
        'Skipping automatic LLM setup. You can set it up manually using the Opik documentation',
      );
    } else {
      const relevantFiles = await getRelevantFilesForIntegration({
        installDir: options.installDir,
        integration: Integration.nodejs,
      });

      const installationDocumentation = getNodejsDocumentation({
        language: typeScriptDetected ? 'typescript' : 'javascript',
      });

      clack.log.info(`Reviewing Opik documentation for Node.js`);

      const filesToChange = await getFilesToChange({
        integration: Integration.nodejs,
        relevantFiles,
        documentation: installationDocumentation,
        apiKey: projectApiKey,
        workspaceName,
      });

      await generateFileChangesForIntegration({
        integration: Integration.nodejs,
        filesToChange,
        apiKey: projectApiKey,
        workspaceName,
        installDir: options.installDir,
        documentation: installationDocumentation,
      });
    }
  }
  */

  debug('Adding environment variables');
  const isLocalDeployment = deploymentType === DeploymentType.LOCAL;
  const environmentVariables: Record<string, string> = {
    [OPIK_ENV_VARS.URL_OVERRIDE]: buildOpikApiUrl(host),
    [OPIK_ENV_VARS.PROJECT_NAME]: projectName,
  };

  // Only add API key and workspace for cloud and self-hosted deployments
  if (!isLocalDeployment) {
    environmentVariables[OPIK_ENV_VARS.API_KEY] = projectApiKey;
    environmentVariables[OPIK_ENV_VARS.WORKSPACE] = workspaceName;
  }

  const { relativeEnvFilePath, addedEnvVariables, addedGitignore } =
    await addOrUpdateEnvironmentVariablesStep({
      variables: environmentVariables,
      installDir: options.installDir,
    });
  debug(`Environment variables added to ${relativeEnvFilePath}`);

  analytics.capture('environment variables configured', {
    envFilePath: relativeEnvFilePath,
    addedEnvVariables,
    addedGitignore,
    variableCount: Object.keys(environmentVariables).length,
  });

  debug('Determining package manager');
  const packageManagerForOutro =
    packageManagerFromInstallStep ?? (await getPackageManager(options));
  debug(`Using package manager: ${packageManagerForOutro}`);

  debug('Running prettier');
  await runPrettierStep({
    installDir: options.installDir,
  });
  debug('Prettier completed');

  // Ask if user wants to add Cursor rules (only if running in Cursor)
  // let addedEditorRules = false;
  // const addCursorRules = await abortIfCancelled(
  //   clack.confirm({
  //     message:
  //       'Do you want to add Opik integration rules for your AI assistant?',
  //     initialValue: true,
  //   }),
  // );

  // if (addCursorRules) {
  //   debug('Adding editor rules');
  //   addedEditorRules = await addEditorRulesStep({
  //     installDir: options.installDir,
  //     rulesName: 'nodejs-rules.md',
  //   });
  //   debug(`Editor rules added: ${addedEditorRules}`);

  //   analytics.capture('editor rules added', {
  //     success: addedEditorRules,
  //   });
  // } else {
  //   clack.log.info('Skipping Opik Cursor rules setup');
  // }

  debug('Uploading environment variables');
  const uploadedEnvVars = await uploadEnvironmentVariablesStep(
    {
      [OPIK_ENV_VARS.URL_OVERRIDE]: buildOpikApiUrl(host),
      [OPIK_ENV_VARS.PROJECT_NAME]: projectName,
      ...(deploymentType !== DeploymentType.LOCAL && {
        [OPIK_ENV_VARS.API_KEY]: projectApiKey,
        [OPIK_ENV_VARS.WORKSPACE]: workspaceName,
      }),
    },
    { integration: Integration.nodejs },
  );
  debug(`Environment variables uploaded: ${uploadedEnvVars.length} variables`);

  if (uploadedEnvVars.length > 0) {
    analytics.capture('environment variables uploaded', {
      uploadedCount: uploadedEnvVars.length,
    });
  }

  // await addMCPServerToClientsStep({
  //   cloudRegion,
  //   integration: Integration.nodejs,
  // });

  debug('Generating outro message');
  const outroMessage = getOutroMessage({
    integration: Integration.nodejs,
    addedEditorRules: false,
    packageManager: packageManagerForOutro,
    envFileChanged: addedEnvVariables ? relativeEnvFilePath : undefined,
    uploadedEnvVars,
  });

  analytics.capture('nodejs wizard completed', {
    addedEditorRules: false,
    envFileChanged: !!addedEnvVariables,
    uploadedEnvVarsCount: uploadedEnvVars.length,
  });

  debug('CLI completed successfully');
  clack.outro(outroMessage);
}
