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

export interface JsonTreePopoverProps {
  data: JsonObject | JsonValue[];
  onSelect: (path: string, value: JsonValue) => void;
  trigger: React.ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  searchQuery?: string;
}
