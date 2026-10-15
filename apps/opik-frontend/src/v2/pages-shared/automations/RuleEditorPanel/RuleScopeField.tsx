import React from "react";
import { UseFormReturn } from "react-hook-form";
import { CornerDownRight } from "lucide-react";

import { FormControl, FormField, FormItem, FormLabel } from "@/ui/form";
import { Switch } from "@/ui/switch";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_SCOPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";

const EXCLUDE_EXPERIMENTS_SWITCH_ID = "rule-editor-exclude-experiments";

type RuleScopeFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onScopeChange: (scope: EVALUATORS_RULE_SCOPE) => void;
  disabled?: boolean;
};

const RuleScopeField: React.FC<RuleScopeFieldProps> = ({
  form,
  onScopeChange,
  disabled = false,
}) => {
  const isSpanLlmJudgeEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.SPAN_LLM_AS_JUDGE_ENABLED,
  );
  const isSpanPythonCodeEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.SPAN_USER_DEFINED_METRIC_PYTHON_ENABLED,
  );
  const uiType = form.watch("uiType");
  const scope = form.watch("scope");
  const isSpanScopeEnabled =
    uiType === UI_EVALUATORS_RULE_TYPE.llm_judge
      ? isSpanLlmJudgeEnabled
      : isSpanPythonCodeEnabled;

  const scopeOptions = [
    { value: EVALUATORS_RULE_SCOPE.thread, label: "Threads" },
    { value: EVALUATORS_RULE_SCOPE.trace, label: "Traces" },
    ...(isSpanScopeEnabled
      ? [{ value: EVALUATORS_RULE_SCOPE.span, label: "Spans" }]
      : []),
  ];

  return (
    <div className="flex flex-col gap-1.5">
      <FormField
        control={form.control}
        name="scope"
        render={({ field }) => (
          <FormItem className="gap-1">
            <FormLabel className="px-0.5 pb-0.5">Scope</FormLabel>
            <FormControl>
              <ToggleGroup
                type="single"
                variant="ghost"
                value={field.value}
                disabled={disabled}
                onValueChange={(value: EVALUATORS_RULE_SCOPE) =>
                  value && value !== field.value && onScopeChange(value)
                }
                className="w-full"
              >
                {scopeOptions.map((option) => (
                  <ToggleGroupItem
                    key={option.value}
                    value={option.value}
                    size="sm"
                    className="comet-body-xs h-6 flex-1 hover:bg-upload-icon-bg data-[state=on]:bg-muted data-[state=on]:text-foreground"
                  >
                    {option.label}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </FormControl>
          </FormItem>
        )}
      />
      {scope === EVALUATORS_RULE_SCOPE.trace && (
        <FormField
          control={form.control}
          name="triggerScope"
          render={({ field }) => (
            <FormItem className="flex-row items-center gap-1.5 pl-1 pr-0.5">
              <FormLabel
                htmlFor={EXCLUDE_EXPERIMENTS_SWITCH_ID}
                className="comet-body-s flex flex-1 cursor-pointer items-center gap-1 font-normal"
              >
                <CornerDownRight className="size-3 shrink-0 text-muted-slate" />
                Exclude experiment traces
              </FormLabel>
              <FormControl>
                <Switch
                  id={EXCLUDE_EXPERIMENTS_SWITCH_ID}
                  size="xs"
                  className="h-[14px] w-6 border data-[state=unchecked]:bg-slate-300 [&>span]:size-3 [&>span]:data-[state=checked]:translate-x-[10px]"
                  checked={field.value === EVAL_TRIGGER_SCOPE.production}
                  onCheckedChange={(checked) =>
                    field.onChange(
                      checked
                        ? EVAL_TRIGGER_SCOPE.production
                        : EVAL_TRIGGER_SCOPE.both,
                    )
                  }
                />
              </FormControl>
            </FormItem>
          )}
        />
      )}
    </div>
  );
};

export default RuleScopeField;
