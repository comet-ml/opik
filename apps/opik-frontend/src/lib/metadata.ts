import uniq from "lodash/uniq";
import { COLUMN_TYPE, DynamicColumn } from "@/types/shared";

/**
 * Splits a path into logical segments, handling '.' and '[' correctly.
 * Array indices are removed from segments.
 * Example: "metadata.items[0].name" -> ["metadata", "items", "name"]
 * Example: "metadata.nested._token" -> ["metadata", "nested", "_token"]
 */
function splitPathIntoSegments(path: string): string[] {
  const segments: string[] = [];
  let currentSegment = "";
  let inArrayIndex = false;

  for (let i = 0; i < path.length; i++) {
    const char = path[i];

    if (char === "[") {
      // Start of array index - save current segment if any
      if (currentSegment) {
        segments.push(currentSegment);
        currentSegment = "";
      }
      inArrayIndex = true;
    } else if (char === "]") {
      // End of array index - ignore the index part
      inArrayIndex = false;
    } else if (char === "." && !inArrayIndex) {
      // Dot separator - save current segment
      if (currentSegment) {
        segments.push(currentSegment);
        currentSegment = "";
      }
    } else if (!inArrayIndex) {
      // Regular character - add to current segment
      currentSegment += char;
    }
    // If inArrayIndex, we ignore the character
  }

  // Add the last segment if any
  if (currentSegment) {
    segments.push(currentSegment);
  }

  return segments;
}

/**
 * Checks if any segment in a path starts with '_' (excluding array indices).
 */
function hasUnderscoreSegment(path: string): boolean {
  const segments = splitPathIntoSegments(path);
  return segments.some((segment) => segment.startsWith("_"));
}

/**
 * Normalizes metadata paths by:
 * - Filtering out paths where any segment starts with underscore (internal/private fields)
 * - Filtering out paths that contain array indices (e.g., "metadata.some_list[0].field")
 * - Extracting base array paths from filtered-out paths (e.g., "metadata.some_list")
 * - Deduplicating and sorting the result
 */
export function normalizeMetadataPaths(paths: string[]): string[] {
  // Filter out paths that contain array indices or have any segment starting with '_'
  const filteredPaths = paths.filter((path: string) => {
    // Filter out paths that contain array indices
    // e.g., exclude "metadata.some_list[0].field" but keep "metadata.some_list"
    if (path.includes("[")) {
      return false;
    }

    // Filter out paths where any segment starts with '_'
    return !hasUnderscoreSegment(path);
  });

  // Extract base array paths from paths that were filtered out
  // e.g., from "metadata.some_list[0].field" extract "metadata.some_list"
  const arrayBasePaths = new Set<string>();
  paths.forEach((path: string) => {
    if (path.includes("[")) {
      // Extract the base path before the first "["
      const basePath = path.substring(0, path.indexOf("["));
      // Only include if no segment starts with '_'
      if (!hasUnderscoreSegment(basePath)) {
        arrayBasePaths.add(basePath);
      }
    }
  });

  // Combine filtered paths and array base paths, then deduplicate and sort
  return uniq([...filteredPaths, ...Array.from(arrayBasePaths)]).sort();
}

/**
 * Builds DynamicColumn array from normalized metadata paths.
 */
export function buildDynamicMetadataColumns(paths: string[]): DynamicColumn[] {
  return paths.map<DynamicColumn>((path: string) => {
    // Strip "metadata." prefix from label for display, keep full path as id for data access
    const label = path.startsWith("metadata.")
      ? path.substring("metadata.".length)
      : path;

    return {
      id: path,
      label: label,
      columnType: COLUMN_TYPE.string,
    };
  });
}
