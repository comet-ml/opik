import type { TestInfo } from '@playwright/test';
import { loadEnvConfig } from '../../config/env.config';

export function shouldLeaveArtifacts(testInfo: TestInfo): boolean {
  return loadEnvConfig().leaveFailures && testInfo.status !== 'passed';
}
