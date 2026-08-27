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
 *
 * One JSON record per line, keyed by `target` (the `deleteUserBaseUrl` the
 * account was created against) and a per-record `registeredAt` — not the
 * file's own mtime, which reflects whichever entry was written *last*, not
 * each entry's own age.
 */
const REGISTRY_FILE = path.resolve(__dirname, '../../.e2e-workspace-role-pending-users');

interface PendingUserRecord {
  username: string;
  target: string;
  registeredAt: number;
}

function parseRecord(line: string): PendingUserRecord | null {
  try {
    const record = JSON.parse(line) as Partial<PendingUserRecord>;
    if (typeof record.username === 'string' && typeof record.target === 'string' && typeof record.registeredAt === 'number') {
      return record as PendingUserRecord;
    }
  } catch {
    // Falls through to null below.
  }
  return null;
}

async function readRecords(): Promise<PendingUserRecord[]> {
  const raw = await fs.readFile(REGISTRY_FILE, 'utf-8');
  return raw
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map(parseRecord)
    .filter((r): r is PendingUserRecord => r !== null);
}

/** Writes the whole registry via a temp file + rename, so a crash or a concurrent reader never observes a half-written file. */
async function writeRecords(records: PendingUserRecord[]): Promise<void> {
  if (records.length === 0) {
    await fs.rm(REGISTRY_FILE, { force: true });
    return;
  }
  const tmpFile = `${REGISTRY_FILE}.${process.pid}.${Date.now()}.tmp`;
  const body = `${records.map((r) => JSON.stringify(r)).join('\n')}\n`;
  await fs.writeFile(tmpFile, body, 'utf-8');
  await fs.rename(tmpFile, REGISTRY_FILE);
}

export async function registerPendingUser(username: string, target: string): Promise<void> {
  try {
    const record: PendingUserRecord = { username, target, registeredAt: Date.now() };
    await fs.appendFile(REGISTRY_FILE, `${JSON.stringify(record)}\n`, 'utf-8');
  } catch (err) {
    console.warn(`[pending-users-registry] could not register "${username}" for crash recovery:`, err);
  }
}

/** Removes one username once its account is confirmed deleted — never throws. */
export async function clearPendingUser(username: string): Promise<void> {
  try {
    const records = await readRecords();
    await writeRecords(records.filter((r) => r.username !== username));
  } catch (err) {
    if ((err as NodeJS.ErrnoException)?.code !== 'ENOENT') {
      console.warn(`[pending-users-registry] could not clear "${username}":`, err);
    }
  }
}

/**
 * Records left behind by a run that never reached its own cleanup, scoped to
 * `target` (never touch another environment's accounts even if this run's
 * working directory carries a leftover registry from a different one) and
 * older than `minAgeMs` (a run still in progress shouldn't have its own
 * pending users swept out from under it). Empty if the file is absent or
 * unreadable — a corrupt or inaccessible registry warns rather than failing
 * every subsequent run's global-setup over a housekeeping file.
 */
export async function readStalePendingUsers(minAgeMs: number, target: string): Promise<string[]> {
  let records: PendingUserRecord[];
  try {
    records = await readRecords();
  } catch (err) {
    if ((err as NodeJS.ErrnoException)?.code !== 'ENOENT') {
      console.warn('[pending-users-registry] could not read registry, skipping recovery this run:', err);
    }
    return [];
  }
  const cutoff = Date.now() - minAgeMs;
  return records.filter((r) => r.target === target && r.registeredAt < cutoff).map((r) => r.username);
}
