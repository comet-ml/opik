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
import PythonCodeRuleDetails from "@/components/pages/TracesPage/RulesTab/AddEditRuleDialog/PythonCodeRuleDetails";
import LLMJudgeRuleDetails from "@/components/pages/TracesPage/RulesTab/AddEditRuleDialog/LLMJudgeRuleDetails";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
  EvaluationRuleFormSchema,
  EvaluationRuleFormType,
  LLMJudgeDetailsFormType,
} from "@/components/pages/TracesPage/RulesTab/AddEditRuleDialog/schema";
import { LLM_JUDGE } from "@/types/llm";
import { LLM_PROMPT_CUSTOM_TEMPLATE } from "@/constants/llm";

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

type AddEditRuleDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  rule?: EvaluatorsRule;
};

const AddEditRuleDialog: React.FC<AddEditRuleDialogProps> = ({
  open,
  setOpen,
  projectId,
  rule: defaultRule,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const form: UseFormReturn<EvaluationRuleFormType> = useForm<
    z.infer<typeof EvaluationRuleFormSchema>
  >({
    resolver: zodResolver(EvaluationRuleFormSchema),
    defaultValues: {
      ruleName: defaultRule?.name || "",
      samplingRate: defaultRule?.sampling_rate ?? DEFAULT_SAMPLING_RATE,
      type: defaultRule?.type || EVALUATORS_RULE_TYPE.llm_judge,
      pythonCodeDetails:
        defaultRule && defaultRule.type === EVALUATORS_RULE_TYPE.python_code
          ? (defaultRule.code as PythonCodeObject)
          : { code: "" },
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

  const getRule = useCallback(() => {
    const formData = form.getValues();
    return {
      name: formData.ruleName,
      project_id: projectId,
      sampling_rate: formData.samplingRate,
      type: formData.type,
      code: isLLMJudge
        ? convertLLMJudgeDataToLLMJudgeObject(formData.llmJudgeDetails)
        : formData.pythonCodeDetails,
    } as EvaluatorsRule;
  }, [form, projectId, isLLMJudge]);

  const createPrompt = useCallback(() => {
    createMutate({
      projectId,
      rule: getRule(),
    });
    setOpen(false);
  }, [createMutate, getRule, projectId, setOpen]);

  const editPrompt = useCallback(() => {
    updateMutate({
      ruleId: defaultRule!.id,
      projectId,
      rule: getRule(),
    });
    setOpen(false);
  }, [updateMutate, getRule, defaultRule, projectId, setOpen]);

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
                  const validationErrors = get(formState.errors, [
                    "llmJudgeDetails",
                    "model",
                  ]);
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
              {isLLMJudge ? (
                <LLMJudgeRuleDetails
                  workspaceName={workspaceName}
                  projectId={projectId}
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
          <Button type="submit" onClick={form.handleSubmit(onSubmit)}>
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditRuleDialog;
