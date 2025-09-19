import React, { useCallback } from "react";
import cloneDeep from "lodash/cloneDeep";
import get from "lodash/get";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, UseFormReturn } from "react-hook-form";
import { Info, MessageCircleWarning } from "lucide-react";

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
import { Switch } from "@/components/ui/switch";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
  LLMJudgeObject,
  PythonCodeObject,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import useAppStore from "@/store/AppStore";
import useRuleCreateMutation from "@/api/automations/useRuleCreateMutation";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import PythonCodeRuleDetails from "@/components/pages-shared/automations/AddEditRuleDialog/PythonCodeRuleDetails";
import LLMJudgeRuleDetails from "@/components/pages-shared/automations/AddEditRuleDialog/LLMJudgeRuleDetails";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
  EvaluationRuleFormSchema,
  EvaluationRuleFormType,
} from "@/components/pages-shared/automations/AddEditRuleDialog/schema";
import { LLM_JUDGE } from "@/types/llm";
import {
  DEFAULT_PYTHON_CODE_THREAD_DATA,
  DEFAULT_PYTHON_CODE_TRACE_DATA,
  LLM_PROMPT_CUSTOM_THREAD_TEMPLATE,
  LLM_PROMPT_CUSTOM_TRACE_TEMPLATE,
} from "@/constants/llm";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { Description } from "@/components/ui/description";
import { ToastAction } from "@/components/ui/toast";
import { useToast } from "@/components/ui/use-toast";
import { useNavigate } from "@tanstack/react-router";
import { getBackendRuleType, getUIRuleScope, getUIRuleType } from "./helpers";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useConfirmAction } from "@/components/shared/ConfirmDialog/useConfirmAction";

export const DEFAULT_SAMPLING_RATE = 1;

export const DEFAULT_LLM_AS_JUDGE_DATA = {
  [EVALUATORS_RULE_SCOPE.trace]: {
    model: "",
    config: {
      temperature: 0.0,
    },
    template: LLM_JUDGE.custom,
    messages: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.messages,
    variables: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.variables,
    schema: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema,
  },
  [EVALUATORS_RULE_SCOPE.thread]: {
    model: "",
    config: {
      temperature: 0.0,
    },
    template: LLM_JUDGE.custom,
    messages: LLM_PROMPT_CUSTOM_THREAD_TEMPLATE.messages,
    variables: LLM_PROMPT_CUSTOM_THREAD_TEMPLATE.variables,
    schema: LLM_PROMPT_CUSTOM_THREAD_TEMPLATE.schema,
  },
};

const DEFAULT_PYTHON_CODE_DATA: Record<
  EVALUATORS_RULE_SCOPE,
  PythonCodeObject
> = {
  [EVALUATORS_RULE_SCOPE.trace]: DEFAULT_PYTHON_CODE_TRACE_DATA,
  [EVALUATORS_RULE_SCOPE.thread]: DEFAULT_PYTHON_CODE_THREAD_DATA,
};

type AddEditRuleDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId?: string;
  rule?: EvaluatorsRule;
};

const isPythonCodeRule = (rule: EvaluatorsRule) => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.python_code ||
    rule.type === EVALUATORS_RULE_TYPE.thread_python_code
  );
};

