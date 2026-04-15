import { useCallback, useEffect, useMemo, useRef, useState } from "react";

export type CollapseBroadcast = {
  action: "expand" | "collapse" | null;
  version: number;
};

export type FieldsCollapseController = {
  isExpanded: (key: string) => boolean;
  toggle: (key: string) => void;
  expandAll: () => void;
  collapseAll: () => void;
  allExpanded: boolean;
  broadcast: CollapseBroadcast;
};

type UseFieldsCollapseOptions = {
  collapsibleKeys: string[];
  defaultExpanded?: boolean;
  initiallyExpandedKeys?: string[];
};

export const useFieldsCollapse = ({
  collapsibleKeys,
  defaultExpanded = false,
  initiallyExpandedKeys,
}: UseFieldsCollapseOptions): FieldsCollapseController => {
  const computeInitial = (): Set<string> => {
    if (initiallyExpandedKeys !== undefined)
      return new Set(initiallyExpandedKeys);
    return defaultExpanded ? new Set(collapsibleKeys) : new Set();
  };

  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(computeInitial);
  const [broadcast, setBroadcast] = useState<CollapseBroadcast>({
    action: defaultExpanded ? "expand" : null,
    version: 0,
  });

  const initialized = useRef(collapsibleKeys.length > 0);
  useEffect(() => {
    if (initialized.current) return;
    if (collapsibleKeys.length === 0) return;
    initialized.current = true;
    setExpandedKeys(computeInitial());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collapsibleKeys]);

  const isExpanded = useCallback(
    (key: string) => expandedKeys.has(key),
    [expandedKeys],
  );

  const toggle = useCallback((key: string) => {
    setExpandedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
    setBroadcast((prev) => ({ action: null, version: prev.version + 1 }));
  }, []);

  const expandAll = useCallback(() => {
    setExpandedKeys(new Set(collapsibleKeys));
    setBroadcast((prev) => ({ action: "expand", version: prev.version + 1 }));
  }, [collapsibleKeys]);

  const collapseAll = useCallback(() => {
    setExpandedKeys(new Set());
    setBroadcast((prev) => ({ action: "collapse", version: prev.version + 1 }));
  }, []);

  const allExpanded = useMemo(() => {
    if (broadcast.action === "expand") return true;
    if (broadcast.action === "collapse") return false;
    if (collapsibleKeys.length === 0) return false;
    return collapsibleKeys.every((k) => expandedKeys.has(k));
  }, [broadcast.action, collapsibleKeys, expandedKeys]);

  return {
    isExpanded,
    toggle,
    expandAll,
    collapseAll,
    allExpanded,
    broadcast,
  };
};
