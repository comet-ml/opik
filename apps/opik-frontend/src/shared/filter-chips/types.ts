import { COLUMN_TYPE } from "@/types/shared";
import { Filter, FilterOperator } from "@/types/filters";

export interface ChipOptionsResult {
  items: string[];
  isLoading: boolean;
}

export type ChipOptionsConfig =
  | { hook: (args: unknown) => ChipOptionsResult; args: unknown }
  | { value: ChipOptionsResult };

export const chipOptions = <TArgs>(
  hook: (args: TArgs) => ChipOptionsResult,
  args: TArgs,
): ChipOptionsConfig => ({
  hook: hook as (args: unknown) => ChipOptionsResult,
  args,
});

export const chipOptionsValue = (
  value: ChipOptionsResult,
): ChipOptionsConfig => ({ value });

export const resolveChipOptions = (
  config: ChipOptionsConfig | undefined,
): ChipOptionsResult => {
  if (!config) return { items: [], isLoading: false };
  if ("value" in config) return config.value;
  return config.hook(config.args);
};

interface ChipDefinitionBase {
  id: string;
  field: string;
  label: string;
  group?: string;
}

interface SingleSelectOption {
  label: string;
  value: string;
}

export interface SingleSelectChipDefinition extends ChipDefinitionBase {
  kind: "single-select";
  options: SingleSelectOption[];
  columnType?: COLUMN_TYPE;
  operator?: FilterOperator;
}

export interface PseudoSearchChipDefinition extends ChipDefinitionBase {
  kind: "pseudo-search";
  searchMode: "contains" | "equals";
  columnType?: COLUMN_TYPE;
  placeholder?: string;
}

export interface BooleanChipDefinition extends ChipDefinitionBase {
  kind: "boolean";
  onOperator: FilterOperator;
  onValue?: string;
  columnType?: COLUMN_TYPE;
}

export type NumericChipMode = "exactly" | "between" | "atLeast" | "atMost";

export type NumericChipFormat = "integer" | "decimal" | "currency" | "duration";

export interface NumericChipDefinition extends ChipDefinitionBase {
  kind: "numeric";
  columnType?: COLUMN_TYPE;
  format?: NumericChipFormat;
}

export type TimeChipMode = "exactly" | "between" | "before" | "after";

export interface TimeChipDefinition extends ChipDefinitionBase {
  kind: "time";
  columnType?: COLUMN_TYPE;
}

interface QueryBuilderKeyConfig {
  placeholder?: string;
  options?: ChipOptionsConfig;
  validate?: (value: string) => string | undefined;
}

interface QueryBuilderValueConfig {
  type?: "text" | "numeric";
  placeholder?: string;
  options?: ChipOptionsConfig;
  decimals?: number;
  validate?: (value: string) => string | undefined;
}

export interface QueryBuilderChipDefinition extends ChipDefinitionBase {
  kind: "query-builder";
  columnType: COLUMN_TYPE;
  operators: FilterOperator[];
  defaultOperator?: FilterOperator;
  addLabel?: string;
  // At least one of `key` / `value` must carry `options` — drives the
  // empty-state search source. Runtime-enforced (not at type level).
  key?: QueryBuilderKeyConfig;
  value?: QueryBuilderValueConfig;
}

export type ChipDefinition =
  | SingleSelectChipDefinition
  | PseudoSearchChipDefinition
  | BooleanChipDefinition
  | NumericChipDefinition
  | TimeChipDefinition
  | QueryBuilderChipDefinition;

export interface SingleSelectChipValue {
  value: string;
}

export interface PseudoSearchChipValue {
  value: string;
}

export interface BooleanChipValue {
  applied: true;
}

export type NumericChipValue =
  | { mode: "exactly"; exact: number }
  | { mode: "atLeast"; min: number }
  | { mode: "atMost"; max: number }
  | { mode: "between"; min: number; max: number };

export type TimeChipValue =
  | { mode: "exactly"; at: string }
  | { mode: "between"; start: string; end: string }
  | { mode: "before"; before: string }
  | { mode: "after"; after: string };

export interface QueryBuilderChipValue {
  rows: Filter[];
}

export type ChipValue =
  | SingleSelectChipValue
  | PseudoSearchChipValue
  | BooleanChipValue
  | NumericChipValue
  | TimeChipValue
  | QueryBuilderChipValue;

export type ChipValueMap = Record<string, ChipValue>;
