import React, { useMemo, useCallback, useState } from "react";
import jmespath from "jmespath";
import { cn } from "@/lib/utils";
import {
  ChevronRight,
  ChevronDown,
  Braces,
  Brackets,
  Hash,
  Type,
  ToggleLeft,
  Circle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Input } from "@/components/ui/input";

/**
 * JMESPath built-in functions that we expose in the UI.
 * These are native JMESPath functions - no custom implementation needed!
 * @see https://jmespath.org/specification.html#built-in-functions
 */
export const JMESPATH_FUNCTIONS = {
  /** Get array length */
  length: "length",
  /** Get first element */
  first: "first",
  /** Get last element */
  last: "last",
  /** Reverse array */
  reverse: "reverse",
  /** Sort array */
  sort: "sort",
  /** Get unique values (via sort) */
  unique: "unique",
  /** Get minimum value */
  min: "min",
  /** Get maximum value */
  max: "max",
  /** Get sum of values */
  sum: "sum",
  /** Get average of values */
  avg: "avg",
  /** Get object keys */
  keys: "keys",
  /** Get object values */
  values: "values",
  /** Flatten nested arrays */
  flatten: "flatten",
  /** Convert to array */
  toArray: "to_array",
  /** Convert to string */
  toString: "to_string",
  /** Convert to number */
  toNumber: "to_number",
} as const;

export type JmesPathFunction = (typeof JMESPATH_FUNCTIONS)[keyof typeof JMESPATH_FUNCTIONS];

export type JsonDynamicBuilderProps = {
  /** The JSON data object to query */
  data: object | null;
  /** The current JMESPath query value */
  value: string;
  /** Callback when the query changes */
  onValueChange: (value: string) => void;
  /** Placeholder text for the input */
  placeholder?: string;
  /** Whether the input has an error state */
  hasError?: boolean;
  /** Additional CSS classes */
  className?: string;
  /** Whether to show the extracted result preview */
  showPreview?: boolean;
};

export type JmesPathResult = {
  /** The extracted value from the JSON using JMESPath */
  result: unknown;
  /** Any error that occurred during extraction */
  error: string | null;
  /** Whether the query was successful */
  isValid: boolean;
};

/**
 * Hook to execute a JMESPath query against JSON data
 */
export const useJmesPathResult = (
  data: object | null,
  query: string,
): JmesPathResult => {
  return useMemo(() => {
    if (!data || !query.trim()) {
      return { result: null, error: null, isValid: false };
    }

    try {
      const result = jmespath.search(data, query);
      return { result, error: null, isValid: true };
    } catch (e) {
      return {
        result: null,
        error: (e as Error).message,
        isValid: false,
      };
    }
  }, [data, query]);
};

// Legacy exports for backward compatibility
export type ParsedOperator = {
  name: string;
  args: (string | number)[];
};

export type JsonDynamicBuilderResult = JmesPathResult & {
  parsedPath: string;
  operators: ParsedOperator[];
};

/** @deprecated Use useJmesPathResult instead */
export const useJsonPathResult = (
  data: object | null,
  query: string,
): JsonDynamicBuilderResult => {
  const result = useJmesPathResult(data, query);
  return {
    ...result,
    parsedPath: query,
    operators: [],
  };
};

/** @deprecated JMESPath handles operators natively */
export const QUERY_OPERATORS = JMESPATH_FUNCTIONS;

/** @deprecated JMESPath handles operators natively */
export const parseQueryWithOperators = (
  query: string,
): { path: string; operators: ParsedOperator[] } => {
  return { path: query, operators: [] };
};

/** @deprecated JMESPath handles operators natively */
export const applyOperator = (value: unknown): unknown => value;

/** @deprecated JMESPath handles operators natively */
export const applyOperators = (value: unknown): unknown => value;

