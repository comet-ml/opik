import clack from '../utils/clack';
import {
  getPackageDotJson,
  getUncommittedOrUntrackedFiles,
  isInGitRepo,
} from '../utils/clack-utils';
import { hasPackageInstalled } from '../utils/package-json';
import type { WizardOptions } from '../utils/types';
import * as childProcess from 'node:child_process';

export async function runPrettierStep({
  installDir,
}: Pick<WizardOptions, 'installDir'> & {}): Promise<void> {
  if (!isInGitRepo()) {
    // We only run formatting on changed files. If we're not in a git repo, we can't find
    // changed files. So let's early-return without showing any formatting-related messages.
    return;
  }

  const changedOrUntrackedFiles = getUncommittedOrUntrackedFiles()
    .map((filename: string) => {
      return filename.startsWith('- ') ? filename.slice(2) : filename;
    })
    .join(' ');

  if (!changedOrUntrackedFiles.length) {
    // Likewise, if we can't find changed or untracked files, there's no point in running Prettier.
    return;
  }

  const packageJson = await getPackageDotJson({ installDir });
  const prettierInstalled = hasPackageInstalled('prettier', packageJson);

  if (!prettierInstalled) {
    return;
  }

  const prettierSpinner = clack.spinner();
  prettierSpinner.start('Running Prettier on your files.');

  try {
    await new Promise<void>((resolve, reject) => {
      childProcess.exec(
        `npx prettier --ignore-unknown --write ${changedOrUntrackedFiles}`,
        (err) => {
          if (err) {
            reject(err);
          } else {
            resolve();
          }
        },
      );
    });
  } catch {
    prettierSpinner.stop(
      'Prettier failed to run. You may want to format the changes manually.',
    );
    return;
  }

  prettierSpinner.stop('Prettier has formatted your files.');
}
