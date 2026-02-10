import type { ViewTree } from "./types";

const STORAGE_PREFIX = "data-view:";
const CURRENT_VERSION = 1;

export interface StorageOptions {
  /** localStorage key prefix */
  prefix?: string;
}

// ============================================================================
// TYPES & HELPERS
// ============================================================================

interface StoredView {
  version: number;
  tree: ViewTree;
  savedAt: string;
}

export class StorageError extends Error {
  constructor(
    public operation: "save" | "load" | "delete",
    public key: string,
    public cause: Error,
  ) {
    super(`Storage ${operation} failed for "${key}": ${cause.message}`);
    this.name = "StorageError";
  }
}

function migrateView(data: StoredView): ViewTree {
  // Add migration logic here as versions change
  console.warn(
    `[DataView] Migrating view from v${data.version} to v${CURRENT_VERSION}`,
  );
  return {
    ...data.tree,
    version: CURRENT_VERSION,
  };
}

// ============================================================================
// STORAGE OPERATIONS
// ============================================================================

/**
 * Save a view tree to localStorage.
 */
export function saveView(
  key: string,
  tree: ViewTree,
  options: StorageOptions = {},
): void {
  const prefix = options.prefix ?? STORAGE_PREFIX;
  const storageKey = `${prefix}${key}`;

  const data: StoredView = {
    version: CURRENT_VERSION,
    tree,
    savedAt: new Date().toISOString(),
  };

  try {
    localStorage.setItem(storageKey, JSON.stringify(data));
  } catch (error) {
    console.error(`[DataView] Failed to save view "${key}":`, error);
    throw new StorageError("save", key, error as Error);
  }
}

/**
 * Load a view tree from localStorage.
 * Returns null if not found.
 */
export function loadView(
  key: string,
  options: StorageOptions = {},
): ViewTree | null {
  const prefix = options.prefix ?? STORAGE_PREFIX;
  const storageKey = `${prefix}${key}`;

  try {
    const raw = localStorage.getItem(storageKey);
    if (!raw) return null;

    const data: StoredView = JSON.parse(raw);

    // Handle version migrations if needed
    if (data.version !== CURRENT_VERSION) {
      return migrateView(data);
    }

    return data.tree;
  } catch (error) {
    console.error(`[DataView] Failed to load view "${key}":`, error);
    return null;
  }
}

/**
 * Delete a view from localStorage.
 */
export function deleteView(key: string, options: StorageOptions = {}): void {
  const prefix = options.prefix ?? STORAGE_PREFIX;
  localStorage.removeItem(`${prefix}${key}`);
}

/**
 * List all saved view keys.
 */
export function listViews(options: StorageOptions = {}): string[] {
  const prefix = options.prefix ?? STORAGE_PREFIX;
  return Array.from({ length: localStorage.length }, (_, i) =>
    localStorage.key(i),
  )
    .filter((key): key is string => key?.startsWith(prefix) ?? false)
    .map((key) => key.slice(prefix.length));
}

/**
 * Check if a view exists in storage.
 */
export function viewExists(key: string, options: StorageOptions = {}): boolean {
  const prefix = options.prefix ?? STORAGE_PREFIX;
  return localStorage.getItem(`${prefix}${key}`) !== null;
}
