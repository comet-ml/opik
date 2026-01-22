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
import { cn } from "@/lib/utils";
import { JsonTreePopoverProps, JsonValue } from "./types";
import { getVisiblePaths } from "./jsonTreeUtils";
import JsonTreeNode from "./JsonTreeNode";
import KeyboardBadge from "./KeyboardBadge";

const JsonTreePopover: React.FC<JsonTreePopoverProps> = ({
  data,
  onSelect,
  trigger,
  defaultExpandedPaths = [],
  maxHeight = 320,
  align = "start",
  side,
  open,
  onOpenChange,
  contentClassName,
  searchQuery = "",
  captureKeyboard = true,
}) => {
  const [internalOpen, setInternalOpen] = useState(false);
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(
    () => new Set(defaultExpandedPaths),
  );
  const [focusedPath, setFocusedPath] = useState<string | null>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  const isControlled = open !== undefined;
  const isOpen = isControlled ? open : internalOpen;
  const setIsOpen = isControlled ? onOpenChange : setInternalOpen;

  // Parse the search query to determine what to expand
  // e.g., "user." means expand "user" and show its children
  // e.g., "user.profile." means expand "user" and "user.profile"
  // e.g., "tags[" means expand "tags" (array access)
  // e.g., "user.tags[0]." means expand "user", "user.tags", "user.tags[0]"
  const { pathToExpand, searchTerm } = useMemo(() => {
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
  }, [searchQuery]);

  // Auto-expand paths based on the typed path
  useEffect(() => {
    if (pathToExpand) {
      // Expand all parent paths leading to the target
      // Handle both dot notation (user.profile) and array notation (tags[0])
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

      setExpandedPaths(pathsToExpand);
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
  const isArrayAccess = useMemo(() => {
    if (!searchQuery) return false;
    const lastBracketIndex = searchQuery.lastIndexOf("[");
    const lastDotIndex = searchQuery.lastIndexOf(".");
    // Array access if "[" is the last separator and query doesn't end with "]"
    return lastBracketIndex > lastDotIndex && !searchQuery.endsWith("]");
  }, [searchQuery]);

  // Filter visible paths based on search term and focus on the right level
  const filteredVisiblePaths = useMemo(() => {
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
  }, [visiblePaths, searchQuery, pathToExpand, searchTerm, isArrayAccess]);

  // Use filtered paths for display
  const displayPaths = filteredVisiblePaths;

  // Track previous open state to detect when popover opens
  const wasOpenRef = useRef(false);
  // Track previous pathToExpand to detect when user navigates deeper
  const prevPathToExpandRef = useRef<string | null>(null);

  useEffect(() => {
    // Only set initial focus when popover opens (transition from closed to open)
    if (isOpen && !wasOpenRef.current && displayPaths.length > 0) {
      setFocusedPath(displayPaths[0].path);
    } else if (!isOpen) {
      setFocusedPath(null);
    }
    wasOpenRef.current = isOpen;
  }, [isOpen, displayPaths]);

  // Focus first child when user types "." or "[" to expand into a path
  useEffect(() => {
    if (
      isOpen &&
      pathToExpand &&
      pathToExpand !== prevPathToExpandRef.current &&
      displayPaths.length > 0
    ) {
      // Find the first child of the expanded path
      const childPrefix = pathToExpand + ".";
      const arrayChildPrefix = pathToExpand + "[";
      const firstChild = displayPaths.find(
        (item) =>
          item.path.startsWith(childPrefix) ||
          item.path.startsWith(arrayChildPrefix),
      );
      if (firstChild) {
        setFocusedPath(firstChild.path);
      }
    }
    prevPathToExpandRef.current = pathToExpand;
  }, [isOpen, pathToExpand, displayPaths]);

  // Update focus when search term changes and current focus is not in filtered results
  useEffect(() => {
    if (isOpen && displayPaths.length > 0 && focusedPath) {
      // Check if current focused path is still in the filtered results
      const isFocusedPathVisible = displayPaths.some(
        (item) => item.path === focusedPath,
      );
      if (!isFocusedPathVisible) {
        // Focus the first item in the filtered results
        setFocusedPath(displayPaths[0].path);
      }
    } else if (isOpen && displayPaths.length > 0 && !focusedPath) {
      // No focus set, set it to the first item
      setFocusedPath(displayPaths[0].path);
    }
  }, [isOpen, displayPaths, focusedPath]);

  const handleSelect = useCallback(
    (path: string, value: JsonValue) => {
      onSelect(path, value);
      setIsOpen?.(false);
    },
    [onSelect, setIsOpen],
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

  // Keyboard navigation handler
  // When captureKeyboard is false, we use stopPropagation to prevent events from reaching other handlers
  // but still handle navigation within the popover
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      const currentIndex = displayPaths.findIndex(
        (p) => p.path === focusedPath,
      );

      // Keys that should be captured by the popover and stopped from propagating
      const navigationKeys = [
        "Tab",
        "ArrowDown",
        "ArrowUp",
        "ArrowRight",
        "ArrowLeft",
        "Enter",
        "Escape",
      ];

      // Only handle navigation keys
      if (!navigationKeys.includes(e.key)) {
        return;
      }

      // Stop propagation to prevent CodeMirror or other handlers from receiving these events
      // ALEX update it later
      if (!captureKeyboard) {
        e.stopPropagation();
      }

      switch (e.key) {
        case "Tab": {
          e.preventDefault();
          if (displayPaths.length === 0) return;

          const nextIndex = e.shiftKey
            ? (currentIndex - 1 + displayPaths.length) % displayPaths.length
            : (currentIndex + 1) % displayPaths.length;
          setFocusedPath(displayPaths[nextIndex].path);
          break;
        }
        case "ArrowDown": {
          e.preventDefault();
          if (displayPaths.length === 0) return;
          const nextIndex = (currentIndex + 1) % displayPaths.length;
          setFocusedPath(displayPaths[nextIndex].path);
          break;
        }
        case "ArrowUp": {
          e.preventDefault();
          if (displayPaths.length === 0) return;
          const prevIndex =
            (currentIndex - 1 + displayPaths.length) % displayPaths.length;
          setFocusedPath(displayPaths[prevIndex].path);
          break;
        }
        case "ArrowRight": {
          e.preventDefault();
          if (focusedPath) {
            const item = displayPaths.find((p) => p.path === focusedPath);
            if (item && typeof item.value === "object" && item.value !== null) {
              if (!expandedPaths.has(focusedPath)) {
                handleToggleExpand(focusedPath);
              }
            }
          }
          break;
        }
        case "ArrowLeft": {
          e.preventDefault();
          if (focusedPath && expandedPaths.has(focusedPath)) {
            handleToggleExpand(focusedPath);
          }
          break;
        }
        case "Enter": {
          if (focusedPath) {
            e.preventDefault();
            const item = displayPaths.find((p) => p.path === focusedPath);
            if (item) {
              handleSelect(item.path, item.value);
            }
          }
          break;
        }
        case "Escape": {
          e.preventDefault();
          setIsOpen?.(false);
          break;
        }
      }
    };

    // Use capture phase to intercept events before they reach other handlers
    document.addEventListener("keydown", handleKeyDown, true);
    return () => document.removeEventListener("keydown", handleKeyDown, true);
  }, [
    isOpen,
    captureKeyboard,
    focusedPath,
    displayPaths,
    expandedPaths,
    handleSelect,
    handleToggleExpand,
    setIsOpen,
  ]);

  const renderTree = () => {
    const entries = Array.isArray(data)
      ? data.map((item, index) => [`[${index}]`, item] as const)
      : Object.entries(data);

    // Check if we have any visible paths after filtering
    if (searchQuery.trim() && displayPaths.length === 0) {
      return (
        <div ref={contentRef} className="overflow-auto" style={{ maxHeight }}>
          <div className="px-3 py-4 text-center text-sm text-muted-foreground">
            No matching keys found
          </div>
        </div>
      );
    }

    return (
      <div ref={contentRef} className="overflow-auto" style={{ maxHeight }}>
        {entries.map(([key, value]) => {
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
    <Popover open={isOpen} onOpenChange={setIsOpen}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        className={cn(
          "min-w-[470px] w-2/3 max-w-[600px] p-0",
          contentClassName,
        )}
        align={align}
        side={side}
        onOpenAutoFocus={(e) => e.preventDefault()}
        collisionPadding={16}
        sideOffset={4}
      >
        <div className="border-b px-4 py-3">
          {searchQuery.trim() ? (
            <>
              <h4 className="comet-body-xs-accented">
                {pathToExpand ? (
                  <>
                    {isArrayAccess ? "Array" : "Path"}:{" "}
                    <span className="font-mono">{pathToExpand}</span>
                    {isArrayAccess && !searchTerm && (
                      <span className="text-light-slate">
                        {" "}
                        → select an index
                      </span>
                    )}
                    {searchTerm && (
                      <span className="text-light-slate">
                        {" "}
                        → {isArrayAccess ? "index" : "filtering by"} &quot;
                        {searchTerm}&quot;
                      </span>
                    )}
                  </>
                ) : (
                  <>
                    Filtering: <span className="font-mono">{searchQuery}</span>
                  </>
                )}
              </h4>
              <p className="comet-body-xs mt-1 text-light-slate">
                {isArrayAccess ? (
                  <>Type an index number to filter array elements</>
                ) : (
                  <>
                    Type <span className="font-mono">.</span> to expand into
                    nested fields, <span className="font-mono">[</span> for
                    arrays
                  </>
                )}
              </p>
            </>
          ) : (
            <>
              <h4 className="comet-body-xs-accented">Select a variable</h4>
              <p className="comet-body-xs mt-1 text-light-slate">
                Start typing to filter, use <span className="font-mono">.</span>{" "}
                for objects, <span className="font-mono">[</span> for arrays
              </p>
            </>
          )}
        </div>

        <div className="p-2">{renderTree()}</div>

        <div className="border-t px-4 py-3">
          <p className="comet-body-xs text-light-slate">
            Press <KeyboardBadge>Tab</KeyboardBadge> or{" "}
            <KeyboardBadge>←↑→↓</KeyboardBadge> to navigate,{" "}
            <KeyboardBadge>Enter</KeyboardBadge> to select, and{" "}
            <KeyboardBadge>Esc</KeyboardBadge> to close.
          </p>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default JsonTreePopover;
