import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setupPreloadErrorHandler } from "./preload-error";

describe("setupPreloadErrorHandler", () => {
  const reload = vi.fn();

  beforeEach(() => {
    reload.mockClear();
    window.sessionStorage.clear();
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00Z"));
    vi.stubGlobal("location", { reload });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  const dispatchPreloadError = () => {
    const event = new Event("vite:preloadError", { cancelable: true });
    window.dispatchEvent(event);
    return event;
  };

  it("reloads the page and prevents the default error on a preload failure", () => {
    setupPreloadErrorHandler();

    const event = dispatchPreloadError();

    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);
  });

  it("does not reload again within the guard window to avoid a reload loop", () => {
    setupPreloadErrorHandler();

    dispatchPreloadError();
    expect(reload).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(5 * 1000);
    const event = dispatchPreloadError();

    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(false);
  });

  it("reloads again once the guard window has elapsed", () => {
    setupPreloadErrorHandler();

    dispatchPreloadError();
    expect(reload).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(11 * 1000);
    dispatchPreloadError();

    expect(reload).toHaveBeenCalledTimes(2);
  });

  it("still recovers when sessionStorage access throws", () => {
    const securityError = () => {
      throw new DOMException("blocked", "SecurityError");
    };
    vi.spyOn(window.sessionStorage, "getItem").mockImplementation(
      securityError,
    );
    vi.spyOn(window.sessionStorage, "setItem").mockImplementation(
      securityError,
    );

    setupPreloadErrorHandler();

    const event = dispatchPreloadError();

    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);
  });
});
