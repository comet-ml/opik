import chalk from 'chalk';
import type { PackageManager } from '../utils/package-manager';
import { ISSUES_URL, Integration } from './constants';
import { INTEGRATION_CONFIG } from './config';

export const getPRDescription = ({
  integration,
  addedEditorRules,
}: {
  integration: Integration;
  addedEditorRules: boolean;
}) => {
  const integrationConfig = INTEGRATION_CONFIG[integration];

  return `This PR adds an integration for Opik.

  The following changes were made:
  ${integrationConfig.defaultChanges}
  ${addedEditorRules ? `• Added Cursor rules for Opik\n` : ''}
  
  
  Note: This used the ${
    integrationConfig.name
  } CLI to setup Opik, this is still in alpha and like all AI, might have got it wrong. Please check the installation carefully!
  
  Learn more about Opik + ${integrationConfig.name}: ${
    integrationConfig.docsUrl
  }`;
};

export const getOutroMessage = ({
  integration,
  addedEditorRules,
  packageManager,
  envFileChanged,
  uploadedEnvVars,
}: {
  integration: Integration;
  addedEditorRules: boolean;
  packageManager?: PackageManager;
  envFileChanged?: string;
  uploadedEnvVars: string[];
}) => {
  const integrationConfig = INTEGRATION_CONFIG[integration];

  const changes = [
    addedEditorRules ? `Added Cursor rules for Opik` : '',
    envFileChanged
      ? `Added your Opik API key to your ${envFileChanged} file`
      : '',
    uploadedEnvVars.length > 0
      ? `Uploaded your Opik API key to your hosting provider`
      : '',
  ].filter(Boolean);

  const nextSteps = [
    uploadedEnvVars.length === 0
      ? `Upload your Opik API key to your hosting provider`
      : '',
  ].filter(Boolean);

  return `
${chalk.green('Successfully installed Opik!')}  
  
${chalk.cyan('Changes made:')}
${integrationConfig.defaultChanges}
${changes.map((change) => `• ${change}`).join('\n')}

${chalk.yellow('Next steps:')}
${integrationConfig.nextSteps}
${nextSteps.map((step) => `• ${step}`).join('\n')}

Learn more about Opik + ${integrationConfig.name}: ${chalk.cyan(
    integrationConfig.docsUrl,
  )}

You should validate your setup by (re)starting your dev environment with Opik${
    packageManager
      ? ` (e.g. ${chalk.cyan(`${packageManager.runScriptCommand} dev`)}).`
      : `.`
  }

${chalk.dim(`If you encounter any issues, let us know here: ${ISSUES_URL}`)}`;
};
