import chalk from 'chalk';
import clack from '../utils/clack';
import { getDotGitignore } from '../utils/file-utils';
import * as fs from 'fs';
import path from 'path';
import { OPIK_ENV_VARS } from '../lib/env-constants';

export async function addOrUpdateEnvironmentVariablesStep({
  installDir,
  variables,
}: {
  installDir: string;
  variables: Record<string, string>;
}): Promise<{
  relativeEnvFilePath: string;
  addedEnvVariables: boolean;
  addedGitignore: boolean;
}> {
  const envVarContent = Object.entries(variables)
    .map(([key, value]) => `${key}=${value}`)
    .join('\n');

  const dotEnvLocalFilePath = path.join(installDir, '.env.local');
  const dotEnvFilePath = path.join(installDir, '.env');
  const targetEnvFilePath = fs.existsSync(dotEnvLocalFilePath)
    ? dotEnvLocalFilePath
    : dotEnvFilePath;

  const dotEnvFileExists = fs.existsSync(targetEnvFilePath);

  const relativeEnvFilePath = path.relative(installDir, targetEnvFilePath);

  let addedGitignore = false;
  let addedEnvVariables = false;

  if (dotEnvFileExists) {
    try {
      let dotEnvFileContent = fs.readFileSync(targetEnvFilePath, 'utf8');

      // Remove all existing OPIK variables first
      for (const varName of Object.values(OPIK_ENV_VARS)) {
        const regex = new RegExp(`^${varName}=.*$`, 'gm');
        dotEnvFileContent = dotEnvFileContent.replace(regex, '');
      }

      // Clean up multiple consecutive newlines
      dotEnvFileContent = dotEnvFileContent.replace(/\n{3,}/g, '\n\n');
      dotEnvFileContent = dotEnvFileContent.trim();

      // Add new environment variables
      for (const [key, value] of Object.entries(variables)) {
        if (!dotEnvFileContent.endsWith('\n') && dotEnvFileContent.length > 0) {
          dotEnvFileContent += '\n';
        }
        dotEnvFileContent += `${key}=${value}\n`;
      }

      await fs.promises.writeFile(targetEnvFilePath, dotEnvFileContent, {
        encoding: 'utf8',
        flag: 'w',
      });
      clack.log.success(
        `Updated environment variables in ${chalk.bold.cyan(
          relativeEnvFilePath,
        )}`,
      );

      addedEnvVariables = true;
    } catch {
      clack.log.warning(
        `Failed to update environment variables in ${chalk.bold.cyan(
          relativeEnvFilePath,
        )}. Please update them manually.`,
      );

      return {
        relativeEnvFilePath,
        addedEnvVariables,
        addedGitignore,
      };
    }
  } else {
    try {
      await fs.promises.writeFile(targetEnvFilePath, envVarContent, {
        encoding: 'utf8',
        flag: 'w',
      });
      clack.log.success(
        `Created ${chalk.bold.cyan(
          relativeEnvFilePath,
        )} with environment variables.`,
      );

      addedEnvVariables = true;
    } catch {
      clack.log.warning(
        `Failed to create ${chalk.bold.cyan(
          relativeEnvFilePath,
        )} with environment variables. Please add them manually.`,
      );

      return {
        relativeEnvFilePath,
        addedEnvVariables,
        addedGitignore,
      };
    }
  }

  const gitignorePath = getDotGitignore({ installDir });

  const envFileName = path.basename(targetEnvFilePath);

  const envFiles = [envFileName];

  if (gitignorePath) {
    const gitignoreContent = fs.readFileSync(gitignorePath, 'utf8');
    const missingEnvFiles = envFiles.filter(
      (file) => !gitignoreContent.includes(file),
    );

    if (missingEnvFiles.length > 0) {
      try {
        const newGitignoreContent = `${gitignoreContent}\n${missingEnvFiles.join(
          '\n',
        )}`;
        await fs.promises.writeFile(gitignorePath, newGitignoreContent, {
          encoding: 'utf8',
          flag: 'w',
        });
        clack.log.success(
          `Updated ${chalk.bold.cyan(
            '.gitignore',
          )} to include ${chalk.bold.cyan(envFileName)}.`,
        );
        addedGitignore = true;
      } catch {
        clack.log.warning(
          `Failed to update ${chalk.bold.cyan(
            '.gitignore',
          )} to include ${chalk.bold.cyan(envFileName)}.`,
        );

        return {
          relativeEnvFilePath,
          addedEnvVariables,
          addedGitignore,
        };
      }
    }
  } else {
    try {
      const newGitignoreContent = `${envFiles.join('\n')}\n`;
      await fs.promises.writeFile(
        path.join(installDir, '.gitignore'),
        newGitignoreContent,
        {
          encoding: 'utf8',
          flag: 'w',
        },
      );
      clack.log.success(
        `Created ${chalk.bold.cyan('.gitignore')} with environment files.`,
      );
      addedGitignore = true;
    } catch {
      clack.log.warning(
        `Failed to create ${chalk.bold.cyan(
          '.gitignore',
        )} with environment files.`,
      );

      return {
        relativeEnvFilePath,
        addedEnvVariables,
        addedGitignore,
      };
    }
  }

  return {
    relativeEnvFilePath,
    addedEnvVariables,
    addedGitignore,
  };
}
