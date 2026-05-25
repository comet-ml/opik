import React, { useEffect, useMemo, useState } from "react";
import { cn, padDecimalsString } from "@/lib/utils";
import { Filter, FilterOperator } from "@/types/filters";
import { createFilter } from "@/lib/filters";
import { NO_VALUE_OPERATORS } from "@/constants/filters";
import { QueryFilterShell } from "@/shared/filter-chips/chips/QueryBuilderChip/QueryFilterShell";
import { OperatorCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/OperatorCell";
import { AutocompleteCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/AutocompleteCell";
import { TextCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/TextCell";
import { NumericCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/NumericCell";
import {
  QueryBuilderChipDefinition,
  QueryBuilderChipValue,
  resolveChipOptions,
} from "@/shared/filter-chips/types";
import {
  getOperatorLabel,
  operatorNeedsValue,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";

interface QueryBuilderChipPopoverContentProps {
  definition: QueryBuilderChipDefinition;
  value: QueryBuilderChipValue | undefined;
  onApply: (value: QueryBuilderChipValue) => void;
  onClear: () => void;
}

const isRowApplied =
  (
    hasKey: boolean,
    keyValidate?: (v: string) => string | undefined,
    valueValidate?: (v: string) => string | undefined,
  ) =>
  (row: Filter): boolean => {
    if (hasKey && !row.key) return false;
    if (!row.operator) return false;
    if (hasKey && keyValidate && keyValidate(row.key ?? "")) return false;
    if (NO_VALUE_OPERATORS.includes(row.operator as FilterOperator))
      return true;
    if (row.value === "" || row.value === undefined || row.value === null)
      return false;
    if (valueValidate && valueValidate(String(row.value))) return false;
    return true;
  };

const QueryBuilderChipPopoverContent: React.FC<
  QueryBuilderChipPopoverContentProps
> = ({ definition, value, onApply, onClear }) => {
  const keyConfig = definition.key;
  const valueConfig = definition.value;

  const keyOptions = resolveChipOptions(keyConfig?.options);
  const valueOptions = resolveChipOptions(valueConfig?.options);

  const operatorOptions = useMemo(
    () =>
      definition.operators.map((op) => ({
        label: getOperatorLabel(op),
        value: op,
      })),
    [definition.operators],
  );

  const defaultOperator: FilterOperator =
    definition.defaultOperator ?? definition.operators[0];

  const [rows, setRows] = useState<Filter[]>(() => value?.rows ?? []);
  const [focusValueRowId, setFocusValueRowId] = useState<string | null>(null);

  useEffect(() => {
    if (focusValueRowId === null) return;
    const raf = requestAnimationFrame(() => setFocusValueRowId(null));
    return () => cancelAnimationFrame(raf);
  }, [focusValueRowId]);

  const rowAppliedCheck = useMemo(
    () =>
      isRowApplied(
        Boolean(keyConfig),
        keyConfig?.validate,
        valueConfig?.validate,
      ),
    [keyConfig, valueConfig],
  );

  const commit = (next: Filter[]) => {
    setRows(next);
    const applied = next.filter(rowAppliedCheck);
    if (applied.length === 0) onClear();
    else onApply({ rows: next });
  };

  const handleAddRow = (picked: string) => {
    const blank = createFilter({
      key: keyConfig ? picked : "",
      operator: defaultOperator,
      value: keyConfig ? "" : picked,
    });
    commit([...rows, blank]);
    if (keyConfig) setFocusValueRowId(blank.id);
  };

  const updateRow = (id: string, patch: Partial<Filter>) =>
    commit(rows.map((row) => (row.id === id ? { ...row, ...patch } : row)));

  const handleRemove = (index: number) =>
    commit(rows.filter((_, i) => i !== index));

  const handleClearAll = () => {
    setRows([]);
    onClear();
  };

  const isNumericValue = valueConfig?.type === "numeric";

  const searchOptions = keyConfig?.options ? keyOptions : valueOptions;
  const searchPlaceholder = keyConfig?.options
    ? keyConfig.placeholder ?? `Filter by ${definition.label.toLowerCase()}…`
    : valueConfig?.placeholder ??
      `Filter by ${definition.label.toLowerCase()}…`;
  const searchItemNoun = definition.label.toLowerCase();

  return (
    <QueryFilterShell<Filter>
      rows={rows}
      renderRow={(row) => {
        const op = (row.operator || defaultOperator) as FilterOperator;
        const showValue = operatorNeedsValue(op);
        const keyError =
          keyConfig?.validate && row.key
            ? keyConfig.validate(row.key)
            : undefined;
        const focusThisValue = focusValueRowId === row.id;
        return (
          <>
            {keyConfig && (
              <AutocompleteCell
                grow
                value={row.key ?? ""}
                placeholder={keyConfig.placeholder ?? "key"}
                options={keyOptions}
                itemNoun={definition.label.toLowerCase()}
                hasError={Boolean(keyError)}
                onChange={(next) => updateRow(row.id, { key: next })}
                onPick={() => setFocusValueRowId(row.id)}
              />
            )}
            <OperatorCell
              value={op}
              options={operatorOptions}
              onSelect={(next) => {
                const patch: Partial<Filter> = { operator: next };
                if (!operatorNeedsValue(next)) patch.value = "";
                updateRow(row.id, patch);
              }}
              ariaLabel="Operator"
            />
            {showValue &&
              (isNumericValue ? (
                <NumericCell
                  value={String(row.value ?? "")}
                  placeholder={valueConfig?.placeholder ?? "0"}
                  autoFocus={focusThisValue}
                  onChange={(next) => updateRow(row.id, { value: next })}
                  onBlur={(event) => {
                    const padded = padDecimalsString(
                      event.target.value,
                      valueConfig?.decimals ?? 2,
                    );
                    if (padded !== event.target.value)
                      updateRow(row.id, { value: padded });
                  }}
                  className={cn("text-right")}
                />
              ) : valueConfig?.options ? (
                <AutocompleteCell
                  grow={!keyConfig}
                  value={String(row.value ?? "")}
                  placeholder={valueConfig.placeholder ?? "value"}
                  options={valueOptions}
                  itemNoun={definition.label.toLowerCase()}
                  autoFocus={focusThisValue}
                  onChange={(next) => updateRow(row.id, { value: next })}
                />
              ) : (
                <TextCell
                  grow={!keyConfig}
                  value={String(row.value ?? "")}
                  placeholder={valueConfig?.placeholder ?? "value"}
                  autoFocus={focusThisValue}
                  onChange={(next) => updateRow(row.id, { value: next })}
                />
              ))}
          </>
        );
      }}
      isRowApplied={rowAppliedCheck}
      onRemoveRow={handleRemove}
      onAddRow={(picked) => {
        handleAddRow(picked);
      }}
      onClearAll={handleClearAll}
      searchPlaceholder={searchPlaceholder}
      searchOptions={searchOptions}
      searchItemNoun={searchItemNoun}
      addLabel={definition.addLabel ?? `Add ${definition.label.toLowerCase()}`}
    />
  );
};

export default QueryBuilderChipPopoverContent;