/** Function definitions with metadata for the UI */
type FunctionDefinition = {
  /** JMESPath function name */
  name: string;
  /** Display label */
  label: string;
  /** Description */
  description: string;
  /** Whether it takes an argument */
  hasArg: boolean;
  /** Argument placeholder */
  argPlaceholder?: string;
  /** JMESPath expression template. Use @ for current value, {n} for argument */
  template: string;
  /** Value types this function applies to */
  applicableTo: ("array" | "object" | "string" | "number" | "any")[];
};

const FUNCTION_DEFINITIONS: FunctionDefinition[] = [
  // Array indexing (JMESPath native)
  {
    name: "first",
    label: "First",
    description: "Get first element",
    hasArg: false,
    template: "[0]",
    applicableTo: ["array"],
  },
  {
    name: "last",
    label: "Last",
    description: "Get last element",
    hasArg: false,
    template: "[-1]",
    applicableTo: ["array"],
  },
  {
    name: "at",
    label: "At Index",
    description: "Get element at index",
    hasArg: true,
    argPlaceholder: "index",
    template: "[{n}]",
    applicableTo: ["array"],
  },
  // Slicing (JMESPath native)
  {
    name: "take",
    label: "Take N",
    description: "Get first n elements",
    hasArg: true,
    argPlaceholder: "n",
    template: "[:{n}]",
    applicableTo: ["array"],
  },
  {
    name: "skip",
    label: "Skip N",
    description: "Skip first n elements",
    hasArg: true,
    argPlaceholder: "n",
    template: "[{n}:]",
    applicableTo: ["array"],
  },
  {
    name: "slice",
    label: "Slice",
    description: "Get elements from start to end",
    hasArg: true,
    argPlaceholder: "start:end",
    template: "[{n}]",
    applicableTo: ["array"],
  },
  {
    name: "every",
    label: "Every N",
    description: "Get every nth element",
    hasArg: true,
    argPlaceholder: "n",
    template: "[::${n}]",
    applicableTo: ["array"],
  },
  // Built-in functions
  {
    name: "length",
    label: "Length",
    description: "Get length/count",
    hasArg: false,
    template: "length(@)",
    applicableTo: ["array", "string", "object"],
  },
  {
    name: "reverse",
    label: "Reverse",
    description: "Reverse array order",
    hasArg: false,
    template: "reverse(@)",
    applicableTo: ["array"],
  },
  {
    name: "sort",
    label: "Sort",
    description: "Sort array",
    hasArg: false,
    template: "sort(@)",
    applicableTo: ["array"],
  },
  {
    name: "sum",
    label: "Sum",
    description: "Sum of numeric values",
    hasArg: false,
    template: "sum(@)",
    applicableTo: ["array"],
  },
  {
    name: "avg",
    label: "Average",
    description: "Average of numeric values",
    hasArg: false,
    template: "avg(@)",
    applicableTo: ["array"],
  },
  {
    name: "min",
    label: "Min",
    description: "Minimum value",
    hasArg: false,
    template: "min(@)",
    applicableTo: ["array"],
  },
  {
    name: "max",
    label: "Max",
    description: "Maximum value",
    hasArg: false,
    template: "max(@)",
    applicableTo: ["array"],
  },
  {
    name: "keys",
    label: "Keys",
    description: "Get object keys",
    hasArg: false,
    template: "keys(@)",
    applicableTo: ["object"],
  },
  {
    name: "values",
    label: "Values",
    description: "Get object values",
    hasArg: false,
    template: "values(@)",
    applicableTo: ["object"],
  },
  {
    name: "flatten",
    label: "Flatten",
    description: "Flatten nested arrays",
    hasArg: false,
    template: "[]",
    applicableTo: ["array"],
  },
  // Projection
  {
    name: "pluck",
    label: "Pluck",
    description: "Extract field from all items",
    hasArg: true,
    argPlaceholder: "field",
    template: "[*].{n}",
    applicableTo: ["array"],
  },
  // Type conversion
  {
    name: "toString",
    label: "To String",
    description: "Convert to string",
    hasArg: false,
    template: "to_string(@)",
    applicableTo: ["any"],
  },
  {
    name: "toNumber",
    label: "To Number",
    description: "Convert to number",
    hasArg: false,
    template: "to_number(@)",
    applicableTo: ["string", "number"],
  },
];

