// Whitelist of routes where post-signup landing must paint without
// waiting for non-critical auth or permission data. Only /get-started
// is whitelisted — /home keeps the full guarded path.
const LANDING_ROUTE_SUFFIXES = ["/get-started"];

export function isLandingRoute(pathname: string): boolean {
  const normalized = pathname.replace(/\/+$/, "");
  return LANDING_ROUTE_SUFFIXES.some((suffix) => normalized.endsWith(suffix));
}
