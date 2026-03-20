import { WorkspaceVersion } from "@/store/AppStore";

export const DEFAULT_WORKSPACE_VERSION: WorkspaceVersion = "v1";

const OPIK_VERSION_OVERRIDE_KEY = "opik-version-override";

export function getVersionOverride(): WorkspaceVersion | null {
  const override = localStorage.getItem(OPIK_VERSION_OVERRIDE_KEY);
  return override === "v1" || override === "v2" ? override : null;
}

export function getWorkspaceNameFromPath(): string | null {
  const basePath = (import.meta.env.VITE_BASE_URL || "/").replace(/\/$/, "");
  const pathname = window.location.pathname;
  const relative = pathname.startsWith(basePath)
    ? pathname.slice(basePath.length)
    : pathname;
  const segments = relative.split("/").filter(Boolean);
  return segments[0] || null;
}
