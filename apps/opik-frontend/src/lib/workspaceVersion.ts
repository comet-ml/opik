import { WorkspaceVersion } from "@/store/AppStore";

export const DEFAULT_WORKSPACE_VERSION: WorkspaceVersion = "v2";

const OPIK_VERSION_OVERRIDE_KEY = "opik-version-override";
const OPIK_WORKSPACE_VERSIONS_KEY = "opik-workspace-versions";

export function getVersionOverride(): WorkspaceVersion | null {
  const override = localStorage.getItem(OPIK_VERSION_OVERRIDE_KEY);
  return override === "v1" || override === "v2" ? override : null;
}

function readVersionMap(): Record<string, WorkspaceVersion> {
  try {
    const raw = localStorage.getItem(OPIK_WORKSPACE_VERSIONS_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

export function getCachedWorkspaceVersion(
  workspaceName: string,
): WorkspaceVersion | null {
  const v = readVersionMap()[workspaceName];
  return v === "v1" || v === "v2" ? v : null;
}

export function setCachedWorkspaceVersion(
  workspaceName: string,
  version: WorkspaceVersion,
): void {
  try {
    const map = readVersionMap();
    if (map[workspaceName] === version) return;
    map[workspaceName] = version;
    localStorage.setItem(OPIK_WORKSPACE_VERSIONS_KEY, JSON.stringify(map));
  } catch {
    // localStorage unavailable (private mode, quota) — silently skip
  }
}

function getRelativePathSegments(): string[] {
  const basePath = (import.meta.env.VITE_BASE_URL || "/").replace(/\/$/, "");
  const pathname = window.location.pathname;
  const relative = pathname.startsWith(basePath)
    ? pathname.slice(basePath.length)
    : pathname;
  return relative.split("/").filter(Boolean);
}

// Pair URLs from the SDK look like `.../pair/v1?workspace=my-ws` — the
// workspace is in the query string, not the path. On OSS (VITE_BASE_URL=/),
// the `/opik` prefix is not stripped by getRelativePathSegments, so skip it
// to detect the "pair" head in both cloud and OSS deployments.
export function getWorkspaceNameFromUrl(): string | null {
  const segments = getRelativePathSegments();
  const head = segments[0] === "opik" ? segments[1] : segments[0];
  if (head === "pair") {
    return new URLSearchParams(window.location.search).get("workspace");
  }
  return segments[0] || null;
}

// Always returns a version synchronously: override > per-workspace cache >
// default. WorkspaceVersionResolver verifies via API after mount and swaps
// the App in place if the guess was wrong.
export function resolveSyncWorkspaceVersion(): WorkspaceVersion {
  const override = getVersionOverride();
  if (override) return override;
  const workspaceName = getWorkspaceNameFromUrl();
  if (!workspaceName) return DEFAULT_WORKSPACE_VERSION;
  return getCachedWorkspaceVersion(workspaceName) ?? DEFAULT_WORKSPACE_VERSION;
}