/** Get the type of a value for function filtering */
const getValueType = (
  value: unknown,
): "array" | "object" | "string" | "number" | "any" => {
  if (Array.isArray(value)) return "array";
  if (typeof value === "object" && value !== null) return "object";
  if (typeof value === "string") return "string";
  if (typeof value === "number") return "number";
  return "any";
};

/** Get icon for value type */
const getTypeIcon = (value: unknown) => {
  if (Array.isArray(value)) return Brackets;
  if (typeof value === "object" && value !== null) return Braces;
  if (typeof value === "string") return Type;
  if (typeof value === "number") return Hash;
  if (typeof value === "boolean") return ToggleLeft;
  return Circle;
};

/** Get applicable functions for a value type */
const getApplicableFunctions = (value: unknown): FunctionDefinition[] => {
  const valueType = getValueType(value);
  return FUNCTION_DEFINITIONS.filter(
    (fn) =>
      fn.applicableTo.includes(valueType) || fn.applicableTo.includes("any"),
  );
};

/**
 * Convert a tree path to JMESPath syntax
 * e.g., "$.user.scores" -> "user.scores"
 *       "$.users[0].name" -> "users[0].name"
 */
const toJmesPath = (treePath: string): string => {
  // Remove leading $ or $.
  let path = treePath;
  if (path.startsWith("$.")) {
    path = path.substring(2);
  } else if (path === "$") {
    path = "@"; // Root reference in JMESPath
  } else if (path.startsWith("$")) {
    path = path.substring(1);
  }
  return path;
};

/**
 * Build JMESPath expression by applying a function to a path
 */
const buildJmesPathExpression = (
  path: string,
  fn: FunctionDefinition,
  arg?: string,
): string => {
  const jmesPath = toJmesPath(path);
  let template = fn.template;

  // Replace {n} with the argument
  if (fn.hasArg && arg) {
    template = template.replace("{n}", arg);
  }

  // If template uses @, wrap the path
  if (template.includes("@")) {
    // For functions like length(@), sum(@), etc.
    if (jmesPath === "@") {
      return template;
    }
    return `${jmesPath} | ${template}`;
  }

  // For array operations like [0], [:3], etc.
  return `${jmesPath}${template}`;
};

/** Tree node component props */
type JsonTreeNodeProps = {
  keyName: string;
  value: unknown;
  path: string;
  depth: number;
  onSelect: (jmesPath: string) => void;
  expandedPaths: Set<string>;
  onToggleExpand: (path: string) => void;
};

