import React, { useState, useCallback } from "react";
import { cn } from "@/lib/utils";
import { JsonTreeViewProps, JsonValue } from "./types";
import JsonTreeNode from "./JsonTreeNode";

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
    <div className={cn("overflow-auto", className)} style={{ maxHeight }}>
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

export default JsonTreeView;
