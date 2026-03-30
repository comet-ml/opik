import { logger } from "../utils/logger";
import { Blueprint } from "./Blueprint";

const DEFAULT_TTL_SECONDS = 300;
const MIN_REFRESH_INTERVAL_MS = 1000;
const MAX_CONCURRENT_REFRESHES = 100;

type RefreshCallback = () => Promise<Blueprint | null>;

function getTtlSeconds(): number {
  const raw = process.env.OPIK_CONFIG_TTL_SECONDS;
  if (raw !== undefined) {
    const parsed = parseInt(raw, 10);
    if (!isNaN(parsed)) return parsed;
  }
  return DEFAULT_TTL_SECONDS;
}

export class CacheEntry {
  private _blueprint: Blueprint | null = null;
  private _lastFetchMs: number | null = null;
  private _refreshCallback: RefreshCallback | null = null;
  private readonly _ttlMs: number;

  constructor(ttlSeconds: number) {
    this._ttlMs = ttlSeconds * 1000;
  }

  setRefreshCallback(callback: RefreshCallback): void {
    if (this._refreshCallback === null) {
      this._refreshCallback = callback;
    }
  }

  update(blueprint: Blueprint): void {
    this._blueprint = blueprint;
    this._lastFetchMs = Date.now();
  }

  getBlueprint(): Blueprint | null {
    return this._blueprint;
  }

  isStale(): boolean {
    if (this._lastFetchMs === null) return true;
    return Date.now() - this._lastFetchMs >= this._ttlMs;
  }

  async tryBackgroundRefresh(): Promise<void> {
    if (this._refreshCallback === null) return;
    try {
      const bp = await this._refreshCallback();
      if (bp !== null) {
        this.update(bp);
      }
    } catch (err) {
      logger.debug("Background blueprint cache refresh failed", err);
    }
  }
}

export class BlueprintCacheRegistry {
  private readonly _entries = new Map<string, CacheEntry>();
  private _intervalHandle: ReturnType<typeof setInterval> | null = null;
  private _refreshRunning = false;

  getOrCreate(
    projectName: string,
    env: string | null,
    maskId: string | null,
    version: string | null = null
  ): CacheEntry {
    const key = `${projectName}::${env ?? ""}::${maskId ?? ""}::${version ?? ""}`;
    let entry = this._entries.get(key);
    if (!entry) {
      entry = new CacheEntry(getTtlSeconds());
      this._entries.set(key, entry);
    }
    return entry;
  }

  ensureRefreshTimerStarted(): void {
    if (this._intervalHandle !== null) return;
    const intervalMs = Math.max(getTtlSeconds() * 1000, MIN_REFRESH_INTERVAL_MS);
    this._intervalHandle = setInterval(() => {
      void this._refreshAllStale();
    }, intervalMs);
    this._intervalHandle.unref();
  }

  private async _refreshAllStale(): Promise<void> {
    if (this._refreshRunning) return;
    this._refreshRunning = true;
    try {
      const stale = [...this._entries.values()].filter((entry) => entry.isStale());
      for (let i = 0; i < stale.length; i += MAX_CONCURRENT_REFRESHES) {
        await Promise.all(stale.slice(i, i + MAX_CONCURRENT_REFRESHES).map((entry) => entry.tryBackgroundRefresh()));
      }
    } finally {
      this._refreshRunning = false;
    }
  }

  clear(): void {
    if (this._intervalHandle !== null) {
      clearInterval(this._intervalHandle);
      this._intervalHandle = null;
    }
    this._refreshRunning = false;
    this._entries.clear();
  }
}

const _registry = new BlueprintCacheRegistry();

export function getGlobalBlueprintRegistry(): BlueprintCacheRegistry {
  return _registry;
}

export function getCachedBlueprint(
  projectName: string,
  env: string | null,
  maskId: string | null,
  version: string | null = null
): CacheEntry {
  return _registry.getOrCreate(projectName, env, maskId, version);
}

export function initBlueprintCacheEntry(
  projectName: string,
  env: string | null,
  maskId: string | null,
  blueprint: Blueprint | null,
  refreshCallback: RefreshCallback | null,
  version: string | null = null
): void {
  const entry = _registry.getOrCreate(projectName, env, maskId, version);
  if (blueprint !== null) {
    entry.update(blueprint);
  }
  if (refreshCallback !== null && maskId === null) {
    entry.setRefreshCallback(refreshCallback);
    _registry.ensureRefreshTimerStarted();
  }
}
