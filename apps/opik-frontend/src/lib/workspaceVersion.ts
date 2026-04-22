import { WorkspaceVersion } from "@/store/AppStore";

export const DEFAULT_WORKSPACE_VERSION: WorkspaceVersion = "v1";

const OPIK_VERSION_OVERRIDE_KEY = "opik-version-override";
const OPIK_NEW_EXPERIENCE_OPT_IN_KEY = "opik-new-experience-opt-in";

export function getVersionOverride(): WorkspaceVersion | null {
  const override = localStorage.getItem(OPIK_VERSION_OVERRIDE_KEY);
  return override === "v1" || override === "v2" ? override : null;
}

export function setVersionOverride(version: WorkspaceVersion): void {
  localStorage.setItem(OPIK_VERSION_OVERRIDE_KEY, version);
}

export function clearVersionOverride(): void {
  localStorage.removeItem(OPIK_VERSION_OVERRIDE_KEY);
}

export function getNewExperienceOptIn(): boolean {
  return localStorage.getItem(OPIK_NEW_EXPERIENCE_OPT_IN_KEY) === "true";
}

export function setNewExperienceOptIn(optIn: boolean): void {
  if (optIn) {
    localStorage.setItem(OPIK_NEW_EXPERIENCE_OPT_IN_KEY, "true");
  } else {
    localStorage.removeItem(OPIK_NEW_EXPERIENCE_OPT_IN_KEY);
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

// URL routes differ between v1 and v2, so after switching versions we can't
// keep the user on the current path — send them to the workspace root and
// let the active app re-route them to its home page.
export function getWorkspaceHomeUrl(workspaceName: string): string {
  const basePath = (import.meta.env.VITE_BASE_URL || "/").replace(/\/$/, "");
  return `${basePath}/${workspaceName}`;
}

// Returns null only when the workspace is in the URL but needs an API fetch
// to resolve its version — the one case that requires a Loader fallback.
export function resolveSyncWorkspaceVersion(): WorkspaceVersion | null {
  const override = getVersionOverride();
  if (override) return override;
  if (getNewExperienceOptIn()) return "v2";
  if (!getWorkspaceNameFromUrl()) return DEFAULT_WORKSPACE_VERSION;
  return null;
}
