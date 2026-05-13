import { logger } from "@/utils/logger";
import type { BasePrompt } from "./BasePrompt";

export const DEFAULT_TTL_SECONDS = 300;
const MIN_REFRESH_INTERVAL_SECONDS = 1.0;
const MAX_CACHE_SIZE = 128;
const MAX_CONCURRENT_REFRESHES = 100;

function intEnv(name: string, defaultValue: number, minimum = 1): number {
  const raw = process.env[name];
  if (raw === undefined) return defaultValue;
  const parsed = parseInt(raw, 10);
  if (isNaN(parsed)) return defaultValue;
  return Math.max(parsed, minimum);
}

const PROMPT_CACHE_TTL_SECONDS = intEnv(
  "OPIK_PROMPT_CACHE_TTL_SECONDS",
  DEFAULT_TTL_SECONDS
);

type RefreshCallback = () => Promise<BasePrompt | null>;

interface CachedPrompt {
  prompt: BasePrompt;
  pinned: boolean;
  ttlSeconds: number;
  lastFetch: number;
  refreshCallback?: RefreshCallback;

  get isStale(): boolean;
}

function makeCachedPrompt(
  prompt: BasePrompt,
  pinned: boolean,
  ttlSeconds: number,
  refreshCallback?: RefreshCallback
): CachedPrompt {
  return {
    prompt,
    pinned,
    ttlSeconds,
    lastFetch: Date.now(),
    refreshCallback,
    get isStale() {
      if (this.pinned) return false;
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
    pinned: boolean
  ): Promise<BasePrompt | null> {
    const existing = this.entries.get(key);
    if (existing) {
      this.entries.delete(key);
      this.entries.set(key, existing);
      return existing.prompt;
    }

    const pending = this.inflight.get(key);
    if (pending) return pending;

    const promise = this.fetchAndCache(key, fetchFn, pinned);
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
    pinned: boolean
  ): Promise<BasePrompt | null> {
    const prompt = await fetchFn();
    if (prompt === null) return null;

    this.entries.set(
      key,
      makeCachedPrompt(
        prompt,
        pinned,
        PROMPT_CACHE_TTL_SECONDS,
        pinned ? undefined : fetchFn,
      ),
    );
    this.evict();

    if (!pinned) {
      this.ensureRefreshTimerStarted();
    }

    return prompt;
  }

  clear(): void {
    this.stopRefreshTimer();
    this.entries.clear();
  }

  private evict(): void {
    while (this.entries.size > this.maxSize) {
      const firstKey = this.entries.keys().next().value;
      if (firstKey !== undefined) {
        this.entries.delete(firstKey);
      }
    }
  }

  private ensureRefreshTimerStarted(): void {
    if (this.stopped || this.refreshTimer !== null) return;

    const intervalMs =
      Math.max(PROMPT_CACHE_TTL_SECONDS, MIN_REFRESH_INTERVAL_SECONDS) * 1000;

    this.refreshTimer = setInterval(() => {
      void this.refreshStaleEntries();
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

        for (const result of results) {
          if (result.status === "rejected") {
            logger.debug("Background prompt cache refresh failed");
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
  templateStructure: string
): string {
  return `${name}|${commit ?? ""}|${projectName ?? ""}|${templateStructure}`;
}

export async function getOrFetch<T extends BasePrompt>(
  name: string,
  commit: string | undefined,
  projectName: string | undefined,
  templateStructure: string,
  fetchFn: () => Promise<T | null>
): Promise<T | null> {
  const key = buildCacheKey(name, commit, projectName, templateStructure);
  const result = await globalCache.getOrFetch(key, fetchFn, commit != null);
  return result as T | null;
}
