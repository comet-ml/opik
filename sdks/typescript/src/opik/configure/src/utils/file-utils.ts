import path from 'path';
import fs from 'fs';
import type { FileChange, WizardOptions } from './types';
import clack from './clack';
import { z } from 'zod';
import { query } from './query';
import fg from 'fast-glob';
import { Integration } from '../lib/constants';
import { abort } from './clack-utils';
import { INTEGRATION_CONFIG } from '../lib/config';
import {
  baseFilterFilesPromptTemplate,
  baseGenerateFileChangesPromptTemplate,
} from '../lib/prompts';

export const GLOBAL_IGNORE_PATTERN = [
  'node_modules',
  'dist',
  'build',
  'public',
  'static',
  '.git',
];
export async function getAllFilesInProject(dir: string): Promise<string[]> {
  let results: string[] = [];

  const entries = await fs.promises.readdir(dir, { withFileTypes: true });

  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);

    if (GLOBAL_IGNORE_PATTERN.some((pattern) => fullPath.includes(pattern))) {
      continue;
    }

    if (entry.isDirectory()) {
      // Recursively get files from subdirectories
      const subDirFiles = await getAllFilesInProject(fullPath);
      results = results.concat(subDirFiles);
    } else {
      results.push(fullPath);
    }
  }

  return results;
}

export function getDotGitignore({
  installDir,
}: Pick<WizardOptions, 'installDir'>) {
  const gitignorePath = path.join(installDir, '.gitignore');
  const gitignoreExists = fs.existsSync(gitignorePath);

  if (gitignoreExists) {
    return gitignorePath;
  }

  return undefined;
}

export async function updateFile(
  change: FileChange,
  { installDir }: Pick<WizardOptions, 'installDir'>,
) {
  const dir = path.dirname(path.join(installDir, change.filePath));
  await fs.promises.mkdir(dir, { recursive: true });
  await fs.promises.writeFile(
    path.join(installDir, change.filePath),
    change.newContent,
  );
}

export async function getFilesToChange({
  integration,
  relevantFiles,
  documentation,
  apiKey,
  workspaceName,
}: {
  integration: Integration;
  relevantFiles: string[];
  documentation: string;
  apiKey: string;
  workspaceName: string;
}) {
  const filterFilesSpinner = clack.spinner();

  filterFilesSpinner.start('Selecting files to change...');

  const filterFilesResponseSchmea = z.object({
    files: z.array(z.string()),
  });

  const prompt = await baseFilterFilesPromptTemplate.format({
    documentation,
    file_list: relevantFiles.join('\n'),
    integration_name: integration,
    integration_rules: INTEGRATION_CONFIG[integration].filterFilesRules,
  });

  const filterFilesResponse = await query({
    message: prompt,
    schema: filterFilesResponseSchmea,
    apiKey,
    workspaceName,
  });

  const filesToChange = filterFilesResponse.files;

  filterFilesSpinner.stop(`Found ${filesToChange.length} files to change`);

  return filesToChange;
}

export async function generateFileContent({
  prompt,
  apiKey,
  workspaceName,
}: {
  prompt: string;
  apiKey: string;
  workspaceName: string;
}) {
  const response = await query({
    message: prompt,
    schema: z.object({
      newContent: z.string(),
    }),
    apiKey,
    workspaceName,
  });

  return response.newContent;
}

export async function generateFileChangesForIntegration({
  integration,
  filesToChange,
  apiKey,
  workspaceName,
  documentation,
  installDir,
}: {
  integration: Integration;
  filesToChange: string[];
  apiKey: string;
  workspaceName: string;
  documentation: string;
  installDir: string;
}) {
  const changes: FileChange[] = [];

  for (const filePath of filesToChange) {
    const fileChangeSpinner = clack.spinner();

    try {
      let oldContent = undefined;
      try {
        oldContent = await fs.promises.readFile(
          path.join(installDir, filePath),
          'utf8',
        );
      } catch (readError: unknown) {
        if (
          readError &&
          typeof readError === 'object' &&
          'code' in readError &&
          readError.code !== 'ENOENT'
        ) {
          await abort(`Error reading file ${filePath}`);
          continue;
        }
      }

      fileChangeSpinner.start(
        `${oldContent ? 'Updating' : 'Creating'} file ${filePath}`,
      );

      const unchangedFiles = filesToChange.filter(
        (filePath) => !changes.some((change) => change.filePath === filePath),
      );

      const prompt = await baseGenerateFileChangesPromptTemplate.format({
        file_content: oldContent,
        file_path: filePath,
        documentation,
        integration_name: INTEGRATION_CONFIG[integration].name,
        integration_rules: INTEGRATION_CONFIG[integration].generateFilesRules,
        changed_files: changes
          .map((change) => `${change.filePath}\n${change.newContent}`)
          .join('\n'),
        unchanged_files: unchangedFiles,
      });

      const newContent = await generateFileContent({
        prompt,
        apiKey,
        workspaceName,
      });

      if (newContent !== oldContent) {
        await updateFile({ filePath, oldContent, newContent }, { installDir });
        changes.push({ filePath, oldContent, newContent });
      }

      fileChangeSpinner.stop(
        `${oldContent ? 'Updated' : 'Created'} file ${filePath}`,
      );
    } catch {
      await abort(`Error processing file ${filePath}`);
    }
  }

  return changes;
}

export async function getRelevantFilesForIntegration({
  installDir,
  integration,
}: Pick<WizardOptions, 'installDir'> & {
  integration: Integration;
}) {
  const filterPatterns = INTEGRATION_CONFIG[integration].filterPatterns;
  const ignorePatterns = INTEGRATION_CONFIG[integration].ignorePatterns;

  const filteredFiles = await fg(filterPatterns, {
    cwd: installDir,
    ignore: ignorePatterns,
  });

  return filteredFiles;
}
