import React from "react";
import { UseFormReturn } from "react-hook-form";
import { Info } from "lucide-react";

import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { FormControl, FormField, FormItem } from "@/ui/form";
import { Label } from "@/ui/label";
import { Switch } from "@/ui/switch";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import { Description } from "@/ui/description";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EVAL_TRIGGER_SCOPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";
import LLMJudgeMaxCostField from "@/v2/pages-shared/automations/AddEditRuleDialog/LLMJudgeMaxCostField";

const ADVANCED_SETTINGS_ITEM = "advanced-settings";

type RuleAdvancedSettingsSectionProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  /** LLM-as-judge on trace or thread scope; span scoring has no loop to cap. */
  showMaxCost: boolean;
  /** Trace scope only; threads and spans always evaluate production data. */
  showTriggerScope: boolean;
  /** Open the section on mount, e.g. when editing a rule with non-default values here. */
  defaultOpen: boolean;
};

/**
 * Settings most rules never touch, collapsed so the main flow reads
 * Scope → Type → Model → Prompt → Score definition without interruption.
 */
const RuleAdvancedSettingsSection: React.FC<
  RuleAdvancedSettingsSectionProps
> = ({ form, showMaxCost, showTriggerScope, defaultOpen }) => (
  <Accordion
    type="single"
    collapsible
    defaultValue={defaultOpen ? ADVANCED_SETTINGS_ITEM : undefined}
    className="w-full border-t border-border"
  >
    <AccordionItem value={ADVANCED_SETTINGS_ITEM} className="border-none">
      <AccordionTrigger
        className="px-3 py-2 hover:no-underline"
        data-testid="add-edit-rule-dialog-advanced-settings-trigger"
      >
        <div className="flex items-center gap-1">
          <Label className="text-sm font-medium">Advanced settings</Label>
          <ExplainerIcon
            className="mt-0.5"
            description="Cost limit, which traces trigger the rule, and whether the rule is active. The defaults work for most rules."
          />
        </div>
      </AccordionTrigger>
      <AccordionContent className="px-3 pb-3">
        <div className="flex flex-col gap-4">
          {showMaxCost && <LLMJudgeMaxCostField form={form} />}

          {showTriggerScope && (
            <FormField
              control={form.control}
              name="triggerScope"
              render={({ field }) => (
                <FormItem>
                  <Label className="flex items-center">
                    Trigger scope{" "}
                    <TooltipWrapper content="Choose whether this rule fires on production traces, experiment traces, or both.">
                      <Info className="ml-1 size-4 text-light-slate" />
                    </TooltipWrapper>
                  </Label>
                  <FormControl>
                    <div className="flex">
                      <ToggleGroup
                        type="single"
                        data-testid="add-edit-rule-dialog-trigger-scope"
                        value={field.value}
                        onValueChange={(value: EVAL_TRIGGER_SCOPE) => {
                          if (!value) return;
                          field.onChange(value);
                        }}
                      >
                        <ToggleGroupItem
                          value={EVAL_TRIGGER_SCOPE.production}
                          aria-label="Production traces"
                        >
                          Production traces
                        </ToggleGroupItem>
                        <ToggleGroupItem
                          value={EVAL_TRIGGER_SCOPE.experiment}
                          aria-label="Experiment traces"
                        >
                          Experiment traces
                        </ToggleGroupItem>
                        <ToggleGroupItem
                          value={EVAL_TRIGGER_SCOPE.both}
                          aria-label="Both"
                        >
                          Both
                        </ToggleGroupItem>
                      </ToggleGroup>
                    </div>
                  </FormControl>
                </FormItem>
              )}
            />
          )}

          <FormField
            control={form.control}
            name="enabled"
            render={({ field }) => (
              <FormItem className="flex flex-row items-center justify-between space-y-0">
                <div className="flex flex-col">
                  <Label htmlFor="enabled" className="text-sm font-medium">
                    Enable rule
                  </Label>
                  <Description>
                    Disabled rules are kept but do not score anything.
                  </Description>
                </div>
                <FormControl>
                  <Switch
                    id="enabled"
                    checked={field.value}
                    onCheckedChange={field.onChange}
                  />
                </FormControl>
              </FormItem>
            )}
          />
        </div>
      </AccordionContent>
    </AccordionItem>
  </Accordion>
);

export default RuleAdvancedSettingsSection;
