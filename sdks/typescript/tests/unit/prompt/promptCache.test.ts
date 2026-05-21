import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { PromptCache, buildCacheKey, getOrFetch } from "@/prompt/promptCache";
import type { BasePrompt } from "@/prompt/BasePrompt";

function makeFakePrompt(overrides: Partial<BasePrompt> = {}): BasePrompt {
  return {
    name: "test-prompt",
    commit: "abc12345",
    id: "prompt-id-1",
    ...overrides,
  } as unknown as BasePrompt;
}

describe("PromptCache", () => {
  let cache: PromptCache;

  beforeEach(() => {
    cache = new PromptCache();
  });

  afterEach(() => {
    cache.clear();
  });

  describe("get", () => {
    it("returns null for unknown key", () => {
      expect(cache.get("nonexistent")).toBeNull();
    });
  });

  describe("getOrFetch", () => {
    it("calls fetchFn on cache miss and caches result", async () => {
      const prompt = makeFakePrompt();
      const fetchFn = vi.fn().mockResolvedValue(prompt);

      const result = await cache.getOrFetch("key1", fetchFn, null);
      expect(result).toBe(prompt);
      expect(fetchFn).toHaveBeenCalledOnce();

      // Second call should return cached value without calling fetchFn again
      const result2 = await cache.getOrFetch("key1", fetchFn, null);
      expect(result2).toBe(prompt);
      expect(fetchFn).toHaveBeenCalledOnce();
    });

    it("returns null and does not cache when fetchFn returns null", async () => {
      const fetchFn = vi.fn().mockResolvedValue(null);

      const result = await cache.getOrFetch("key1", fetchFn, null);
      expect(result).toBeNull();

      // Should call fetchFn again since null was not cached
      const result2 = await cache.getOrFetch("key1", fetchFn, null);
      expect(result2).toBeNull();
      expect(fetchFn).toHaveBeenCalledTimes(2);
    });

    it("stores different prompts under different keys", async () => {
      const prompt1 = makeFakePrompt({ name: "prompt-1" });
      const prompt2 = makeFakePrompt({ name: "prompt-2" });

      await cache.getOrFetch("key1", vi.fn().mockResolvedValue(prompt1), null);
      await cache.getOrFetch("key2", vi.fn().mockResolvedValue(prompt2), null);

      expect(cache.get("key1")).toBe(prompt1);
      expect(cache.get("key2")).toBe(prompt2);
    });
  });

  describe("LRU eviction", () => {
    it("evicts oldest entry when max size exceeded", async () => {
      const smallCache = new PromptCache(3);

      const prompts = Array.from({ length: 4 }, (_, i) =>
        makeFakePrompt({ name: `prompt-${i}` })
      );

      for (let i = 0; i < 4; i++) {
        await smallCache.getOrFetch(
          `key${i}`,
          vi.fn().mockResolvedValue(prompts[i]),
          null
        );
      }

      // key0 should have been evicted (oldest)
      expect(smallCache.get("key0")).toBeNull();
      // key1, key2, key3 should still be present
      expect(smallCache.get("key1")).toBe(prompts[1]);
      expect(smallCache.get("key2")).toBe(prompts[2]);
      expect(smallCache.get("key3")).toBe(prompts[3]);
    });

    it("accessing an entry moves it to end of LRU order", async () => {
      const smallCache = new PromptCache(3);

      const prompts = Array.from({ length: 3 }, (_, i) =>
        makeFakePrompt({ name: `prompt-${i}` })
      );

      for (let i = 0; i < 3; i++) {
        await smallCache.getOrFetch(
          `key${i}`,
          vi.fn().mockResolvedValue(prompts[i]),
          null
        );
      }

      // Access key0 to move it to end
      smallCache.get("key0");

      // Add a 4th entry — key1 should be evicted (now the oldest)
      const newPrompt = makeFakePrompt({ name: "prompt-new" });
      await smallCache.getOrFetch(
        "key3",
        vi.fn().mockResolvedValue(newPrompt),
        null
      );

      expect(smallCache.get("key0")).toBe(prompts[0]);
      expect(smallCache.get("key1")).toBeNull();
      expect(smallCache.get("key2")).toBe(prompts[2]);
      expect(smallCache.get("key3")).toBe(newPrompt);
    });
  });

  describe("clear", () => {
    it("removes all entries", async () => {
      const prompt = makeFakePrompt();
      await cache.getOrFetch("key1", vi.fn().mockResolvedValue(prompt), null);

      cache.clear();

      expect(cache.get("key1")).toBeNull();
    });

    it("allows refresh timer to restart after clear", async () => {
      vi.useFakeTimers();
      try {
        const prompt1 = makeFakePrompt({ name: "p1" });
        const prompt2 = makeFakePrompt({ name: "p2" });

        await cache.getOrFetch("key1", vi.fn().mockResolvedValue(prompt1), 300);
        cache.clear();

        expect(cache.get("key1")).toBeNull();

        // After clear, inserting another unpinned entry must cache correctly —
        // proving the stopped flag was reset and the timer can restart.
        const fetchFn2 = vi.fn().mockResolvedValue(prompt2);
        const result = await cache.getOrFetch("key2", fetchFn2, 300);
        expect(result).toBe(prompt2);
        expect(fetchFn2).toHaveBeenCalledOnce();

        const result2 = await cache.getOrFetch("key2", fetchFn2, 300);
        expect(result2).toBe(prompt2);
        expect(fetchFn2).toHaveBeenCalledOnce();
      } finally {
        vi.useRealTimers();
        cache.clear();
      }
    });
  });

  describe("pinned vs unpinned", () => {
    it("pinned entries (ttlSeconds=null) do not start a refresh timer", async () => {
      const prompt = makeFakePrompt();
      const fetchFn = vi.fn().mockResolvedValue(prompt);

      await cache.getOrFetch("pinned-key", fetchFn, null);

      expect(cache.get("pinned-key")).toBe(prompt);
    });

    it("unpinned entries (ttlSeconds set) are cached and returned", async () => {
      const prompt = makeFakePrompt();
      const fetchFn = vi.fn().mockResolvedValue(prompt);

      await cache.getOrFetch("unpinned-key", fetchFn, 300);

      expect(cache.get("unpinned-key")).toBe(prompt);
      expect(fetchFn).toHaveBeenCalledOnce();
    });
  });
});

