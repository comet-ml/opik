import React, { useCallback, useRef, useEffect } from "react";
import { ChevronRight, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { JsonTreeNodeProps, JsonValue } from "./types";
import {
  VALUE_TYPE_STYLES,
  getValueTypeStyle,
  getValuePreview,
} from "./jsonTreeUtils";

const INDENT_PER_DEPTH = 16;
const BASE_PADDING_LEFT = 4;

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
  const nodeRef = useRef<HTMLDivElement>(null);
  const indent = depth * INDENT_PER_DEPTH + BASE_PADDING_LEFT;
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
    [path, value, onSelect],
  );

  const handleToggle = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onToggleExpand(path);
    },
    [path, onToggleExpand],
  );

  const handleMouseEnter = useCallback(() => {
    onFocusPath?.(path);
  }, [path, onFocusPath]);

  useEffect(() => {
    if (isFocused && nodeRef.current) {
      nodeRef.current.scrollIntoView({
        block: "nearest",
        behavior: "auto",
      });
    }
  }, [isFocused]);

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
            value={val as JsonValue}
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
        ref={nodeRef}
        className={cn(
          "flex items-center gap-1 py-1 pr-2 rounded cursor-pointer pl-[var(--node-indent)]",
          "hover:bg-muted transition-colors font-mono",
          isFocused && "bg-muted",
        )}
        style={{ "--node-indent": `${indent}px` } as React.CSSProperties}
        onClick={handleClick}
        onMouseEnter={handleMouseEnter}
      >
        {isExpandable ? (
          <button onClick={handleToggle} className="shrink-0 p-0.5">
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
          className="comet-body-s truncate text-[var(--key-color)]"
          style={
            {
              "--key-color": VALUE_TYPE_STYLES.key.color,
            } as React.CSSProperties
          }
        >
          {nodeKey}
        </span>
        {showValues && (
          <>
            <span className="comet-body-s text-muted-foreground">:</span>
            <span
              className="comet-body-s truncate text-[var(--value-color)]"
              style={
                {
                  "--value-color": getValueTypeStyle(value).color,
                } as React.CSSProperties
              }
            >
              {getValuePreview(value)}
            </span>
          </>
        )}
      </div>
      {renderChildren()}
    </div>
  );
};

export default JsonTreeNode;
