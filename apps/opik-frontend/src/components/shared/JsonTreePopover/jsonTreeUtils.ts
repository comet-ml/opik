import React from "react";
import { JsonValue, JsonObject } from "./types";

export interface ParsedSearchQuery {
  pathToExpand: string | null;
  searchTerm: string;
}

export interface VisiblePathItem {
  path: string;
  value: JsonValue;
}

/**
 * Parses a search query to determine which path to expand and what term to filter by.
 *
 * Examples:
 * - "user." -> { pathToExpand: "user", searchTerm: "" } (expand user, show children)
 * - "user.profile." -> { pathToExpand: "user.profile", searchTerm: "" }
 * - "tags[" -> { pathToExpand: "tags", searchTerm: "" } (array access)
 * - "user.name" -> { pathToExpand: "user", searchTerm: "name" }
 * - "tags[0" -> { pathToExpand: "tags", searchTerm: "0" }
 * - "name" -> { pathToExpand: null, searchTerm: "name" } (root level search)
 */
export const parseSearchQuery = (searchQuery: string): ParsedSearchQuery => {
  if (!searchQuery) {
    return { pathToExpand: null, searchTerm: "" };
  }

  // Check if query ends with "[" - means user wants to access array elements
  if (searchQuery.endsWith("[")) {
    const pathWithoutBracket = searchQuery.slice(0, -1);
    return { pathToExpand: pathWithoutBracket, searchTerm: "" };
  }

  // Check if query ends with "." - means user wants to see children
  if (searchQuery.endsWith(".")) {
    const pathWithoutDot = searchQuery.slice(0, -1);
    return { pathToExpand: pathWithoutDot, searchTerm: "" };
  }

  // Find the last separator (either "." or "[" that starts array access)
  const lastDotIndex = searchQuery.lastIndexOf(".");
  const lastBracketIndex = searchQuery.lastIndexOf("[");

  // Determine which separator is more recent
  const lastSeparatorIndex = Math.max(lastDotIndex, lastBracketIndex);

  if (lastSeparatorIndex > 0) {
    // For bracket, we need to include everything up to (but not including) the bracket
    // For dot, we include everything up to (but not including) the dot
    const parentPath = searchQuery.slice(0, lastSeparatorIndex);
    const currentSearch = searchQuery.slice(lastSeparatorIndex + 1);
    return { pathToExpand: parentPath, searchTerm: currentSearch };
  }

  // No separator - just searching at root level
  return { pathToExpand: null, searchTerm: searchQuery };
};

export const isArrayAccessMode = (searchQuery: string): boolean => {
  if (!searchQuery) return false;
  const lastBracketIndex = searchQuery.lastIndexOf("[");
  const lastDotIndex = searchQuery.lastIndexOf(".");
  return lastBracketIndex > lastDotIndex && !searchQuery.endsWith("]");
};

/**
 * Computes all paths that need to be expanded to show the target path.
 * Handles both dot notation (user.profile) and array notation (tags[0]).
 *
 * Examples:
 * - "user" -> ["user"]
 * - "user.profile" -> ["user", "user.profile"]
 * - "tags[0]" -> ["tags", "tags[0]"]
 * - "user.tags[0].name" -> ["user", "user.tags", "user.tags[0]", "user.tags[0].name"]
 */
