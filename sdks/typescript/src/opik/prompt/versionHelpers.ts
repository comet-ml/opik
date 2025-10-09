/**
 * Helper utilities for prompt version management and comparison.
 * Used by OpikClient for smart versioning logic.
 */

import type * as OpikApi from "@/rest_api/api";
import type { Prompts } from "@/rest_api/api/resources/prompts/client/Client";
import type { RequestOptions } from "@/types/request";
import fastDeepEqual from "fast-deep-equal";

/**
 * Fetches the latest version of a prompt, returning null if not found (404).
 * Rethrows any non-404 errors.
 *
 * @param api - The REST API prompts client
 * @param name - The prompt name
 * @param requestOptions - Optional request configuration
 * @returns Promise resolving to the latest version or null if not found
 */
export async function fetchLatestPromptVersion(
  api: Prompts,
  name: string,
  requestOptions?: RequestOptions
): Promise<OpikApi.PromptVersionDetail | null> {
  try {
    return await api.retrievePromptVersion({ name }, requestOptions);
  } catch (error) {
    // 404 means prompt doesn't exist yet (first-time creation)
    if (isNotFoundError(error)) {
      return null;
    }
    throw error;
  }
}

/**
 * Determines if a new version should be created based on smart versioning logic.
 * Compares template, metadata (deep equality), and type.
 *
 * @param options - The prompt options being created
 * @param latestVersion - The existing latest version (or null if none exists)
 * @param normalizedType - The normalized prompt type
 * @returns true if a new version should be created
 */
export function shouldCreateNewVersion(
  options: {
    prompt: string;
    metadata?: Record<string, unknown>;
  },
  latestVersion: OpikApi.PromptVersionDetail | null,
  normalizedType: OpikApi.PromptType
): boolean {
  // Always create if no existing version
  if (!latestVersion) {
    return true;
  }

  // Check if any content differs
  return (
    latestVersion.template !== options.prompt ||
    !isMetadataEqual(latestVersion.metadata, options.metadata) ||
    latestVersion.type !== normalizedType
  );
}

/**
 * Deep equality check for metadata objects.
 * Handles undefined and null as equivalent to empty objects.
 * Uses fast-deep-equal for reliable structural comparison.
 *
 * @param a - First metadata object
 * @param b - Second metadata object
 * @returns true if metadata is semantically equal
 */
export function isMetadataEqual(
  a: Record<string, unknown> | undefined,
  b: Record<string, unknown> | undefined
): boolean {
  // Treat undefined/null as empty objects for comparison
  const normalizedA = a ?? {};
  const normalizedB = b ?? {};

  // Use fast-deep-equal for robust structural comparison
  return fastDeepEqual(normalizedA, normalizedB);
}

/**
 * Type guard to check if an error is a 404 Not Found error.
 *
 * @param error - The error to check
 * @returns true if error is a 404 OpikApiError
 */
function isNotFoundError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "statusCode" in error &&
    error.statusCode === 404
  );
}
