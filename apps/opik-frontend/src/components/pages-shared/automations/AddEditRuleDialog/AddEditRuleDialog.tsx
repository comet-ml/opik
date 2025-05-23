import React, { useCallback } from "react";

import cloneDeep from "lodash/cloneDeep";
import get from "lodash/get";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, UseFormReturn } from "react-hook-form";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
  LLMJudgeObject,
  PythonCodeObject,
} from "@/types/automations";
import useAppStore from "@/store/AppStore";
import useRuleCreateMutation from "@/api/automations/useRuleCreateMutation";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import PythonCodeRuleDetails from "@/components/pages-shared/automations/AddEditRuleDialog/PythonCodeRuleDetails";
import LLMJudgeRuleDetails from "@/components/pages-shared/automations/AddEditRuleDialog/LLMJudgeRuleDetails";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
  EvaluationRuleFormSchema,
  EvaluationRuleFormType,
  LLMJudgeDetailsFormType,
  PythonCodeDetailsFormType,
} from "@/components/pages-shared/automations/AddEditRuleDialog/schema";
import { LLM_JUDGE } from "@/types/llm";
import { LLM_PROMPT_CUSTOM_TEMPLATE } from "@/constants/llm";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

export const DEFAULT_SAMPLING_RATE = 1;

export const DEFAULT_LLM_AS_JUDGE_DATA: LLMJudgeDetailsFormType = {
  model: "",
  config: {
    temperature: 0.0,
  },
  template: LLM_JUDGE.custom,
  messages: LLM_PROMPT_CUSTOM_TEMPLATE.messages,
  variables: LLM_PROMPT_CUSTOM_TEMPLATE.variables,
  schema: LLM_PROMPT_CUSTOM_TEMPLATE.schema,
};

export const DEFAULT_PYTHON_CODE_DATA: PythonCodeDetailsFormType = {
  metric:
    "from typing import Any\n" +
    "from opik.evaluation.metrics import base_metric, score_result\n" +
    "\n" +
    "class MyCustomMetric(base_metric.BaseMetric):\n" +
    '    def __init__(self, name: str = "my_custom_metric"):\n' +
    "        self.name = name\n" +
    "\n" +
    "    def score(self, input: str, output: str, **ignored_kwargs: Any):\n" +
    "        # Add you logic here\n" +
    "\n" +
    "        return score_result.ScoreResult(\n" +
    "            value=0,\n" +
    "            name=self.name,\n" +
    '            reason="Optional reason for the score"\n' +
    "        )",
  arguments: {
    input: "",
    output: "",
  },
};

type AddEditRuleDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId?: string;
  rule?: EvaluatorsRule;
};

