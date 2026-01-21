import React, { useMemo, useCallback, useState } from "react";
import { JSONPath } from "jsonpath-plus";
import { getJSONPaths, cn } from "@/lib/utils";
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
 * Available operators that can be applied to query results.
 * Use pipe syntax: $.path | operator or $.path | operator(arg)
 */
export const QUERY_OPERATORS = {
  /** Get the first element of an array */
  first: "first",
  /** Get the last element of an array */
  last: "last",
  /** Get every nth element: every(n) */
  every: "every",
  /** Get the first n elements: take(n) */
  take: "take",
  /** Skip the first n elements: skip(n) */
  skip: "skip",
  /** Get element at specific index: at(n) */
  at: "at",
  /** Reverse the array */
  reverse: "reverse",
  /** Get unique values */
  unique: "unique",
  /** Flatten nested arrays */
  flatten: "flatten",
  /** Get array length or string length */
  length: "length",
  /** Get sum of numeric array */
  sum: "sum",
  /** Get average of numeric array */
  avg: "avg",
  /** Get minimum value */
  min: "min",
  /** Get maximum value */
  max: "max",
  /** Get keys of an object */
  keys: "keys",
  /** Get values of an object */
  values: "values",
} as const;

export type QueryOperator = (typeof QUERY_OPERATORS)[keyof typeof QUERY_OPERATORS];

export type JsonDynamicBuilderProps = {
  /** The JSON data object to extract paths from */
  data: object | null;
  /** The current path/query value */
  value: string;
  /** Callback when the path/query changes */
  onValueChange: (value: string) => void;
  /** Placeholder text for the input */
  placeholder?: string;
  /** Whether to show intermediate nodes (objects/arrays) in the path list */
  includeIntermediateNodes?: boolean;
  /** Root prefix for paths (default: "$") */
  rootPrefix?: string;
  /** Whether to exclude the root prefix from displayed paths */
  excludeRoot?: boolean;
  /** Whether the input has an error state */
  hasError?: boolean;
  /** Additional CSS classes */
  className?: string;
  /** Whether to show the extracted result preview */
  showPreview?: boolean;
  /** Custom empty message when no paths are found */
  emptyMessage?: string;
};

export type JsonDynamicBuilderResult = {
  /** The extracted value from the JSON using the current path */
  result: unknown;
  /** Any error that occurred during extraction */
  error: string | null;
  /** Whether the extraction was successful */
  isValid: boolean;
  /** The parsed path (without operators) */
  parsedPath: string;
  /** The operators applied to the result */
  operators: ParsedOperator[];
};

export type ParsedOperator = {
  name: string;
  args: (string | number)[];
};

/**
 * Parse a query string into path and operators
 * Example: "$.users | first | take(5)" -> { path: "$.users", operators: [{name: "first", args: []}, {name: "take", args: [5]}] }
 */
export const parseQueryWithOperators = (
  query: string,
): { path: string; operators: ParsedOperator[] } => {
  const parts = query.split("|").map((p) => p.trim());
  const path = parts[0] || "";
  const operators: ParsedOperator[] = [];

  for (let i = 1; i < parts.length; i++) {
    const operatorStr = parts[i];
    const match = operatorStr.match(/^(\w+)(?:\(([^)]*)\))?$/);

    if (match) {
      const name = match[1];
      const argsStr = match[2];
      const args: (string | number)[] = [];

      if (argsStr) {
        argsStr.split(",").forEach((arg) => {
          const trimmed = arg.trim();
          const num = Number(trimmed);
          args.push(isNaN(num) ? trimmed : num);
        });
      }

      operators.push({ name, args });
    }
  }

  return { path, operators };
};

/**
 * Apply an operator to a value
 */
