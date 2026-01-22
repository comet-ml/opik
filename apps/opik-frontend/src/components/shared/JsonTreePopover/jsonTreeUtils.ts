import React from "react";
import { JsonValue, JsonObject } from "./types";

// Color styles using CSS variables from the design system
// Keys: #11A675 (green) - using --color-green
// Object/Array: #373D4D - using --chart-tick-stroke
// Strings: #056BD1 (blue) - custom color close to system blue
// Numbers/Booleans: #7C3AED (purple) - using --color-purple

export const VALUE_TYPE_STYLES = {
  key: { color: "var(--color-green)" }, // #11A675 equivalent
  object: { color: "var(--chart-tick-stroke)" }, // #373D4D
  array: { color: "var(--chart-tick-stroke)" }, // #373D4D
  string: { color: "#056BD1" }, // Strings blue
  number: { color: "var(--color-purple)" }, // #7C3AED equivalent
  boolean: { color: "var(--color-purple)" }, // #7C3AED equivalent
  null: { color: "var(--muted-foreground)" },
  default: {},
} as const;

export const getValueTypeStyle = (value: JsonValue): React.CSSProperties => {
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

export const getValuePreview = (value: JsonValue): string => {
  if (value === null) return "null";
  if (Array.isArray(value)) return `Array[${value.length}]`;
  if (typeof value === "object") return `Object{${Object.keys(value).length}}`;
  if (typeof value === "string" && value.length > 30) {
    return `"${value.substring(0, 30)}..."`;
  }
  return JSON.stringify(value);
};

export const getVisiblePaths = (
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
    if (
      expandedPaths.has(path) &&
      value !== null &&
      typeof value === "object"
    ) {
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