/** Single tree node component */
const JsonTreeNode: React.FC<JsonTreeNodeProps> = ({
  keyName,
  value,
  path,
  depth,
  onSelect,
  expandedPaths,
  onToggleExpand,
}) => {
  const [functionArg, setFunctionArg] = useState<string>("");
  const isExpandable =
    (typeof value === "object" && value !== null) || Array.isArray(value);
  const isExpanded = expandedPaths.has(path);
  const TypeIcon = getTypeIcon(value);
  const applicableFunctions = getApplicableFunctions(value);

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (isExpandable) {
      onToggleExpand(path);
    }
  };

  const handleSelect = () => {
    onSelect(toJmesPath(path));
  };

  const handleFunctionClick = (fn: FunctionDefinition, e: React.MouseEvent) => {
    e.stopPropagation();
    const arg = fn.hasArg ? functionArg || "0" : undefined;
    const expression = buildJmesPathExpression(path, fn, arg);
    onSelect(expression);
  };

  const getPreviewValue = () => {
    if (Array.isArray(value)) {
      return `[${value.length}]`;
    }
    if (typeof value === "object" && value !== null) {
      return `{${Object.keys(value).length}}`;
    }
    if (typeof value === "string") {
      return value.length > 20
        ? `"${value.substring(0, 20)}..."`
        : `"${value}"`;
    }
    return String(value);
  };

  return (
    <div>
      <div
        className={cn(
          "group flex items-center gap-1 rounded px-1 py-0.5 hover:bg-muted",
          "cursor-pointer",
        )}
        style={{ paddingLeft: `${depth * 16 + 4}px` }}
      >
        {/* Expand/collapse button */}
        <button
          onClick={handleToggle}
          className={cn(
            "flex size-4 shrink-0 items-center justify-center rounded hover:bg-muted-foreground/20",
            !isExpandable && "invisible",
          )}
        >
          {isExpanded ? (
            <ChevronDown className="size-3" />
          ) : (
            <ChevronRight className="size-3" />
          )}
        </button>

        {/* Type icon */}
        <TypeIcon className="size-3.5 shrink-0 text-muted-slate" />

        {/* Key name - clickable to select path */}
        <button
          onClick={handleSelect}
          className="flex-1 truncate text-left font-mono text-xs hover:text-primary"
          title={`Select: ${toJmesPath(path)}`}
        >
          {keyName}
        </button>

        {/* Value preview */}
        <span className="shrink-0 font-mono text-xs text-muted-slate">
          {getPreviewValue()}
        </span>

        {/* Functions popover */}
        {applicableFunctions.length > 0 && (
          <Popover>
            <PopoverTrigger asChild>
              <Button
                variant="ghost"
                size="icon-xs"
                className="size-5 shrink-0 opacity-0 group-hover:opacity-100"
                onClick={(e) => e.stopPropagation()}
              >
                <span className="text-[10px] font-bold">fx</span>
              </Button>
            </PopoverTrigger>
            <PopoverContent
              className="w-64 p-2"
              align="end"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="mb-2 text-xs font-medium text-muted-slate">
                Apply function to:{" "}
                <code className="text-foreground">{keyName}</code>
              </div>

              {/* Argument input for functions that need it */}
              <div className="mb-2">
                <Input
                  type="text"
                  placeholder="Argument (n, field, start:end)"
                  value={functionArg}
                  onChange={(e) => setFunctionArg(e.target.value)}
                  className="h-7 text-xs"
                  onClick={(e) => e.stopPropagation()}
                />
              </div>

              <div className="flex flex-wrap gap-1">
                {applicableFunctions.map((fn) => (
                  <TooltipProvider key={fn.name}>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Button
                          variant="outline"
                          size="sm"
                          className="h-6 px-2 text-xs"
                          onClick={(e) => handleFunctionClick(fn, e)}
                        >
                          {fn.label}
                          {fn.hasArg && (
                            <span className="ml-0.5 text-muted-slate">
                              ({functionArg || fn.argPlaceholder})
                            </span>
                          )}
                        </Button>
                      </TooltipTrigger>
                      <TooltipContent side="bottom">
                        <p>{fn.description}</p>
                        <code className="mt-1 block text-xs text-muted-foreground">
                          {fn.template}
                        </code>
                      </TooltipContent>
                    </Tooltip>
                  </TooltipProvider>
                ))}
              </div>
            </PopoverContent>
          </Popover>
        )}
      </div>

      {/* Children */}
      {isExpanded && isExpandable && (
        <div>
          {Array.isArray(value)
            ? value.map((item, index) => (
                <JsonTreeNode
                  key={`${path}[${index}]`}
                  keyName={`[${index}]`}
                  value={item}
                  path={`${path}[${index}]`}
                  depth={depth + 1}
                  onSelect={onSelect}
                  expandedPaths={expandedPaths}
                  onToggleExpand={onToggleExpand}
                />
              ))
            : Object.entries(value as object).map(([key, val]) => (
                <JsonTreeNode
                  key={`${path}.${key}`}
                  keyName={key}
                  value={val}
                  path={`${path}.${key}`}
                  depth={depth + 1}
                  onSelect={onSelect}
                  expandedPaths={expandedPaths}
                  onToggleExpand={onToggleExpand}
                />
              ))}
        </div>
      )}
    </div>
  );
};

