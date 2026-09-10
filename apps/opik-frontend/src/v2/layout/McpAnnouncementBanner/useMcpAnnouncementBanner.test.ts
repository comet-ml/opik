import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

import { useMcpAnnouncementBanner } from "./useMcpAnnouncementBanner";

// ── mutable state the mock factories read ──────────────────────────────────
const storage: Record<string, unknown> = {};
// ───────────────────────────────────────────────────────────────────────────

// A working in-memory stand-in, so "dismissal survives a remount" is a real
// assertion rather than a mock echoing itself.
vi.mock("use-local-storage-state", async () => {
  const { useCallback, useState } = await import("react");
  return {
    default: function useLocalStorageStateMock(
      key: string,
      options?: { defaultValue?: unknown },
    ) {
      const [value, setValue] = useState(() =>
        key in storage ? storage[key] : options?.defaultValue,
      );
      const set = useCallback(
        (next: unknown) => {
          const resolved = typeof next === "function" ? next(value) : next;
          storage[key] = resolved;
          setValue(resolved);
        },
        [key, value],
      );
      return [value, set];
    },
  };
});

const INSIDE_WINDOW = "2026-10-01T09:00:00Z";
const LAST_DAY_OF_CAMPAIGN = "2026-11-15T23:30:00Z";
const AFTER_WINDOW = "2026-11-16T00:30:00Z";

beforeEach(() => {
  for (const key of Object.keys(storage)) delete storage[key];
  vi.useFakeTimers();
  vi.setSystemTime(new Date(INSIDE_WINDOW));
});

afterEach(() => {
  vi.useRealTimers();
});

const banner = () => renderHook(() => useMcpAnnouncementBanner()).result;

describe("useMcpAnnouncementBanner", () => {
  describe("campaign window", () => {
    it("is visible inside the campaign window", () => {
      expect(banner().current.visible).toBe(true);
    });

    it("is visible on the last day of the campaign", () => {
      vi.setSystemTime(new Date(LAST_DAY_OF_CAMPAIGN));
      expect(banner().current.visible).toBe(true);
    });

    it("is hidden once the campaign window has passed", () => {
      vi.setSystemTime(new Date(AFTER_WINDOW));
      expect(banner().current.visible).toBe(false);
    });
  });

  describe("dismissal", () => {
    it("hides immediately when dismissed", () => {
      const result = banner();

      act(() => result.current.dismiss());

      expect(result.current.visible).toBe(false);
    });

    it("stays hidden for a freshly mounted banner", () => {
      const first = banner();

      act(() => first.current.dismiss());

      expect(banner().current.visible).toBe(false);
    });
  });
});
