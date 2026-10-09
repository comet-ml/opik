import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { searchAndWaitForDone } from "@/utils/searchHelpers";

describe("searchAndWaitForDone", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("returns immediately once the condition is satisfied (unchanged behavior)", async () => {
    const searchFn = vi.fn().mockResolvedValue([1, 2, 3]);
    const result = await searchAndWaitForDone(searchFn, 2, 100, 5000);
    expect(result).toEqual([1, 2, 3]);
    expect(searchFn).toHaveBeenCalledTimes(1);
  });

  it("never sleeps past the caller-provided timeout when the poll interval is longer (regression for #7936)", async () => {
    // waitForTimeout=100ms but sleepTime=5000ms: the old code slept the full
    // 5s before re-checking, so a short timeout waited far longer than requested.
    const searchFn = vi.fn().mockResolvedValue([]);
    const promise = searchAndWaitForDone(searchFn, 1, 100, 5000);

    // The capped sleep resolves at the timeout budget (100ms), not the poll interval.
    await vi.advanceTimersByTimeAsync(100);

    await expect(promise).resolves.toEqual([]);
    // initial poll + one re-check after the budget was exhausted
    expect(searchFn).toHaveBeenCalledTimes(2);
  });

  it("re-polls with the full interval while the budget remains, and caps only the final sleep", async () => {
    const searchFn = vi.fn().mockResolvedValue([]);
    const promise = searchAndWaitForDone(searchFn, 1, 1600, 500);

    await vi.advanceTimersByTimeAsync(1600);

    await expect(promise).resolves.toEqual([]);
    // sleeps 500 + 500 + 500 + capped(100) = 1600 -> 5 polls
    expect(searchFn).toHaveBeenCalledTimes(5);
  });
});
