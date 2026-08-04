import { WorkspaceVersion } from "@/store/AppStore";
import { buildFullBaseUrl } from "@/lib/utils";

export const DEFAULT_WORKSPACE_VERSION: WorkspaceVersion = "v2";

const OPIK_VERSION_OVERRIDE_KEY = "opik-version-override";
const OPIK_WORKSPACE_VERSIONS_KEY = "opik-workspace-versions";
const OPIK_NEW_EXPERIENCE_OPT_IN_KEY = "opik-new-experience-opt-in";

export function getVersionOverride(): WorkspaceVersion | null {
  const override = localStorage.getItem(OPIK_VERSION_OVERRIDE_KEY);
  return override === "v1" || override === "v2" ? override : null;
}

export function getNewExperienceOptIn(): boolean {
  try {
    return localStorage.getItem(OPIK_NEW_EXPERIENCE_OPT_IN_KEY) === "true";
  } catch {
    return false;
  }
}

export function setNewExperienceOptIn(optIn: boolean): void {
  try {
    if (optIn) {
      localStorage.setItem(OPIK_NEW_EXPERIENCE_OPT_IN_KEY, "true");
    } else {
      localStorage.removeItem(OPIK_NEW_EXPERIENCE_OPT_IN_KEY);
    }
  } catch {
    // localStorage unavailable (private mode, quota) — silently skip
  }
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

export function navigateToWorkspaceRoot(workspaceName: string): void {
  const base = buildFullBaseUrl();
  const normalizedBase = base.endsWith("/") ? base : `${base}/`;
  window.location.href = normalizedBase + workspaceName;
}

// Opik V2 is the only supported experience: every workspace resolves to V2
// regardless of any legacy override / opt-in / cached value. This is the
// client-side replacement for the removed backend forceWorkspaceVersion default.
export function resolveSyncWorkspaceVersion(): WorkspaceVersion {
  return DEFAULT_WORKSPACE_VERSION;
}
