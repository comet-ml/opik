import React, { useCallback, useRef, useEffect } from "react";
import { ChevronRight, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { JsonTreeNodeProps, JsonValue } from "./types";
import {
  VALUE_TYPE_STYLES,
  getValueTypeStyle,
  getValuePreview,
} from "./jsonTreeUtils";

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

  useEffect(() => {
    if (isFocused && nodeRef.current) {
      nodeRef.current.scrollIntoView({
        block: "nearest",
        behavior: "smooth",
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
          "flex items-center gap-1 py-1 px-2 rounded cursor-pointer",
          "hover:bg-muted transition-colors font-mono",
          isFocused && "bg-muted"
        )}
        style={{ paddingLeft: indent + 4 }}
        onClick={handleClick}
        onMouseEnter={handleMouseEnter}
      >
        {isExpandable ? (
          <button onClick={handleToggle} className="p-0.5 shrink-0">
            {isExpanded ? (
              <ChevronDown className="size-4 text-muted-foreground" />
            ) : (
              <ChevronRight className="size-4 text-muted-foreground" />
            )}
          </button>
        ) : (
          <span className="w-5 shrink-0" />
        )}
        <span className="comet-body-s truncate" style={VALUE_TYPE_STYLES.key}>
          {nodeKey}
        </span>
        {showValues && (
          <>
            <span className="comet-body-s text-muted-foreground">:</span>
            <span
              className="comet-body-s truncate"
              style={getValueTypeStyle(value)}
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
