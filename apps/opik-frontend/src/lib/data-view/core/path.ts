// ============================================================================
// PATH UTILITIES
// Custom path resolution using JSON Pointer-like syntax (e.g., /tools/0/name)
// ============================================================================

import type { SourceData, DynamicValue } from "./types";

/**
 * Type guard to check if a value is a PathRef.
 * PathRef uses the `path` key with JSON Pointer-like syntax.
 */
export function isPathRef(value: unknown): value is { path: string } {
  return (
    value !== null &&
    typeof value === "object" &&
    "path" in value &&
    typeof (value as { path: string }).path === "string"
  );
}

/**
 * Get a value from an object using JSON Pointer-like path syntax.
 * Path format: /segment/segment/index (e.g., /tools/0/name)
 *
 * @example
 * getByPath({ tools: [{ name: "calc" }] }, "/tools/0/name") // "calc"
 * getByPath({ a: { b: 1 } }, "/a/b") // 1
 * getByPath(data, "/") // returns data itself
 */
export function getByPath(obj: unknown, path: string): unknown {
  // Empty path or root returns the object itself
  if (!path || path === "/") return obj;

  // Parse path segments (skip leading slash)
  const segments = path.startsWith("/")
    ? path.slice(1).split("/")
    : path.split("/");

  let current: unknown = obj;

  for (const segment of segments) {
    if (current === null || current === undefined) {
      return undefined;
    }

    if (Array.isArray(current)) {
      // For arrays, try to parse segment as number
      const index = parseInt(segment, 10);
      if (Number.isNaN(index)) {
        return undefined;
      }
      current = current[index];
    } else if (typeof current === "object") {
      current = (current as Record<string, unknown>)[segment];
    } else {
      return undefined;
    }
  }

  return current;
}

/**
 * Check if a path exists in source data.
 */
export function hasPath(obj: unknown, path: string): boolean {
  if (!path || path === "/") return obj !== undefined;

  const segments = path.startsWith("/")
    ? path.slice(1).split("/")
    : path.split("/");

  let current: unknown = obj;

  for (const segment of segments) {
    if (current === null || current === undefined) {
      return false;
    }

    if (Array.isArray(current)) {
      const index = parseInt(segment, 10);
      if (Number.isNaN(index) || index < 0 || index >= current.length) {
        return false;
      }
      current = current[index];
    } else if (typeof current === "object") {
      if (!(segment in current)) {
        return false;
      }
      current = (current as Record<string, unknown>)[segment];
    } else {
      return false;
    }
  }

  return true;
}

/**
 * Set a value in an object using JSON Pointer-like path syntax.
 * Creates intermediate objects/arrays as needed.
 *
 * @example
 * const obj = {};
 * setByPath(obj, "/a/b", 1) // obj is now { a: { b: 1 } }
 */
export function setByPath(
  obj: Record<string, unknown>,
  path: string,
  value: unknown,
): void {
  if (!path || path === "/") {
    throw new Error("Cannot set root path");
  }

  const segments = path.startsWith("/")
    ? path.slice(1).split("/")
    : path.split("/");

  let current: Record<string, unknown> = obj;

  for (let i = 0; i < segments.length - 1; i++) {
    const key = segments[i];
    const nextKey = segments[i + 1];
    const nextIsIndex = /^\d+$/.test(nextKey);

    if (
      !(key in current) ||
      typeof current[key] !== "object" ||
      current[key] === null
    ) {
      // Create array if next segment is numeric, otherwise object
      current[key] = nextIsIndex ? [] : {};
    }

    current = current[key] as Record<string, unknown>;
  }

  const lastKey = segments[segments.length - 1];

  // Handle JSON Patch "-" syntax for array append (RFC 6902)
  if (lastKey === "-" && Array.isArray(current)) {
    current.push(value);
  } else {
    current[lastKey] = value;
  }
}

/**
 * Remove a value from an object using JSON Pointer-like path syntax.
 */
