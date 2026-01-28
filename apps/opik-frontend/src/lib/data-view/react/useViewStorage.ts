import { useCallback, useEffect, useRef } from "react";
import { useDataView } from "./DataViewProvider";
import { saveView, loadView, listViews, deleteView } from "../core/storage";

export interface UseViewStorageResult {
  /** Save current view */
  save: (key?: string) => void;
  /** Load a view by key */
  load: (key: string) => boolean;
  /** List all saved views */
  list: () => string[];
  /** Delete a saved view */
  remove: (key: string) => void;
}

export function useViewStorage(defaultKey?: string): UseViewStorageResult {
  const { tree, setTree } = useDataView();

  const save = useCallback(
    (key?: string) => {
      const storageKey = key ?? defaultKey;
      if (!storageKey) throw new Error("No storage key provided");
      saveView(storageKey, tree);
    },
    [tree, defaultKey],
  );

  const load = useCallback(
    (key: string): boolean => {
      const loaded = loadView(key);
      if (loaded) {
        setTree(loaded);
        return true;
      }
      return false;
    },
    [setTree],
  );

  return {
    save,
    load,
    list: useCallback(() => listViews(), []),
    remove: useCallback((key: string) => deleteView(key), []),
  };
}

/**
 * Hook that auto-saves the view tree on changes with debounce.
 */
export function useAutoSave(key: string, debounceMs = 1000): void {
  const { tree } = useDataView();
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();
  const isInitialMount = useRef(true);

  useEffect(() => {
    // Skip initial mount to avoid saving on load
    if (isInitialMount.current) {
      isInitialMount.current = false;
      return;
    }

    // Skip if tree is empty
    if (!tree.root) {
      return;
    }

    clearTimeout(timeoutRef.current);
    timeoutRef.current = setTimeout(() => {
      saveView(key, tree);
    }, debounceMs);

    return () => clearTimeout(timeoutRef.current);
  }, [key, tree, debounceMs]);
}
