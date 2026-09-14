import { test as baseTest } from './bystander.fixture';
import { uuid7, type BackendClient } from '../core/backend';

/**
 * Shared detection for envs that refuse the out-of-window ids some specs exist
 * to seed.
 *
 * `UuidV7TimestampValidator` bounds an ingested id's embedded timestamp to
 * `[now - window, now + window]` and answers 400 when `uuidValidation.enabled=true`
 * and `auditOnly=false`. It ships disabled, so a default install seeds fine, but
 * the mode is not readable from the client — it has to be detected from a write.
 */
export const UUID_VALIDATION_SKIP_REASON =
  'this env runs UUID timestamp validation in reject mode (UUID_VALIDATION_ENABLED=true, ' +
  'auditOnly=false), which refuses the out-of-window ids these specs seed — set auditOnly=true ' +
  'or disable validation to run them';

/**
 * Matched on the `message` field, not the `too_old` / `too_far_future` reason:
 * the reason lives in the response's `details`, which `rawFetch` drops when it
 * narrows the body to `message`. Verified against a reject-mode backend.
 */
export function isUuidWindowRejection(err: unknown): boolean {
  const message = err instanceof Error ? err.message : String(err);
  return message.includes('Invalid UUID for id');
}

/**
 * Skip the test unless this env accepts an id aged `ageMs` into the past.
 *
 * For seeds that go through the Python SDK bridge rather than REST, because the
 * bridge cannot report this rejection. The SDK batches, so the 400 lands on a
 * background worker that logs and drops it; the driver then fails its own
 * post-flush visibility check and raises "not visible after create + flush" —
 * a 500 that is indistinguishable from a genuine ingestion outage, and so is
 * the one thing a skip must never key on. Probing over REST surfaces the clean
 * 400 instead, which says exactly why.
 *
 * The probe writes one trace and removes it. A delete failure is not fatal: the
 * probe trace carries no spans and no usage, so it cannot move any aggregate the
 * callers assert on.
 */
export async function skipUnlessBackdatedIdsAccepted(
  backendClient: BackendClient,
  projectName: string,
  ageMs: number,
): Promise<void> {
  const id = uuid7(new Date(Date.now() - ageMs));

  try {
    await backendClient.createTraceWithSource({
      id,
      projectName,
      name: `uuid-window-probe-${id.slice(0, 8)}`,
      source: 'sdk',
    });
  } catch (err) {
    if (isUuidWindowRejection(err)) baseTest.skip(true, UUID_VALIDATION_SKIP_REASON);
    throw err;
  }

  try {
    await backendClient.deleteTraces([id]);
  } catch (err) {
    console.warn('[uuid-window-guard] probe trace delete warning:', err);
  }
}
