const RELOAD_GUARD_KEY = "opik-preload-error-reloaded-at";

// Avoid an infinite reload loop: if reloading didn't resolve the failed import
// within this window, stop reloading and let the error surface instead.
const RELOAD_GUARD_WINDOW = 10 * 1000;

/**
 * Handles Vite's `vite:preloadError` event, which fires when a lazily-imported
 * chunk fails to load. The most common cause in production is a new deploy:
 * the content-hashed chunk a long-lived tab is referencing (e.g.
 * `PlaygroundPage-CuYvVjy3.js`) gets removed from the server, so navigating to
 * a not-yet-loaded route requests a file that no longer exists and the import
 * rejects.
 *
 * Reloading the page fetches a fresh `index.html` with the current asset hashes
 * and transparently recovers, so the user never sees the generic error screen.
 *
 * A short time-based guard prevents a reload loop in the rare case where the
 * chunk is genuinely unreachable for another reason (offline, server outage).
 */
export const setupPreloadErrorHandler = () => {
  window.addEventListener("vite:preloadError", (event) => {
    const now = Date.now();
    const lastReloadAt = Number(
      window.sessionStorage.getItem(RELOAD_GUARD_KEY) ?? 0,
    );

    if (now - lastReloadAt < RELOAD_GUARD_WINDOW) {
      // We already reloaded very recently and still hit a preload error — stop
      // reloading and let the error propagate to the error boundary.
      return;
    }

    // Prevent Vite from throwing the error so the page can recover quietly.
    event.preventDefault();

    window.sessionStorage.setItem(RELOAD_GUARD_KEY, String(now));
    window.location.reload();
  });
};
