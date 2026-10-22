import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as Sentry from "@sentry/react";

vi.mock("@sentry/react", () => ({ captureException: vi.fn() }));
vi.mock("@/constants/app", () => ({ APP_VERSION: "2.2.109" }));

const RELOADED_VERSION_KEY = "opik-chunk-reloaded-version";
const NOW = new Date("2026-10-21T12:00:00Z").getTime();

const preloadError = (): VitePreloadErrorEvent =>
  Object.assign(new Event("vite:preloadError", { cancelable: true }), {
    payload: new Error("Failed to fetch dynamically imported module"),
  });

describe("handlePreloadError", () => {
  let handlePreloadError: typeof import("./chunk-load-recovery").handlePreloadError;
  let reloadWindowRemainingMs: typeof import("./chunk-load-recovery").reloadWindowRemainingMs;
  let reload: ReturnType<typeof vi.spyOn>;

  // A fresh module per test is a fresh page: no reload pending.
  beforeEach(async () => {
    vi.resetModules();
    ({ handlePreloadError, reloadWindowRemainingMs } = await import(
      "./chunk-load-recovery"
    ));
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    reload = vi.spyOn(window.location, "reload").mockImplementation(() => {});
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.mocked(Sentry.captureException).mockClear();
  });

  it("reloads once and lets the original error through when a chunk fails to load", () => {
    const event = preloadError();

    handlePreloadError(event);

    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(false);
    expect(reloadWindowRemainingMs()).toBe(10_000);
    expect(sessionStorage.getItem(RELOADED_VERSION_KEY)).toBe(`2.2.109|${NOW}`);
    expect(Sentry.captureException).not.toHaveBeenCalled();
  });

  it("ignores further failures on the same page while its reload is pending", () => {
    handlePreloadError(preloadError());
    handlePreloadError(preloadError());
    handlePreloadError(preloadError());

    expect(reload).toHaveBeenCalledTimes(1);
    expect(reloadWindowRemainingMs()).toBe(10_000);
    expect(Sentry.captureException).not.toHaveBeenCalled();
  });

  it("reports and shows the error when the same build fails again soon after its reload", () => {
    sessionStorage.setItem(RELOADED_VERSION_KEY, `2.2.109|${NOW - 30_000}`);
    const event = preloadError();

    handlePreloadError(event);

    expect(reload).not.toHaveBeenCalled();
    expect(reloadWindowRemainingMs()).toBe(0);
    expect(Sentry.captureException).toHaveBeenCalledWith(event.payload);
  });

  it("reloads again when the same build's last reload was long ago", () => {
    sessionStorage.setItem(
      RELOADED_VERSION_KEY,
      `2.2.109|${NOW - 10 * 60_000}`,
    );

    handlePreloadError(preloadError());

    expect(reload).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(RELOADED_VERSION_KEY)).toBe(`2.2.109|${NOW}`);
    expect(Sentry.captureException).not.toHaveBeenCalled();
  });

  it("reloads again when a newer release removed the chunk", () => {
    sessionStorage.setItem(RELOADED_VERSION_KEY, `2.2.108|${NOW - 30_000}`);

    handlePreloadError(preloadError());

    expect(reload).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(RELOADED_VERSION_KEY)).toBe(`2.2.109|${NOW}`);
  });

  it("reports a failure after a cancelled reload once the window has passed", () => {
    handlePreloadError(preloadError());
    vi.advanceTimersByTime(4_000);
    expect(reloadWindowRemainingMs()).toBe(6_000);

    vi.advanceTimersByTime(6_000);
    const event = preloadError();
    handlePreloadError(event);

    expect(reloadWindowRemainingMs()).toBe(0);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(Sentry.captureException).toHaveBeenCalledWith(event.payload);
  });

  it("does not reload when session storage is unavailable", () => {
    vi.spyOn(
      Object.getPrototypeOf(sessionStorage),
      "getItem",
    ).mockImplementation(() => {
      throw new DOMException("Access is denied", "SecurityError");
    });

    handlePreloadError(preloadError());

    expect(reload).not.toHaveBeenCalled();
    expect(Sentry.captureException).not.toHaveBeenCalled();
  });
});
