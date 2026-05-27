import React, { useState } from "react";
import { FormErrorSkeleton } from "@/ui/form";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { PopoverClearFooter } from "@/shared/filter-chips/chips/PopoverClearFooter";
import FilterOperatorSelect, {
  FilterOperatorOption,
} from "@/shared/filter-chips/chips/FilterOperatorSelect";
import { cn, padDecimalsString } from "@/lib/utils";
import {
  NumericChipDefinition,
  NumericChipMode,
  NumericChipValue,
} from "@/shared/filter-chips/types";
import {
  NumericFormat,
  resolveNumericFormat,
} from "@/shared/filter-chips/chips/NumericChip/NumericChip.format";
import {
  NUMERIC_DEFAULT_MODE,
  isNumericApplied,
} from "@/shared/filter-chips/chips/NumericChip/NumericChip.logic";
import { toNumber, trimValue } from "@/shared/filter-chips/lib/helpers";

interface NumericChipPopoverContentProps {
  definition: NumericChipDefinition;
  value: NumericChipValue | undefined;
  onApply: (value: NumericChipValue) => void;
  onClear: () => void;
}

const OPERATOR_OPTIONS: FilterOperatorOption<NumericChipMode>[] = [
  { value: "exactly", label: "Is" },
  { value: "atLeast", label: "Is at least" },
  { value: "atMost", label: "Is at most" },
  { value: "between", label: "Is between" },
];

const initialFromDraft = (value: NumericChipValue | undefined): string => {
  if (!value) return "";
  if (value.mode === "exactly") return String(value.exact);
  if (value.mode === "atMost") return String(value.max);
  return String(value.min);
};

const initialToDraft = (value: NumericChipValue | undefined): string =>
  value?.mode === "between" ? String(value.max) : "";

const NumericChipPopoverContent: React.FC<NumericChipPopoverContentProps> = ({
  definition,
  value,
  onApply,
  onClear,
}) => {
  const [mode, setMode] = useState<NumericChipMode>(
    value?.mode ?? NUMERIC_DEFAULT_MODE,
  );
  const [fromDraft, setFromDraft] = useState<string>(() =>
    initialFromDraft(value),
  );
  const [toDraft, setToDraft] = useState<string>(() => initialToDraft(value));
  const format = resolveNumericFormat(definition);

  const apply = (next: {
    mode?: NumericChipMode;
    from?: string;
    to?: string;
  }) => {
    const m = next.mode ?? mode;
    const fromRaw = next.from ?? fromDraft;
    const toRaw = next.to ?? toDraft;
    const fromEmpty = trimValue(fromRaw) === "";
    const toEmpty = trimValue(toRaw) === "";
    if (m === "between" ? fromEmpty || toEmpty : fromEmpty) {
      onClear();
      return;
    }
    const f = toNumber(fromRaw);
    const t = toNumber(toRaw);
    switch (m) {
      case "exactly":
        if (f !== null) onApply({ mode: "exactly", exact: f });
        break;
      case "atLeast":
        if (f !== null) onApply({ mode: "atLeast", min: f });
        break;
      case "atMost":
        if (f !== null) onApply({ mode: "atMost", max: f });
        break;
      case "between":
        if (f !== null && t !== null && f <= t)
          onApply({ mode: "between", min: f, max: t });
        break;
    }
  };

  const handleModeChange = (next: NumericChipMode) => {
    setMode(next);
    apply({ mode: next });
  };

  const handleFromChange = (
    raw: string | number | readonly string[] | undefined,
  ) => {
    const s = String(raw ?? "");
    setFromDraft(s);
    apply({ from: s });
  };

  const handleToChange = (
    raw: string | number | readonly string[] | undefined,
  ) => {
    const s = String(raw ?? "");
    setToDraft(s);
    apply({ to: s });
  };

  const handleClear = () => {
    setMode(NUMERIC_DEFAULT_MODE);
    setFromDraft("");
    setToDraft("");
    onClear();
  };

  const padDraftOnBlur = (
    side: "from" | "to",
    event: React.FocusEvent<HTMLInputElement>,
  ) => {
    const current = event.target.value;
    const padded = padDecimalsString(
      current,
      format.decimals,
      format.integerOnly,
    );
    if (padded === current) return;
    if (side === "from") setFromDraft(padded);
    else setToDraft(padded);
    apply({ [side]: padded });
  };

  const fromNum = toNumber(fromDraft);
  const toNum = toNumber(toDraft);
  const betweenError =
    mode === "between" && fromNum !== null && toNum !== null && fromNum > toNum;

  return (
    <div className="flex w-[291px] flex-col gap-4 p-3">
      <FilterOperatorSelect
        fieldLabel={definition.label}
        value={mode}
        options={OPERATOR_OPTIONS}
        onChange={handleModeChange}
      />

      {mode === "between" ? (
        <div className="flex flex-col gap-1">
          <div className="flex gap-2">
            <NumericInputField
              label="From"
              value={fromDraft}
              format={format}
              hasError={betweenError}
              onValueChange={handleFromChange}
              onBlur={(event) => padDraftOnBlur("from", event)}
            />
            <NumericInputField
              label="To"
              value={toDraft}
              format={format}
              hasError={betweenError}
              onValueChange={handleToChange}
              onBlur={(event) => padDraftOnBlur("to", event)}
            />
          </div>
          {betweenError && (
            <FormErrorSkeleton className="comet-body-xs">
              From value must be less than or equal to To value
            </FormErrorSkeleton>
          )}
        </div>
      ) : (
        <NumericInputField
          value={fromDraft}
          format={format}
          onValueChange={handleFromChange}
          onBlur={(event) => padDraftOnBlur("from", event)}
        />
      )}

      <PopoverClearFooter
        onClear={handleClear}
        disabled={!isNumericApplied(value)}
      />
    </div>
  );
};

interface NumericInputFieldProps {
  label?: string;
  value: string;
  format: NumericFormat;
  hasError?: boolean;
  onValueChange: (raw: string | number | readonly string[] | undefined) => void;
  onBlur?: (event: React.FocusEvent<HTMLInputElement>) => void;
}

const NumericInputField: React.FC<NumericInputFieldProps> = ({
  label,
  value,
  format,
  hasError = false,
  onValueChange,
  onBlur,
}) => (
  <div className="flex min-w-0 flex-1 flex-col gap-1">
    {label && (
      <label className="comet-body-xs px-0.5 text-muted-slate">{label}</label>
    )}
    <div className="relative">
      {format.prefix && (
        <span className="comet-body-s pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-slate">
          {format.prefix}
        </span>
      )}
      {format.suffix && (
        <span className="comet-body-s pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-muted-slate">
          {format.suffix}
        </span>
      )}
      <DebounceInput
        type="number"
        dimension="sm"
        step={format.integerOnly ? "1" : "any"}
        value={value}
        onValueChange={onValueChange}
        onBlur={onBlur}
        placeholder={format.placeholder}
        className={cn(
          "text-right",
          format.prefix && "pl-7",
          format.suffix && "pr-7",
          hasError && "border-destructive",
        )}
      />
    </div>
  </div>
);

export default NumericChipPopoverContent;
