import { useCallback, useMemo, useState } from "react";
import uniqid from "uniqid";
import { Filter, FilterOperator } from "@/types/filters";
import { JsonValue } from "@/types/shared";
import { createFilter } from "@/lib/filters";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  ChipDefinition,
  ChipValue,
  ChipValueMap,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";
import {
  QuickAttributeFilterApi,
  QuickFilterSection,
} from "@/shared/filter-chips/QuickAttributeFilterContext";
import { QuickFilterDraft } from "@/shared/filter-chips/QuickFilterDialog";

const METADATA_CHIP_ID = "metadata";
const CUSTOM_CHIP_ID = "custom";
const PROVIDER_CHIP_ID = "provider";
const DEFAULT_OPERATOR: FilterOperator = "=";
const FALLBACK_OPERATORS: FilterOperator[] = ["="];

// "providers" (trace) is a read-time aggregate with no stored column; "provider"
// (span) is enriched into metadata but is filterable via the dedicated provider
// column. Match the root key and any array/object descendants of "providers".
const PROVIDERS_KEY = "providers";
const PROVIDER_KEY = "provider";

const isProvidersAggregateKey = (path: string): boolean =>
  path === PROVIDERS_KEY ||
  path.startsWith(`${PROVIDERS_KEY}[`) ||
  path.startsWith(`${PROVIDERS_KEY}.`);

const isProviderRootKey = (path: string): boolean => path === PROVIDER_KEY;

interface UseQuickAttributeFilterActionsArgs {
  type: TRACE_DATA_TYPE;
  definitions: ChipDefinition[];
  values: ChipValueMap;
  applyValue: (id: string, value: ChipValue) => void;
  pinChip: (id: string) => void;
}

export interface QuickAttributeFilterActions extends QuickAttributeFilterApi {
  dialog: {
    draft: QuickFilterDraft | null;
    onApply: (operator: FilterOperator, value: string) => void;
    onClose: () => void;
  };
}

const getRows = (value: ChipValue | undefined): Filter[] => {
  if (!value || typeof value !== "object") return [];
  const candidate = (value as QueryBuilderChipValue).rows;
  return Array.isArray(candidate) ? candidate : [];
};

export const stringifyFilterValue = (value: JsonValue): string => {
  if (value === null) return "";
  if (typeof value === "string") return value;
  return String(value);
};

type FilterTarget = { chipId: string; key?: string };

// Resolves which chip/field a quick-filter targets. Returns null when the
// attribute can't be filtered for the active tab (caller hides the action).
export const resolveQuickFilterTarget = (
  section: QuickFilterSection,
  type: TRACE_DATA_TYPE,
  path: string,
): FilterTarget | null => {
  if (section === "metadata") {
    if (isProvidersAggregateKey(path)) return null;
    if (isProviderRootKey(path)) {
      // Spans store provider in a dedicated column; traces have no such field.
      return type === TRACE_DATA_TYPE.spans
        ? { chipId: PROVIDER_CHIP_ID }
        : null;
    }
    return { chipId: METADATA_CHIP_ID, key: path };
  }
  // input / output map to the custom filter, which keeps the root prefix.
  return { chipId: CUSTOM_CHIP_ID, key: `${section}.${path}` };
};

export const useQuickAttributeFilterActions = ({
  type,
  definitions,
  values,
  applyValue,
  pinChip,
}: UseQuickAttributeFilterActionsArgs): QuickAttributeFilterActions => {
  const [draft, setDraft] = useState<QuickFilterDraft | null>(null);

  const canFilter = useCallback(
    (section: QuickFilterSection, path: string) =>
      Boolean(path) && resolveQuickFilterTarget(section, type, path) !== null,
    [type],
  );

  // Opens the approval dialog seeded from the clicked attribute. The actual
  // filter is applied only when the user confirms.
  const filter = useCallback(
    (section: QuickFilterSection, path: string, value: JsonValue) => {
      const target = resolveQuickFilterTarget(section, type, path);
      if (!target) return;

      const definition = definitions.find((d) => d.id === target.chipId);
      const operators =
        definition && definition.kind === "query-builder"
          ? definition.operators
          : FALLBACK_OPERATORS;
      const chipLabel = definition?.label ?? target.chipId;

      setDraft({
        id: uniqid(),
        chipId: target.chipId,
        key: target.key,
        chipLabel,
        field: target.key ?? chipLabel,
        operators,
        defaultOperator: DEFAULT_OPERATOR,
        value: stringifyFilterValue(value),
      });
    },
    [type, definitions],
  );

  const onClose = useCallback(() => setDraft(null), []);

  const onApply = useCallback(
    (operator: FilterOperator, value: string) => {
      if (!draft) return;
      const { chipId, key } = draft;

      const existing = getRows(values[chipId]);
      const alreadyApplied = existing.some(
        (row) =>
          (row.key ?? "") === (key ?? "") &&
          row.operator === operator &&
          String(row.value) === value,
      );

      if (!alreadyApplied) {
        const nextRow = createFilter({
          ...(key !== undefined ? { key } : {}),
          operator,
          value,
        });
        // Drop blank draft rows but keep any real rows the user already added.
        const nextRows = existing.filter(
          (row) => (row.key ?? "") !== "" || (row.value ?? "") !== "",
        );
        applyValue(chipId, { rows: [...nextRows, nextRow] });
      }

      pinChip(chipId);
      setDraft(null);
    },
    [draft, values, applyValue, pinChip],
  );

  return useMemo(
    () => ({ canFilter, filter, dialog: { draft, onApply, onClose } }),
    [canFilter, filter, draft, onApply, onClose],
  );
};
