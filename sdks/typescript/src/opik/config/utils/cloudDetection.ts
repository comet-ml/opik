/**
 * Determines if an API URL points to a cloud-hosted instance
 * Cloud instances are identified by the comet.com domain
 */
export function isCloud(apiUrl: string): boolean {
  return new URL(apiUrl).hostname.endsWith("comet.com");
}
