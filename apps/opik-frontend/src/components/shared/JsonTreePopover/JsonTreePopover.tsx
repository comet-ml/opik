import React, {
  useState,
  useCallback,
  useEffect,
  useRef,
  useMemo,
} from "react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { JsonTreePopoverProps, JsonValue } from "./types";
import {
  getVisiblePaths,
  parseSearchQuery,
  isArrayAccessMode,
  computePathsToExpand,
  filterVisiblePaths,
  findFirstChildPath,
  computeVisibleTopLevelKeys,
} from "./jsonTreeUtils";
import JsonTreeNode from "./JsonTreeNode";
import PopoverHeader from "./PopoverHeader";
import PopoverFooter from "./PopoverFooter";

const MAX_HEIGHT = "320px";

const KEY_CYCLE_FOCUS = "Tab";
const KEY_FOCUS_NEXT = "ArrowDown";
const KEY_FOCUS_PREV = "ArrowUp";
const KEY_EXPAND = "ArrowRight";
const KEY_COLLAPSE = "ArrowLeft";
const KEY_SELECT = "Enter";
const KEY_CLOSE = "Escape";

const NAVIGATION_KEYS = [
  KEY_CYCLE_FOCUS,
  KEY_FOCUS_NEXT,
  KEY_FOCUS_PREV,
  KEY_EXPAND,
  KEY_COLLAPSE,
  KEY_SELECT,
  KEY_CLOSE,
];

