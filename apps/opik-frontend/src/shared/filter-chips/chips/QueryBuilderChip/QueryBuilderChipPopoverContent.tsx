import React, { useEffect, useMemo, useState } from "react";
import { Plus } from "lucide-react";
import { Button } from "@/ui/button";
import { PopoverClose } from "@/ui/popover";
import { cn, padDecimalsString } from "@/lib/utils";
import { Filter, FilterOperator } from "@/types/filters";
import { createFilter } from "@/lib/filters";
import { NO_VALUE_OPERATORS } from "@/constants/filters";
import { FilterRow } from "@/shared/filter-chips/chips/QueryBuilderChip/FilterRow";
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

type FocusedField = { rowId: string; kind: "key" | "value" } | null;

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

  const initialDraft = useMemo(
    () => createFilter({ operator: defaultOperator }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const [rows, setRows] = useState<Filter[]>(() => {
    const existing = value?.rows ?? [];
    return existing.length > 0 ? existing : [initialDraft];
  });

  const [focusedField, setFocusedField] = useState<FocusedField>(() => {
    const existing = value?.rows ?? [];
    if (existing.length > 0) return null;
    return { rowId: initialDraft.id, kind: keyConfig ? "key" : "value" };
  });

  useEffect(() => {
    if (focusedField === null) return;
    const raf = requestAnimationFrame(() => setFocusedField(null));
    return () => cancelAnimationFrame(raf);
  }, [focusedField]);

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

  const handleAddEmpty = () => {
    const blank = createFilter({ operator: defaultOperator });
    commit([...rows, blank]);
    setFocusedField({ rowId: blank.id, kind: keyConfig ? "key" : "value" });
  };

  const updateRow = (id: string, patch: Partial<Filter>) =>
    commit(rows.map((row) => (row.id === id ? { ...row, ...patch } : row)));

  const handleRemove = (index: number) =>
    commit(rows.filter((_, i) => i !== index));

  const handleClearAll = () => {
    const blank = createFilter({ operator: defaultOperator });
    setRows([blank]);
    onClear();
  };

  const isNumericValue = valueConfig?.type === "numeric";

  const renderRow = (row: Filter) => {
    const op = (row.operator || defaultOperator) as FilterOperator;
    const showValue = operatorNeedsValue(op);
    const keyError =
      keyConfig?.validate && row.key ? keyConfig.validate(row.key) : undefined;
    const focusKey =
      focusedField?.rowId === row.id && focusedField.kind === "key";
    const focusValue =
      focusedField?.rowId === row.id && focusedField.kind === "value";
    return (
      <>
        {keyConfig && (
          <AutocompleteCell
            grow
            value={row.key ?? ""}
            placeholder={keyConfig.placeholder ?? "key"}
            options={keyOptions}
            itemNoun={definition.label.toLowerCase()}
            autoFocus={focusKey}
            hasError={Boolean(keyError)}
            onChange={(next) => updateRow(row.id, { key: next })}
            onPick={() => setFocusedField({ rowId: row.id, kind: "value" })}
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
              autoFocus={focusValue}
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
              autoFocus={focusValue}
              onChange={(next) => updateRow(row.id, { value: next })}
            />
          ) : (
            <TextCell
              grow={!keyConfig}
              value={String(row.value ?? "")}
              placeholder={valueConfig?.placeholder ?? "value"}
              autoFocus={focusValue}
              onChange={(next) => updateRow(row.id, { value: next })}
            />
          ))}
      </>
    );
  };

  const appliedCount = rows.filter(rowAppliedCheck).length;
  const addLabel =
    definition.addLabel ?? `Add ${definition.label.toLowerCase()}`;

  return (
    <div
      className={cn("flex flex-col gap-2 p-3", "min-w-[360px] max-w-[800px]")}
    >
      <ul className="mb-2 flex flex-col gap-2">
        {rows.map((row, index) => (
          <li key={row.id}>
            <FilterRow
              onRemove={() => handleRemove(index)}
              disableRemove={rows.length === 1}
            >
              {renderRow(row)}
            </FilterRow>
          </li>
        ))}
      </ul>

      <Button
        variant="ghost"
        size="2xs"
        onClick={handleAddEmpty}
        className="self-start px-0 text-foreground hover:text-primary"
      >
        <Plus className="mr-1 size-3" />
        {addLabel}
      </Button>

      <div className="flex flex-col">
        <div className="border-t border-border" />
        <div className="flex items-center pt-2">
          <PopoverClose asChild>
            <Button
              variant="ghost"
              size="2xs"
              onClick={handleClearAll}
              disabled={appliedCount === 0}
              className="px-0 text-foreground hover:text-primary"
            >
              Clear{appliedCount > 0 ? ` (${appliedCount})` : ""}
            </Button>
          </PopoverClose>
        </div>
      </div>
    </div>
  );
};

export default QueryBuilderChipPopoverContent;
