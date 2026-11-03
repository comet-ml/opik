import React, { useState } from "react";
import { UseFormReturn } from "react-hook-form";

import { cn } from "@/lib/utils";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";

const POSITIVE_DECIMAL_REGEX = /^\d*\.?\d*$/;

type CostInputProps = {
  value: number | null | undefined;
  hasError: boolean;
  onChange: (value: number | null) => void;
};

const parseCost = (text: string) => {
  if (text === "") return null;
  const parsed = Number(text);
  return Number.isNaN(parsed) ? null : parsed;
};

const formatCost = (value: number | null | undefined) =>
  value == null ? "" : String(value);

const CostInput: React.FC<CostInputProps> = ({ value, hasError, onChange }) => {
  const [text, setText] = useState(formatCost(value));
  const [syncedValue, setSyncedValue] = useState(value);

  if (value !== syncedValue) {
    setSyncedValue(value);
    if ((value ?? null) !== parseCost(text)) setText(formatCost(value));
  }

  return (
    <Input
      type="text"
      inputMode="decimal"
      dimension="sm"
      placeholder="No limit"
      value={text}
      className={cn(hasError && "border-destructive")}
      onChange={(event) => {
        const raw = event.target.value;
        if (raw !== "" && !POSITIVE_DECIMAL_REGEX.test(raw)) return;
        setText(raw);
        onChange(parseCost(raw));
      }}
    />
  );
};

type CostLimitFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
};

const CostLimitField: React.FC<CostLimitFieldProps> = ({ form }) => (
  <FormField
    control={form.control}
    name="llmJudgeDetails.maxCostUsd"
    render={({ field, fieldState }) => (
      <FormItem className="gap-1">
        <FormLabel className="px-0.5 pb-0.5">Cost limit (USD)</FormLabel>
        <FormControl>
          <CostInput
            value={field.value}
            hasError={Boolean(fieldState.error)}
            onChange={field.onChange}
          />
        </FormControl>
        <Description className="pl-0.5 pr-1">
          Set a spending limit per evaluation. Once reached, the judge stops and
          returns the scores collected so far.
        </Description>
        <FormMessage />
      </FormItem>
    )}
  />
);

export default CostLimitField;