export const computePathsToExpand = (pathToExpand: string): Set<string> => {
  const pathsToExpand = new Set<string>();

  // Split by "." but preserve array indices
  // e.g., "user.tags[0].name" -> ["user", "tags[0]", "name"]
  const parts = pathToExpand.split(".");
  let currentPath = "";

  parts.forEach((part) => {
    // Check if this part contains array access like "tags[0]"
    const bracketMatch = part.match(/^([^[]+)(\[.+\])$/);

    if (bracketMatch) {
      // First expand the base (e.g., "tags")
      const basePart = bracketMatch[1];
      currentPath = currentPath ? `${currentPath}.${basePart}` : basePart;
      pathsToExpand.add(currentPath);

      // Then expand with the full array access (e.g., "tags[0]")
      currentPath = currentPath.slice(0, -basePart.length) + part;
      if (currentPath.startsWith(".")) {
        currentPath = currentPath.slice(1);
      }
      pathsToExpand.add(currentPath);
    } else {
      currentPath = currentPath ? `${currentPath}.${part}` : part;
      pathsToExpand.add(currentPath);
    }
  });

  return pathsToExpand;
};

/**
 * Filters visible paths based on search query, path to expand, and search term.
 */
export const filterVisiblePaths = (
  visiblePaths: VisiblePathItem[],
  searchQuery: string,
  pathToExpand: string | null,
  searchTerm: string,
  isArrayAccess: boolean,
): VisiblePathItem[] => {
  if (!searchQuery.trim()) {
    return visiblePaths;
  }

  // If we have a path to expand (user typed "field1." or "field1[")
  if (pathToExpand) {
    // For array access (e.g., "tags[0"), look for array children like "tags[0]", "tags[1]"
    if (isArrayAccess) {
      const arrayChildPrefix = pathToExpand + "[";
      let filtered = visiblePaths.filter((item) => {
        if (item.path.startsWith(arrayChildPrefix)) {
          // If there's a search term (the index), filter by it
          if (searchTerm) {
            // Extract the index part, e.g., from "tags[0]" get "0]" then "0"
            const afterBracket = item.path.slice(arrayChildPrefix.length);
            const indexMatch = afterBracket.match(/^(\d+)\]/);
            if (indexMatch) {
              return indexMatch[1].startsWith(searchTerm);
            }
          }
          return true;
        }
        return false;
      });

      if (filtered.length === 0) {
        filtered = visiblePaths.filter((item) =>
          item.path.toLowerCase().includes(searchQuery.toLowerCase()),
        );
      }
      return filtered;
    }

    // For dot access (e.g., "user.name"), look for children with dot prefix
    const childPrefix = pathToExpand + ".";
    let filtered = visiblePaths.filter((item) => {
      // Show items that are direct children of the expanded path
      if (item.path.startsWith(childPrefix)) {
        // If there's a search term, filter by it
        if (searchTerm) {
          const childPart = item.path.slice(childPrefix.length);
          // Only match the immediate child name (before any further dots or brackets)
          const immediateChild = childPart.split(/[.[]/)[0];
          return immediateChild
            .toLowerCase()
            .includes(searchTerm.toLowerCase());
        }
        return true;
      }
      return false;
    });

    // If no children found, maybe the path doesn't exist - show all matching
    if (filtered.length === 0) {
      filtered = visiblePaths.filter((item) =>
        item.path.toLowerCase().includes(searchQuery.toLowerCase()),
      );
    }
    return filtered;
  }

  // No path to expand - filter at root level
  return visiblePaths.filter((item) => {
    const rootKey = item.path.split(/[.[]/)[0];
    return rootKey.toLowerCase().includes(searchTerm.toLowerCase());
  });
};

/**
 * Finds the first child path of an expanded path for focus management.
 */
export const findFirstChildPath = (
  filteredVisiblePaths: VisiblePathItem[],
  pathToExpand: string,
): string | null => {
  const childPrefix = pathToExpand + ".";
  const arrayChildPrefix = pathToExpand + "[";
  const firstChild = filteredVisiblePaths.find(
    (item) =>
      item.path.startsWith(childPrefix) ||
      item.path.startsWith(arrayChildPrefix),
  );
  return firstChild?.path ?? null;
};

/**
 * Extracts the top-level key from a path.
 * For object keys: first segment before '.' or '['
 * For array indices: the '[index]' part at the start
 *
 * Examples:
 * - "user" -> "user"
 * - "user.name" -> "user"
 * - "user[0]" -> "user"
 * - "[0]" -> "[0]"
 * - "[0].name" -> "[0]"
 */
export const extractTopLevelKey = (path: string): string => {
  const dotIndex = path.indexOf(".");
  const bracketIndex = path.indexOf("[");

  if (bracketIndex === 0) {
    // Path starts with '[' - it's a root array element like '[0]'
    const closeBracket = path.indexOf("]");
    return path.slice(0, closeBracket + 1);
  } else if (dotIndex === -1 && bracketIndex === -1) {
    // No separator - the whole path is the top-level key
    return path;
  } else if (dotIndex === -1) {
    // Only bracket exists
    return path.slice(0, bracketIndex);
  } else if (bracketIndex === -1) {
    // Only dot exists
    return path.slice(0, dotIndex);
  } else {
    // Both exist - take the earlier one
    return path.slice(0, Math.min(dotIndex, bracketIndex));
  }
};

/**
 * Computes the set of top-level keys that should be visible based on filtered paths.
 */
export const computeVisibleTopLevelKeys = (
  filteredVisiblePaths: VisiblePathItem[],
): Set<string> => {
  const visibleTopLevelKeys = new Set<string>();

  for (const item of filteredVisiblePaths) {
    visibleTopLevelKeys.add(extractTopLevelKey(item.path));
  }

  return visibleTopLevelKeys;
};

export const VALUE_TYPE_STYLES = {
  key: { color: "var(--color-green)" },
  object: { color: "var(--chart-tick-stroke)" },
  array: { color: "var(--chart-tick-stroke)" },
  string: { color: "var(--color-blue)" },
  number: { color: "var(--color-purple)" },
  boolean: { color: "var(--color-purple)" },
  null: { color: "var(--muted-foreground)" },
  default: {},
} as const;

export const getValueTypeStyle = (value: JsonValue): React.CSSProperties => {
  if (value === null) return VALUE_TYPE_STYLES.null;
  if (Array.isArray(value)) return VALUE_TYPE_STYLES.array;
  switch (typeof value) {
    case "string":
      return VALUE_TYPE_STYLES.string;
    case "number":
      return VALUE_TYPE_STYLES.number;
    case "boolean":
      return VALUE_TYPE_STYLES.boolean;
    case "object":
      return VALUE_TYPE_STYLES.object;
    default:
      return VALUE_TYPE_STYLES.default;
  }
};

export const getValuePreview = (value: JsonValue): string => {
  if (value === null) return "null";
  if (Array.isArray(value)) return `Array[${value.length}]`;
  if (typeof value === "object") return `Object{${Object.keys(value).length}}`;
  if (typeof value === "string" && value.length > 30) {
    return `"${value.substring(0, 30)}..."`;
  }
  return JSON.stringify(value);
};

export const getVisiblePaths = (
  data: JsonObject | JsonValue[],
  expandedPaths: Set<string>,
  parentPath: string = "",
): Array<{ path: string; value: JsonValue }> => {
  const result: Array<{ path: string; value: JsonValue }> = [];

  const entries = Array.isArray(data)
    ? data.map((item, index) => [`[${index}]`, item] as const)
    : Object.entries(data);

  for (const [key, value] of entries) {
    const path = parentPath
      ? Array.isArray(data)
        ? `${parentPath}${key}`
        : `${parentPath}.${key}`
      : String(key);

    result.push({ path, value: value as JsonValue });

    // If expanded and has children, recurse
    if (
      expandedPaths.has(path) &&
      value !== null &&
      typeof value === "object"
    ) {
      const children = getVisiblePaths(
        value as JsonObject | JsonValue[],
        expandedPaths,
        path,
      );
      result.push(...children);
    }
  }

  return result;
};
