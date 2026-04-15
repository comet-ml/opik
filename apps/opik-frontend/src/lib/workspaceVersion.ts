import { WorkspaceVersion } from "@/store/AppStore";

export const DEFAULT_WORKSPACE_VERSION: WorkspaceVersion = "v1";

const OPIK_VERSION_OVERRIDE_KEY = "opik-version-override";

// Path segments (directly under basepath) that are only valid in V2.
// Add new V2-only top-level routes here — nothing else needs to change.
const V2_ONLY_SEGMENTS: ReadonlySet<string> = new Set(["pair"]);

export function getVersionOverride(): WorkspaceVersion | null {
  const override = localStorage.getItem(OPIK_VERSION_OVERRIDE_KEY);
  return override === "v1" || override === "v2" ? override : null;
}

function getRelativePathSegments(): string[] {
  const basePath = (import.meta.env.VITE_BASE_URL || "/").replace(/\/$/, "");
  const pathname = window.location.pathname;
  const relative = pathname.startsWith(basePath)
    ? pathname.slice(basePath.length)
    : pathname;
  return relative.split("/").filter(Boolean);
}

export function getWorkspaceNameFromPath(): string | null {
  return getRelativePathSegments()[0] || null;
}

// Returns a version that the current path forces regardless of workspace.
// Used by WorkspaceVersionGate to short-circuit V2-only routes (e.g. /pair/*)
// without an API call and without touching localStorage.
//
// The pairing URL from the SDK is always of the form `.../opik/pair/v1`.
// On cloud (VITE_BASE_URL=/opik), the `/opik` prefix is stripped by
// getRelativePathSegments so the first segment is "pair". On OSS
// (VITE_BASE_URL=/), nothing is stripped and "opik" remains as the first
// segment — skip it so detection works in both deployments.
export function getForcedVersionFromPath(): WorkspaceVersion | null {
  const segments = getRelativePathSegments();
  const head = segments[0] === "opik" ? segments[1] : segments[0];
  return head && V2_ONLY_SEGMENTS.has(head) ? "v2" : null;
}

// Resolves the workspace version synchronously when possible. Returns null
// only when the workspace name is in the URL but its version requires an
// API fetch — the one case that needs to fall back to a Loader.
export function resolveSyncWorkspaceVersion(): WorkspaceVersion | null {
  const override = getVersionOverride();
  if (override) return override;
  const forced = getForcedVersionFromPath();
  if (forced) return forced;
  if (!getWorkspaceNameFromPath()) return DEFAULT_WORKSPACE_VERSION;
  return null;
}
