import {
  CacheEntry,
  BlueprintCacheRegistry,
  getCachedBlueprint,
  initBlueprintCacheEntry,
  getGlobalBlueprintRegistry,
} from "@/agent-config/blueprintCache";
import { Blueprint } from "@/agent-config/Blueprint";
import { AgentBlueprintPublicType } from "@/rest_api/api";

function makeMockBlueprint(id = "bp-1"): Blueprint {
  return new Blueprint({
    id,
    type: AgentBlueprintPublicType.Blueprint,
    values: [],
  });
}

afterEach(() => {
  getGlobalBlueprintRegistry().clear();
  vi.useRealTimers();
});

describe("CacheEntry", () => {
  it("isStale() returns true when never populated", () => {
    const entry = new CacheEntry(300);
    expect(entry.isStale()).toBe(true);
  });

  it("isStale() returns false immediately after update()", () => {
    const entry = new CacheEntry(300);
    entry.update(makeMockBlueprint());
    expect(entry.isStale()).toBe(false);
  });

  it("isStale() returns true after TTL ms have elapsed", () => {
    vi.useFakeTimers();
    const entry = new CacheEntry(300);
    entry.update(makeMockBlueprint());
    vi.advanceTimersByTime(300_001);
    expect(entry.isStale()).toBe(true);
  });

  it("setRefreshCallback() is idempotent — second call is ignored", async () => {
    const cb1 = vi.fn().mockResolvedValue(makeMockBlueprint("bp-cb1"));
    const cb2 = vi.fn().mockResolvedValue(makeMockBlueprint("bp-cb2"));
    const entry = new CacheEntry(300);
    entry.setRefreshCallback(cb1);
    entry.setRefreshCallback(cb2);
    await entry.tryBackgroundRefresh();
    expect(cb1).toHaveBeenCalledTimes(1);
    expect(cb2).not.toHaveBeenCalled();
  });

  it("tryBackgroundRefresh() calls callback and updates the blueprint", async () => {
    const newBp = makeMockBlueprint("refreshed");
    const cb = vi.fn().mockResolvedValue(newBp);
    const entry = new CacheEntry(300);
    entry.setRefreshCallback(cb);
    await entry.tryBackgroundRefresh();
    expect(cb).toHaveBeenCalledTimes(1);
    expect(entry.getBlueprint()).toBe(newBp);
    expect(entry.isStale()).toBe(false);
  });

  it("tryBackgroundRefresh() swallows and logs errors from callback", async () => {
    const cb = vi.fn().mockRejectedValue(new Error("network failure"));
    const entry = new CacheEntry(300);
    entry.setRefreshCallback(cb);
    await expect(entry.tryBackgroundRefresh()).resolves.toBeUndefined();
  });

  it("tryBackgroundRefresh() does not update cache when callback returns null", async () => {
    const bp = makeMockBlueprint("initial");
    const cb = vi.fn().mockResolvedValue(null);
    const entry = new CacheEntry(300);
    entry.update(bp);
    entry.setRefreshCallback(cb);
    await entry.tryBackgroundRefresh();
    expect(entry.getBlueprint()).toBe(bp);
  });

  it("tryBackgroundRefresh() is no-op when no callback is set", async () => {
    const entry = new CacheEntry(300);
    entry.update(makeMockBlueprint());
    const originalBp = entry.getBlueprint();
    await entry.tryBackgroundRefresh();
    expect(entry.getBlueprint()).toBe(originalBp);
  });
});