const AddEditRuleDialog: React.FC<AddEditRuleDialogProps> = ({
  open,
  setOpen,
  projectId,
  rule: defaultRule,
}) => {
  const isCodeMetricEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PYTHON_EVALUATOR_ENABLED,
  );
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const form: UseFormReturn<EvaluationRuleFormType> = useForm<
    z.infer<typeof EvaluationRuleFormSchema>
  >({
    resolver: zodResolver(EvaluationRuleFormSchema),
    defaultValues: {
      ruleName: defaultRule?.name || "",
      projectId: defaultRule?.project_id || projectId || "",
      samplingRate: defaultRule?.sampling_rate ?? DEFAULT_SAMPLING_RATE,
      type: defaultRule?.type || EVALUATORS_RULE_TYPE.llm_judge,
      pythonCodeDetails:
        defaultRule && defaultRule.type === EVALUATORS_RULE_TYPE.python_code
          ? (defaultRule.code as PythonCodeObject)
          : cloneDeep(DEFAULT_PYTHON_CODE_DATA),
      llmJudgeDetails:
        defaultRule && defaultRule.type === EVALUATORS_RULE_TYPE.llm_judge
          ? convertLLMJudgeObjectToLLMJudgeData(
              defaultRule.code as LLMJudgeObject,
            )
          : cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA),
    },
  });
  const isLLMJudge = form.getValues("type") === EVALUATORS_RULE_TYPE.llm_judge;

  const { mutate: createMutate } = useRuleCreateMutation();
  const { mutate: updateMutate } = useRuleUpdateMutation();

  const isEdit = Boolean(defaultRule);
  const title = isEdit ? "Edit rule" : "Create a new rule";
  const submitText = isEdit ? "Update rule" : "Create rule";

  const isCodeMetricEditBlock = !isCodeMetricEnabled && !isLLMJudge && isEdit;

  const getRule = useCallback(() => {
    const formData = form.getValues();
    return {
      name: formData.ruleName,
      project_id: formData.projectId,
      sampling_rate: formData.samplingRate,
      type: formData.type,
      code:
        formData.type === EVALUATORS_RULE_TYPE.llm_judge
          ? convertLLMJudgeDataToLLMJudgeObject(formData.llmJudgeDetails)
          : formData.pythonCodeDetails,
    } as EvaluatorsRule;
  }, [form]);

  const createPrompt = useCallback(() => {
    createMutate({
      rule: getRule(),
    });
    setOpen(false);
  }, [createMutate, getRule, setOpen]);

  const editPrompt = useCallback(() => {
    updateMutate({
      ruleId: defaultRule!.id,
      rule: getRule(),
    });
    setOpen(false);
  }, [updateMutate, getRule, defaultRule, setOpen]);

  const onSubmit = useCallback(
    () => (isEdit ? editPrompt() : createPrompt()),
    [isEdit, editPrompt, createPrompt],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[790px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <Form {...form}>
            <form
              className="flex flex-col gap-4 pb-4"
              onSubmit={form.handleSubmit(onSubmit)}
            >
              <FormField
                control={form.control}
                name="ruleName"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["ruleName"]);
                  return (
                    <FormItem>
                      <Label>Name</Label>
                      <FormControl>
                        <Input
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder="Rule name"
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
              {!projectId && (
                <FormField
                  control={form.control}
                  name="projectId"
                  render={({ field, formState }) => {
                    const validationErrors = get(formState.errors, [
                      "projectId",
                    ]);

                    return (
                      <FormItem>
                        <Label>Project</Label>
                        <FormControl>
                          <ProjectsSelectBox
                            value={field.value}
                            onChange={field.onChange}
                            className={cn({
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    );
                  }}
                />
              )}
              <FormField
                control={form.control}
                name="samplingRate"
                render={({ field }) => (
                  <SliderInputControl
                    min={0}
                    max={1}
                    step={0.01}
                    defaultValue={DEFAULT_SAMPLING_RATE}
                    value={field.value}
                    onChange={field.onChange}
                    id="sampling_rate"
                    label="Sampling rate"
                    tooltip="Percentage of traces to evaluate"
                  />
                )}
              />
              {!isEdit && (
                <FormField
                  control={form.control}
                  name="type"
                  render={({ field }) => (
                    <FormItem>
                      <Label>Type</Label>
                      <FormControl>
                        <div className="flex">
                          <ToggleGroup
                            type="single"
                            value={field.value}
                            onValueChange={(value) =>
                              value && field.onChange(value)
                            }
                          >
                            <ToggleGroupItem
                              value={EVALUATORS_RULE_TYPE.llm_judge}
                              aria-label="LLM-as-judge"
                            >
                              LLM-as-judge
                            </ToggleGroupItem>
                            {isCodeMetricEnabled ? (
                              <ToggleGroupItem
                                value={EVALUATORS_RULE_TYPE.python_code}
                                aria-label="Code metric"
                              >
                                Code metric
                              </ToggleGroupItem>
                            ) : (
                              <TooltipWrapper content="This feature is not available for this environment">
                                <span>
                                  <ToggleGroupItem
                                    value={EVALUATORS_RULE_TYPE.python_code}
                                    aria-label="Code metric"
                                    disabled
                                  >
                                    Code metric
                                  </ToggleGroupItem>
                                </span>
                              </TooltipWrapper>
                            )}
                          </ToggleGroup>
                        </div>
                      </FormControl>
                    </FormItem>
                  )}
                />
              )}
              {isLLMJudge ? (
                <LLMJudgeRuleDetails
                  workspaceName={workspaceName}
                  form={form}
                />
              ) : (
                <PythonCodeRuleDetails form={form} />
              )}
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          {isCodeMetricEditBlock ? (
            <TooltipWrapper content="Code metric cannot be updated. This feature is not available for this environment">
              <span>
                <Button type="submit" disabled>
                  {submitText}
                </Button>
              </span>
            </TooltipWrapper>
          ) : (
            <Button type="submit" onClick={form.handleSubmit(onSubmit)}>
              {submitText}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditRuleDialog;
