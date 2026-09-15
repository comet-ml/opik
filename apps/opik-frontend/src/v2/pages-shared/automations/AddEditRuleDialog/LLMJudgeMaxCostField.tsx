import React, { useEffect, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";
import {
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";

// Positive decimal only (also rejects the sign/exponent/comma that type=number would accept).
const POSITIVE_DECIMAL_REGEX = /^\d*\.?\d*$/;

type MaxCostInputProps = {
  value: number | null | undefined;
  hasError: boolean;
  onChange: (value: number | null) => void;
};

// Decimal budget field. The committed form value is a number, but a controlled type=number bound to
// that number erases an in-progress trailing decimal point ("1." commits as 1 and re-renders "1"),
// making fractional entry impossible. So keep the raw text as the source of truth for what's displayed
// and commit the parsed number separately, re-syncing only when the value changes from outside typing.
const MaxCostInput: React.FC<MaxCostInputProps> = ({
  value,
  hasError,
  onChange,
}) => {
  const [text, setText] = useState(value == null ? "" : String(value));

  useEffect(() => {
    const parsed = text === "" ? null : Number(text);
    const reflectsCurrentText =
      value === parsed || (value == null && parsed == null);
    if (!reflectsCurrentText) {
      setText(value == null ? "" : String(value));
    }
    // Re-sync display only on external value changes (form reset / editing an existing rule), not on
    // the value we just committed from our own typing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  return (
    <Input
      type="text"
      inputMode="decimal"
      placeholder="No limit"
      value={text}
      className={cn("max-w-40", { "border-destructive": hasError })}
      onChange={(event) => {
        const raw = event.target.value;
        if (raw !== "" && !POSITIVE_DECIMAL_REGEX.test(raw)) {
          return;
        }
        setText(raw);
        const parsed = raw === "" ? null : Number(raw);
        onChange(parsed === null || Number.isNaN(parsed) ? null : parsed);
      }}
    />
  );
};

type LLMJudgeMaxCostFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
};

/**
 * Per-evaluation spend limit for LLM-as-judge rules. Only meaningful where the
 * judge can run a multi-turn (agentic) loop — trace and thread scope — so the
 * caller hides it on span scope, where scoring is a single LLM call.
 */
const LLMJudgeMaxCostField: React.FC<LLMJudgeMaxCostFieldProps> = ({
  form,
}) => (
  <FormField
    control={form.control}
    name="llmJudgeDetails.maxCostUsd"
    render={({ field, formState }) => {
      const validationErrors = get(formState.errors, [
        "llmJudgeDetails",
        "maxCostUsd",
      ]);

      return (
        <FormItem>
          <FormLabel>Max cost per evaluation (USD)</FormLabel>
          <FormControl>
            <MaxCostInput
              value={field.value}
              hasError={Boolean(validationErrors?.message)}
              onChange={field.onChange}
            />
          </FormControl>
          <FormDescription className="comet-body-xs text-muted-slate">
            Once an evaluation&apos;s spend reaches this amount the judge wraps
            up and returns its scores so far. Leave empty for no limit.
          </FormDescription>
          <FormMessage />
        </FormItem>
      );
    }}
  />
);

export default LLMJudgeMaxCostField;
