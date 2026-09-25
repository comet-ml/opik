import React from "react";
import { UseFormReturn } from "react-hook-form";
import { CornerDownRight, Info } from "lucide-react";

import { FormControl, FormField, FormItem } from "@/ui/form";
import { Label } from "@/ui/label";
import { Switch } from "@/ui/switch";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { EVAL_TRIGGER_SCOPE, EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";

type RuleScopeSectionProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onScopeChange: (scope: EVALUATORS_RULE_SCOPE) => void;
  showSpanScope: boolean;
  disabled: boolean;
};

const SCOPE_OPTIONS: { value: EVALUATORS_RULE_SCOPE; label: string }[] = [
  { value: EVALUATORS_RULE_SCOPE.thread, label: "Threads" },
  { value: EVALUATORS_RULE_SCOPE.trace, label: "Traces" },
  { value: EVALUATORS_RULE_SCOPE.span, label: "Spans" },
];

const TRIGGER_SCOPE_OPTIONS: { value: EVAL_TRIGGER_SCOPE; label: string }[] = [
  { value: EVAL_TRIGGER_SCOPE.production, label: "Production traces" },
  { value: EVAL_TRIGGER_SCOPE.experiment, label: "Experiment traces" },
  { value: EVAL_TRIGGER_SCOPE.both, label: "Both" },
];

const RuleScopeSection: React.FC<RuleScopeSectionProps> = ({
  form,
  onScopeChange,
  showSpanScope,
  disabled,
}) => {
  const scope = form.watch("scope");
  const options = SCOPE_OPTIONS.filter(
    (option) => showSpanScope || option.value !== EVALUATORS_RULE_SCOPE.span,
  );

  return (
    <div className="flex flex-col gap-2">
      <FormField
        control={form.control}
        name="scope"
        render={({ field }) => (
          <FormItem>
            <Label className="flex items-center">
              Scope
              <TooltipWrapper content="Thread rules score the whole conversation, trace rules score one model response at a time, and span rules score individual operations inside a trace.">
                <Info className="ml-1 size-4 text-light-slate" />
              </TooltipWrapper>
            </Label>
            <FormControl>
              <ToggleGroup
                type="single"
                variant="secondary"
                className="w-full"
                value={field.value}
                disabled={disabled}
                data-testid="add-edit-rule-dialog-scope"
                onValueChange={(value: EVALUATORS_RULE_SCOPE) => {
                  if (value && value !== field.value) onScopeChange(value);
                }}
              >
                {options.map((option) => (
                  <ToggleGroupItem
                    key={option.value}
                    value={option.value}
                    aria-label={option.label}
                    className="h-6 flex-1"
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
          render={({ field }) =>
            // An experiment-only rule (set through the API or an older UI) has no
            // switch position, so it keeps the full three-way control instead.
            field.value === EVAL_TRIGGER_SCOPE.experiment ? (
              <FormItem>
                <Label className="comet-body-s font-normal text-foreground">
                  Trigger scope
                </Label>
                <FormControl>
                  <ToggleGroup
                    type="single"
                    variant="secondary"
                    className="w-full"
                    value={field.value}
                    data-testid="add-edit-rule-dialog-trigger-scope"
                    onValueChange={(value: EVAL_TRIGGER_SCOPE) => {
                      if (value) field.onChange(value);
                    }}
                  >
                    {TRIGGER_SCOPE_OPTIONS.map((option) => (
                      <ToggleGroupItem
                        key={option.value}
                        value={option.value}
                        aria-label={option.label}
                        className="h-6 flex-1"
                      >
                        {option.label}
                      </ToggleGroupItem>
                    ))}
                  </ToggleGroup>
                </FormControl>
              </FormItem>
            ) : (
              <FormItem className="flex flex-row items-center justify-between space-y-0 pl-1">
                <Label
                  htmlFor="exclude-experiment-traces"
                  className="comet-body-s flex items-center gap-1 font-normal text-foreground"
                >
                  <CornerDownRight className="size-3.5 text-light-slate" />
                  Exclude experiment traces
                  <TooltipWrapper content="On, the rule scores production traces only. Off, it also scores traces logged by experiments.">
                    <Info className="size-4 text-light-slate" />
                  </TooltipWrapper>
                </Label>
                <FormControl>
                  <Switch
                    id="exclude-experiment-traces"
                    size="sm"
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
            )
          }
        />
      )}
    </div>
  );
};

export default RuleScopeSection;
