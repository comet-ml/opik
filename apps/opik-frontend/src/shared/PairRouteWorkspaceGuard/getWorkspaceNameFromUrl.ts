const getRelativePathSegments = (): string[] => {
  const basePath = (import.meta.env.VITE_BASE_URL || "/").replace(/\/$/, "");
  const pathname = window.location.pathname;
  const relative = pathname.startsWith(basePath)
    ? pathname.slice(basePath.length)
    : pathname;
  return relative.split("/").filter(Boolean);
};

// Pair URLs from the SDK look like `.../pair/v1?workspace=my-ws` — the
// workspace is in the query string, not the path. On OSS (VITE_BASE_URL=/),
// the `/opik` prefix is not stripped by getRelativePathSegments, so skip it
// to detect the "pair" head in both cloud and OSS deployments.
export const getWorkspaceNameFromUrl = (): string | null => {
  const segments = getRelativePathSegments();
  const head = segments[0] === "opik" ? segments[1] : segments[0];
  if (head === "pair") {
    return new URLSearchParams(window.location.search).get("workspace");
  }
  return segments[0] || null;
};