const isLLMJudgeRule = (rule: EvaluatorsRule) => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.thread_llm_judge
  );
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
  const navigate = useNavigate();
  const { isOpen, setIsOpen, requestConfirm, confirm, cancel } =
    useConfirmAction();
  const { toast } = useToast();

  const formUIRuleType = defaultRule?.type
    ? getUIRuleType(defaultRule.type)
    : UI_EVALUATORS_RULE_TYPE.llm_judge;
  const formScope = defaultRule?.type
    ? getUIRuleScope(defaultRule.type)
    : EVALUATORS_RULE_SCOPE.trace;

  const form: UseFormReturn<EvaluationRuleFormType> = useForm<
    z.infer<typeof EvaluationRuleFormSchema>
  >({
    resolver: zodResolver(EvaluationRuleFormSchema),
    defaultValues: {
      ruleName: defaultRule?.name || "",
      projectId: defaultRule?.project_id || projectId || "",
      samplingRate: defaultRule?.sampling_rate ?? DEFAULT_SAMPLING_RATE,
      uiType: formUIRuleType,
      scope: formScope,
      type: getBackendRuleType(formScope, formUIRuleType),
      enabled: defaultRule?.enabled ?? true,
      pythonCodeDetails:
        defaultRule && isPythonCodeRule(defaultRule)
          ? (defaultRule.code as PythonCodeObject)
          : cloneDeep(DEFAULT_PYTHON_CODE_DATA[formScope]),
      llmJudgeDetails:
        defaultRule && isLLMJudgeRule(defaultRule)
          ? convertLLMJudgeObjectToLLMJudgeData(
              defaultRule.code as LLMJudgeObject,
            )
          : cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA[formScope]),
    },
  });

  const isLLMJudge =
    form.getValues("uiType") === UI_EVALUATORS_RULE_TYPE.llm_judge;
  const scope = form.getValues("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;

  const handleScopeChange = useCallback(
    (value: EVALUATORS_RULE_SCOPE) => {
      const applyChange = () => {
        const { uiType } = form.getValues();
        const type = getBackendRuleType(value, uiType);

        form.setValue("scope", value);
        form.setValue("type", type);

        form.setValue(
          "llmJudgeDetails",
          cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA[value]),
        );
        form.setValue(
          "pythonCodeDetails",
          cloneDeep(DEFAULT_PYTHON_CODE_DATA[value]),
        );
      };

      if (
        Object.keys(form.formState.dirtyFields).some(
          (key) => key === "llmJudgeDetails" || key === "pythonCodeDetails",
        )
      ) {
        requestConfirm(applyChange);
      } else {
        applyChange();
      }
    },
    [form, requestConfirm],
  );

  const { mutate: createMutate } = useRuleCreateMutation();
  const { mutate: updateMutate } = useRuleUpdateMutation();

  const isEdit = Boolean(defaultRule);
  const title = isEdit ? "Edit rule" : "Create a new rule";
  const submitText = isEdit ? "Update rule" : "Create rule";

  const isCodeMetricEditBlock = !isCodeMetricEnabled && !isLLMJudge && isEdit;

  const onRuleCreatedEdited = useCallback(() => {
    const expainerIdMap = {
      [EVALUATORS_RULE_SCOPE.trace]:
        EXPLAINER_ID.i_added_edited_a_new_online_evaluation_rule_now_what,
      [EVALUATORS_RULE_SCOPE.thread]:
        EXPLAINER_ID.i_added_edited_a_new_online_evaluation_thread_level_rule_now_what,
    };
    const explainer = EXPLAINERS_MAP[expainerIdMap[scope]];

    toast({
      title: explainer.title,
      description: explainer.description,
      actions: [
        <ToastAction
          variant="link"
          size="sm"
          className="px-0"
          altText="Go to project"
          key="Go to project"
          onClick={() => {
            navigate({
              to: "/$workspaceName/projects/$projectId/traces",
              params: {
                projectId: form.getValues("projectId"),
                workspaceName,
              },
              search: {
                type: {
                  [EVALUATORS_RULE_SCOPE.trace]: "traces",
                  [EVALUATORS_RULE_SCOPE.thread]: "threads",
                }[scope],
              },
            });
          }}
        >
          Go to project
        </ToastAction>,
      ],
    });
  }, [form, navigate, toast, workspaceName, scope]);

  const getRule = useCallback(() => {
    const formData = form.getValues();
    const ruleType = formData.type;

    const ruleData = {
      name: formData.ruleName,
      project_id: formData.projectId,
      sampling_rate: formData.samplingRate,
      enabled: formData.enabled,
      type: ruleType,
    };

    if (ruleType === EVALUATORS_RULE_TYPE.llm_judge) {
      return {
        ...ruleData,
        code: convertLLMJudgeDataToLLMJudgeObject(formData.llmJudgeDetails),
      } as EvaluatorsRule;
    }

    if (ruleType === EVALUATORS_RULE_TYPE.thread_llm_judge) {
      return {
        ...ruleData,
        code: {
          ...convertLLMJudgeDataToLLMJudgeObject(formData.llmJudgeDetails),
          variables: undefined,
        },
      } as EvaluatorsRule;
    }

    return {
      ...ruleData,
      code: formData.pythonCodeDetails,
    } as EvaluatorsRule;
  }, [form]);

  const createPrompt = useCallback(() => {
    createMutate(
      {
        rule: getRule(),
      },
      { onSuccess: onRuleCreatedEdited },
    );
    setOpen(false);
  }, [createMutate, getRule, onRuleCreatedEdited, setOpen]);

  const editPrompt = useCallback(() => {
    updateMutate(
      {
        ruleId: defaultRule!.id,
        rule: getRule(),
      },
      { onSuccess: onRuleCreatedEdited },
    );
    setOpen(false);
  }, [updateMutate, defaultRule, getRule, onRuleCreatedEdited, setOpen]);

  const onSubmit = useCallback(
    () => (isEdit ? editPrompt() : createPrompt()),
    [isEdit, editPrompt, createPrompt],
  );

  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg sm:max-w-[790px]">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
          </DialogHeader>
          <DialogAutoScrollBody>
            {isEdit && (
              <ExplainerCallout
                Icon={MessageCircleWarning}
                className="mb-2"
                isDismissable={false}
                {...EXPLAINERS_MAP[
                  isThreadScope
                    ? EXPLAINER_ID.what_happens_if_i_edit_a_thread_rule
                    : EXPLAINER_ID.what_happens_if_i_edit_a_rule
                ]}
              />
            )}
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
                      "ruleName",
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
                <div className="flex gap-4">
                  <FormField
                    control={form.control}
                    name="projectId"
                    render={({ field, formState }) => {
                      const validationErrors = get(formState.errors, [
                        "projectId",
                      ]);

                      return (
                        <FormItem className="flex-1">
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
                              disabled={Boolean(projectId)}
                            />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      );
                    }}
                  />

                  <FormField
                    control={form.control}
                    name="scope"
                    render={({ field }) => (
                      <FormItem className="flex-1">
                        <Label className="flex items-center">
                          Scope{" "}
                          <TooltipWrapper content="Choose whether the evaluation rule scores the entire thread or each individual trace. Thread-level rules assess the full conversation, while trace-level rules evaluate one model response at a time.">
                            <Info className="ml-1 size-4 text-light-slate" />
                          </TooltipWrapper>
                        </Label>
                        <FormControl>
                          <Select
                            value={field.value}
                            onValueChange={handleScopeChange}
                            disabled={isEdit}
                          >
                            <SelectTrigger>
                              <SelectValue placeholder="Select scope" />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value={EVALUATORS_RULE_SCOPE.trace}>
                                Trace
                              </SelectItem>
                              <SelectItem value={EVALUATORS_RULE_SCOPE.thread}>
                                Thread
                              </SelectItem>
                            </SelectContent>
                          </Select>
                        </FormControl>
                      </FormItem>
                    )}
                  />
                </div>

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

                <FormField
                  control={form.control}
                  name="enabled"
                  render={({ field }) => (
                    <FormItem className="flex flex-row items-center justify-between space-y-0">
                      <div className="flex flex-col">
                        <Label
                          htmlFor="enabled"
                          className="text-sm font-medium"
                        >
                          Enable rule
                        </Label>
                        <Description>
                          Enable or disable this evaluation rule
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

                {!isEdit && (
                  <FormField
                    control={form.control}
                    name="uiType"
                    render={({ field }) => (
                      <FormItem>
                        <Label>Type</Label>
                        <FormControl>
                          <div className="flex">
                            <ToggleGroup
                              type="single"
                              value={field.value}
                              onValueChange={(
                                value: UI_EVALUATORS_RULE_TYPE,
                              ) => {
                                if (!value) return;

                                const { scope } = form.getValues();
                                const type = getBackendRuleType(scope, value);

                                field.onChange(value);
                                form.setValue("type", type);
                              }}
                            >
                              <ToggleGroupItem
                                value={UI_EVALUATORS_RULE_TYPE.llm_judge}
                                aria-label="LLM-as-judge"
                              >
                                LLM-as-judge
                              </ToggleGroupItem>
                              {isCodeMetricEnabled ? (
                                <ToggleGroupItem
                                  value={UI_EVALUATORS_RULE_TYPE.python_code}
                                  aria-label="Code metric"
                                >
                                  Code metric
                                </ToggleGroupItem>
                              ) : (
                                <TooltipWrapper content="This feature is not available for this environment">
                                  <span>
                                    <ToggleGroupItem
                                      value={
                                        UI_EVALUATORS_RULE_TYPE.python_code
                                      }
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
                        <Description>
                          {isLLMJudge
                            ? EXPLAINERS_MAP[EXPLAINER_ID.whats_llm_as_a_judge]
                                .description
                            : EXPLAINERS_MAP[EXPLAINER_ID.whats_a_code_metric]
                                .description}
                        </Description>
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

      <ConfirmDialog
        open={isOpen}
        setOpen={setIsOpen}
        onCancel={cancel}
        onConfirm={confirm}
        title="You’re about to lose your changes"
        description={`If you change the evaluation scope, your current rule settings — including prompt, model, and variable mappings — will be reset.
Are you sure you want to continue?`}
        cancelText="Cancel"
        confirmText="Reset and continue"
      />
    </>
  );
};

export default AddEditRuleDialog;