export const applyOperator = (
  value: unknown,
  operator: ParsedOperator,
): unknown => {
  const { name, args } = operator;

  switch (name) {
    case QUERY_OPERATORS.first:
      if (Array.isArray(value)) return value[0];
      if (typeof value === "string") return value[0];
      return value;

    case QUERY_OPERATORS.last:
      if (Array.isArray(value)) return value[value.length - 1];
      if (typeof value === "string") return value[value.length - 1];
      return value;

    case QUERY_OPERATORS.every: {
      const n = typeof args[0] === "number" ? args[0] : 1;
      if (n <= 0) return value;
      if (Array.isArray(value)) {
        return value.filter((_, index) => index % n === 0);
      }
      return value;
    }

    case QUERY_OPERATORS.take: {
      const n = typeof args[0] === "number" ? args[0] : 1;
      if (Array.isArray(value)) return value.slice(0, n);
      if (typeof value === "string") return value.slice(0, n);
      return value;
    }

    case QUERY_OPERATORS.skip: {
      const n = typeof args[0] === "number" ? args[0] : 0;
      if (Array.isArray(value)) return value.slice(n);
      if (typeof value === "string") return value.slice(n);
      return value;
    }

    case QUERY_OPERATORS.at: {
      const index = typeof args[0] === "number" ? args[0] : 0;
      if (Array.isArray(value)) return value[index];
      if (typeof value === "string") return value[index];
      return value;
    }

    case QUERY_OPERATORS.reverse:
      if (Array.isArray(value)) return [...value].reverse();
      if (typeof value === "string") return value.split("").reverse().join("");
      return value;

    case QUERY_OPERATORS.unique:
      if (Array.isArray(value)) return [...new Set(value)];
      return value;

    case QUERY_OPERATORS.flatten:
      if (Array.isArray(value)) return value.flat(Infinity);
      return value;

    case QUERY_OPERATORS.length:
      if (Array.isArray(value)) return value.length;
      if (typeof value === "string") return value.length;
      if (typeof value === "object" && value !== null) {
        return Object.keys(value).length;
      }
      return 0;

    case QUERY_OPERATORS.sum:
      if (Array.isArray(value)) {
        return value.reduce((acc: number, v) => {
          const num = Number(v);
          return acc + (isNaN(num) ? 0 : num);
        }, 0);
      }
      return value;

    case QUERY_OPERATORS.avg:
      if (Array.isArray(value) && value.length > 0) {
        const sum = value.reduce((acc: number, v) => {
          const num = Number(v);
          return acc + (isNaN(num) ? 0 : num);
        }, 0);
        return sum / value.length;
      }
      return value;

    case QUERY_OPERATORS.min:
      if (Array.isArray(value)) {
        const nums = value.map(Number).filter((n) => !isNaN(n));
        return nums.length > 0 ? Math.min(...nums) : value;
      }
      return value;

    case QUERY_OPERATORS.max:
      if (Array.isArray(value)) {
        const nums = value.map(Number).filter((n) => !isNaN(n));
        return nums.length > 0 ? Math.max(...nums) : value;
      }
      return value;

    case QUERY_OPERATORS.keys:
      if (typeof value === "object" && value !== null && !Array.isArray(value)) {
        return Object.keys(value);
      }
      if (Array.isArray(value)) {
        return value.map((_, i) => i);
      }
      return value;

    case QUERY_OPERATORS.values:
      if (typeof value === "object" && value !== null && !Array.isArray(value)) {
        return Object.values(value);
      }
      return value;

    default:
      return value;
  }
};

/**
 * Apply multiple operators in sequence
 */
export const applyOperators = (
  value: unknown,
  operators: ParsedOperator[],
): unknown => {
  return operators.reduce((acc, op) => applyOperator(acc, op), value);
};

/**
 * Hook to extract a value from JSON data using a JSONPath query with optional operators
 */
