import * as fs from 'node:fs/promises';
import * as path from 'node:path';

/**
 * Crash-recovery registry for the disposable Comet accounts
 * `workspace-role-member.fixture.ts` creates via `signUpCometUser`.
 *
 * Those accounts don't carry the `cuj-{runId}-` naming convention the seeded
 * Opik-backend entities use (see `workspace-role-resources.fixture.ts`), and
 * there is no "list users by prefix" superuser API to rediscover them after
 * the fact. So if the process is SIGKILLed or CI-timed-out before the owning
 * fixture's own teardown runs, neither its in-process rollback nor
 * global-teardown (part of the same, possibly-killed process) can react.
 * This file is what survives that: written the moment a user is created,
 * read back by global-setup's next-run orphan sweep.
 */
const REGISTRY_FILE = path.resolve(__dirname, '../../.e2e-workspace-role-pending-users');

export async function registerPendingUser(username: string): Promise<void> {
  try {
    await fs.appendFile(REGISTRY_FILE, `${username}\n`, 'utf-8');
  } catch (err) {
    console.warn(`[pending-users-registry] could not register "${username}" for crash recovery:`, err);
  }
}

/** Removes one username once its account is confirmed deleted — never throws. */
export async function clearPendingUser(username: string): Promise<void> {
  try {
    const raw = await fs.readFile(REGISTRY_FILE, 'utf-8');
    const remaining = raw
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line && line !== username);
    if (remaining.length === 0) {
      await fs.rm(REGISTRY_FILE, { force: true });
    } else {
      await fs.writeFile(REGISTRY_FILE, `${remaining.join('\n')}\n`, 'utf-8');
    }
  } catch (err) {
    if ((err as NodeJS.ErrnoException)?.code !== 'ENOENT') {
      console.warn(`[pending-users-registry] could not clear "${username}":`, err);
    }
  }
}

/**
 * Usernames left behind by a run that never reached its own cleanup — empty
 * if the file is absent, or younger than `minAgeMs` (a run still in progress
 * shouldn't have its own pending users swept out from under it).
 */
export async function readStalePendingUsers(minAgeMs: number): Promise<string[]> {
  try {
    const stat = await fs.stat(REGISTRY_FILE);
    if (Date.now() - stat.mtimeMs < minAgeMs) return [];
    const raw = await fs.readFile(REGISTRY_FILE, 'utf-8');
    return raw
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean);
  } catch {
    return [];
  }
}

export async function clearPendingUsersFile(): Promise<void> {
  await fs.rm(REGISTRY_FILE, { force: true }).catch(() => undefined);
}
