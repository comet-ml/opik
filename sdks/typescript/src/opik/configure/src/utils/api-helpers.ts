/**
 * API helper functions for the Opik wizard
 */

import axios from 'axios';
import { DEFAULT_HOST_URL } from '../lib/constants';

export interface AccountDetails {
  defaultWorkspaceName: string;
}

export const DEFAULT_LOCAL_URL = 'http://localhost:5173/';
export const MAX_URL_VALIDATION_RETRIES = 3;

/**
 * Fetches the default workspace name for the given API key
 * @param apiKey - The Opik API key
 * @param baseUrl - The base URL of the Opik instance (defaults to cloud URL)
 * @returns The default workspace name
 * @throws Error if the request fails or the workspace name is not found
 */
export async function getDefaultWorkspace(
  apiKey: string,
  baseUrl: string = DEFAULT_HOST_URL,
): Promise<string> {
  if (!apiKey || apiKey.trim() === '') {
    throw new Error('API key is required');
  }

  try {
    const accountDetailsUrl = new URL(
      'api/rest/v2/account-details',
      baseUrl,
    ).toString();

    const response = await axios.get<AccountDetails>(accountDetailsUrl, {
      headers: {
        Authorization: apiKey,
        'Content-Type': 'application/json',
      },
    });

    if (!response.data.defaultWorkspaceName) {
      throw new Error('defaultWorkspaceName not found in the response');
    }

    return response.data.defaultWorkspaceName;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const status = error.response?.status || 'unknown';
      const message = error.response?.data || error.message;
      throw new Error(
        `Failed to fetch account details (status ${status}): ${JSON.stringify(
          message,
        )}`,
      );
    }
    if (error instanceof Error) {
      throw new Error(`Error fetching default workspace: ${error.message}`);
    }
    throw new Error('Unexpected error while fetching default workspace');
  }
}

/**
 * Validates if a URL is properly formatted
 * @param url - The URL to validate
 * @returns True if the URL is valid, false otherwise
 */
export function isValidUrlFormat(url: string): boolean {
  if (!url || url.trim() === '') {
    return false;
  }

  // Check if URL starts with http:// or https://
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    return false;
  }

  try {
    new URL(url);
    return true;
  } catch {
    return false;
  }
}

/**
 * Checks if an Opik instance is accessible at the given URL
 * @param url - The URL to check
 * @param timeoutMs - Request timeout in milliseconds (default: 5000)
 * @returns True if Opik is accessible, false otherwise
 */
export async function isOpikAccessible(
  url: string,
  timeoutMs: number = 5000,
): Promise<boolean> {
  try {
    // Normalize URL - ensure it has trailing slash
    const normalizedUrl = url.endsWith('/') ? url : `${url}/`;
    const healthCheckUrl = new URL('health', normalizedUrl).toString();

    const response = await axios.get(healthCheckUrl, {
      timeout: timeoutMs,
      validateStatus: (status) => status >= 200 && status < 500, // Accept 2xx-4xx as valid
    });

    // Consider Opik accessible if we get any response (even 404 means server is running)
    return response.status >= 200 && response.status < 500;
  } catch {
    return false;
  }
}

/**
 * Validates and normalizes an Opik URL
 * @param url - The URL to validate
 * @returns Normalized URL with trailing slash
 * @throws Error if URL format is invalid
 */
export function normalizeOpikUrl(url: string): string {
  if (!isValidUrlFormat(url)) {
    throw new Error(
      'Invalid URL format. The URL should follow a format similar to http://localhost:5173/',
    );
  }

  // Ensure trailing slash
  return url.endsWith('/') ? url : `${url}/`;
}
