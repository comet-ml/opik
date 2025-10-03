import { IS_DEV } from '../lib/constants';

export const getCloudUrl = () => {
  if (IS_DEV) {
    return 'http://localhost:5173';
  }

  return 'https://www.comet.com/';
};

/**
 * Builds the complete Opik API URL from a host override
 * @param host - The base host URL (e.g., "http://localhost:5173" or "https://example.com")
 * @returns The complete API URL with /api for local or /opik/api for cloud/self-hosted
 *
 * @example
 * buildOpikApiUrl("http://localhost:5173") // returns "http://localhost:5173/api"
 * buildOpikApiUrl("http://localhost:5173/") // returns "http://localhost:5173/api"
 * buildOpikApiUrl("https://dev.comet.com") // returns "https://dev.comet.com/opik/api"
 */
export const buildOpikApiUrl = (host: string): string => {
  // Remove trailing slash if present
  const normalizedHost = host.endsWith('/') ? host.slice(0, -1) : host;

  // For local deployment (localhost), use /api
  // For cloud/self-hosted, use /opik/api
  const isLocalHost =
    normalizedHost.includes('localhost') ||
    normalizedHost.includes('127.0.0.1');
  const apiPath = isLocalHost ? '/api' : '/opik/api';

  return `${normalizedHost}${apiPath}`;
};
