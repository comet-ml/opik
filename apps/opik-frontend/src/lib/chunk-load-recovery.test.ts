import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as Sentry from "@sentry/react";

vi.mock("@sentry/react", () => ({ captureException: vi.fn() }));
vi.mock("@/constants/app", () => ({ APP_VERSION: "2.2.109" }));

const LAST_RELOAD_AT_KEY = "opik-chunk-last-reload-at";
const NOW = new Date("2026-10-22T12:00:00Z").getTime();

const preloadError = (): VitePreloadErrorEvent =>
  Object.assign(new Event("vite:preloadError", { cancelable: true }), {
    payload: new Error("Failed to fetch dynamically imported module"),
  });

const serveVersion = (version: string) =>
  vi.fn(async () => new Response(JSON.stringify({ version })));

describe("handlePreloadError", () => {
  let handlePreloadError: typeof import("./chunk-load-recovery").handlePreloadError;
  let store: typeof import("@/store/ChunkReloadStore").default;
  let reload: ReturnType<typeof vi.spyOn>;
  const reloadPending = () => store.getState().reloadPending;

  // Fresh modules per test are a fresh page: no recovery in progress.
  beforeEach(async () => {
    vi.resetModules();
    ({ handlePreloadError } = await import("./chunk-load-recovery"));
    store = (await import("@/store/ChunkReloadStore")).default;
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    reload = vi.spyOn(window.location, "reload").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    sessionStorage.clear();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.mocked(Sentry.captureException).mockClear();
  });

  it("reloads once when the server already serves another build", async () => {
    const fetchMock = serveVersion("2.2.110");
    vi.stubGlobal("fetch", fetchMock);
    const event = preloadError();

    await handlePreloadError(event);

    expect(fetchMock).toHaveBeenCalledWith(
      "/version.json",
      expect.objectContaining({
        cache: "no-store",
        signal: expect.any(AbortSignal),
      }),
    );
    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(false);
    expect(reloadPending()).toBe(true);
    expect(sessionStorage.getItem(LAST_RELOAD_AT_KEY)).toBe(String(NOW));
    expect(Sentry.captureException).not.toHaveBeenCalled();
  });

  it("asks for version.json under the app's base path, as configured for comet.com/opik", async () => {
    vi.stubEnv("VITE_BASE_URL", "/opik");
    const fetchMock = serveVersion("2.2.110");
    vi.stubGlobal("fetch", fetchMock);

    await handlePreloadError(preloadError());

    expect(fetchMock).toHaveBeenCalledWith(
      "/opik/version.json",
      expect.anything(),
    );
  });

  it("reloads as well when the server rolled back to an older build", async () => {
    vi.stubGlobal("fetch", serveVersion("2.2.108"));

    await handlePreloadError(preloadError());

    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("reports and shows the error when the server serves this same build", async () => {
    vi.stubGlobal("fetch", serveVersion("2.2.109"));
    const event = preloadError();

    await handlePreloadError(event);

    expect(reload).not.toHaveBeenCalled();
    expect(reloadPending()).toBe(false);
    expect(Sentry.captureException).toHaveBeenCalledWith(event.payload, {
      tags: { chunkRecovery: "same-build" },
      extra: { servedVersion: "2.2.109" },
    });
  });

  it("shows the error without reloading when the served version is unknown", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );

    await handlePreloadError(preloadError());

    expect(reload).not.toHaveBeenCalled();
    expect(reloadPending()).toBe(false);
    expect(Sentry.captureException).not.toHaveBeenCalled();
  });

  it.each([
    ["an HTML page (SPA fallback)", () => new Response("<!doctype html>")],
    [
      "an error status, even with a readable body",
      () =>
        new Response(JSON.stringify({ version: "2.2.110" }), { status: 503 }),
    ],
    ["a null version", () => new Response(JSON.stringify({ version: null }))],
  ])(
    "treats %s from version.json as an unknown version",
    async (_label, response) => {
      vi.stubGlobal(
        "fetch",
        vi.fn(async () => response()),
      );

      await handlePreloadError(preloadError());

      expect(reload).not.toHaveBeenCalled();
      expect(reloadPending()).toBe(false);
      expect(Sentry.captureException).not.toHaveBeenCalled();
    },
  );

  it("checks once and reloads once when several imports fail together", async () => {
    const fetchMock = serveVersion("2.2.110");
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([
      handlePreloadError(preloadError()),
      handlePreloadError(preloadError()),
      handlePreloadError(preloadError()),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("does not reload again within a minute of the last automatic reload", async () => {
    vi.stubGlobal("fetch", serveVersion("2.2.110"));
    sessionStorage.setItem(LAST_RELOAD_AT_KEY, String(NOW - 30_000));
    const event = preloadError();

    await handlePreloadError(event);

    expect(reload).not.toHaveBeenCalled();
    expect(reloadPending()).toBe(false);
    expect(Sentry.captureException).toHaveBeenCalledWith(event.payload, {
      tags: { chunkRecovery: "throttled" },
      extra: { servedVersion: "2.2.110" },
    });
  });

  it("does not reload again within a minute even when another version answers (mixed pods in a rollout)", async () => {
    vi.stubGlobal("fetch", serveVersion("2.2.108"));
    sessionStorage.setItem(LAST_RELOAD_AT_KEY, String(NOW - 30_000));
    const event = preloadError();

    await handlePreloadError(event);

    expect(reload).not.toHaveBeenCalled();
    expect(reloadPending()).toBe(false);
    expect(Sentry.captureException).toHaveBeenCalledWith(event.payload, {
      tags: { chunkRecovery: "throttled" },
      extra: { servedVersion: "2.2.108" },
    });
  });

  it("retries the reload once a minute has passed, so a finished rollout recovers", async () => {
    vi.stubGlobal("fetch", serveVersion("2.2.110"));
    sessionStorage.setItem(LAST_RELOAD_AT_KEY, String(NOW - 90_000));

    await handlePreloadError(preloadError());

    expect(reload).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(LAST_RELOAD_AT_KEY)).toBe(String(NOW));
  });

  it("does not reload when session storage is unavailable", async () => {
    vi.stubGlobal("fetch", serveVersion("2.2.110"));
    vi.stubGlobal("sessionStorage", {
      getItem: () => {
        throw new DOMException("Access is denied", "SecurityError");
      },
      setItem: vi.fn(),
    });

    await handlePreloadError(preloadError());

    expect(reload).not.toHaveBeenCalled();
    expect(reloadPending()).toBe(false);
  });

  it("stops the pending state when the reload was cancelled", async () => {
    vi.stubGlobal("fetch", serveVersion("2.2.110"));

    await handlePreloadError(preloadError());
    expect(reloadPending()).toBe(true);

    vi.advanceTimersByTime(9_999);
    expect(reloadPending()).toBe(true);
    vi.advanceTimersByTime(1);

    expect(reloadPending()).toBe(false);
  });
});
