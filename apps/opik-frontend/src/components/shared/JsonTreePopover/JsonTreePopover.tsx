import React, { useState, useCallback, useEffect, useRef, useMemo } from "react";
import { ChevronRight, ChevronDown } from "lucide-react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { isMac } from "@/lib/utils";

// Types for JSON values
export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonObject
  | JsonValue[];

export interface JsonObject {
  [key: string]: JsonValue;
}

export interface JsonTreeNodeProps {
  nodeKey: string;
  value: JsonValue;
  path: string;
  depth: number;
  expandedPaths: Set<string>;
  onToggleExpand: (path: string) => void;
  onSelect: (path: string, value: JsonValue) => void;
  showValues?: boolean;
  focusedPath?: string | null;
  onFocusPath?: (path: string) => void;
}

// Helper to get all visible paths from a tree
const getVisiblePaths = (
  data: JsonObject | JsonValue[],
  expandedPaths: Set<string>,
  parentPath: string = ""
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
    if (expandedPaths.has(path) && value !== null && typeof value === "object") {
      const children = getVisiblePaths(
        value as JsonObject | JsonValue[],
        expandedPaths,
        path
      );
      result.push(...children);
    }
  }

  return result;
};

// Color styles using CSS variables from the design system
// Keys: #11A675 (green) - using --color-green
// Object/Array: #373D4D - using --chart-tick-stroke
// Strings: #056BD1 (blue) - custom color close to system blue
// Numbers/Booleans: #7C3AED (purple) - using --color-purple

const VALUE_TYPE_STYLES = {
  key: { color: "var(--color-green)" }, // #11A675 equivalent
  object: { color: "var(--chart-tick-stroke)" }, // #373D4D
  array: { color: "var(--chart-tick-stroke)" }, // #373D4D
  string: { color: "#056BD1" }, // Strings blue
  number: { color: "var(--color-purple)" }, // #7C3AED equivalent
  boolean: { color: "var(--color-purple)" }, // #7C3AED equivalent
  null: { color: "var(--muted-foreground)" },
  default: {},
} as const;

const getValueTypeStyle = (
  value: JsonValue
): React.CSSProperties => {
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

const getValuePreview = (value: JsonValue): string => {
  if (value === null) return "null";
  if (Array.isArray(value)) return `Array[${value.length}]`;
  if (typeof value === "object") return `Object{${Object.keys(value).length}}`;
  if (typeof value === "string" && value.length > 30) {
    return `"${value.substring(0, 30)}..."`;
  }
  return JSON.stringify(value);
};

const JsonTreeNode: React.FC<JsonTreeNodeProps> = ({
  nodeKey,
  value,
  path,
  depth,
  expandedPaths,
  onToggleExpand,
  onSelect,
  showValues = true,
  focusedPath,
  onFocusPath,
}) => {
  const indent = depth * 16;
  const isExpanded = expandedPaths.has(path);
  const isFocused = focusedPath === path;
  const isExpandable =
    value !== null &&
    (typeof value === "object" || Array.isArray(value)) &&
    (Array.isArray(value) ? value.length > 0 : Object.keys(value).length > 0);

  const handleClick = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onSelect(path, value);
    },
    [path, value, onSelect]
  );

  const handleToggle = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onToggleExpand(path);
    },
    [path, onToggleExpand]
  );

  const handleMouseEnter = useCallback(() => {
    onFocusPath?.(path);
  }, [path, onFocusPath]);

  // Render children for objects and arrays
  const renderChildren = () => {
    if (!isExpanded || !isExpandable) return null;

    if (Array.isArray(value)) {
      return value.map((item, index) => {
        const itemPath = `${path}[${index}]`;
        return (
          <JsonTreeNode
            key={itemPath}
            nodeKey={`[${index}]`}
            value={item}
            path={itemPath}
            depth={depth + 1}
            expandedPaths={expandedPaths}
            onToggleExpand={onToggleExpand}
            onSelect={onSelect}
            showValues={showValues}
            focusedPath={focusedPath}
            onFocusPath={onFocusPath}
          />
        );
      });
    }

    if (typeof value === "object" && value !== null) {
      return Object.entries(value).map(([key, val]) => {
        const childPath = path ? `${path}.${key}` : key;
        return (
          <JsonTreeNode
            key={childPath}
            nodeKey={key}
            value={val}
            path={childPath}
            depth={depth + 1}
            expandedPaths={expandedPaths}
            onToggleExpand={onToggleExpand}
            onSelect={onSelect}
            showValues={showValues}
            focusedPath={focusedPath}
            onFocusPath={onFocusPath}
          />
        );
      });
    }

    return null;
  };

  return (
    <div>
      <div
        className={cn(
          "flex items-center gap-1 py-1 px-2 rounded cursor-pointer",
          "hover:bg-muted transition-colors font-mono",
          isFocused && "bg-muted ring-1 ring-primary"
        )}
        style={{ paddingLeft: indent + 4 }}
        onClick={handleClick}
        onMouseEnter={handleMouseEnter}
      >
        {isExpandable ? (
          <button
            onClick={handleToggle}
            className="p-0.5 shrink-0"
          >
            {isExpanded ? (
              <ChevronDown className="size-4 text-muted-foreground" />
            ) : (
              <ChevronRight className="size-4 text-muted-foreground" />
            )}
          </button>
        ) : (
          <span className="w-5 shrink-0" />
        )}
        <span
          className="comet-body-s truncate"
          style={VALUE_TYPE_STYLES.key}
        >
          {nodeKey}
        </span>
        {showValues && (
          <>
            <span className="comet-body-s text-muted-foreground">:</span>
            <span className="comet-body-s truncate" style={getValueTypeStyle(value)}>
              {getValuePreview(value)}
            </span>
          </>
        )}
      </div>
      {renderChildren()}
    </div>
  );
};