/**
 * A component for building JMESPath queries with a tree view.
 * Shows a hierarchical tree of the JSON data with JMESPath functions
 * available for each node based on its type.
 *
 * Uses JMESPath syntax (cross-platform compatible with Java/Python):
 * - user.name - Access nested property
 * - users[0] - First element
 * - users[-1] - Last element
 * - users[:3] - First 3 elements
 * - users[2:] - Skip first 2
 * - users | length(@) - Get length
 * - users | sum(@) - Sum values
 * - users[*].name - Extract field from all items
 *
 * @see https://jmespath.org/
 */
const JsonDynamicBuilder: React.FC<JsonDynamicBuilderProps> = ({
  data,
  value,
  onValueChange,
  placeholder = "Click to build JMESPath query...",
  hasError,
  className,
  showPreview = false,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(
    () => new Set(["$"]),
  );

  // Get the result of the current query
  const queryResult = useJmesPathResult(data, value);

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

  const handleSelect = useCallback(
    (jmesPath: string) => {
      onValueChange(jmesPath);
      setIsOpen(false);
    },
    [onValueChange],
  );

  const handleInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onValueChange(e.target.value);
    },
    [onValueChange],
  );

  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <Popover open={isOpen} onOpenChange={setIsOpen}>
        <PopoverTrigger asChild>
          <div className="relative">
            <Input
              value={value}
              onChange={handleInputChange}
              placeholder={placeholder}
              className={cn(
                "cursor-pointer font-mono text-sm pr-10",
                hasError && "border-destructive",
                !queryResult.isValid &&
                  value.trim() !== "" &&
                  "border-destructive",
              )}
            />
            <Button
              variant="ghost"
              size="icon-xs"
              className="absolute right-2 top-1/2 -translate-y-1/2"
              onClick={(e) => {
                e.stopPropagation();
                setIsOpen(true);
              }}
            >
              <Braces className="size-4" />
            </Button>
          </div>
        </PopoverTrigger>
        <PopoverContent
          className="max-h-[400px] w-[400px] overflow-y-auto p-2"
          align="start"
        >
          <div className="mb-2 flex items-center justify-between border-b pb-2">
            <span className="text-xs font-medium">
              Select a path (JMESPath)
            </span>
            <span className="text-xs text-muted-slate">
              Click <span className="font-bold">fx</span> for functions
            </span>
          </div>

          {data ? (
            <JsonTreeNode
              keyName="@"
              value={data}
              path="$"
              depth={0}
              onSelect={handleSelect}
              expandedPaths={expandedPaths}
              onToggleExpand={handleToggleExpand}
            />
          ) : (
            <div className="py-4 text-center text-sm text-muted-slate">
              No data available
            </div>
          )}
        </PopoverContent>
      </Popover>

      {showPreview && value.trim() !== "" && (
        <div
          className={cn(
            "rounded-md border bg-muted p-2 font-mono text-xs",
            queryResult.error && "border-destructive",
          )}
        >
          {queryResult.error ? (
            <span className="text-destructive">{queryResult.error}</span>
          ) : queryResult.result !== undefined ? (
            <pre className="max-h-24 overflow-auto whitespace-pre-wrap">
              {typeof queryResult.result === "object"
                ? JSON.stringify(queryResult.result, null, 2)
                : String(queryResult.result)}
            </pre>
          ) : (
            <span className="text-muted-slate">null</span>
          )}
        </div>
      )}
    </div>
  );
};

export default JsonDynamicBuilder;