export function unsetByPath(obj: Record<string, unknown>, path: string): void {
  if (!path || path === "/") {
    throw new Error("Cannot unset root path");
  }

  const segments = path.startsWith("/")
    ? path.slice(1).split("/")
    : path.split("/");

  let current: unknown = obj;

  for (let i = 0; i < segments.length - 1; i++) {
    if (current === null || current === undefined) return;
    if (typeof current !== "object") return;
    current = (current as Record<string, unknown>)[segments[i]];
  }

  if (current !== null && typeof current === "object") {
    const lastKey = segments[segments.length - 1];
    if (Array.isArray(current)) {
      const index = parseInt(lastKey, 10);
      if (!Number.isNaN(index)) {
        current.splice(index, 1);
      }
    } else {
      delete (current as Record<string, unknown>)[lastKey];
    }
  }
}

// ============================================================================
// VALUE RESOLUTION
// ============================================================================

/**
 * Resolve a dynamic value to its actual value.
 * Returns undefined if path doesn't exist.
 */
export function resolveDynamicValue<T>(
  value: DynamicValue<T>,
  data: SourceData,
): T | undefined {
  if (isPathRef(value)) {
    return getByPath(data, value.path) as T | undefined;
  }
  return value as T;
}

/**
 * Resolve all bindings in a props object.
 * Returns resolved props object.
 */
export function resolveProps(
  props: Record<string, unknown>,
  data: SourceData,
): Record<string, unknown> {
  const resolved: Record<string, unknown> = {};

  for (const [key, value] of Object.entries(props)) {
    resolved[key] = resolveValue(value, data);
  }

  return resolved;
}

function resolveValue(value: unknown, data: SourceData): unknown {
  if (isPathRef(value)) {
    return getByPath(data, value.path);
  }

  if (Array.isArray(value)) {
    return value.map((item) => resolveValue(item, data));
  }

  if (value !== null && typeof value === "object") {
    const resolved: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      resolved[k] = resolveValue(v, data);
    }
    return resolved;
  }

  return value;
}

// ============================================================================
// PATH EXTRACTION & ANALYSIS
// ============================================================================

/**
 * Extract all paths used in a view tree.
 * Useful for validating against source data schema.
 */
export function extractAllPaths(nodes: Record<string, unknown>): string[] {
  const paths = new Set<string>();

  function traverse(value: unknown): void {
    if (isPathRef(value)) {
      paths.add(value.path);
      return;
    }
    if (Array.isArray(value)) {
      value.forEach(traverse);
      return;
    }
    if (value !== null && typeof value === "object") {
      Object.values(value).forEach(traverse);
    }
  }

  traverse(nodes);
  return Array.from(paths);
}

/**
 * Path info for prompt generation.
 */
export interface PathInfo {
  path: string;
  type: "string" | "number" | "boolean" | "array" | "object" | "null";
  sample?: unknown;
  arrayLength?: number;
}

/**
 * Get available paths from source data (for prompt generation).
 * Uses JSON Pointer-like syntax for paths.
 */
export function analyzeSourceData(
  data: SourceData,
  maxDepth = 4,
  maxArraySamples = 2,
): PathInfo[] {
  const paths: PathInfo[] = [];

  const traverse = (obj: unknown, path: string, depth: number): void => {
    if (depth > maxDepth) return;

    if (obj === null) {
      if (path) paths.push({ path, type: "null" });
    } else if (Array.isArray(obj)) {
      if (path) paths.push({ path, type: "array", arrayLength: obj.length });
      obj
        .slice(0, maxArraySamples)
        .forEach((item, i) => traverse(item, `${path}/${i}`, depth + 1));
    } else if (typeof obj === "object") {
      if (path) paths.push({ path, type: "object" });
      Object.entries(obj).forEach(([key, value]) =>
        traverse(value, `${path}/${key}`, depth + 1),
      );
    } else {
      const type = typeof obj as "string" | "number" | "boolean";
      const sample =
        type === "string" && (obj as string).length > 100
          ? (obj as string).slice(0, 100) + "..."
          : obj;
      paths.push({ path, type, sample });
    }
  };

  traverse(data, "", 0);
  return paths.filter((p) => p.path);
}
