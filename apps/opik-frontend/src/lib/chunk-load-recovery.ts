import * as Sentry from "@sentry/react";
import { APP_VERSION } from "@/constants/app";
import useChunkReloadStore from "@/store/ChunkReloadStore";

const LAST_RELOAD_AT_KEY = "opik-chunk-last-reload-at";
const VERSION_CHECK_TIMEOUT_MS = 5_000;
const RELOAD_RETRY_AFTER_MS = 60_000;
const RELOAD_CANCELLED_AFTER_MS = 10_000;

const setReloadPending = (reloadPending: boolean) =>
  useChunkReloadStore.setState({ reloadPending });

const fetchServedVersion = async (): Promise<string | undefined> => {
  try {
    const basePath = (import.meta.env.VITE_BASE_URL || "/").replace(/\/$/, "");
    const response = await fetch(`${basePath}/version.json`, {
      cache: "no-store",
      signal: AbortSignal.timeout(VERSION_CHECK_TIMEOUT_MS),
    });
    if (!response.ok) return undefined;
    const { version } = await response.json();
    return typeof version === "string" ? version : undefined;
  } catch {
    return undefined;
  }
};

// The server's version.json tells a stale tab (another build is live, newer or
// rolled back: reload) from a chunk that is really missing (same build: show the
// error). Without an answer (offline, no version.json) nothing reload-worthy is
// known. Automatic reloads are spaced a minute apart whatever version answers, so
// a rollout that serves old and new pages side by side cannot cause a reload loop.
// Without storage there is no such guard, so no reload. The event is not
// prevented, so callers still get the real error. During a rollout an old pod can
// answer with this tab's own version although the chunk only failed on a new pod,
// so Sentry reports are tagged to tell rollout noise from real missing chunks.
export const handlePreloadError = async (event: VitePreloadErrorEvent) => {
  if (useChunkReloadStore.getState().reloadPending) return;
  setReloadPending(true);

  const servedVersion = await fetchServedVersion();
  if (servedVersion === undefined) {
    setReloadPending(false);
    return;
  }
  if (servedVersion === String(APP_VERSION)) {
    Sentry.captureException(event.payload, {
      tags: { chunkRecovery: "same-build" },
      extra: { servedVersion },
    });
    setReloadPending(false);
    return;
  }

  try {
    const lastReloadAt = Number(sessionStorage.getItem(LAST_RELOAD_AT_KEY));
    if (Date.now() - lastReloadAt < RELOAD_RETRY_AFTER_MS) {
      Sentry.captureException(event.payload, {
        tags: { chunkRecovery: "throttled" },
        extra: { servedVersion },
      });
      setReloadPending(false);
      return;
    }
    sessionStorage.setItem(LAST_RELOAD_AT_KEY, String(Date.now()));
  } catch {
    setReloadPending(false);
    return;
  }

  window.location.reload();
  // Still here after this long: the reload was cancelled ("Leave site?").
  setTimeout(() => setReloadPending(false), RELOAD_CANCELLED_AFTER_MS);
};
