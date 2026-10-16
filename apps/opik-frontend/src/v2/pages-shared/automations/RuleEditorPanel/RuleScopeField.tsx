import React from "react";
import { UseFormReturn } from "react-hook-form";

import { FormControl, FormField, FormItem, FormLabel } from "@/ui/form";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import SelectBox from "@/shared/SelectBox/SelectBox";
import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_SCOPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";

const SCORED_TRACES_SELECT_ID = "rule-editor-scored-traces";

const SCORED_TRACES_OPTIONS = [
  { value: EVAL_TRIGGER_SCOPE.production, label: "production traces" },
  { value: EVAL_TRIGGER_SCOPE.experiment, label: "experiment traces" },
  { value: EVAL_TRIGGER_SCOPE.both, label: "all traces" },
];

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
  const isDecisionModelRule = uiType === UI_EVALUATORS_RULE_TYPE.decision_model;
  const isSpanScopeEnabled =
    uiType === UI_EVALUATORS_RULE_TYPE.python_code
      ? isSpanPythonCodeEnabled
      : isSpanLlmJudgeEnabled;

  const scopeOptions = [
    ...(isDecisionModelRule
      ? []
      : [{ value: EVALUATORS_RULE_SCOPE.thread, label: "Threads" }]),
    { value: EVALUATORS_RULE_SCOPE.trace, label: "Traces" },
    ...(isSpanScopeEnabled || scope === EVALUATORS_RULE_SCOPE.span
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
            <FormItem className="flex-row items-center gap-0 px-0.5">
              <FormLabel
                htmlFor={SCORED_TRACES_SELECT_ID}
                className="comet-body-s font-normal"
              >
                Score
              </FormLabel>
              <FormControl>
                <SelectBox
                  id={SCORED_TRACES_SELECT_ID}
                  value={field.value}
                  onChange={field.onChange}
                  options={SCORED_TRACES_OPTIONS}
                  variant="ghost"
                  className="comet-body-s h-6 w-auto gap-1 px-1 font-normal"
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