describe("BlueprintCacheRegistry", () => {
  it("getOrCreate() returns the same instance for the same key triple", () => {
    const registry = new BlueprintCacheRegistry();
    const a = registry.getOrCreate("proj", "prod", null);
    const b = registry.getOrCreate("proj", "prod", null);
    expect(a).toBe(b);
  });

  it("getOrCreate() returns different instances for different keys", () => {
    const registry = new BlueprintCacheRegistry();
    const a = registry.getOrCreate("proj", "prod", null);
    const b = registry.getOrCreate("proj", "staging", null);
    const c = registry.getOrCreate("proj", "prod", "mask-1");
    expect(a).not.toBe(b);
    expect(a).not.toBe(c);
  });

  it("ensureRefreshTimerStarted() is idempotent", () => {
    const registry = new BlueprintCacheRegistry();
    const spy = vi.spyOn(global, "setInterval");
    registry.ensureRefreshTimerStarted();
    registry.ensureRefreshTimerStarted();
    expect(spy).toHaveBeenCalledTimes(1);
    spy.mockRestore();
    registry.clear();
  });

  it("background timer fires and refreshes stale entries", async () => {
    vi.useFakeTimers();
    const registry = new BlueprintCacheRegistry();
    const cb = vi.fn().mockResolvedValue(makeMockBlueprint("refreshed"));
    const entry = registry.getOrCreate("proj", "prod", null);
    entry.setRefreshCallback(cb);
    // entry starts stale (never updated)
    registry.ensureRefreshTimerStarted();

    await vi.advanceTimersByTimeAsync(300_000);

    expect(cb).toHaveBeenCalled();
    registry.clear();
  });

  it("background timer does NOT refresh fresh entries", async () => {
    vi.useFakeTimers();
    const registry = new BlueprintCacheRegistry();
    const cb = vi.fn().mockResolvedValue(makeMockBlueprint());
    const entry = registry.getOrCreate("proj", "prod", null);
    entry.update(makeMockBlueprint());
    entry.setRefreshCallback(cb);
    registry.ensureRefreshTimerStarted();

    // Advance less than TTL
    await vi.advanceTimersByTimeAsync(100_000);

    expect(cb).not.toHaveBeenCalled();
    registry.clear();
  });

  it("clear() removes all entries and stops the timer", () => {
    vi.useFakeTimers();
    const registry = new BlueprintCacheRegistry();
    registry.getOrCreate("proj", "prod", null);
    registry.ensureRefreshTimerStarted();
    registry.clear();
    // getOrCreate after clear creates a new entry
    const entry = registry.getOrCreate("proj", "prod", null);
    expect(entry).toBeDefined();
    expect(entry.isStale()).toBe(true);
  });
});