export const useJsonPathResult = (
  data: object | null,
  query: string,
): JsonDynamicBuilderResult => {
  return useMemo(() => {
    if (!data || !query.trim()) {
      return {
        result: null,
        error: null,
        isValid: false,
        parsedPath: "",
        operators: [],
      };
    }

    try {
      const { path, operators } = parseQueryWithOperators(query);

      if (!path.trim()) {
        return {
          result: null,
          error: null,
          isValid: false,
          parsedPath: path,
          operators,
        };
      }

      // Ensure the path starts with $ for JSONPath
      const jsonPath = path.startsWith("$") ? path : `$.${path}`;
      const result = JSONPath({ path: jsonPath, json: data });

      if (result.length === 0) {
        return {
          result: undefined,
          error: null,
          isValid: true,
          parsedPath: path,
          operators,
        };
      }

      // Get the base result
      let finalResult = result.length === 1 ? result[0] : result;

      // Apply operators if any
      if (operators.length > 0) {
        finalResult = applyOperators(finalResult, operators);
      }

      return {
        result: finalResult,
        error: null,
        isValid: true,
        parsedPath: path,
        operators,
      };
    } catch (e) {
      const { path, operators } = parseQueryWithOperators(query);
      return {
        result: null,
        error: (e as Error).message,
        isValid: false,
        parsedPath: path,
        operators,
      };
    }
  }, [data, query]);
};

/** Operator definitions with metadata */
type OperatorDefinition = {
  value: string;
  label: string;
  description: string;
  hasArg: boolean;
  argPlaceholder?: string;
  applicableTo: ("array" | "object" | "string" | "number" | "any")[];
};

const OPERATOR_DEFINITIONS: OperatorDefinition[] = [
  {
    value: "first",
    label: "First",
    description: "Get first element",
    hasArg: false,
    applicableTo: ["array", "string"],
  },
  {
    value: "last",
    label: "Last",
    description: "Get last element",
    hasArg: false,
    applicableTo: ["array", "string"],
  },
  {
    value: "every",
    label: "Every N",
    description: "Get every nth element",
    hasArg: true,
    argPlaceholder: "n",
    applicableTo: ["array"],
  },
  {
    value: "take",
    label: "Take N",
    description: "Get first n elements",
    hasArg: true,
    argPlaceholder: "n",
    applicableTo: ["array", "string"],
  },
  {
    value: "skip",
    label: "Skip N",
    description: "Skip first n elements",
    hasArg: true,
    argPlaceholder: "n",
    applicableTo: ["array", "string"],
  },
  {
    value: "at",
    label: "At Index",
    description: "Get element at index",
    hasArg: true,
    argPlaceholder: "index",
    applicableTo: ["array", "string"],
  },
  {
    value: "reverse",
    label: "Reverse",
    description: "Reverse order",
    hasArg: false,
    applicableTo: ["array", "string"],
  },
  {
    value: "unique",
    label: "Unique",
    description: "Get unique values",
    hasArg: false,
    applicableTo: ["array"],
  },
  {
    value: "flatten",
    label: "Flatten",
    description: "Flatten nested arrays",
    hasArg: false,
    applicableTo: ["array"],
  },
  {
    value: "length",
    label: "Length",
    description: "Get length/count",
    hasArg: false,
    applicableTo: ["array", "string", "object"],
  },
  {
    value: "sum",
    label: "Sum",
    description: "Sum of values",
    hasArg: false,
    applicableTo: ["array"],
  },
  {
    value: "avg",
    label: "Average",
    description: "Average of values",
    hasArg: false,
    applicableTo: ["array"],
  },
  {
    value: "min",
    label: "Min",
    description: "Minimum value",
    hasArg: false,
    applicableTo: ["array"],
  },
  {
    value: "max",
    label: "Max",
    description: "Maximum value",
    hasArg: false,
    applicableTo: ["array"],
  },
  {
    value: "keys",
    label: "Keys",
    description: "Get object keys",
    hasArg: false,
    applicableTo: ["object", "array"],
  },
  {
    value: "values",
    label: "Values",
    description: "Get object values",
    hasArg: false,
    applicableTo: ["object"],
  },
];

/** Get the type of a value for operator filtering */
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

/** Get applicable operators for a value type */
const getApplicableOperators = (value: unknown): OperatorDefinition[] => {
  const valueType = getValueType(value);
  return OPERATOR_DEFINITIONS.filter(
    (op) => op.applicableTo.includes(valueType) || op.applicableTo.includes("any"),
  );
};

