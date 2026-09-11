const LANDING_ROUTE_SUFFIXES = ["/get-started"];

export function isLandingRoute(pathname: string): boolean {
  const normalized = pathname.replace(/\/+$/, "");
  return LANDING_ROUTE_SUFFIXES.some((suffix) => normalized.endsWith(suffix));
}
