const PLACEHOLDER_ORIGIN = "http://localhost";
// Mirrors the compareExperimentsRoute path — the only page that links here.
const EXPERIMENT_ROUTE_PATTERN =
  /^\/[^/]+\/projects\/[^/]+\/experiments\/[^/]+\/compare$/;

// `from` arrives via the URL, so only accept same-origin paths under the app
// basepath that point at an experiments page — anything else would make the
// "Back to experiment" button an open redirect or mislabelled.
export const parseExperimentReturnHref = (
  href: string | null | undefined,
  basepath: string,
): { to: string; searchStr: string } | null => {
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

  const to = url.pathname.slice(base.length);
  if (!EXPERIMENT_ROUTE_PATTERN.test(to)) return null;

  return { to, searchStr: url.search };
};