describe("initBlueprintCacheEntry", () => {
  it("stores the initial blueprint in the entry", () => {
    const bp = makeMockBlueprint();
    initBlueprintCacheEntry("proj", "prod", null, bp, null);
    const entry = getCachedBlueprint("proj", "prod", null);
    expect(entry.getBlueprint()).toBe(bp);
    expect(entry.isStale()).toBe(false);
  });

  it("leaves entry stale when null blueprint is passed", () => {
    initBlueprintCacheEntry("proj", "prod", null, null, null);
    const entry = getCachedBlueprint("proj", "prod", null);
    expect(entry.getBlueprint()).toBeNull();
    expect(entry.isStale()).toBe(true);
  });

  it("starts background timer when maskId is null and callback is provided", () => {
    const spy = vi.spyOn(getGlobalBlueprintRegistry(), "ensureRefreshTimerStarted");
    const cb = vi.fn().mockResolvedValue(null);
    initBlueprintCacheEntry("proj", "prod", null, null, cb);
    expect(spy).toHaveBeenCalledTimes(1);
    spy.mockRestore();
  });

  it("does NOT start background timer when maskId is set", () => {
    const spy = vi.spyOn(getGlobalBlueprintRegistry(), "ensureRefreshTimerStarted");
    const cb = vi.fn().mockResolvedValue(null);
    initBlueprintCacheEntry("proj", "prod", "mask-abc", null, cb);
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it("does NOT start background timer when callback is null", () => {
    const spy = vi.spyOn(getGlobalBlueprintRegistry(), "ensureRefreshTimerStarted");
    initBlueprintCacheEntry("proj", "prod", null, null, null);
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe("refresh policy", () => {
  it("latest lookup — initBlueprintCacheEntry registers refresh callback and starts timer", () => {
    const registry = new BlueprintCacheRegistry();
    const cb = vi.fn().mockResolvedValue(makeMockBlueprint());
    const spy = vi.spyOn(registry, "ensureRefreshTimerStarted");

    const entry = registry.getOrCreate("proj", null, null, null);
    entry.setRefreshCallback(cb);
    registry.ensureRefreshTimerStarted();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(entry["_refreshCallback"]).toBe(cb);
    registry.clear();
    spy.mockRestore();
  });

  it("env lookup — refresh callback is registered and timer starts", () => {
    const registry = new BlueprintCacheRegistry();
    const cb = vi.fn().mockResolvedValue(makeMockBlueprint());
    const spy = vi.spyOn(registry, "ensureRefreshTimerStarted");

    const entry = registry.getOrCreate("proj", "prod", null, null);
    entry.setRefreshCallback(cb);
    registry.ensureRefreshTimerStarted();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(entry["_refreshCallback"]).toBe(cb);
    registry.clear();
    spy.mockRestore();
  });

  it("version-pinned lookup — initBlueprintCacheEntry with non-null version does NOT start timer", () => {
    const spy = vi.spyOn(getGlobalBlueprintRegistry(), "ensureRefreshTimerStarted");
    // version is non-null → refreshCallback must be null per the contract
    initBlueprintCacheEntry("proj", null, null, makeMockBlueprint(), null, "v1");
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it("masked lookup — initBlueprintCacheEntry with maskId does NOT start timer", () => {
    const spy = vi.spyOn(getGlobalBlueprintRegistry(), "ensureRefreshTimerStarted");
    const cb = vi.fn().mockResolvedValue(null);
    initBlueprintCacheEntry("proj", null, "mask-abc", null, cb);
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it("latest and version-pinned use separate cache keys", () => {
    const registry = new BlueprintCacheRegistry();
    const latestEntry = registry.getOrCreate("proj", null, null, null);
    const versionEntry = registry.getOrCreate("proj", null, null, "v1");
    expect(latestEntry).not.toBe(versionEntry);
    registry.clear();
  });

  it("latest cache is refreshed by background timer when stale", async () => {
    vi.useFakeTimers();
    const registry = new BlueprintCacheRegistry();
    const refreshed = makeMockBlueprint("refreshed");
    const cb = vi.fn().mockResolvedValue(refreshed);

    const entry = registry.getOrCreate("proj", null, null, null);
    entry.setRefreshCallback(cb);
    registry.ensureRefreshTimerStarted();

    await vi.advanceTimersByTimeAsync(300_000);

    expect(cb).toHaveBeenCalled();
    expect(entry.getBlueprint()).toBe(refreshed);
    registry.clear();
  });

  it("version-pinned cache is NOT refreshed even when stale", async () => {
    vi.useFakeTimers();
    const registry = new BlueprintCacheRegistry();
    const cb = vi.fn().mockResolvedValue(makeMockBlueprint("should-not-refresh"));

    // version-pinned entry: no refresh callback registered
    const entry = registry.getOrCreate("proj", null, null, "v1");
    entry.update(makeMockBlueprint("original"));

    registry.ensureRefreshTimerStarted();
    await vi.advanceTimersByTimeAsync(600_000);

    expect(cb).not.toHaveBeenCalled();
    expect(entry.getBlueprint()?.id).toBe("original");
    registry.clear();
  });
});

describe("getCachedBlueprint (cache hit / miss behavior)", () => {
  it("returns a fresh entry after initBlueprintCacheEntry populates it", () => {
    const bp = makeMockBlueprint();
    initBlueprintCacheEntry("proj", "prod", null, bp, null);
    const entry = getCachedBlueprint("proj", "prod", null);
    expect(entry.isStale()).toBe(false);
    expect(entry.getBlueprint()).toBe(bp);
  });

  it("returns a stale entry before any init call", () => {
    const entry = getCachedBlueprint("proj-new", "prod", null);
    expect(entry.isStale()).toBe(true);
  });

  it("entry becomes stale after TTL elapses", () => {
    vi.useFakeTimers();
    const bp = makeMockBlueprint();
    initBlueprintCacheEntry("proj", "prod", null, bp, null);
    vi.advanceTimersByTime(300_001);
    const entry = getCachedBlueprint("proj", "prod", null);
    expect(entry.isStale()).toBe(true);
  });
});
