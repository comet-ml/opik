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
