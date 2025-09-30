/* eslint-disable max-lines */

import {
  abort,
  askForAIConsent,
  confirmContinueIfNoOrDirtyGitRepo,
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
import { getNodejsDocumentation } from './docs';
import {
  generateFileChangesForIntegration,
  getFilesToChange,
  getRelevantFilesForIntegration,
} from '../utils/file-utils';
import type { WizardOptions } from '../utils/types';
import { getOutroMessage } from '../lib/messages';
import {
  addEditorRulesStep,
  addOrUpdateEnvironmentVariablesStep,
  runPrettierStep,
} from '../steps';
import { uploadEnvironmentVariablesStep } from '../steps/upload-environment-variables';

export async function runNodejsWizard(options: WizardOptions): Promise<void> {
  printWelcome({
    wizardName: 'Opik Node.js wizard',
  });

  const aiConsent = await askForAIConsent(options);

  if (!aiConsent) {
    await abort(
      'The Node.js wizard requires AI to get setup right now. Please view the docs to setup Node.js manually instead: https://www.comet.com/docs/opik/reference/typescript-sdk/overview',
      0,
    );
  }

  const typeScriptDetected = isUsingTypeScript(options);

  await confirmContinueIfNoOrDirtyGitRepo(options);

  await ensureNodejsIsInstalled();

  const packageJson = await getPackageDotJson(options);

  const { projectApiKey, wizardHash, host, workspaceName, projectName } =
    await getOrAskForProjectData();

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

  const { relativeEnvFilePath, addedEnvVariables } =
    await addOrUpdateEnvironmentVariablesStep({
      variables: {
        ['OPIK_API_KEY']: projectApiKey,
        ['OPIK_URL_OVERRIDE']: host,
        ['OPIK_WORKSPACE_NAME']: workspaceName,
        ['OPIK_PROJECT_NAME']: projectName,
      },
      installDir: options.installDir,
    });

  const packageManagerForOutro =
    packageManagerFromInstallStep ?? (await getPackageManager(options));

  await runPrettierStep({
    installDir: options.installDir,
  });

  const addedEditorRules = await addEditorRulesStep({
    installDir: options.installDir,
    rulesName: 'nodejs-rules.md',
  });

  const uploadedEnvVars = await uploadEnvironmentVariablesStep(
    {
      ['OPIK_API_KEY']: projectApiKey,
      ['OPIK_URL_OVERRIDE']: host,
      ['OPIK_WORKSPACE_NAME']: workspaceName,
      ['OPIK_PROJECT_NAME']: projectName,
    },
    {
      integration: Integration.nodejs,
    },
  );

  // await addMCPServerToClientsStep({
  //   cloudRegion,
  //   integration: Integration.nodejs,
  // });

  const outroMessage = getOutroMessage({
    options,
    integration: Integration.nodejs,
    addedEditorRules,
    packageManager: packageManagerForOutro,
    envFileChanged: addedEnvVariables ? relativeEnvFilePath : undefined,
    uploadedEnvVars,
  });

  clack.outro(outroMessage);
}
