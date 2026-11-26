import { DropdownOption } from "@/types/shared";

export const WINDOW_OPTIONS: DropdownOption<string>[] = [
  { label: "5 mins", value: "300" },
  { label: "15 mins", value: "900" },
  { label: "30 mins", value: "1800" },
  { label: "1 hour", value: "3600" },
  { label: "6 hours", value: "21600" },
  { label: "12 hours", value: "43200" },
  { label: "24 hours", value: "86400" },
  { label: "7 days", value: "604800" },
  { label: "15 days", value: "1296000" },
  { label: "30 days", value: "2592000" },
];

export const WINDOW_LABEL_BY_VALUE: Record<string, string> = Object.fromEntries(
  WINDOW_OPTIONS.map((o) => [o.value, o.label]),
);

/**
 * The operators every caller supports, and the shape the shared condition type is built from. Alerts
 * accept exactly these — their API rejects anything else — so this stays the narrow pair.
 */
export const OPERATOR_VALUES = [">", "<"] as const;

/** Everything the builder can render. Callers opt into the extras with the `operators` prop. */
export const ALL_OPERATOR_VALUES = [">", "<", "="] as const;
export type OperatorValue = (typeof ALL_OPERATOR_VALUES)[number];

export const DEFAULT_OPERATORS: OperatorValue[] = [...OPERATOR_VALUES];

export const OPERATOR_LABELS: Record<OperatorValue, string> = {
  ">": "greater than",
  "<": "less than",
  "=": "equals",
};
