/* eslint-disable max-lines */

import {
  abortIfCancelled,
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
import { debug } from '../utils/debug';
// import { getNodejsDocumentation } from './docs';
// import {
//   generateFileChangesForIntegration,
//   getFilesToChange,
//   getRelevantFilesForIntegration,
// } from '../utils/file-utils';
import type { WizardOptions } from '../utils/types';
import { getOutroMessage } from '../lib/messages';
import {
  addEditorRulesStep,
  addOrUpdateEnvironmentVariablesStep,
  runPrettierStep,
  createOpikClientStep,
} from '../steps';
import { uploadEnvironmentVariablesStep } from '../steps/upload-environment-variables';
import { buildOpikApiUrl } from '../utils/urls';

export async function runNodejsWizard(options: WizardOptions): Promise<void> {
  debug('Starting Node.js wizard');

  printWelcome({
    wizardName: 'Opik Node.js wizard',
  });

  debug('Detecting TypeScript usage');
  const typeScriptDetected = isUsingTypeScript(options);
  debug(`TypeScript detected: ${typeScriptDetected}`);

  debug('Confirming git repo status');
  await confirmContinueIfNoOrDirtyGitRepo(options);

  debug('Ensuring Node.js is installed');
  await ensureNodejsIsInstalled();

  debug('Reading package.json');
  const packageJson = await getPackageDotJson(options);

  debug('Getting project data');
  const {
    projectApiKey,
    wizardHash,
    host,
    workspaceName,
    projectName,
    deploymentType,
  } = await getOrAskForProjectData();
  debug(
    `Project data obtained: deploymentType=${deploymentType}, workspace=${workspaceName}, project=${projectName}`,
  );

  debug('Installing opik package');
  const { packageManager: packageManagerFromInstallStep } =
    await installPackage({
      packageName: 'opik',
      packageNameDisplayLabel: 'opik',
      alreadyInstalled: !!packageJson?.dependencies?.['opik'],
      forceInstall: options.forceInstall,
      askBeforeUpdating: false,
      installDir: options.installDir,
      integration: Integration.nodejs,
    });
  debug('Opik package installed successfully');

  // Create opik-client file with basic setup and examples
  debug('Creating opik-client file');
  await createOpikClientStep({
    installDir: options.installDir,
    isTypeScript: typeScriptDetected,
  });
  debug('Opik-client file created successfully');

  // TODO: AI-powered LLM setup (commented out - backend endpoint not ready)
  // This will be enabled once the backend endpoint for LLM analysis is available
  /*
  // Ask if user wants AI-powered LLM setup (optional)
  let setupLLMIntegration = false;
  if (!options.default) {
    setupLLMIntegration = await abortIfCancelled(
      clack.confirm({
        message:
          'Do you want to set up automatic LLM tracing?\n  This will analyze your code and add Opik decorators to LLM functions.',
        initialValue: false,
      }),
    );
  }

  // Only run AI-powered file analysis if user opted in
  if (setupLLMIntegration) {
    const aiConsent = await askForAIConsent(options);

    if (!aiConsent) {
      clack.log.info(
        'Skipping automatic LLM setup. You can set it up manually using the examples in opik-client file',
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
    ['OPIK_URL_OVERRIDE']: buildOpikApiUrl(host),
    ['OPIK_PROJECT_NAME']: projectName,
  };

  // Only add API key and workspace for cloud and self-hosted deployments
  if (!isLocalDeployment) {
    environmentVariables['OPIK_API_KEY'] = projectApiKey;
    environmentVariables['OPIK_WORKSPACE'] = workspaceName;
  }

  const { relativeEnvFilePath, addedEnvVariables } =
    await addOrUpdateEnvironmentVariablesStep({
      variables: environmentVariables,
      installDir: options.installDir,
    });
  debug(`Environment variables added to ${relativeEnvFilePath}`);

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
  let addedEditorRules = false;
  const addCursorRules = await abortIfCancelled(
    clack.confirm({
      message:
        'Do you want to add Opik integration rules for LLM?\n  This will help your AI assistant understand how to use Opik in your project.',
      initialValue: true,
    }),
  );

  if (addCursorRules) {
    debug('Adding editor rules');
    addedEditorRules = await addEditorRulesStep({
      installDir: options.installDir,
      rulesName: 'nodejs-rules.md',
    });
    debug(`Editor rules added: ${addedEditorRules}`);
  } else {
    clack.log.info('Skipping Cursor rules setup');
  }

  debug('Uploading environment variables');
  const uploadedEnvVars = await uploadEnvironmentVariablesStep(
    environmentVariables,
    {
      integration: Integration.nodejs,
    },
  );
  debug(`Environment variables uploaded: ${uploadedEnvVars}`);

  // await addMCPServerToClientsStep({
  //   cloudRegion,
  //   integration: Integration.nodejs,
  // });

  debug('Generating outro message');
  const outroMessage = getOutroMessage({
    options,
    integration: Integration.nodejs,
    addedEditorRules,
    packageManager: packageManagerForOutro,
    envFileChanged: addedEnvVariables ? relativeEnvFilePath : undefined,
    uploadedEnvVars,
  });

  debug('Wizard completed successfully');
  clack.outro(outroMessage);
}
