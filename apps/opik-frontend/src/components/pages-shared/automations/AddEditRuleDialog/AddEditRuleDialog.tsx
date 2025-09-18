import React, { useCallback, useMemo, useState, useEffect } from "react";
import cloneDeep from "lodash/cloneDeep";
import get from "lodash/get";
import uniqid from "uniqid";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, UseFormReturn } from "react-hook-form";
import {
  Info,
  MessageCircleWarning,
  Plus,
  ChevronDown,
  ChevronRight,
} from "lucide-react";

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
import {
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_CUSTOM_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { Filter } from "@/types/filters";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
import { createFilter } from "@/lib/filters";
import useAppStore from "@/store/AppStore";
import useRuleCreateMutation from "@/api/automations/useRuleCreateMutation";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import PythonCodeRuleDetails from "@/components/pages-shared/automations/AddEditRuleDialog/PythonCodeRuleDetails";
import LLMJudgeRuleDetails from "@/components/pages-shared/automations/AddEditRuleDialog/LLMJudgeRuleDetails";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import FilterRow from "@/components/shared/FiltersButton/FilterRow";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
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

const AUTOMATION_RULE_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
  },
  {
    id: "input",
    label: "Input",
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: "Output",
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
  },
  {
    id: "thread_id",
    label: "Thread ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];

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

  // Normalize filters from backend to ensure they have the correct structure
  const normalizeFilters = useCallback((filters?: Filter[]) => {
    if (!filters || filters.length === 0) return [];

    return filters.map((filter) => {
      // Find the column type for this field
      const column = AUTOMATION_RULE_FILTER_COLUMNS.find(
        (col) => col.id === filter.field,
      );
      const columnType = column?.type || COLUMN_TYPE.string;

      return {
        id: filter.id || uniqid(),
        field: filter.field || "",
        type: columnType,
        operator: filter.operator || "",
        key: filter.key || "",
        value: filter.value || "",
        error: filter.error || "",
      } as Filter;
    });
  }, []);

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
      filters: normalizeFilters(defaultRule?.filters),
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

  // Re-initialize form when defaultRule changes
  useEffect(() => {
    if (defaultRule) {
      const formData = {
        ruleName: defaultRule.name || "",
        projectId: defaultRule.project_id || projectId || "",
        samplingRate: defaultRule.sampling_rate ?? DEFAULT_SAMPLING_RATE,
        uiType: formUIRuleType,
        scope: formScope,
        type: getBackendRuleType(formScope, formUIRuleType),
        enabled: defaultRule.enabled ?? true,
        filters: normalizeFilters(defaultRule.filters),
        pythonCodeDetails: isPythonCodeRule(defaultRule)
          ? (defaultRule.code as PythonCodeObject)
          : cloneDeep(DEFAULT_PYTHON_CODE_DATA[formScope]),
        llmJudgeDetails: isLLMJudgeRule(defaultRule)
          ? convertLLMJudgeObjectToLLMJudgeData(
              defaultRule.code as LLMJudgeObject,
            )
          : cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA[formScope]),
      };
      form.reset(formData as EvaluationRuleFormType);
    }
  }, [
    defaultRule,
    form,
    formUIRuleType,
    formScope,
    projectId,
    normalizeFilters,
  ]);

  const isLLMJudge =
    form.getValues("uiType") === UI_EVALUATORS_RULE_TYPE.llm_judge;
  const scope = form.getValues("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;

  const [isFiltersExpanded, setIsFiltersExpanded] = useState(false);

  const formProjectId = form.watch("projectId");

  const automationRuleFiltersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["metadata"],
            projectId: formProjectId,
            type: TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: true,
          },
        },
        [COLUMN_CUSTOM_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["input", "output"],
            projectId: formProjectId,
            type: TRACE_DATA_TYPE.traces,
            placeholder: "key",
            excludeRoot: false,
          },
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return `Key is invalid, it should begin with "input", or "output" and follow this format: "input.[PATH]" For example: "input.message" `;
            }
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent:
            TracesOrSpansFeedbackScoresSelect as React.FC<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId: formProjectId,
            type: TRACE_DATA_TYPE.traces,
            placeholder: "Select score",
          },
        },
      },
    }),
    [formProjectId],
  );

  const handleAddFilter = useCallback(() => {
    const currentFilters = form.getValues("filters");
    form.setValue("filters", [...currentFilters, createFilter()]);
  }, [form]);

  const handleRemoveFilter = useCallback(
    (filterId: string) => {
      const currentFilters = form.getValues("filters");
      const updatedFilters = currentFilters.filter((f) => f.id !== filterId);
      form.setValue("filters", updatedFilters);
    },
    [form],
  );

  const handleFilterChange = useCallback(
    (updatedFilter: Filter) => {
      const currentFilters = form.getValues("filters");
      const updatedFilters = currentFilters.map((f) =>
        f.id === updatedFilter.id ? updatedFilter : f,
      );
      form.setValue("filters", updatedFilters);
    },
    [form],
  );

  const getFilterConfig = useCallback(
    (field: string) =>
      automationRuleFiltersConfig.rowsMap[
        field as keyof typeof automationRuleFiltersConfig.rowsMap
      ],
    [automationRuleFiltersConfig],
  );

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
                projectId: formProjectId,
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
  }, [navigate, toast, workspaceName, scope, formProjectId]);

  const getRule = useCallback(() => {
    const formData = form.getValues();
    const ruleType = formData.type;

    const ruleData = {
      name: formData.ruleName,
      project_id: formData.projectId,
      sampling_rate: formData.samplingRate,
      enabled: formData.enabled,
      filters: formData.filters,
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

                {/* Filtering Section */}
                <div className="rounded-md border bg-background p-3">
                  {/* Expandable Header */}
                  <div
                    className="flex cursor-pointer items-center gap-2"
                    onClick={() => setIsFiltersExpanded(!isFiltersExpanded)}
                  >
                    {isFiltersExpanded ? (
                      <ChevronDown className="size-4 text-muted-foreground" />
                    ) : (
                      <ChevronRight className="size-4 text-muted-foreground" />
                    )}
                    <Label className="cursor-pointer text-sm font-medium">
                      Filtering
                    </Label>
                    <TooltipWrapper content="Apply filters to select which traces will be evaluated by this rule">
                      <Info className="size-3.5 text-muted-foreground" />
                    </TooltipWrapper>
                  </div>

                  {/* Collapsible Content */}
                  {isFiltersExpanded && (
                    <div className="mt-4 space-y-4">
                      {/* Description */}
                      <div className="text-sm text-muted-foreground">
                        Use filters and sampling rate to control which traces
                        this rule applies to. If nothing is defined, the rule
                        will evaluate all traces.
                      </div>

                      {/* Filters */}
                      <FormField
                        control={form.control}
                        name="filters"
                        render={({ field }) => (
                          <FormItem>
                            <div className="space-y-3">
                              <Label className="text-sm font-medium">
                                Filters
                              </Label>

                              {/* Filter rows */}
                              {field.value.length > 0 && (
                                <div className="space-y-2">
                                  <table className="w-full">
                                    <tbody>
                                      {field.value.map((filter, index) => (
                                        <FilterRow
                                          key={filter.id}
                                          filter={filter}
                                          columns={
                                            AUTOMATION_RULE_FILTER_COLUMNS
                                          }
                                          prefix={index === 0 ? "Where" : "And"}
                                          getConfig={getFilterConfig}
                                          onRemove={handleRemoveFilter}
                                          onChange={handleFilterChange}
                                        />
                                      ))}
                                    </tbody>
                                  </table>
                                </div>
                              )}

                              {/* Add filter button on new line */}
                              <div className="pt-1">
                                <Button
                                  type="button"
                                  variant="outline"
                                  size="sm"
                                  onClick={handleAddFilter}
                                  className="w-fit"
                                >
                                  <Plus className="mr-1 size-3.5" />
                                  Add filter
                                </Button>
                              </div>
                            </div>
                          </FormItem>
                        )}
                      />

                      {/* Sampling Rate */}
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
                    </div>
                  )}
                </div>
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