export interface JsonTreeViewProps {
  data: JsonObject | JsonValue[];
  onSelect: (path: string, value: JsonValue) => void;
  showValues?: boolean;
  className?: string;
  defaultExpandedPaths?: string[];
  maxHeight?: number | string;
  focusedPath?: string | null;
  onFocusPath?: (path: string) => void;
}

export const JsonTreeView: React.FC<JsonTreeViewProps> = ({
  data,
  onSelect,
  className,
  defaultExpandedPaths = [],
  maxHeight = 320,
  focusedPath,
  onFocusPath,
}) => {
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(
    () => new Set(defaultExpandedPaths)
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

  const entries = Array.isArray(data)
    ? data.map((item, index) => [`[${index}]`, item] as const)
    : Object.entries(data);

  return (
    <div
      className={cn("overflow-auto", className)}
      style={{ maxHeight }}
    >
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
            onSelect={onSelect}
            showValues
            focusedPath={focusedPath}
            onFocusPath={onFocusPath}
          />
        );
      })}
    </div>
  );
};

// Keyboard shortcut badge component
const KeyboardBadge: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => (
  <kbd className="inline-flex items-center justify-center px-1.5 py-0.5 text-xs font-medium bg-muted border rounded">
    {children}
  </kbd>
);

export interface JsonTreePopoverProps {
  data: JsonObject | JsonValue[];
  onSelect: (path: string, value: JsonValue) => void;
  trigger: React.ReactNode;
  defaultExpandedPaths?: string[];
  maxHeight?: number | string;
  align?: "start" | "center" | "end";
  side?: "top" | "right" | "bottom" | "left";
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  contentClassName?: string;
}

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
}) => {
  const [internalOpen, setInternalOpen] = useState(false);
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(
    () => new Set(defaultExpandedPaths)
  );
  const [focusedPath, setFocusedPath] = useState<string | null>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  const isControlled = open !== undefined;
  const isOpen = isControlled ? open : internalOpen;
  const setIsOpen = isControlled ? onOpenChange : setInternalOpen;

  // Get all visible paths for keyboard navigation
  const visiblePaths = useMemo(() => {
    if (!data || (Array.isArray(data) && data.length === 0) ||
        (typeof data === "object" && Object.keys(data).length === 0)) {
      return [];
    }
    return getVisiblePaths(data, expandedPaths);
  }, [data, expandedPaths]);

  // Reset focus when popover opens
  useEffect(() => {
    if (isOpen && visiblePaths.length > 0) {
      setFocusedPath(visiblePaths[0].path);
    } else if (!isOpen) {
      setFocusedPath(null);
    }
  }, [isOpen, visiblePaths]);

  const handleSelect = useCallback(
    (path: string, value: JsonValue) => {
      onSelect(path, value);
      setIsOpen?.(false);
    },
    [onSelect, setIsOpen]
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

  // Keyboard navigation
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      const currentIndex = visiblePaths.findIndex((p) => p.path === focusedPath);

      switch (e.key) {
        case "Tab": {
          e.preventDefault();
          if (visiblePaths.length === 0) return;

          const nextIndex = e.shiftKey
            ? (currentIndex - 1 + visiblePaths.length) % visiblePaths.length
            : (currentIndex + 1) % visiblePaths.length;
          setFocusedPath(visiblePaths[nextIndex].path);
          break;
        }
        case "ArrowDown": {
          e.preventDefault();
          if (visiblePaths.length === 0) return;
          const nextIndex = (currentIndex + 1) % visiblePaths.length;
          setFocusedPath(visiblePaths[nextIndex].path);
          break;
        }
        case "ArrowUp": {
          e.preventDefault();
          if (visiblePaths.length === 0) return;
          const prevIndex = (currentIndex - 1 + visiblePaths.length) % visiblePaths.length;
          setFocusedPath(visiblePaths[prevIndex].path);
          break;
        }
        case "ArrowRight": {
          e.preventDefault();
          if (focusedPath) {
            const item = visiblePaths.find((p) => p.path === focusedPath);
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
          if ((e.metaKey || e.ctrlKey) && focusedPath) {
            e.preventDefault();
            const item = visiblePaths.find((p) => p.path === focusedPath);
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

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, focusedPath, visiblePaths, expandedPaths, handleSelect, handleToggleExpand, setIsOpen]);

  // Custom tree rendering that shares state with popover
  const renderTree = () => {
    const entries = Array.isArray(data)
      ? data.map((item, index) => [`[${index}]`, item] as const)
      : Object.entries(data);

    return (
      <div
        ref={contentRef}
        className="overflow-auto"
        style={{ maxHeight }}
      >
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

  const modifierKey = isMac ? "Cmd" : "Ctrl";

  return (
    <Popover open={isOpen} onOpenChange={setIsOpen}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        className={cn("min-w-[470px] w-2/3 max-w-[600px] p-0", contentClassName)}
        align={align}
        side={side}
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        {/* Header */}
        <div className="px-4 py-3 border-b">
          <h4 className="comet-body-xs-accented">
            Explore available paths
          </h4>
          <p className="comet-body-xs text-light-slate mt-1">
            Paths are a merged view of the most common structures from the selected projects. They help you select variables that exist across multiple traces.
          </p>
        </div>

        {/* Tree content */}
        <div className="p-2">
          {renderTree()}
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t">
          <p className="comet-body-xs text-light-slate">
            Press{" "}
            <KeyboardBadge>Tab</KeyboardBadge> to navigate,{" "}
            <KeyboardBadge>{modifierKey}+Enter</KeyboardBadge> to select, and{" "}
            <KeyboardBadge>Esc</KeyboardBadge> to close.
          </p>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default JsonTreePopover;
