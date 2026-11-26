import React, { useRef, useState } from "react";
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
import { OperatorCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/OperatorCell";
import { NumericCell } from "@/shared/filter-chips/chips/QueryBuilderChip/cells/NumericCell";
import { InlineFilterRow } from "@/shared/filter-chips/chips/QueryBuilderChip/FilterRow";

interface NumericChipPopoverContentProps {
  definition: NumericChipDefinition;
  value: NumericChipValue | undefined;
  onApply: (value: NumericChipValue) => void;
  onClear: () => void;
  inline?: boolean;
}

const OPERATOR_OPTIONS: FilterOperatorOption<NumericChipMode>[] = [
  { value: "exactly", label: "Is" },
  { value: "atLeast", label: "Is at least" },
  { value: "atMost", label: "Is at most" },
  { value: "between", label: "Is between" },
];

const pickFirstEmptyValue = (
  mode: NumericChipMode,
  from: string,
  to: string,
): "from" | "to" => {
  if (trimValue(from) === "") return "from";
  if (mode === "between" && trimValue(to) === "") return "to";
  return "from";
};

const initialFromDraft = (value: NumericChipValue | undefined): string => {
  if (!value) return "";
  if (value.mode === "exactly") return String(value.exact);
  if (value.mode === "atMost") return String(value.max);
  return String(value.min);
};

const initialToDraft = (value: NumericChipValue | undefined): string =>
  value?.mode === "between" ? String(value.max) : "";

const toNumericValue = (
  mode: NumericChipMode,
  from: number | null,
  to: number | null,
): NumericChipValue | null => {
  if (from === null) return null;
  switch (mode) {
    case "exactly":
      return { mode, exact: from };
    case "atLeast":
      return { mode, min: from };
    case "atMost":
      return { mode, max: from };
    case "between":
      return to !== null && from <= to ? { mode, min: from, max: to } : null;
  }
};

const NumericChipPopoverContent: React.FC<NumericChipPopoverContentProps> = ({
  definition,
  value,
  onApply,
  onClear,
  inline = false,
}) => {
  const [mode, setMode] = useState<NumericChipMode>(
    value?.mode ?? NUMERIC_DEFAULT_MODE,
  );
  const [fromDraft, setFromDraft] = useState<string>(() =>
    initialFromDraft(value),
  );
  const [toDraft, setToDraft] = useState<string>(() => initialToDraft(value));
  const format = resolveNumericFormat(definition);

  const inputFromRef = useRef<HTMLInputElement>(null);
  const inputToRef = useRef<HTMLInputElement>(null);
  const pendingValueFocusRef = useRef<"from" | "to" | null>(null);

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
    const value = toNumericValue(m, toNumber(fromRaw), toNumber(toRaw));
    if (value) onApply(value);
    // A popover keeps its last valid filter while one is mistyped. Inline, that filter would be saved
    // while a different one is shown, so an invalid entry clears it instead.
    else if (inline) onClear();
  };

  const handleModeChange = (next: NumericChipMode) => {
    setMode(next);
    apply({ mode: next });
    pendingValueFocusRef.current = pickFirstEmptyValue(
      next,
      fromDraft,
      toDraft,
    );
  };

  const handleOperatorMenuClose = (event: Event) => {
    event.preventDefault();
    const target = pendingValueFocusRef.current;
    pendingValueFocusRef.current = null;
    if (target === "from") inputFromRef.current?.focus();
    else if (target === "to") inputToRef.current?.focus();
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

  // The unit sits inside each value cell, as it does in the popover's inputs.
  const renderInlineValue = (
    draft: string,
    onChange: (next: string) => void,
    onBlur: (event: React.FocusEvent<HTMLInputElement>) => void,
  ) => (
    <div className="relative flex min-w-0 flex-1">
      {format.prefix && (
        <span className="comet-body-xs pointer-events-none absolute left-2 top-1/2 z-20 -translate-y-1/2 text-muted-slate">
          {format.prefix}
        </span>
      )}
      <NumericCell
        grow
        value={draft}
        placeholder={format.placeholder}
        onChange={onChange}
        onBlur={onBlur}
        className={cn(
          "text-right",
          format.prefix && "pl-5",
          format.suffix && "pr-5",
          betweenError && "border-destructive",
        )}
      />
      {format.suffix && (
        <span className="comet-body-xs pointer-events-none absolute right-2 top-1/2 z-20 -translate-y-1/2 text-muted-slate">
          {format.suffix}
        </span>
      )}
    </div>
  );

  if (inline) {
    return (
      <div className="flex flex-col gap-1">
        <InlineFilterRow>
          <OperatorCell
            value={mode}
            options={OPERATOR_OPTIONS}
            onSelect={handleModeChange}
            ariaLabel="Operator"
          />
          {renderInlineValue(fromDraft, handleFromChange, (event) =>
            padDraftOnBlur("from", event),
          )}
          {mode === "between" && (
            <>
              <span className="comet-body-xs shrink-0 px-2 text-muted-slate">
                and
              </span>
              {renderInlineValue(toDraft, handleToChange, (event) =>
                padDraftOnBlur("to", event),
              )}
            </>
          )}
        </InlineFilterRow>
        {betweenError && (
          <FormErrorSkeleton className="comet-body-xs">
            From value must be less than or equal to To value
          </FormErrorSkeleton>
        )}
      </div>
    );
  }

  return (
    <div className="flex w-[291px] flex-col gap-4 p-3">
      <FilterOperatorSelect
        fieldLabel={definition.label}
        value={mode}
        options={OPERATOR_OPTIONS}
        onChange={handleModeChange}
        onCloseAutoFocus={handleOperatorMenuClose}
      />

      {mode === "between" ? (
        <div className="flex flex-col gap-1">
          <div className="flex gap-2">
            <NumericInputField
              label="From"
              value={fromDraft}
              format={format}
              hasError={betweenError}
              autoFocus
              inputRef={inputFromRef}
              onValueChange={handleFromChange}
              onBlur={(event) => padDraftOnBlur("from", event)}
            />
            <NumericInputField
              label="To"
              value={toDraft}
              format={format}
              hasError={betweenError}
              inputRef={inputToRef}
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
          autoFocus
          inputRef={inputFromRef}
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
  autoFocus?: boolean;
  inputRef?: React.Ref<HTMLInputElement>;
  onValueChange: (raw: string | number | readonly string[] | undefined) => void;
  onBlur?: (event: React.FocusEvent<HTMLInputElement>) => void;
}

const NumericInputField: React.FC<NumericInputFieldProps> = ({
  label,
  value,
  format,
  hasError = false,
  autoFocus,
  inputRef,
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
        ref={inputRef}
        type="number"
        dimension="sm"
        step={format.integerOnly ? "1" : "any"}
        autoFocus={autoFocus}
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