const JsonTreePopover: React.FC<JsonTreePopoverProps> = ({
  data,
  onSelect,
  trigger,
  open,
  onOpenChange,
  searchQuery = "",
}) => {
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(
    () => new Set(),
  );
  const [focusedPath, setFocusedPath] = useState<string | null>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) {
      setExpandedPaths(new Set());
    }
  }, [open]);

  const { pathToExpand, searchTerm } = useMemo(
    () => parseSearchQuery(searchQuery),
    [searchQuery],
  );

  // Auto-expand paths based on the typed path
  useEffect(() => {
    if (pathToExpand) {
      setExpandedPaths(computePathsToExpand(pathToExpand));
    }
  }, [pathToExpand]);

  const visiblePaths = useMemo(() => {
    if (
      !data ||
      (Array.isArray(data) && data.length === 0) ||
      (typeof data === "object" && Object.keys(data).length === 0)
    ) {
      return [];
    }
    return getVisiblePaths(data, expandedPaths);
  }, [data, expandedPaths]);

  // Determine if we're in array access mode (user typed "[" after a path)
  const isArrayAccess = useMemo(
    () => isArrayAccessMode(searchQuery),
    [searchQuery],
  );

  const filteredVisiblePaths = useMemo(
    () =>
      filterVisiblePaths(
        visiblePaths,
        searchQuery,
        pathToExpand,
        searchTerm,
        isArrayAccess,
      ),
    [visiblePaths, searchQuery, pathToExpand, searchTerm, isArrayAccess],
  );

  const entries = useMemo(
    () =>
      Array.isArray(data)
        ? data.map((item, index) => [`[${index}]`, item] as const)
        : Object.entries(data),
    [data],
  );

  // Track previous pathToExpand to detect when user navigates deeper
  const prevPathToExpandRef = useRef<string | null>(null);

  useEffect(() => {
    if (!open) {
      setFocusedPath(null);
    }
  }, [open]);

  // Focus first child when user types "." or "[" to expand into a path
  useEffect(() => {
    if (
      open &&
      pathToExpand &&
      pathToExpand !== prevPathToExpandRef.current &&
      filteredVisiblePaths.length > 0
    ) {
      const firstChildPath = findFirstChildPath(
        filteredVisiblePaths,
        pathToExpand,
      );
      if (firstChildPath) {
        setFocusedPath(firstChildPath);
      }
    }
    prevPathToExpandRef.current = pathToExpand;
  }, [open, pathToExpand, filteredVisiblePaths]);

  // Update focus when search term changes and current focus is not in filtered results
  useEffect(() => {
    if (open && filteredVisiblePaths.length > 0 && focusedPath) {
      // Check if current focused path is still in the filtered results
      const isFocusedPathVisible = filteredVisiblePaths.some(
        (item) => item.path === focusedPath,
      );
      if (!isFocusedPathVisible) {
        // Focus the first item in the filtered results
        setFocusedPath(filteredVisiblePaths[0].path);
      }
    } else if (open && filteredVisiblePaths.length > 0 && !focusedPath) {
      // No focus set, set it to the first item
      setFocusedPath(filteredVisiblePaths[0].path);
    }
  }, [open, filteredVisiblePaths, focusedPath]);

  const handleSelect = useCallback(
    (path: string, value: JsonValue) => {
      onSelect(path, value);
      onOpenChange(false);
    },
    [onSelect, onOpenChange],
  );

  const handleToggleExpand = useCallback((path: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  }, []);

  useEffect(() => {
    if (!open) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Only handle navigation keys
      if (!NAVIGATION_KEYS.includes(e.key)) {
        return;
      }

      const currentIndex = filteredVisiblePaths.findIndex(
        (p) => p.path === focusedPath,
      );

      e.stopPropagation();

      switch (e.key) {
        case KEY_CYCLE_FOCUS: {
          e.preventDefault();
          if (filteredVisiblePaths.length === 0) return;

          const nextIndex = e.shiftKey
            ? (currentIndex - 1 + filteredVisiblePaths.length) %
              filteredVisiblePaths.length
            : (currentIndex + 1) % filteredVisiblePaths.length;
          setFocusedPath(filteredVisiblePaths[nextIndex].path);
          break;
        }
        case KEY_FOCUS_NEXT: {
          e.preventDefault();
          if (filteredVisiblePaths.length === 0) return;
          const nextIndex = (currentIndex + 1) % filteredVisiblePaths.length;
          setFocusedPath(filteredVisiblePaths[nextIndex].path);
          break;
        }
        case KEY_FOCUS_PREV: {
          e.preventDefault();
          if (filteredVisiblePaths.length === 0) return;
          const prevIndex =
            (currentIndex - 1 + filteredVisiblePaths.length) %
            filteredVisiblePaths.length;
          setFocusedPath(filteredVisiblePaths[prevIndex].path);
          break;
        }
        case KEY_EXPAND: {
          e.preventDefault();
          if (focusedPath) {
            const item = filteredVisiblePaths.find(
              (p) => p.path === focusedPath,
            );
            if (item && typeof item.value === "object" && item.value !== null) {
              if (!expandedPaths.has(focusedPath)) {
                handleToggleExpand(focusedPath);
              }
            }
          }
          break;
        }
        case KEY_COLLAPSE: {
          e.preventDefault();
          if (focusedPath && expandedPaths.has(focusedPath)) {
            handleToggleExpand(focusedPath);
          }
          break;
        }
        case KEY_SELECT: {
          if (focusedPath) {
            e.preventDefault();
            const item = filteredVisiblePaths.find(
              (p) => p.path === focusedPath,
            );
            if (item) {
              handleSelect(item.path, item.value);
            }
          }
          break;
        }
        case KEY_CLOSE: {
          e.preventDefault();
          onOpenChange(false);
          break;
        }
      }
    };

    // Use capture phase to intercept events before they reach other handlers
    document.addEventListener("keydown", handleKeyDown, true);
    return () => document.removeEventListener("keydown", handleKeyDown, true);
  }, [
    open,
    focusedPath,
    filteredVisiblePaths,
    expandedPaths,
    handleSelect,
    handleToggleExpand,
    onOpenChange,
  ]);

  // When filtering is active, compute which top-level entries should be shown
  const filteredEntries = useMemo(() => {
    if (!searchQuery.trim()) {
      return entries;
    }

    const visibleTopLevelKeys =
      computeVisibleTopLevelKeys(filteredVisiblePaths);

    return entries.filter(([key]) => {
      const path = Array.isArray(data) ? `[${key.slice(1, -1)}]` : key;
      return visibleTopLevelKeys.has(path);
    }) as typeof entries;
  }, [searchQuery, filteredVisiblePaths, entries, data]);

  const renderTree = () => {
    if (searchQuery.trim() && filteredVisiblePaths.length === 0) {
      return (
        <div
          ref={contentRef}
          className="max-h-[var(--tree-max-height)] overflow-auto"
          style={{ "--tree-max-height": MAX_HEIGHT } as React.CSSProperties}
        >
          <div className="px-3 py-4 text-center text-sm text-muted-foreground">
            No matching keys found
          </div>
        </div>
      );
    }

    return (
      <div
        ref={contentRef}
        className="max-h-[var(--tree-max-height)] overflow-auto"
        style={{ "--tree-max-height": MAX_HEIGHT } as React.CSSProperties}
      >
        {filteredEntries.map(([key, value]) => {
          const path = Array.isArray(data) ? `[${key.slice(1, -1)}]` : key;
          return (
            <JsonTreeNode
              key={path}
              nodeKey={String(key)}
              value={value as JsonValue}
              path={path}
              depth={0}
              expandedPaths={expandedPaths}
              onToggleExpand={handleToggleExpand}
              onSelect={handleSelect}
              showValues
              focusedPath={focusedPath}
              onFocusPath={setFocusedPath}
            />
          );
        })}
      </div>
    );
  };

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        className="w-2/3 min-w-[470px] max-w-[600px] p-0"
        align="start"
        side="bottom"
        onOpenAutoFocus={(e) => e.preventDefault()}
        collisionPadding={16}
        sideOffset={4}
      >
        <PopoverHeader
          searchQuery={searchQuery}
          pathToExpand={pathToExpand}
          searchTerm={searchTerm}
          isArrayAccess={isArrayAccess}
        />

        <div className="p-2">{renderTree()}</div>

        <PopoverFooter />
      </PopoverContent>
    </Popover>
  );
};

export default JsonTreePopover;
