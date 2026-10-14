const PLACEHOLDER_ORIGIN = "http://localhost";
export const COMPARE_EXPERIMENTS_ROUTE =
  "/$workspaceName/projects/$projectId/experiments/$datasetId/compare";
// Mirrors COMPARE_EXPERIMENTS_ROUTE — the only page that links here.
const COMPARE_EXPERIMENTS_ROUTE_PATTERN =
  /^\/([^/]+)\/projects\/([^/]+)\/experiments\/([^/]+)\/compare$/;

export type ExperimentReturn = {
  params: { workspaceName: string; projectId: string; datasetId: string };
  searchStr: string;
};

// `from` arrives via the URL, so only accept same-origin paths under the app
// basepath that point at an experiments page — anything else would make the
// "Back to experiment" button an open redirect or mislabelled.
// Returns route params rather than a literal path: TanStack Router strips the
// basepath from a literal `to` by string prefix, which mangles workspaces whose
// name starts with the basepath (e.g. "opik-demo" under "/opik").
export const parseExperimentReturnHref = (
  href: string | null | undefined,
  basepath: string,
): ExperimentReturn | null => {
  if (!href || !href.startsWith("/")) return null;

  let url: URL;
  try {
    url = new URL(href, PLACEHOLDER_ORIGIN);
  } catch {
    return null;
  }
  if (url.origin !== PLACEHOLDER_ORIGIN) return null;

  const base = basepath === "/" ? "" : basepath.replace(/\/$/, "");
  if (base && !url.pathname.startsWith(`${base}/`)) return null;

  const match = url.pathname
    .slice(base.length)
    .match(COMPARE_EXPERIMENTS_ROUTE_PATTERN);
  if (!match) return null;

  try {
    const [workspaceName, projectId, datasetId] = match
      .slice(1)
      .map(decodeURIComponent);
    return {
      params: { workspaceName, projectId, datasetId },
      searchStr: url.search,
    };
  } catch {
    return null;
  }
};
