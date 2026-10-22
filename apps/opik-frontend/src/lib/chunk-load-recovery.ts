import * as Sentry from "@sentry/react";
import { APP_VERSION } from "@/constants/app";

const RELOADED_VERSION_KEY = "opik-chunk-reloaded-version";
const RELOAD_WINDOW_MS = 10_000;
const SAME_BUILD_GUARD_MS = 5 * 60_000;

let reloadStartedAt = 0;

// Time-bounded because the reload can be cancelled (a "Leave site?" prompt),
// and the page must not keep treating every later error as a pending reload.
export const reloadWindowRemainingMs = () =>
  Math.max(0, RELOAD_WINDOW_MS - (Date.now() - reloadStartedAt));

// A tab opened before a release asks for chunk hashes the release removed, and
// reloading picks up the new build. If the same build fails again soon after its
// reload, the chunk is really missing, so the error is shown instead of reloading
// in a loop; an older reload of the same build (say, after a network blip) does
// not block recovering from a later release. Failures while this page's reload is
// pending (several imports failing together) are ignored. Without storage there
// is no loop guard, so no reload either. The event is not prevented, so callers
// still get the real error.
export const handlePreloadError = (event: VitePreloadErrorEvent) => {
  if (reloadWindowRemainingMs() > 0) return;

  const version = String(APP_VERSION);
  let alreadyReloaded: boolean;
  try {
    const [reloadedVersion, reloadedAt] = (
      sessionStorage.getItem(RELOADED_VERSION_KEY) ?? ""
    ).split("|");
    alreadyReloaded =
      reloadedVersion === version &&
      Date.now() - Number(reloadedAt) < SAME_BUILD_GUARD_MS;
    if (!alreadyReloaded) {
      sessionStorage.setItem(RELOADED_VERSION_KEY, `${version}|${Date.now()}`);
    }
  } catch {
    return;
  }

  if (alreadyReloaded) {
    Sentry.captureException(event.payload);
    return;
  }

  reloadStartedAt = Date.now();
  window.location.reload();
};
