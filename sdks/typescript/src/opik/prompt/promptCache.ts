import { logger } from "@/utils/logger";
import type { BasePrompt } from "./BasePrompt";

export const DEFAULT_TTL_SECONDS = 300;
const MIN_REFRESH_INTERVAL_SECONDS = 1.0;
const MAX_CACHE_SIZE = 128;
const MAX_CONCURRENT_REFRESHES = 100;

type RefreshCallback = () => Promise<BasePrompt | null>;

interface CachedPrompt {
  prompt: BasePrompt;
  ttlSeconds: number | null;
  lastFetch: number;
  refreshCallback?: RefreshCallback;

  get isStale(): boolean;
}

function makeCachedPrompt(
  prompt: BasePrompt,
  ttlSeconds: number | null,
  refreshCallback?: RefreshCallback
): CachedPrompt {
  return {
    prompt,
    ttlSeconds,
    lastFetch: Date.now(),
    refreshCallback,
    get isStale() {
      if (this.ttlSeconds === null) return false;
      return Date.now() - this.lastFetch >= this.ttlSeconds * 1000;
    },
  };
}

export class PromptCache {
  private entries = new Map<string, CachedPrompt>();
  private inflight = new Map<string, Promise<BasePrompt | null>>();
  private maxSize: number;
  private refreshTimer: ReturnType<typeof setInterval> | null = null;
  private refreshRunning = false;
  private stopped = false;

  constructor(maxSize = MAX_CACHE_SIZE) {
    this.maxSize = maxSize;
  }

  get(key: string): BasePrompt | null {
    const entry = this.entries.get(key);
    if (!entry) return null;
    // LRU: delete and re-insert to move to end
    this.entries.delete(key);
    this.entries.set(key, entry);
    return entry.prompt;
  }

  async getOrFetch(
    key: string,
    fetchFn: RefreshCallback,
    ttlSeconds: number | null,
    refreshCallback?: RefreshCallback
  ): Promise<BasePrompt | null> {
    const existing = this.entries.get(key);
    if (existing) {
      this.entries.delete(key);
      this.entries.set(key, existing);
      return existing.prompt;
    }

    const pending = this.inflight.get(key);
    if (pending) return pending;

    const promise = this.fetchAndCache(key, fetchFn, ttlSeconds, refreshCallback);
    this.inflight.set(key, promise);
    try {
      return await promise;
    } finally {
      this.inflight.delete(key);
    }
  }

  private async fetchAndCache(
    key: string,
    fetchFn: RefreshCallback,
    ttlSeconds: number | null,
    refreshCallback?: RefreshCallback
  ): Promise<BasePrompt | null> {
    const prompt = await fetchFn();
    if (prompt === null) return null;

    this.entries.set(
      key,
      makeCachedPrompt(
        prompt,
        ttlSeconds,
        refreshCallback,
      ),
    );
    this.evict();

    if (ttlSeconds !== null) {
      this.ensureRefreshTimerStarted(ttlSeconds);
    }

    return prompt;
  }

  evictByIds(ids: string[]): void {
    const idSet = new Set(ids);
    for (const [key, entry] of this.entries) {
      if (entry.prompt.id && idSet.has(entry.prompt.id)) {
        this.entries.delete(key);
      }
    }
  }

  /**
   * Drop every cached entry for the given prompt name + project scope.
   *
   * Used after operations that change the environment-to-version mapping (such
   * as `setPromptEnvironments` or assigning environments during `createPrompt`)
   * so that subsequent `getPrompt({ name, environment })` calls cannot return a
   * stale version.
   */
  invalidateForPrompt(name: string, projectName: string | undefined): void {
    const scope = projectName ?? "";
    for (const key of this.entries.keys()) {
      const [keyName, , keyProject] = JSON.parse(key) as string[];
      if (keyName === name && keyProject === scope) {
        this.entries.delete(key);
      }
    }
  }

  clear(): void {
    this.stopRefreshTimer();
    this.entries.clear();
    this.stopped = false;
  }

  private evict(): void {
    while (this.entries.size > this.maxSize) {
      const firstKey = this.entries.keys().next().value;
      if (firstKey !== undefined) {
        this.entries.delete(firstKey);
      }
    }
  }

  private ensureRefreshTimerStarted(ttlSeconds: number): void {
    if (this.stopped || this.refreshTimer !== null) return;

    const intervalMs =
      Math.max(ttlSeconds, MIN_REFRESH_INTERVAL_SECONDS) * 1000;

    this.refreshTimer = setInterval(() => {
      this.refreshStaleEntries().catch((err) => {
        logger.debug("Prompt cache background refresh loop failed", { error: err });
      });
    }, intervalMs);

    // Don't keep the Node.js process alive just for cache refresh
    if (this.refreshTimer && typeof this.refreshTimer === "object" && "unref" in this.refreshTimer) {
      this.refreshTimer.unref();
    }
  }

  private async refreshStaleEntries(): Promise<void> {
    if (this.refreshRunning) return;
    this.refreshRunning = true;
    try {
      const stale = Array.from(this.entries.values()).filter(
        (entry) => entry.isStale && entry.refreshCallback
      );

      for (let i = 0; i < stale.length; i += MAX_CONCURRENT_REFRESHES) {
        if (this.stopped) break;
        const batch = stale.slice(i, i + MAX_CONCURRENT_REFRESHES);
        const results = await Promise.allSettled(
          batch.map(async (entry) => {
            const newPrompt = await entry.refreshCallback!();
            if (newPrompt !== null) {
              entry.prompt = newPrompt;
              entry.lastFetch = Date.now();
            }
          })
        );

        for (let j = 0; j < results.length; j++) {
          const result = results[j];
          if (result.status === "rejected") {
            logger.debug("Background prompt cache refresh failed", {
              promptName: batch[j].prompt.name,
              error: result.reason,
            });
          }
        }
      }
    } finally {
      this.refreshRunning = false;
    }
  }

  private stopRefreshTimer(): void {
    this.stopped = true;
    if (this.refreshTimer !== null) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }
}

const globalCache = new PromptCache();

export function getGlobalCache(): PromptCache {
  return globalCache;
}

export function buildCacheKey(
  name: string,
  commit: string | undefined,
  projectName: string | undefined,
  templateStructure: string,
  maskId?: string | null,
  version?: string,
  environment?: string,
): string {
  // version and commit can never collide (commits are 8 hex chars, versions are "v<N>")
  // so we reuse the same slot in the key.
  const pin = version ?? commit ?? "";
  return JSON.stringify([name, pin, projectName ?? "", templateStructure, environment ?? "", maskId ?? ""]);
}

export async function getOrFetch<T extends BasePrompt>(
  name: string,
  commit: string | undefined,
  projectName: string | undefined,
  templateStructure: string,
  fetchFn: () => Promise<T | null>,
  ttlSeconds?: number,
  maskId?: string | null,
  version?: string,
  environment?: string,
): Promise<T | null> {
  // Only commit pins indefinitely. A sequential version like "v3" can be
  // reassigned by the backend if the underlying version is deleted and
  // recreated, so it follows the normal TTL refresh.
  const resolvedTtl = commit != null ? null : (ttlSeconds ?? DEFAULT_TTL_SECONDS);
  const refreshCallback = resolvedTtl !== null && !maskId ? fetchFn : undefined;
  const key = buildCacheKey(name, commit, projectName, templateStructure, maskId, version, environment);
  const result = await globalCache.getOrFetch(key, fetchFn, resolvedTtl, refreshCallback);
  return result as T | null;
}
