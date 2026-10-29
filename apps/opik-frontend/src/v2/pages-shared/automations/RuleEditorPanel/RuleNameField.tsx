import React from "react";
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
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";

type RuleNameFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
};

const RuleNameField: React.FC<RuleNameFieldProps> = ({ form }) => {
  return (
    <FormField
      control={form.control}
      name="ruleName"
      render={({ field, fieldState }) => (
        <FormItem className="gap-1">
          <FormLabel className="px-0.5 pb-0.5">Name</FormLabel>
          <FormControl>
            <Input
              dimension="sm"
              placeholder="Evaluator name"
              className={cn(fieldState.error && "border-destructive")}
              {...field}
            />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
};

export default RuleNameField;
