import type { TestInfo, TestStatus } from '@playwright/test';
import { loadEnvConfig } from '../../config/env.config';

const FAILURE_STATUSES: ReadonlySet<TestStatus> = new Set(['failed', 'timedOut']);

export function shouldLeaveArtifacts(testInfo: TestInfo): boolean {
  return (
    loadEnvConfig().leaveFailures &&
    testInfo.status !== undefined &&
    FAILURE_STATUSES.has(testInfo.status)
  );
}
