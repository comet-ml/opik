import React, { useState, useCallback, useEffect, useRef, useMemo } from "react";
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

  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      const currentIndex = visiblePaths.findIndex(
        (p) => p.path === focusedPath
      );

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
          const prevIndex =
            (currentIndex - 1 + visiblePaths.length) % visiblePaths.length;
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
          if (focusedPath) {
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
  }, [
    isOpen,
    focusedPath,
    visiblePaths,
    expandedPaths,
    handleSelect,
    handleToggleExpand,
    setIsOpen,
  ]);

  const renderTree = () => {
    const entries = Array.isArray(data)
      ? data.map((item, index) => [`[${index}]`, item] as const)
      : Object.entries(data);

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
          contentClassName
        )}
        align={align}
        side={side}
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <div className="px-4 py-3 border-b">
          <h4 className="comet-body-xs-accented">Explore available paths</h4>
          <p className="comet-body-xs text-light-slate mt-1">
            Paths are a merged view of the most common structures from the
            selected projects. They help you select variables that exist across
            multiple traces.
          </p>
        </div>

        <div className="p-2">{renderTree()}</div>

        <div className="px-4 py-3 border-t">
          <p className="comet-body-xs text-light-slate">
            Press <KeyboardBadge>Tab</KeyboardBadge> to navigate,{" "}
            <KeyboardBadge>Enter</KeyboardBadge> to select, and{" "}
            <KeyboardBadge>Esc</KeyboardBadge> to close.
          </p>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default JsonTreePopover;
