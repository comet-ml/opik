import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { test as baseTest } from './project.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface ScratchDir {
  path: string;
  clone(sourceDir: string): Promise<void>;
}

export interface ScratchDirFixtures {
  scratchDir: ScratchDir;
}

const E2E_DIR = path.resolve(__dirname, '..');

export const test = baseTest.extend<ScratchDirFixtures>({
  scratchDir: async ({ envConfig, testNamespace }, use, testInfo) => {
    const slugOnly = testNamespace.replace(
      `${envConfig.cujPrefix}-w${testInfo.workerIndex}-`,
      '',
    );
    const dir = path.resolve(
      E2E_DIR,
      envConfig.scratchRoot,
      envConfig.runId,
      `w${testInfo.workerIndex}`,
      slugOnly,
    );
    await fs.mkdir(dir, { recursive: true });

    const scratch: ScratchDir = {
      path: dir,
      async clone(sourceDir) {
        await fs.cp(sourceDir, dir, { recursive: true });
      },
    };

    await use(scratch);

    if (!shouldLeaveArtifacts(testInfo)) {
      await fs.rm(dir, { recursive: true, force: true });
    } else {
      await testInfo.attach('opik.scratch-dir', {
        body: `scratch dir preserved at: ${dir}`,
        contentType: 'text/plain',
      });
    }
  },
});

export { expect } from './base.fixture';