/** Tree node component props */
type JsonTreeNodeProps = {
  keyName: string;
  value: unknown;
  path: string;
  depth: number;
  onSelect: (path: string, operator?: string) => void;
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
  const [operatorArg, setOperatorArg] = useState<string>("");
  const isExpandable =
    (typeof value === "object" && value !== null) || Array.isArray(value);
  const isExpanded = expandedPaths.has(path);
  const TypeIcon = getTypeIcon(value);
  const applicableOperators = getApplicableOperators(value);

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (isExpandable) {
      onToggleExpand(path);
    }
  };

  const handleSelect = () => {
    onSelect(path);
  };

  const handleOperatorClick = (op: OperatorDefinition, e: React.MouseEvent) => {
    e.stopPropagation();
    if (op.hasArg) {
      const arg = operatorArg || "1";
      onSelect(path, `${op.value}(${arg})`);
    } else {
      onSelect(path, op.value);
    }
  };

  const getPreviewValue = () => {
    if (Array.isArray(value)) {
      return `[${value.length}]`;
    }
    if (typeof value === "object" && value !== null) {
      return `{${Object.keys(value).length}}`;
    }
    if (typeof value === "string") {
      return value.length > 20 ? `"${value.substring(0, 20)}..."` : `"${value}"`;
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
          title={`Select: ${path}`}
        >
          {keyName}
        </button>

        {/* Value preview */}
        <span className="shrink-0 font-mono text-xs text-muted-slate">
          {getPreviewValue()}
        </span>

        {/* Operators popover */}
        {applicableOperators.length > 0 && (
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
              className="w-56 p-2"
              align="end"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="mb-2 text-xs font-medium text-muted-slate">
                Apply operator to: <code className="text-foreground">{keyName}</code>
              </div>

              {/* Argument input for operators that need it */}
              <div className="mb-2">
                <Input
                  type="number"
                  placeholder="Argument (n)"
                  value={operatorArg}
                  onChange={(e) => setOperatorArg(e.target.value)}
                  className="h-7 text-xs"
                  onClick={(e) => e.stopPropagation()}
                />
              </div>

              <div className="flex flex-wrap gap-1">
                {applicableOperators.map((op) => (
                  <TooltipProvider key={op.value}>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Button
                          variant="outline"
                          size="sm"
                          className="h-6 px-2 text-xs"
                          onClick={(e) => handleOperatorClick(op, e)}
                        >
                          {op.label}
                          {op.hasArg && (
                            <span className="ml-0.5 text-muted-slate">
                              ({operatorArg || op.argPlaceholder})
                            </span>
                          )}
                        </Button>
                      </TooltipTrigger>
                      <TooltipContent side="bottom">
                        <p>{op.description}</p>
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
 * A component for building dynamic JSON path queries with a tree view.
 * Shows a hierarchical tree of the JSON data with operators available
 * for each node based on its type.
 *
 * Supports operators via pipe syntax:
 * - $.path | first - Get first element
 * - $.path | last - Get last element
 * - $.path | every(2) - Get every 2nd element
 * - $.path | take(5) - Get first 5 elements
 * - $.path | skip(2) - Skip first 2 elements
 * - $.path | first | take(3) - Chain multiple operators
 */
const JsonDynamicBuilder: React.FC<JsonDynamicBuilderProps> = ({
  data,
  value,
  onValueChange,
  placeholder = "Click to build query...",
  hasError,
  className,
  showPreview = false,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(
    () => new Set(["$"]),
  );

  // Get the result of the current path query
  const queryResult = useJsonPathResult(data, value);

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
    (path: string, operator?: string) => {
      const newValue = operator ? `${path} | ${operator}` : path;
      onValueChange(newValue);
      if (!operator) {
        setIsOpen(false);
      }
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
                "cursor-pointer font-mono text-sm",
                hasError && "border-destructive",
                !queryResult.isValid && value.trim() !== "" && "border-destructive",
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
            <span className="text-xs font-medium">Select a path</span>
            <span className="text-xs text-muted-slate">
              Click <span className="font-bold">fx</span> for operators
            </span>
          </div>

          {data ? (
            <JsonTreeNode
              keyName="$"
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
            <span className="text-muted-slate">No match</span>
          )}
        </div>
      )}
    </div>
  );
};

export default JsonDynamicBuilder;