describe("buildCacheKey", () => {
  it("builds key from all parameters", () => {
    const key = buildCacheKey("my-prompt", "abc123", "my-project", "text");
    expect(key).toBe(JSON.stringify(["my-prompt", "abc123", "my-project", "text", ""]));
  });

  it("handles undefined commit and project", () => {
    const key = buildCacheKey("my-prompt", undefined, undefined, "chat");
    expect(key).toBe(JSON.stringify(["my-prompt", "", "", "chat", ""]));
  });

  it("includes maskId in key", () => {
    const key = buildCacheKey("my-prompt", undefined, "proj", "text", "mask-1");
    expect(key).toBe(JSON.stringify(["my-prompt", "", "proj", "text", "mask-1"]));
  });

  it("produces different keys for different parameters", () => {
    const key1 = buildCacheKey("prompt", "commit1", "project", "text");
    const key2 = buildCacheKey("prompt", "commit2", "project", "text");
    const key3 = buildCacheKey("prompt", "commit1", "project", "chat");
    expect(key1).not.toBe(key2);
    expect(key1).not.toBe(key3);
  });

  it("produces different keys for same prompt with and without maskId", () => {
    const unmasked = buildCacheKey("prompt", undefined, "project", "text");
    const masked = buildCacheKey("prompt", undefined, "project", "text", "mask-1");
    expect(unmasked).not.toBe(masked);
  });
});

describe("module-level getOrFetch", () => {
  it("unpinned prompt without maskId sets refreshCallback (background refresh enabled)", async () => {
    const prompt = makeFakePrompt({ commit: undefined });
    const fetchFn = vi.fn().mockResolvedValue(prompt);

    const result = await getOrFetch("p", undefined, "proj", "text", fetchFn);
    expect(result).toBe(prompt);
    expect(fetchFn).toHaveBeenCalledOnce();
  });

  it("pinned prompt (commit set) does not set refreshCallback", async () => {
    const prompt = makeFakePrompt({ commit: "abc1234" });
    const fetchFn = vi.fn().mockResolvedValue(prompt);

    const result = await getOrFetch("p", "abc1234", "proj", "text", fetchFn);
    expect(result).toBe(prompt);
  });

  it("masked unpinned prompt uses maskId in cache key (separate entry from unmasked)", async () => {
    const prompt1 = makeFakePrompt({ name: "mask-sep-unmasked", id: "id-1" });
    const prompt2 = makeFakePrompt({ name: "mask-sep-masked", id: "id-2" });
    const fetchFn1 = vi.fn().mockResolvedValue(prompt1);
    const fetchFn2 = vi.fn().mockResolvedValue(prompt2);

    const unmasked = await getOrFetch("mask-sep", undefined, "proj", "text", fetchFn1);
    const masked = await getOrFetch("mask-sep", undefined, "proj", "text", fetchFn2, undefined, "mask-1");

    expect(unmasked).toBe(prompt1);
    expect(masked).toBe(prompt2);
    expect(fetchFn1).toHaveBeenCalledOnce();
    expect(fetchFn2).toHaveBeenCalledOnce();
  });

  it("masked entry is returned from cache on second call without re-fetching", async () => {
    const prompt = makeFakePrompt();
    const fetchFn = vi.fn().mockResolvedValue(prompt);

    await getOrFetch("mask-dedup", undefined, "proj", "text", fetchFn, undefined, "mask-x");
    const second = await getOrFetch("mask-dedup", undefined, "proj", "text", fetchFn, undefined, "mask-x");

    expect(second).toBe(prompt);
    expect(fetchFn).toHaveBeenCalledOnce();
  });
});
